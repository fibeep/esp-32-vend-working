/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * led_status.c - WS2812 LED status indicator and buzzer controller
 *
 * This module provides visual and audible feedback about the device's
 * operational state.  It runs as a FreeRTOS task on Core 0 and blocks
 * on the xLedEventGroup event group, waking up whenever another module
 * sets BIT_EVT_TRIGGER.
 *
 * LED colour mapping:
 *   YELLOW (80,60,0)  - Not installed: passkey or subdomain missing
 *   GREEN  (10,80,10) - Fully operational: MDB + Internet
 *   BLUE   (5,15,80)  - MDB active but no Internet connection
 *   RED    (80,5,5)   - Inactive or disabled state
 *
 * Buzzer:
 *   When BIT_EVT_BUZZER is set (by MQTT credit handler), the buzzer
 *   is activated for 1 second to alert the user near the machine.
 */

#include <freertos/FreeRTOS.h>
#include <driver/gpio.h>

#include "config.h"
#include "led_status.h"

/* ======================================================================
 * vTaskBitEvent - LED status indicator task
 * ======================================================================
 *
 * Infinite loop:
 *   1. Wait for BIT_EVT_TRIGGER (auto-clear on read)
 *   2. Evaluate which bits are set
 *   3. Set LED colour based on priority rules
 *   4. If BIT_EVT_BUZZER is set, pulse the buzzer for 1 second
 *
 * The BIT_EVT_TRIGGER bit is consumed (cleared) by xEventGroupWaitBits
 * so the task only wakes when another module explicitly signals a
 * state change.
 */
void vTaskBitEvent(void *pvParameters)
{
    while (1) {
        /*
         * Block until BIT_EVT_TRIGGER is set.  The pdTRUE parameter
         * clears BIT_EVT_TRIGGER on exit.  portMAX_DELAY means wait
         * indefinitely.
         */
        EventBits_t uxBits = xEventGroupWaitBits(
            xLedEventGroup,
            BIT_EVT_TRIGGER,   /* Bits to wait for */
            pdTRUE,            /* Clear on exit */
            pdFALSE,           /* Wait for any bit (not all) */
            portMAX_DELAY      /* Wait forever */
        );

        /*
         * LED colour selection (priority order):
         *
         * 1. If device is not fully provisioned (missing passkey or
         *    subdomain), show YELLOW regardless of other state.
         * 2. If MDB is active AND Internet is connected, show GREEN.
         * 3. If only MDB is active (no Internet), show BLUE.
         * 4. Default: show RED (inactive/disabled).
         */
        if ((uxBits & MASK_EVT_INSTALLED) != MASK_EVT_INSTALLED) {
            /* Not fully provisioned */
            led_strip_set_pixel(led_strip, 0, 80, 60, 0);    /* YELLOW */
        } else if ((uxBits & BIT_EVT_MDB) && (uxBits & BIT_EVT_INTERNET)) {
            /* MDB active + Internet connected */
            led_strip_set_pixel(led_strip, 0, 10, 80, 10);   /* GREEN */
        } else if (uxBits & BIT_EVT_MDB) {
            /* MDB active, no Internet */
            led_strip_set_pixel(led_strip, 0, 5, 15, 80);    /* BLUE */
        } else {
            /* Inactive or disabled */
            led_strip_set_pixel(led_strip, 0, 80, 5, 5);     /* RED */
        }
        led_strip_refresh(led_strip);

        /*
         * Buzzer handling: if BIT_EVT_BUZZER was set, pulse the piezo
         * buzzer for 1 second (active high on PIN_BUZZER_PWR).
         */
        if (uxBits & BIT_EVT_BUZZER) {
            gpio_set_level(PIN_BUZZER_PWR, 1);
            vTaskDelay(pdMS_TO_TICKS(1000));
            gpio_set_level(PIN_BUZZER_PWR, 0);

            xEventGroupClearBits(xLedEventGroup, BIT_EVT_BUZZER);
        }
    }
}
