/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * display_handler.c - ILI9488 SPI TFT display with LVGL graphics
 *
 * Renders the Yappy payment flow directly on a 4" IPS TFT (ILI9488 320x480)
 * connected via SPI.  Replaces the Android tablet + web dashboard approach.
 *
 * Architecture:
 *   - SPI2 host with DMA drives the ILI9488
 *   - LVGL v9 handles all rendering (via esp_lvgl_port)
 *   - vTaskDisplayUpdate polls yappy_get_state() every 200ms
 *   - QR codes rendered via LVGL's built-in lv_qrcode widget
 *   - LEDC PWM controls backlight brightness
 */

#include "display_handler.h"
#include "config.h"
#include "yappy_handler.h"

#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_ili9488.h"
#include "esp_lvgl_port.h"
#include "driver/spi_master.h"
#include "driver/ledc.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_heap_caps.h"

#include "lvgl.h"

#define DISP_TAG "display"

/* ======================== LVGL SCREEN OBJECTS =========================== */
static lv_obj_t *scr_idle    = NULL;
static lv_obj_t *scr_payment = NULL;
static lv_obj_t *scr_success = NULL;
static lv_obj_t *scr_error   = NULL;

/* ======================== PAYMENT SCREEN WIDGETS ======================== */
static lv_obj_t *lbl_price     = NULL;
static lv_obj_t *lbl_item_info = NULL;
static lv_obj_t *qr_code_obj   = NULL;
static lv_obj_t *lbl_scan_msg  = NULL;
static lv_obj_t *spinner_obj   = NULL;

/* ======================== ERROR SCREEN WIDGETS ========================== */
static lv_obj_t *lbl_error_msg = NULL;

/* ======================== STATE TRACKING ================================ */
static yappy_state_t prev_display_state = YAPPY_IDLE;
static char prev_qr_hash[512] = {0};

/* ======================== COLOUR PALETTE (B&W only) ===================== */
#define COL_BG          lv_color_hex(0x000000)
#define COL_WHITE       lv_color_hex(0xFFFFFF)
#define COL_BLACK       lv_color_hex(0x000000)

/* ======================================================================== */
/*                          BACKLIGHT (LEDC PWM)                            */
/* ======================================================================== */

#define BL_LEDC_TIMER   LEDC_TIMER_1
#define BL_LEDC_CHANNEL LEDC_CHANNEL_1
#define BL_LEDC_FREQ    5000

static void backlight_init(void)
{
    ledc_timer_config_t timer_conf = {
        .speed_mode      = LEDC_LOW_SPEED_MODE,
        .timer_num       = BL_LEDC_TIMER,
        .duty_resolution = LEDC_TIMER_10_BIT,
        .freq_hz         = BL_LEDC_FREQ,
        .clk_cfg         = LEDC_AUTO_CLK,
    };
    ledc_timer_config(&timer_conf);

    ledc_channel_config_t ch_conf = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel    = BL_LEDC_CHANNEL,
        .timer_sel  = BL_LEDC_TIMER,
        .intr_type  = LEDC_INTR_DISABLE,
        .gpio_num   = PIN_LCD_BL,
        .duty       = 0,
        .hpoint     = 0,
    };
    ledc_channel_config(&ch_conf);
}

void display_set_backlight(uint8_t brightness_pct)
{
    if (brightness_pct > 100) brightness_pct = 100;
    uint32_t duty = (1023 * brightness_pct) / 100;
    ledc_set_duty(LEDC_LOW_SPEED_MODE, BL_LEDC_CHANNEL, duty);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, BL_LEDC_CHANNEL);
}

/* ======================================================================== */
/*                      SPI BUS + LCD PANEL INIT                            */
/* ======================================================================== */

static esp_lcd_panel_handle_t panel_handle = NULL;
static esp_lcd_panel_io_handle_t io_handle = NULL;

static void lcd_panel_init(void)
{
    ESP_LOGI(DISP_TAG, "Initializing SPI bus for ILI9488...");

    /* --- SPI bus --- */
    spi_bus_config_t bus_cfg = {
        .mosi_io_num     = PIN_LCD_MOSI,
        .miso_io_num     = PIN_LCD_MISO,
        .sclk_io_num     = PIN_LCD_CLK,
        .quadwp_io_num   = -1,
        .quadhd_io_num   = -1,
        .max_transfer_sz = LCD_H_RES * LCD_DRAW_BUF_LINES * 3, /* 18-bit = 3 bytes/pixel */
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO));

    /* --- Panel IO (SPI) --- */
    esp_lcd_panel_io_spi_config_t io_cfg = {
        .dc_gpio_num       = PIN_LCD_DC,
        .cs_gpio_num       = PIN_LCD_CS,
        .pclk_hz           = LCD_SPI_CLK_HZ,
        .lcd_cmd_bits      = 8,
        .lcd_param_bits    = 8,
        .spi_mode          = 0,
        .trans_queue_depth  = 10,
    };
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi(SPI2_HOST, &io_cfg, &io_handle));

    /* --- ILI9488 panel driver --- */
    esp_lcd_panel_dev_config_t panel_cfg = {
        .reset_gpio_num = PIN_LCD_RST,
        .rgb_ele_order  = LCD_RGB_ELEMENT_ORDER_BGR,
        .bits_per_pixel = 18,   /* ILI9488 SPI uses 18-bit colour */
    };
    /* buffer_size = PIXELS (driver allocates ×3 bytes internally for 18-bit).
     * Must be >= LVGL draw buffer size so the flush fits without overflow.
     * With LCD_DRAW_BUF_LINES=10: 3200 pixels → 9600 bytes DMA RAM. */
    const size_t ili_buf_size = LCD_H_RES * LCD_DRAW_BUF_LINES;
    ESP_ERROR_CHECK(esp_lcd_new_panel_ili9488(io_handle, &panel_cfg, ili_buf_size, &panel_handle));

    /* --- Reset and initialize --- */
    ESP_ERROR_CHECK(esp_lcd_panel_reset(panel_handle));
    ESP_ERROR_CHECK(esp_lcd_panel_init(panel_handle));

    /* Portrait orientation: 320 wide x 480 tall */
    ESP_ERROR_CHECK(esp_lcd_panel_swap_xy(panel_handle, false));
    ESP_ERROR_CHECK(esp_lcd_panel_mirror(panel_handle, false, false));

    /* Turn on display */
    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(panel_handle, true));

    ESP_LOGI(DISP_TAG, "ILI9488 panel initialized (%dx%d)", LCD_H_RES, LCD_V_RES);
}

/* ======================================================================== */
/*                           LVGL INIT                                      */
/* ======================================================================== */

static lv_display_t *lvgl_display = NULL;

static void lvgl_init(void)
{
    ESP_LOGI(DISP_TAG, "Initializing LVGL via esp_lvgl_port...");

    /* --- LVGL port init (increase task stack for WiFi-heavy build) --- */
    lvgl_port_cfg_t port_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    port_cfg.task_stack = 16384;  /* Proven in hello-display; ILI9488 18-bit conversion needs headroom */
    ESP_ERROR_CHECK(lvgl_port_init(&port_cfg));

    /* --- Add display --- */
    const lvgl_port_display_cfg_t disp_cfg = {
        .io_handle   = io_handle,
        .panel_handle = panel_handle,
        .buffer_size  = LCD_H_RES * LCD_DRAW_BUF_LINES,  /* pixels, NOT bytes! Port multiplies by color_bytes internally */
        .double_buffer = false,   /* Single buffer to save internal RAM */
        .hres         = LCD_H_RES,
        .vres         = LCD_V_RES,
        .monochrome   = false,
        .rotation = {
            .swap_xy  = false,
            .mirror_x = false,
            .mirror_y = false,
        },
        .flags = {
            .buff_dma    = true,    /* SPI display needs DMA-capable buffer */
            .buff_spiram = false,   /* No PSRAM — use internal DMA-capable RAM */
        },
    };
    lvgl_display = lvgl_port_add_disp(&disp_cfg);
    assert(lvgl_display != NULL);

    ESP_LOGI(DISP_TAG, "LVGL display added (%dx%d, single-buffer in internal RAM)", LCD_H_RES, LCD_V_RES);
}

/* ======================================================================== */
/*                         SCREEN BUILDERS                                  */
/* ======================================================================== */

/* Helper: set up a screen with dark background and centered flex layout */
static void setup_screen(lv_obj_t *scr, lv_color_t bg_color)
{
    lv_obj_set_style_bg_color(scr, bg_color, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_flex_flow(scr, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(scr, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_row(scr, 10, LV_PART_MAIN);
}

/* Helper: create a styled label */
static lv_obj_t *make_label(lv_obj_t *parent, const char *text,
                             const lv_font_t *font, lv_color_t color)
{
    lv_obj_t *lbl = lv_label_create(parent);
    lv_label_set_text(lbl, text);
    lv_obj_set_style_text_font(lbl, font, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl, color, LV_PART_MAIN);
    lv_obj_set_style_text_align(lbl, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_label_set_long_mode(lbl, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(lbl, LCD_H_RES - 40);
    return lbl;
}

static void build_idle_screen(void)
{
    scr_idle = lv_obj_create(NULL);
    setup_screen(scr_idle, COL_BG);

    /* Spacer */
    lv_obj_t *spacer = lv_obj_create(scr_idle);
    lv_obj_set_size(spacer, 1, 40);
    lv_obj_set_style_bg_opa(spacer, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(spacer, 0, LV_PART_MAIN);

    /* Logo */
    make_label(scr_idle, "VMflow", &lv_font_montserrat_36, COL_WHITE);

    /* Subtitle */
    make_label(scr_idle, "Select a product\non the machine", &lv_font_montserrat_20, COL_WHITE);

    /* Cart icon circle */
    lv_obj_t *circle = lv_obj_create(scr_idle);
    lv_obj_set_size(circle, 120, 120);
    lv_obj_set_style_radius(circle, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_color(circle, COL_BLACK, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(circle, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_color(circle, COL_WHITE, LV_PART_MAIN);
    lv_obj_set_style_border_width(circle, 3, LV_PART_MAIN);
    lv_obj_set_flex_flow(circle, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(circle, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);

    lv_obj_t *cart_icon = lv_label_create(circle);
    lv_label_set_text(cart_icon, LV_SYMBOL_DOWNLOAD);
    lv_obj_set_style_text_font(cart_icon, &lv_font_montserrat_36, LV_PART_MAIN);
    lv_obj_set_style_text_color(cart_icon, COL_WHITE, LV_PART_MAIN);
}

static void build_payment_screen(void)
{
    scr_payment = lv_obj_create(NULL);
    setup_screen(scr_payment, COL_BG);

    /* Price */
    lbl_price = make_label(scr_payment, "$0.00", &lv_font_montserrat_48, COL_WHITE);

    /* Item info */
    lbl_item_info = make_label(scr_payment, "Item #0", &lv_font_montserrat_14, COL_WHITE);

    /* QR code container (white background) */
    lv_obj_t *qr_container = lv_obj_create(scr_payment);
    lv_obj_set_size(qr_container, 220, 220);
    lv_obj_set_style_bg_color(qr_container, COL_WHITE, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(qr_container, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_radius(qr_container, 12, LV_PART_MAIN);
    lv_obj_set_style_border_width(qr_container, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(qr_container, 10, LV_PART_MAIN);
    lv_obj_set_flex_flow(qr_container, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(qr_container, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);

    /* QR code widget */
    qr_code_obj = lv_qrcode_create(qr_container);
    lv_qrcode_set_size(qr_code_obj, 200);
    lv_qrcode_set_dark_color(qr_code_obj, COL_BLACK);
    lv_qrcode_set_light_color(qr_code_obj, COL_WHITE);

    /* Scan message with spinner */
    lv_obj_t *status_row = lv_obj_create(scr_payment);
    lv_obj_set_size(status_row, LCD_H_RES - 40, LV_SIZE_CONTENT);
    lv_obj_set_style_bg_opa(status_row, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(status_row, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(status_row, 0, LV_PART_MAIN);
    lv_obj_set_flex_flow(status_row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(status_row, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(status_row, 8, LV_PART_MAIN);

    spinner_obj = lv_spinner_create(status_row);
    lv_spinner_set_anim_params(spinner_obj, 1000, 270);
    lv_obj_set_size(spinner_obj, 24, 24);
    lv_obj_set_style_arc_color(spinner_obj, COL_WHITE, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(spinner_obj, lv_color_hex(0x333333), LV_PART_MAIN);
    lv_obj_set_style_arc_width(spinner_obj, 3, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(spinner_obj, 3, LV_PART_MAIN);

    lbl_scan_msg = lv_label_create(status_row);
    lv_label_set_text(lbl_scan_msg, "Scan with Yappy app to pay");
    lv_obj_set_style_text_font(lbl_scan_msg, &lv_font_montserrat_14, LV_PART_MAIN);
    lv_obj_set_style_text_color(lbl_scan_msg, COL_WHITE, LV_PART_MAIN);

    /* Powered by Yappy */
    lv_obj_t *brand = make_label(scr_payment, "Powered by Yappy", &lv_font_montserrat_14, COL_WHITE);
    lv_obj_set_style_opa(brand, LV_OPA_70, LV_PART_MAIN);
}

static void build_success_screen(void)
{
    scr_success = lv_obj_create(NULL);
    setup_screen(scr_success, COL_BG);

    /* Spacer */
    lv_obj_t *spacer = lv_obj_create(scr_success);
    lv_obj_set_size(spacer, 1, 60);
    lv_obj_set_style_bg_opa(spacer, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(spacer, 0, LV_PART_MAIN);

    /* Check icon */
    make_label(scr_success, LV_SYMBOL_OK, &lv_font_montserrat_48, COL_WHITE);

    /* Text */
    make_label(scr_success, "Payment Confirmed!", &lv_font_montserrat_28, COL_WHITE);
    make_label(scr_success, "Dispensing your product...", &lv_font_montserrat_20, COL_WHITE);
}

static void build_error_screen(void)
{
    scr_error = lv_obj_create(NULL);
    setup_screen(scr_error, COL_BG);

    /* Spacer */
    lv_obj_t *spacer = lv_obj_create(scr_error);
    lv_obj_set_size(spacer, 1, 60);
    lv_obj_set_style_bg_opa(spacer, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(spacer, 0, LV_PART_MAIN);

    /* Warning icon */
    make_label(scr_error, LV_SYMBOL_WARNING, &lv_font_montserrat_48, COL_WHITE);

    /* Text */
    make_label(scr_error, "Payment Error", &lv_font_montserrat_28, COL_WHITE);

    lbl_error_msg = make_label(scr_error, "Something went wrong", &lv_font_montserrat_14, COL_WHITE);
}

/* ======================================================================== */
/*                        QR CODE UPDATE                                    */
/* ======================================================================== */

static void update_qr_code(const char *data)
{
    if (qr_code_obj == NULL || data == NULL || data[0] == '\0') return;

    lv_result_t res = lv_qrcode_update(qr_code_obj, data, strlen(data));
    if (res != LV_RESULT_OK) {
        ESP_LOGE(DISP_TAG, "QR code update failed for data len=%d", (int)strlen(data));
    }
}

/* ======================================================================== */
/*                       DISPLAY INIT (PUBLIC)                              */
/* ======================================================================== */

void display_init(void)
{
    ESP_LOGI(DISP_TAG, "=== ILI9488 Display Init ===");

    /* Step 1: Backlight PWM */
    backlight_init();
    display_set_backlight(0);   /* Off until screens are ready */

    /* Step 2: SPI bus + ILI9488 panel */
    lcd_panel_init();

    /* Step 3: LVGL */
    lvgl_init();

    /* Step 4: Build all screens (with LVGL lock) */
    lvgl_port_lock(-1);
    build_idle_screen();
    build_payment_screen();
    build_success_screen();
    build_error_screen();

    /* Start on idle screen */
    lv_screen_load(scr_idle);
    lvgl_port_unlock();

    /* Step 5: Turn on backlight */
    display_set_backlight(100);

    ESP_LOGI(DISP_TAG, "Display initialized: ILI9488 320x480 via SPI2");
}

/* ======================================================================== */
/*                    DISPLAY UPDATE TASK (PUBLIC)                          */
/* ======================================================================== */

void vTaskDisplayUpdate(void *pvParameters)
{
    ESP_LOGI(DISP_TAG, "Display update task started");

    for (;;) {
        yappy_payment_state_t ys = yappy_get_state();

        if (ys.state != prev_display_state) {
            /* State transition detected */
            lvgl_port_lock(-1);

            switch (ys.state) {
            case YAPPY_IDLE:
                lv_screen_load(scr_idle);
                break;

            case YAPPY_QR_PENDING:
                /* Show payment screen with price, QR will appear later */
                lv_label_set_text_fmt(lbl_price, "$%.2f", ys.display_price);
                lv_label_set_text_fmt(lbl_item_info, "Item #%u", ys.item_number);
                lv_label_set_text(lbl_scan_msg, "Generating QR code...");
                lv_screen_load(scr_payment);
                break;

            case YAPPY_QR_READY:
            case YAPPY_POLLING:
                /* Update price and QR code */
                lv_label_set_text_fmt(lbl_price, "$%.2f", ys.display_price);
                lv_label_set_text_fmt(lbl_item_info, "Item #%u", ys.item_number);
                lv_label_set_text(lbl_scan_msg, "Scan with Yappy app to pay");
                if (ys.qr_hash[0] != '\0' && strcmp(ys.qr_hash, prev_qr_hash) != 0) {
                    update_qr_code(ys.qr_hash);
                    strncpy(prev_qr_hash, ys.qr_hash, sizeof(prev_qr_hash) - 1);
                    prev_qr_hash[sizeof(prev_qr_hash) - 1] = '\0';
                }
                if (prev_display_state != YAPPY_QR_READY &&
                    prev_display_state != YAPPY_POLLING &&
                    prev_display_state != YAPPY_QR_PENDING) {
                    lv_screen_load(scr_payment);
                }
                break;

            case YAPPY_PAID:
                lv_screen_load(scr_success);
                prev_qr_hash[0] = '\0';
                break;

            case YAPPY_ERROR:
                lv_label_set_text(lbl_error_msg, ys.error_msg[0] ? ys.error_msg : "Something went wrong");
                lv_screen_load(scr_error);
                prev_qr_hash[0] = '\0';
                break;
            }

            lvgl_port_unlock();
            prev_display_state = ys.state;

        } else if (ys.state == YAPPY_QR_READY || ys.state == YAPPY_POLLING) {
            /* Same state but QR hash might have changed */
            if (ys.qr_hash[0] != '\0' && strcmp(ys.qr_hash, prev_qr_hash) != 0) {
                lvgl_port_lock(-1);
                update_qr_code(ys.qr_hash);
                strncpy(prev_qr_hash, ys.qr_hash, sizeof(prev_qr_hash) - 1);
                prev_qr_hash[sizeof(prev_qr_hash) - 1] = '\0';
                lvgl_port_unlock();
            }
        }

        vTaskDelay(pdMS_TO_TICKS(200));
    }
}
