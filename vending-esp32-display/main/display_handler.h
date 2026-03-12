/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * display_handler.h - ILI9488 SPI TFT display with LVGL graphics
 *
 * Drives a 4" IPS TFT (ILI9488, 320x480, SPI) to render the payment flow
 * directly on the vending machine — replacing the Android tablet + web
 * dashboard approach.
 *
 * The display shows four screens:
 *   - Idle:    VMflow logo + "Select a product on the machine"
 *   - Payment: Price + QR code + "Scan with Yappy app to pay"
 *   - Success: Checkmark + "Payment Confirmed!" + "Dispensing..."
 *   - Error:   Warning + error message
 *
 * Integration:
 *   vTaskDisplayUpdate() polls yappy_get_state() every 200ms and updates
 *   the LVGL screens when the Yappy state changes.  This mirrors the old
 *   web dashboard's 500ms HTTP polling but with direct memory access.
 *
 * Hardware:
 *   SPI2 host with DMA, pins defined in config.h (GPIO 35-41).
 *   LEDC PWM backlight on PIN_LCD_BL.
 *   Draw buffers allocated in PSRAM (double-buffered, 80 lines each).
 */

#ifndef DISPLAY_HANDLER_H
#define DISPLAY_HANDLER_H

#include <stdint.h>
#include <stdbool.h>

/* Display resolution */
#define LCD_H_RES       320
#define LCD_V_RES       480

/* SPI clock (ILI9488 supports up to 40 MHz for writes) */
#define LCD_SPI_CLK_HZ  (40 * 1000 * 1000)

/* LVGL draw buffer: lines to buffer (minimal for internal RAM — no PSRAM) */
#define LCD_DRAW_BUF_LINES  10

/**
 * @brief Initialize the ILI9488 SPI display and LVGL.
 *
 * Sets up:
 *   1. LEDC PWM backlight
 *   2. SPI2 bus with DMA
 *   3. esp_lcd panel IO (SPI) + ILI9488 panel driver
 *   4. LVGL via esp_lvgl_port (creates internal render task)
 *   5. Builds all four LVGL screens (idle, payment, success, error)
 *
 * Call from app_main() after yappy_init() but before task creation.
 */
void display_init(void);

/**
 * @brief FreeRTOS task: polls yappy_get_state() and updates the display.
 *
 * Runs every 200ms.  Detects state transitions and QR hash changes,
 * then switches LVGL screens and updates widget content accordingly.
 *
 * All LVGL API calls are protected by lvgl_port_lock/unlock.
 *
 * @param pvParameters  Unused (NULL).
 */
void vTaskDisplayUpdate(void *pvParameters);

/**
 * @brief Set backlight brightness (0-100%).
 *
 * Uses LEDC PWM on PIN_LCD_BL.
 *
 * @param brightness_pct  0 = off, 100 = full brightness.
 */
void display_set_backlight(uint8_t brightness_pct);

#endif /* DISPLAY_HANDLER_H */
