#include <string.h>
#include "freertos/FreeRTOS.h"
#include "esp_wifi.h"
#include "esp_http_server.h"
#include "nvs_flash.h"
#include "cJSON.h"
#include <esp_log.h>
#include "lwip/udp.h"
#include "lwip/ip_addr.h"
#include "esp_chip_info.h"
#include "webui_server.h"
#include "config.h"
#include "yappy_handler.h"

#undef  TAG
#define TAG "webui"

#define AP_SSID  "VMflow"
#define AP_PASS  "12345678"

static httpd_handle_t rest_server = NULL;

extern const uint8_t index_html_start[] asm("_binary_index_html_start");
extern const uint8_t index_html_end[] asm("_binary_index_html_end");

extern const uint8_t dashboard_html_start[] asm("_binary_dashboard_html_start");
extern const uint8_t dashboard_html_end[] asm("_binary_dashboard_html_end");

static struct udp_pcb *dns_pcb;

/* -----------     DNS     ---------- */

static void dns_recv(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *addr, u16_t port) {

    if (!p) return;

    // resposta básica DNS (eco + IP do AP)
    uint8_t *data = (uint8_t *)p->payload;

    // QR = response
    data[2] |= 0x80;
    // RA = recursion available
    data[3] |= 0x80;
    // ANCOUNT = 1
    data[7] = 1;

    // append resposta A (IPv4)
    uint8_t response[] = {
        0xC0, 0x0C,     // pointer to domain name
        0x00, 0x01,     // type A
        0x00, 0x01,     // class IN
        0x00, 0x00, 0x00, 0x3C, // TTL
        0x00, 0x04,     // data length
        192, 168, 4, 1  // IP do ESP32 AP
    };

    struct pbuf *resp = pbuf_alloc(PBUF_TRANSPORT, p->len + sizeof(response), PBUF_RAM);

    memcpy(resp->payload, data, p->len);
    memcpy((uint8_t *)resp->payload + p->len, response, sizeof(response));

    udp_sendto(pcb, resp, addr, port);

    pbuf_free(resp);
    pbuf_free(p);
}

/* ---------- HTTP HANDLER ---------- */

static esp_err_t index_get_handler(httpd_req_t *req) {

    const size_t html_len = index_html_end - index_html_start;

    httpd_resp_set_type(req, "text/html");
    httpd_resp_send(req, (const char *) index_html_start, html_len);
    return ESP_OK;
}

static esp_err_t wifi_set_handler(httpd_req_t *req) {

    char buf[512];   // ajuste se necessário
    int total_len = req->content_len;
    int cur_len = 0;
    int received;

    if (total_len >= sizeof(buf)) {
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Payload too large");
        return ESP_FAIL;
    }

    while (cur_len < total_len) {
        received = httpd_req_recv(req, buf + cur_len, total_len - cur_len);
        if (received <= 0) {
            httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Failed to receive data");
            return ESP_FAIL;
        }
        cur_len += received;
    }

    buf[total_len] = '\0';  // garante string válida

    /* ---- PRINT DO JSON RECEBIDO ---- */
    ESP_LOGI(TAG, "JSON recebido: %s", buf);

    /* ---- Parse opcional (exemplo) ---- */
    cJSON *root = cJSON_Parse(buf);
    if (!root) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Invalid JSON");
        return ESP_FAIL;
    }

    cJSON *ssid = cJSON_GetObjectItem(root, "ssid");
    cJSON *password = cJSON_GetObjectItem(root, "password");
    cJSON *mqtt = cJSON_GetObjectItem(root, "mqtt_server");

    if (ssid && password && mqtt) {
        ESP_LOGI(TAG, "SSID=%s", ssid->valuestring);
        ESP_LOGI(TAG, "PASSWORD=%s", password->valuestring);
        ESP_LOGI(TAG, "MQTT=%s", mqtt->valuestring);

        //
        wifi_config_t wifi_config = {0};
        esp_wifi_get_config(WIFI_IF_STA, &wifi_config);

        strncpy((char*) wifi_config.sta.ssid, ssid->valuestring, sizeof(wifi_config.sta.ssid) - 1);
        strncpy((char*) wifi_config.sta.password, password->valuestring, sizeof(wifi_config.sta.password) - 1);

        esp_wifi_set_config(WIFI_IF_STA, &wifi_config);
        esp_wifi_connect();
        //

        nvs_handle_t handle;
        if (nvs_open("vmflow", NVS_READWRITE, &handle) == ESP_OK) {

            nvs_set_str(handle, "mqtt", mqtt->valuestring);
            nvs_commit(handle);

            nvs_close(handle);
            ESP_LOGI(TAG, "MQTT Server updated to: %s", buf);
        }
    }

    cJSON_Delete(root);

    return ESP_OK;
}

static esp_err_t system_info_get_handler(httpd_req_t *req) {

    /* Chip info */
    esp_chip_info_t chip_info;
    esp_chip_info(&chip_info);

    /* Wi-Fi config */
    wifi_config_t wifi_cfg = {0};
    esp_wifi_get_config(WIFI_IF_STA, &wifi_cfg);

    /* MQTT server (NVS) */
    char mqtt_server[32] = "";
    nvs_handle_t handle;
    size_t s_len = sizeof(mqtt_server);

    if (nvs_open("vmflow", NVS_READONLY, &handle) == ESP_OK) {
        nvs_get_str(handle, "mqtt", mqtt_server, &s_len);
        nvs_close(handle);
    }

    cJSON *root = cJSON_CreateObject();

     /* Populate JSON */
    cJSON_AddStringToObject(root, "version", IDF_VER);
    cJSON_AddNumberToObject(root, "cores", chip_info.cores);
    cJSON_AddNumberToObject(root, "model", chip_info.model);
    cJSON_AddStringToObject(root, "wifi_ssid", (char *)wifi_cfg.sta.ssid);
    cJSON_AddStringToObject(root, "wifi_password", (char *)wifi_cfg.sta.password);
    cJSON_AddStringToObject(root, "mqtt_server", mqtt_server);

    char *json_str = cJSON_PrintUnformatted(root);

    /* HTTP response */
    httpd_resp_set_type(req, "application/json");
    httpd_resp_send(req, json_str, HTTPD_RESP_USE_STRLEN);

    cJSON_free(json_str);
    cJSON_Delete(root);

    return ESP_OK;
}

static esp_err_t captive_handler(httpd_req_t *req) {

    ESP_LOGI(TAG, "Captive portal redirect: %s", req->uri);

    httpd_resp_set_status(req, "302 Found");
    httpd_resp_set_hdr(req, "Location", "/dashboard");
    httpd_resp_send(req, NULL, 0);

    return ESP_OK;
}

/* ---------- DASHBOARD HANDLERS ---------- */

/*
 * GET /dashboard - Serve the kiosk dashboard HTML
 *
 * This is the main page displayed on the Android tablet mounted on the
 * vending machine.  It polls /api/v1/vending/state every 500ms to update
 * the display (idle, QR code, payment status, etc.).
 */
static esp_err_t dashboard_get_handler(httpd_req_t *req) {

    const size_t html_len = dashboard_html_end - dashboard_html_start;

    httpd_resp_set_type(req, "text/html");
    httpd_resp_send(req, (const char *) dashboard_html_start, html_len);
    return ESP_OK;
}

/*
 * GET /api/v1/vending/state - Return current machine and Yappy state as JSON
 *
 * Called every 500ms by the dashboard.  Returns:
 *   machine_state:  0-4 (INACTIVE through VEND)
 *   yappy_state:    "IDLE", "QR_PENDING", "QR_READY", "POLLING", "PAID", "ERROR"
 *   qr_hash:        QR code data string (empty if not in QR_READY/POLLING)
 *   item_price:     Item price in MDB scale-factor units
 *   item_number:    Item/slot number
 *   error:          Error message (empty if no error)
 */
static esp_err_t vending_state_get_handler(httpd_req_t *req) {

    yappy_payment_state_t ys = yappy_get_state();

    /* Map enum to string */
    const char *state_str;
    switch (ys.state) {
        case YAPPY_IDLE:       state_str = "IDLE";       break;
        case YAPPY_QR_PENDING: state_str = "QR_PENDING"; break;
        case YAPPY_QR_READY:   state_str = "QR_READY";   break;
        case YAPPY_POLLING:    state_str = "POLLING";     break;
        case YAPPY_PAID:       state_str = "PAID";        break;
        case YAPPY_ERROR:      state_str = "ERROR";       break;
        default:               state_str = "UNKNOWN";     break;
    }

    cJSON *root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "wifi_connected", wifi_sta_connected);
    cJSON_AddBoolToObject(root, "mqtt_connected", mqtt_started);
    cJSON_AddNumberToObject(root, "machine_state", (int)machine_state);
    cJSON_AddStringToObject(root, "yappy_state", state_str);
    cJSON_AddStringToObject(root, "qr_hash", ys.qr_hash);
    cJSON_AddNumberToObject(root, "item_price", ys.item_price);
    cJSON_AddNumberToObject(root, "item_number", ys.item_number);
    cJSON_AddStringToObject(root, "error", ys.error_msg);

    char *json_str = cJSON_PrintUnformatted(root);

    httpd_resp_set_type(req, "application/json");
    /* Allow cross-origin requests from any tablet browser */
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(req, json_str, HTTPD_RESP_USE_STRLEN);

    cJSON_free(json_str);
    cJSON_Delete(root);

    return ESP_OK;
}

/*
 * POST /api/v1/vending/cancel - Cancel the current Yappy payment
 *
 * Called when the user taps the Cancel button on the dashboard.
 * Cancels the Yappy transaction and denies the pending vend.
 */
static esp_err_t vending_cancel_handler(httpd_req_t *req) {

    ESP_LOGI(TAG, "Cancel requested from dashboard");

    yappy_cancel();

    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(req, "{\"status\":\"cancelled\"}", HTTPD_RESP_USE_STRLEN);

    return ESP_OK;
}

/* ---------- YAPPY SETTINGS HANDLERS ---------- */

/*
 * GET /api/v1/yappy/settings - Return current Yappy credentials
 *
 * Returns all Yappy configuration values (for populating a settings form).
 * Note: keys are shown in full for configuration purposes.
 */
static esp_err_t yappy_settings_get_handler(httpd_req_t *req) {

    /* Read from NVS (or get empty strings) */
    char api_key[128] = "";
    char secret_key[128] = "";
    char base_url[128] = "";
    char id_device[64] = "";
    char name_device[64] = "";
    char user_device[64] = "";
    char id_group[64] = "";

    nvs_handle_t handle;
    if (nvs_open("yappy", NVS_READONLY, &handle) == ESP_OK) {
        size_t len;

        len = sizeof(api_key);
        nvs_get_str(handle, "api_key", api_key, &len);

        len = sizeof(secret_key);
        nvs_get_str(handle, "secret_key", secret_key, &len);

        len = sizeof(base_url);
        if (nvs_get_str(handle, "base_url", base_url, &len) != ESP_OK) {
            strncpy(base_url, CONFIG_YAPPY_BASE_URL, sizeof(base_url) - 1);
        }

        len = sizeof(id_device);
        nvs_get_str(handle, "id_device", id_device, &len);

        len = sizeof(name_device);
        if (nvs_get_str(handle, "name_device", name_device, &len) != ESP_OK) {
            strncpy(name_device, CONFIG_YAPPY_NAME_DEVICE, sizeof(name_device) - 1);
        }

        len = sizeof(user_device);
        nvs_get_str(handle, "user_device", user_device, &len);

        len = sizeof(id_group);
        nvs_get_str(handle, "id_group", id_group, &len);

        nvs_close(handle);
    } else {
        /* No NVS namespace yet — use Kconfig defaults */
        strncpy(base_url, CONFIG_YAPPY_BASE_URL, sizeof(base_url) - 1);
        strncpy(name_device, CONFIG_YAPPY_NAME_DEVICE, sizeof(name_device) - 1);
    }

    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "base_url", base_url);
    cJSON_AddStringToObject(root, "api_key", api_key);
    cJSON_AddStringToObject(root, "secret_key", secret_key);
    cJSON_AddStringToObject(root, "id_device", id_device);
    cJSON_AddStringToObject(root, "name_device", name_device);
    cJSON_AddStringToObject(root, "user_device", user_device);
    cJSON_AddStringToObject(root, "id_group", id_group);

    char *json_str = cJSON_PrintUnformatted(root);

    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(req, json_str, HTTPD_RESP_USE_STRLEN);

    cJSON_free(json_str);
    cJSON_Delete(root);

    return ESP_OK;
}

/*
 * POST /api/v1/yappy/settings - Save Yappy credentials to NVS
 *
 * Accepts JSON with any of: base_url, api_key, secret_key,
 * id_device, name_device, user_device, id_group.
 * Stores them in NVS "yappy" namespace.
 * ESP32 must be restarted (or credentials reloaded) for changes to take effect.
 */
static esp_err_t yappy_settings_post_handler(httpd_req_t *req) {

    char buf[1024];
    int total_len = req->content_len;
    int cur_len = 0;
    int received;

    if (total_len >= (int)sizeof(buf)) {
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Payload too large");
        return ESP_FAIL;
    }

    while (cur_len < total_len) {
        received = httpd_req_recv(req, buf + cur_len, total_len - cur_len);
        if (received <= 0) {
            httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Failed to receive data");
            return ESP_FAIL;
        }
        cur_len += received;
    }
    buf[total_len] = '\0';

    ESP_LOGI(TAG, "Yappy settings received: %s", buf);

    cJSON *root = cJSON_Parse(buf);
    if (!root) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Invalid JSON");
        return ESP_FAIL;
    }

    nvs_handle_t handle;
    esp_err_t err = nvs_open("yappy", NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to open NVS yappy namespace: %s", esp_err_to_name(err));
        cJSON_Delete(root);
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "NVS error");
        return ESP_FAIL;
    }

    /* Save each field if present in JSON */
    cJSON *item;

    item = cJSON_GetObjectItem(root, "base_url");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "base_url", item->valuestring);

    item = cJSON_GetObjectItem(root, "api_key");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "api_key", item->valuestring);

    item = cJSON_GetObjectItem(root, "secret_key");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "secret_key", item->valuestring);

    item = cJSON_GetObjectItem(root, "id_device");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "id_device", item->valuestring);

    item = cJSON_GetObjectItem(root, "name_device");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "name_device", item->valuestring);

    item = cJSON_GetObjectItem(root, "user_device");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "user_device", item->valuestring);

    item = cJSON_GetObjectItem(root, "id_group");
    if (item && cJSON_IsString(item)) nvs_set_str(handle, "id_group", item->valuestring);

    nvs_commit(handle);
    nvs_close(handle);

    cJSON_Delete(root);

    ESP_LOGI(TAG, "Yappy settings saved to NVS — restart ESP32 to apply");

    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_send(req, "{\"status\":\"saved\",\"note\":\"Restart ESP32 to apply changes\"}", HTTPD_RESP_USE_STRLEN);

    return ESP_OK;
}

void stop_dns_server(void) {

    if (dns_pcb == NULL) {
        ESP_LOGW(TAG, "DNS captive portal não está ativo");
        return;
    }

    /* Remove callback */
    udp_recv(dns_pcb, NULL, NULL);

    /* Fecha e libera o PCB */
    udp_remove(dns_pcb);
    dns_pcb = NULL;

    ESP_LOGI(TAG, "DNS captive portal parado");
}

void start_dns_server(void) {

    dns_pcb = udp_new();
    udp_bind(dns_pcb, IP_ADDR_ANY, 53);
    udp_recv(dns_pcb, dns_recv, NULL);

    ESP_LOGI(TAG, "DNS captive portal iniciado");
}

void stop_rest_server(void) {

    if (rest_server == NULL) {
        ESP_LOGW(TAG, "REST server not running");
        return;
    }
    httpd_stop(rest_server);

    rest_server = NULL;
}

void start_rest_server(void) {

    if (rest_server != NULL) {
        ESP_LOGW(TAG, "REST server already running");
        return;
    }

    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.max_uri_handlers = 12;  /* Increased for dashboard + API endpoints */
    config.max_open_sockets = 4;   /* Reduced from default 7 to leave sockets for Yappy HTTPS */
    httpd_start(&rest_server, &config);

    /* --- Original config/setup endpoints --- */

    httpd_uri_t index = {
        .uri      = "/",
        .method   = HTTP_GET,
        .handler  = index_get_handler
    };
    httpd_register_uri_handler(rest_server, &index);

    httpd_uri_t system_info_get_uri = {
        .uri = "/api/v1/system/info",
        .method = HTTP_GET,
        .handler = system_info_get_handler
    };
    httpd_register_uri_handler(rest_server, &system_info_get_uri);

    httpd_uri_t settings_set_uri = {
        .uri = "/api/v1/settings/set",
        .method = HTTP_POST,
        .handler = wifi_set_handler,
    };
    httpd_register_uri_handler(rest_server, &settings_set_uri);

    httpd_uri_t and_generate_204 = {
        .uri = "/generate_204",
        .method = HTTP_GET,
        .handler = captive_handler
    };
    httpd_register_uri_handler(rest_server, &and_generate_204);

    httpd_uri_t ios_generate_204 = {
        .uri = "/hotspot-detect.html",
        .method = HTTP_GET,
        .handler = captive_handler
    };
    httpd_register_uri_handler(rest_server, &ios_generate_204);

    /* --- Dashboard + Vending API endpoints --- */

    httpd_uri_t dashboard = {
        .uri      = "/dashboard",
        .method   = HTTP_GET,
        .handler  = dashboard_get_handler
    };
    httpd_register_uri_handler(rest_server, &dashboard);

    httpd_uri_t vending_state = {
        .uri      = "/api/v1/vending/state",
        .method   = HTTP_GET,
        .handler  = vending_state_get_handler
    };
    httpd_register_uri_handler(rest_server, &vending_state);

    httpd_uri_t vending_cancel = {
        .uri      = "/api/v1/vending/cancel",
        .method   = HTTP_POST,
        .handler  = vending_cancel_handler
    };
    httpd_register_uri_handler(rest_server, &vending_cancel);

    /* --- Yappy settings endpoints --- */

    httpd_uri_t yappy_settings_get = {
        .uri      = "/api/v1/yappy/settings",
        .method   = HTTP_GET,
        .handler  = yappy_settings_get_handler
    };
    httpd_register_uri_handler(rest_server, &yappy_settings_get);

    httpd_uri_t yappy_settings_post = {
        .uri      = "/api/v1/yappy/settings",
        .method   = HTTP_POST,
        .handler  = yappy_settings_post_handler
    };
    httpd_register_uri_handler(rest_server, &yappy_settings_post);

    ESP_LOGI(TAG, "REST server started with %d endpoints", 10);
}

/* ---------- WIFI SOFTAP ---------- */
void start_softap(void) {

    wifi_config_t wifi_config = {
        .ap = {
            .ssid = AP_SSID,
            .ssid_len = strlen(AP_SSID),
            .password = AP_PASS,
            .max_connection = 4,
            .authmode = WIFI_AUTH_WPA_WPA2_PSK
        },
    };
    esp_wifi_set_config(WIFI_IF_AP, &wifi_config);
}