/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * yappy_handler.c - Yappy mobile payment via Supabase Edge Function
 *
 * This module handles Yappy payments by calling the Supabase Edge Function
 * (yappy-payment) which proxies all Yappy API interactions.  This approach
 * matches the Android app's implementation and offloads Yappy session
 * management, token caching, and sale recording to the server.
 *
 * Architecture:
 *   ESP32  -->  POST Edge Function (generate-qr / check-status / cancel)
 *          <--  JSON response (qr_hash, transaction_id, status)
 *
 * The Edge Function handles:
 *   - Yappy session token management (open/cache/refresh)
 *   - QR code generation via Yappy API
 *   - Transaction status polling
 *   - Sale recording in Supabase database
 *   - Transaction cancellation
 *
 * The ESP32 handles:
 *   - XOR-encoding the price/item into a 19-byte payload
 *   - Base64-encoding the payload for the Edge Function
 *   - Setting vend_approved_todo when payment is confirmed (zero latency)
 *   - Keeping MDB session alive during payment polling
 *
 * Authentication: ESP32 uses x-device-key header (shared secret)
 * instead of Supabase JWT.  No user login needed on the ESP32.
 */

#include <string.h>
#include <math.h>
#include <time.h>
#include <sys/time.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"

#include "esp_log.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "cJSON.h"

#include "mbedtls/base64.h"

#include "lwip/dns.h"
#include "lwip/ip_addr.h"
#include "esp_netif.h"

#include "esp_heap_caps.h"

#include "config.h"
#include "yappy_handler.h"
#include "xor_crypto.h"

#undef  TAG
#define TAG "yappy"

/* ======================== EMBEDDED ROOT CA ================================ */
/* GTS Root R4 — Google Trust Services ECDSA root used by Supabase/Cloudflare.
 * The ESP-IDF cert bundle doesn't contain the GlobalSign cross-signer, so we
 * embed the self-signed GTS Root R4 to short-circuit chain verification. */
extern const uint8_t gts_root_r4_pem_start[] asm("_binary_gts_root_r4_pem_start");
extern const uint8_t gts_root_r4_pem_end[]   asm("_binary_gts_root_r4_pem_end");

/* ======================== SUPABASE CREDENTIALS =========================== */
static char supabase_url[128];
static char supabase_anon_key[256];
static char esp32_device_key[64];

/* ======================== STATE + MUTEX ================================== */
static yappy_payment_state_t yappy_state;
static SemaphoreHandle_t     yappy_mutex;

/* Stored XOR payload (base64) — generated once per QR, reused for status */
static char stored_payload_b64[32];  /* base64(19 bytes) = 28 chars + null */

/* HTTP response buffer for Edge Function calls */
#define HTTP_RESPONSE_BUF_SIZE  4096
static char http_response_buf[HTTP_RESPONSE_BUF_SIZE];
static int  http_response_len;

/* Edge Function URL (built once at init) */
static char edge_fn_url[256];

/* ======================== HELPER: HTTP EVENT HANDLER ===================== */
/*
 * Accumulates the response body into http_response_buf.
 * Used by all Edge Function calls.
 */
static esp_err_t http_event_handler(esp_http_client_event_t *evt)
{
    switch (evt->event_id) {
    case HTTP_EVENT_ERROR:
        ESP_LOGE(TAG, "HTTP_EVENT_ERROR");
        break;
    case HTTP_EVENT_ON_DATA:
        if (http_response_len + evt->data_len < HTTP_RESPONSE_BUF_SIZE - 1) {
            memcpy(http_response_buf + http_response_len, evt->data, evt->data_len);
            http_response_len += evt->data_len;
            http_response_buf[http_response_len] = '\0';
        } else {
            ESP_LOGW(TAG, "HTTP response buffer overflow! current=%d incoming=%d",
                     http_response_len, evt->data_len);
        }
        break;
    case HTTP_EVENT_DISCONNECTED:
        ESP_LOGW(TAG, "HTTP_EVENT_DISCONNECTED");
        break;
    default:
        break;
    }
    return ESP_OK;
}

/* ======================== yappy_init ===================================== */
void yappy_init(void)
{
    yappy_mutex = xSemaphoreCreateMutex();
    memset(&yappy_state, 0, sizeof(yappy_state));
    yappy_state.state = YAPPY_IDLE;
    memset(stored_payload_b64, 0, sizeof(stored_payload_b64));

    /* Load Supabase credentials from Kconfig */
    strncpy(supabase_url, CONFIG_SUPABASE_URL, sizeof(supabase_url) - 1);
    strncpy(supabase_anon_key, CONFIG_SUPABASE_ANON_KEY, sizeof(supabase_anon_key) - 1);
    strncpy(esp32_device_key, CONFIG_ESP32_DEVICE_KEY, sizeof(esp32_device_key) - 1);

    /* Build Edge Function URL once */
    snprintf(edge_fn_url, sizeof(edge_fn_url),
             "%s/functions/v1/yappy-payment", supabase_url);

    ESP_LOGI(TAG, "============================================");
    ESP_LOGI(TAG, "Yappy handler initialized (Edge Function mode)");
    ESP_LOGI(TAG, "  Supabase URL:   [%s]", supabase_url);
    ESP_LOGI(TAG, "  Anon Key:       [%.20s...] (len=%d)",
             supabase_anon_key, (int)strlen(supabase_anon_key));
    ESP_LOGI(TAG, "  Device Key:     [%.10s...] (len=%d)",
             esp32_device_key, (int)strlen(esp32_device_key));
    ESP_LOGI(TAG, "  Edge Fn URL:    [%s]", edge_fn_url);
    ESP_LOGI(TAG, "  Subdomain:      [%s]", my_subdomain);
    ESP_LOGI(TAG, "============================================");

    if (strlen(supabase_url) == 0) {
        ESP_LOGW(TAG, "*** WARNING: Supabase URL is EMPTY! ***");
    }
    if (strlen(supabase_anon_key) == 0) {
        ESP_LOGW(TAG, "*** WARNING: Supabase Anon Key is EMPTY! ***");
    }
    if (strlen(esp32_device_key) == 0) {
        ESP_LOGW(TAG, "*** WARNING: ESP32 Device Key is EMPTY! ***");
    }
    if (strlen(my_subdomain) == 0) {
        ESP_LOGW(TAG, "*** WARNING: Subdomain is EMPTY! Set via NVS ***");
    }
}

/* ======================== HELPER: XOR ENCODE + BASE64 ==================== */
/*
 * XOR-encode the price/item into a 19-byte payload using the device passkey,
 * then base64-encode it.
 *
 * The Edge Function decodes this payload server-side using the device's
 * passkey from the 'embedded' table, matching the Android app's approach.
 */
static bool encode_payload_b64(uint16_t price, uint16_t item_number,
                                char *dest_b64, size_t dest_size)
{
    uint8_t payload[19];
    xorEncodeWithPasskey(0x0A, price, item_number, 0, payload);

    size_t b64_len = 0;
    int ret = mbedtls_base64_encode(
        (unsigned char *)dest_b64, dest_size, &b64_len,
        payload, sizeof(payload));

    if (ret != 0) {
        ESP_LOGE(TAG, "Base64 encode failed: %d", ret);
        return false;
    }
    dest_b64[b64_len] = '\0';
    return true;
}

/* ======================== HELPER: CALL EDGE FUNCTION ===================== */
/*
 * POST JSON body to the Supabase Edge Function.
 * Returns: HTTP status code (200 = success), or -1 on error.
 * Response body is stored in http_response_buf.
 */
static int call_edge_function(const char *json_body)
{
    if (!wifi_sta_connected) {
        ESP_LOGE(TAG, "WiFi STA not connected — cannot call Edge Function");
        return -1;
    }

    ESP_LOGW(TAG, "FREE HEAP before HTTPS: %lu bytes (internal=%lu)",
             (unsigned long)esp_get_free_heap_size(),
             (unsigned long)heap_caps_get_free_size(MALLOC_CAP_INTERNAL));

    /* Check system time — TLS cert verification needs correct date */
    {
        time_t now = 0;
        time(&now);
        struct tm timeinfo = {0};
        localtime_r(&now, &timeinfo);
        ESP_LOGW(TAG, "System time: %04d-%02d-%02d %02d:%02d:%02d (epoch=%ld)",
                 timeinfo.tm_year + 1900, timeinfo.tm_mon + 1, timeinfo.tm_mday,
                 timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec, (long)now);
        if (timeinfo.tm_year < (2024 - 1900)) {
            ESP_LOGE(TAG, "*** CLOCK NOT SYNCED (year=%d) — TLS cert verification will fail! ***",
                     timeinfo.tm_year + 1900);
            ESP_LOGE(TAG, "*** Waiting for SNTP sync... ***");
            /* Wait up to 15 seconds for SNTP */
            for (int i = 0; i < 30; i++) {
                vTaskDelay(pdMS_TO_TICKS(500));
                time(&now);
                localtime_r(&now, &timeinfo);
                if (timeinfo.tm_year >= (2024 - 1900)) {
                    ESP_LOGI(TAG, "SNTP synced! time=%04d-%02d-%02d %02d:%02d:%02d",
                             timeinfo.tm_year + 1900, timeinfo.tm_mon + 1, timeinfo.tm_mday,
                             timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
                    break;
                }
            }
            /* Re-check */
            time(&now);
            localtime_r(&now, &timeinfo);
            if (timeinfo.tm_year < (2024 - 1900)) {
                ESP_LOGE(TAG, "SNTP sync timeout — TLS will likely fail");
            }
        }
    }

    ESP_LOGI(TAG, "Calling Edge Function: %s", edge_fn_url);
    ESP_LOGI(TAG, "Request body: %s", json_body);

    /* Reset response buffer */
    http_response_len = 0;
    memset(http_response_buf, 0, HTTP_RESPONSE_BUF_SIZE);

    /* Configure HTTP client — use embedded GTS Root R4 cert instead of
     * the full bundle (which is missing the GlobalSign cross-signer). */
    esp_http_client_config_t config = {
        .url = edge_fn_url,
        .method = HTTP_METHOD_POST,
        .event_handler = http_event_handler,
        .cert_pem = (const char *)gts_root_r4_pem_start,
        .timeout_ms = 30000,   /* Edge Function may take time for Yappy API */
        .buffer_size = 2048,
        .buffer_size_tx = 1024,
    };

    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (!client) {
        ESP_LOGE(TAG, "Failed to init HTTP client");
        return -1;
    }

    /* Set headers */
    esp_http_client_set_header(client, "Content-Type", "application/json");
    esp_http_client_set_header(client, "apikey", supabase_anon_key);
    esp_http_client_set_header(client, "x-device-key", esp32_device_key);

    /* Set POST body */
    esp_http_client_set_post_field(client, json_body, strlen(json_body));

    /* Execute request */
    esp_err_t err = esp_http_client_perform(client);
    int status_code = esp_http_client_get_status_code(client);

    ESP_LOGI(TAG, "Edge Function response: err=%d (%s) status=%d len=%d",
             err, esp_err_to_name(err), status_code, http_response_len);

    esp_http_client_cleanup(client);

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "HTTPS request failed: %d (%s)", err, esp_err_to_name(err));
        return -1;
    }

    if (http_response_len > 0) {
        /* Log up to 500 chars of response */
        ESP_LOGI(TAG, "Response: %.500s%s", http_response_buf,
                 http_response_len > 500 ? "...(truncated)" : "");
    }

    return status_code;
}

/* ======================== GENERATE QR ==================================== */
/*
 * Calls Edge Function with action=generate-qr.
 * On success, stores qr_hash and transaction_id in yappy_state.
 *
 * Returns: true on success, false on failure.
 */
static bool generate_qr(uint16_t price, uint16_t item_number)
{
    ESP_LOGI(TAG, "Generating QR via Edge Function: price=%u item=%u", price, item_number);

    /* XOR-encode and base64-encode the payload */
    if (!encode_payload_b64(price, item_number,
                            stored_payload_b64, sizeof(stored_payload_b64))) {
        return false;
    }

    ESP_LOGI(TAG, "XOR payload (base64): %s", stored_payload_b64);

    /* Build JSON request body */
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "action", "generate-qr");
    cJSON_AddStringToObject(root, "payload", stored_payload_b64);
    cJSON_AddStringToObject(root, "subdomain", my_subdomain);

    char *json_str = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);

    if (!json_str) {
        ESP_LOGE(TAG, "Failed to build generate-qr JSON");
        return false;
    }

    /* Call Edge Function */
    int status = call_edge_function(json_str);
    free(json_str);

    if (status != 200) {
        ESP_LOGE(TAG, "generate-qr failed with HTTP %d", status);
        return false;
    }

    /* Parse response */
    cJSON *resp = cJSON_Parse(http_response_buf);
    if (!resp) {
        ESP_LOGE(TAG, "Failed to parse generate-qr response");
        return false;
    }

    /* Extract qr_hash and transaction_id */
    cJSON *qr_hash = cJSON_GetObjectItem(resp, "qr_hash");
    cJSON *txn_id = cJSON_GetObjectItem(resp, "transaction_id");
    cJSON *amount = cJSON_GetObjectItem(resp, "amount");

    if (!qr_hash || !cJSON_IsString(qr_hash) ||
        !txn_id || !cJSON_IsString(txn_id)) {
        ESP_LOGE(TAG, "Missing qr_hash or transaction_id in response");
        cJSON_Delete(resp);
        return false;
    }

    strncpy(yappy_state.qr_hash, qr_hash->valuestring,
            sizeof(yappy_state.qr_hash) - 1);
    strncpy(yappy_state.transaction_id, txn_id->valuestring,
            sizeof(yappy_state.transaction_id) - 1);

    if (amount && cJSON_IsNumber(amount)) {
        yappy_state.display_price = (float)amount->valuedouble;
    }

    cJSON_Delete(resp);
    ESP_LOGI(TAG, "QR generated: txn=%s amount=%.2f",
             yappy_state.transaction_id, yappy_state.display_price);
    return true;
}

/* ======================== CHECK STATUS =================================== */
/*
 * Calls Edge Function with action=check-status.
 *
 * Returns: true if payment is confirmed (PAGADO), false otherwise.
 *
 * When PAGADO is detected, the Edge Function automatically records the
 * sale in the database — no MQTT publish needed from the ESP32.
 */
static bool check_payment_status(void)
{
    /* Build JSON request body */
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "action", "check-status");
    cJSON_AddStringToObject(root, "transaction_id", yappy_state.transaction_id);
    cJSON_AddStringToObject(root, "payload", stored_payload_b64);
    cJSON_AddStringToObject(root, "subdomain", my_subdomain);

    char *json_str = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);

    if (!json_str) {
        ESP_LOGE(TAG, "Failed to build check-status JSON");
        return false;
    }

    /* Call Edge Function */
    int status = call_edge_function(json_str);
    free(json_str);

    if (status != 200) {
        ESP_LOGW(TAG, "check-status returned HTTP %d", status);
        return false;
    }

    /* Parse response */
    cJSON *resp = cJSON_Parse(http_response_buf);
    if (!resp) {
        ESP_LOGW(TAG, "Failed to parse check-status response");
        return false;
    }

    /* Check status field */
    cJSON *tx_status = cJSON_GetObjectItem(resp, "status");
    if (!tx_status || !cJSON_IsString(tx_status)) {
        ESP_LOGW(TAG, "No status field in check-status response");
        cJSON_Delete(resp);
        return false;
    }

    const char *status_str = tx_status->valuestring;
    ESP_LOGI(TAG, "Transaction %s status: %s",
             yappy_state.transaction_id, status_str);

    /* Check if status indicates payment */
    static const char *paid_statuses[] = {
        "PAGADO", "EJECUTADO", "COMPLETADO", "APROBADO",
        "PAID", "COMPLETED", "APPROVED"
    };

    bool is_paid = false;
    for (int i = 0; i < (int)(sizeof(paid_statuses) / sizeof(paid_statuses[0])); i++) {
        if (strcasecmp(status_str, paid_statuses[i]) == 0) {
            is_paid = true;
            break;
        }
    }

    cJSON_Delete(resp);
    return is_paid;
}

/* ======================== CANCEL TRANSACTION ============================= */
/*
 * Calls Edge Function with action=cancel.
 */
static void cancel_transaction(void)
{
    if (yappy_state.transaction_id[0] == '\0') return;

    ESP_LOGI(TAG, "Cancelling transaction: %s", yappy_state.transaction_id);

    /* Build JSON request body */
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "action", "cancel");
    cJSON_AddStringToObject(root, "transaction_id", yappy_state.transaction_id);

    char *json_str = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);

    if (!json_str) {
        ESP_LOGE(TAG, "Failed to build cancel JSON");
        return;
    }

    int status = call_edge_function(json_str);
    free(json_str);

    if (status == 200) {
        ESP_LOGI(TAG, "Transaction cancelled successfully");
    } else {
        ESP_LOGW(TAG, "Cancel returned HTTP %d", status);
    }
}

/* ======================== PUBLIC API ====================================== */

void yappy_request_qr(uint16_t price, uint16_t item_number)
{
    xSemaphoreTake(yappy_mutex, portMAX_DELAY);

    if (yappy_state.state != YAPPY_IDLE) {
        ESP_LOGW(TAG, "QR request ignored: state=%d (not IDLE)", yappy_state.state);
        xSemaphoreGive(yappy_mutex);
        return;
    }

    yappy_state.state = YAPPY_QR_PENDING;
    yappy_state.item_price = price;
    yappy_state.item_number = item_number;
    yappy_state.error_msg[0] = '\0';
    yappy_state.qr_hash[0] = '\0';
    yappy_state.transaction_id[0] = '\0';

    /* Convert MDB price to display price for dashboard */
    yappy_state.display_price = FROM_SCALE_FACTOR(price,
        CONFIG_MDB_SCALE_FACTOR, CONFIG_MDB_DECIMAL_PLACES);

    xSemaphoreGive(yappy_mutex);

    ESP_LOGI(TAG, "QR requested: price=%u (display=$%.2f) item=%u",
             price, yappy_state.display_price, item_number);
}

void yappy_cancel(void)
{
    xSemaphoreTake(yappy_mutex, portMAX_DELAY);

    if (yappy_state.state == YAPPY_IDLE) {
        xSemaphoreGive(yappy_mutex);
        return;
    }

    ESP_LOGI(TAG, "Cancelling Yappy payment");

    /* Cancel on server if we have a transaction */
    if (yappy_state.transaction_id[0] != '\0') {
        cancel_transaction();
    }

    /* Reset state */
    yappy_state.state = YAPPY_IDLE;
    yappy_state.qr_hash[0] = '\0';
    yappy_state.transaction_id[0] = '\0';
    yappy_state.error_msg[0] = '\0';

    /* Deny the pending vend so VMC returns to idle */
    if (machine_state == VEND_STATE) {
        vend_denied_todo = true;
    }

    xSemaphoreGive(yappy_mutex);
}

yappy_payment_state_t yappy_get_state(void)
{
    yappy_payment_state_t copy;

    xSemaphoreTake(yappy_mutex, portMAX_DELAY);
    memcpy(&copy, &yappy_state, sizeof(copy));
    xSemaphoreGive(yappy_mutex);

    return copy;
}

/* ======================== YAPPY POLL TASK ================================ */
/*
 * Main loop for the Yappy payment state machine.  Runs forever on Core 0.
 *
 * States handled:
 *   IDLE       -> sleep 500ms, nothing to do
 *   QR_PENDING -> call Edge Function generate-qr -> QR_READY or ERROR
 *   QR_READY   -> start polling (transition to POLLING)
 *   POLLING    -> call Edge Function check-status every 3s -> PAID or timeout
 *   PAID       -> hold for 5s (dashboard shows success), then -> IDLE
 *   ERROR      -> hold for 5s (dashboard shows error), then -> IDLE
 */
void vTaskYappyPoll(void *pvParameters)
{
    TickType_t poll_start_ticks = 0;
    const TickType_t POLL_TIMEOUT_TICKS = pdMS_TO_TICKS(5 * 60 * 1000); /* 5 min */

    for (;;) {
        xSemaphoreTake(yappy_mutex, portMAX_DELAY);
        yappy_state_t current_state = yappy_state.state;
        xSemaphoreGive(yappy_mutex);

        switch (current_state) {

        /* ---- IDLE: nothing to do ---- */
        case YAPPY_IDLE:
            vTaskDelay(pdMS_TO_TICKS(500));
            break;

        /* ---- QR_PENDING: call Edge Function to generate QR ---- */
        case YAPPY_QR_PENDING: {
            ESP_LOGI(TAG, "=== YAPPY_QR_PENDING: calling Edge Function ===");

            xSemaphoreTake(yappy_mutex, portMAX_DELAY);
            uint16_t price = yappy_state.item_price;
            uint16_t item = yappy_state.item_number;
            xSemaphoreGive(yappy_mutex);

            if (!generate_qr(price, item)) {
                xSemaphoreTake(yappy_mutex, portMAX_DELAY);
                yappy_state.state = YAPPY_ERROR;
                strncpy(yappy_state.error_msg, "Failed to generate QR code",
                        sizeof(yappy_state.error_msg) - 1);
                xSemaphoreGive(yappy_mutex);
                break;
            }

            /* Success - transition to QR_READY */
            xSemaphoreTake(yappy_mutex, portMAX_DELAY);
            yappy_state.state = YAPPY_QR_READY;
            xSemaphoreGive(yappy_mutex);

            /* Reset MDB session timer to prevent timeout while waiting */
            session_timer_reset_todo = true;

            ESP_LOGI(TAG, "QR ready, starting payment poll");
            break;
        }

        /* ---- QR_READY: start polling ---- */
        case YAPPY_QR_READY:
            xSemaphoreTake(yappy_mutex, portMAX_DELAY);
            yappy_state.state = YAPPY_POLLING;
            xSemaphoreGive(yappy_mutex);
            poll_start_ticks = xTaskGetTickCount();
            /* fall through to POLLING immediately */
            /* FALLTHROUGH */

        /* ---- POLLING: check status every 3 seconds ---- */
        case YAPPY_POLLING: {
            /* Check for polling timeout (5 minutes) */
            if ((xTaskGetTickCount() - poll_start_ticks) > POLL_TIMEOUT_TICKS) {
                ESP_LOGW(TAG, "Yappy polling timed out after 5 minutes");

                xSemaphoreTake(yappy_mutex, portMAX_DELAY);
                cancel_transaction();
                yappy_state.state = YAPPY_ERROR;
                strncpy(yappy_state.error_msg, "Payment timed out",
                        sizeof(yappy_state.error_msg) - 1);
                xSemaphoreGive(yappy_mutex);

                /* Deny the pending vend */
                if (machine_state == VEND_STATE) {
                    vend_denied_todo = true;
                }
                break;
            }

            /* Keep MDB session alive while polling */
            session_timer_reset_todo = true;

            /* Check payment status via Edge Function */
            if (check_payment_status()) {
                /* PAYMENT CONFIRMED! */
                ESP_LOGI(TAG, "*** PAYMENT CONFIRMED ***");

                xSemaphoreTake(yappy_mutex, portMAX_DELAY);
                yappy_state.state = YAPPY_PAID;
                xSemaphoreGive(yappy_mutex);

                /* Set vend_approved_todo — this is the key flag that makes
                 * the MDB task send VEND APPROVED to the VMC.
                 * Zero latency: no MQTT relay needed. */
                if (machine_state == VEND_STATE) {
                    vend_approved_todo = true;
                    ESP_LOGI(TAG, "vend_approved_todo set!");
                } else {
                    ESP_LOGW(TAG, "Payment confirmed but machine not in VEND_STATE (%d)",
                             machine_state);
                }

                /* Sale is recorded by the Edge Function automatically —
                 * no MQTT publish needed from the ESP32 */

            } else {
                /* Not paid yet — wait 3 seconds before next check */
                vTaskDelay(pdMS_TO_TICKS(3000));
            }
            break;
        }

        /* ---- PAID: show success, then reset ---- */
        case YAPPY_PAID:
            /* Hold success state for 5 seconds (dashboard shows checkmark) */
            vTaskDelay(pdMS_TO_TICKS(5000));

            xSemaphoreTake(yappy_mutex, portMAX_DELAY);
            yappy_state.state = YAPPY_IDLE;
            yappy_state.qr_hash[0] = '\0';
            yappy_state.transaction_id[0] = '\0';
            yappy_state.error_msg[0] = '\0';
            xSemaphoreGive(yappy_mutex);

            ESP_LOGI(TAG, "Payment cycle complete, returning to IDLE");
            break;

        /* ---- ERROR: show error, then reset ---- */
        case YAPPY_ERROR:
            /* Hold error state for 5 seconds (dashboard shows error) */
            vTaskDelay(pdMS_TO_TICKS(5000));

            xSemaphoreTake(yappy_mutex, portMAX_DELAY);
            yappy_state.state = YAPPY_IDLE;
            yappy_state.qr_hash[0] = '\0';
            yappy_state.transaction_id[0] = '\0';
            yappy_state.error_msg[0] = '\0';
            xSemaphoreGive(yappy_mutex);

            /* Deny the pending vend if still in VEND_STATE */
            if (machine_state == VEND_STATE) {
                vend_denied_todo = true;
            }

            ESP_LOGI(TAG, "Error cycle complete, returning to IDLE");
            break;
        }
    }
}
