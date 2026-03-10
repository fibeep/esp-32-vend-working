/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * led_status.h - WS2812 LED status indicator task
 *
 * A single WS2812 addressable LED on PIN_MDB_LED (GPIO21) provides
 * visual status feedback.  The LED colour is determined by the
 * combination of event bits in xLedEventGroup:
 *
 *   YELLOW - Device not fully provisioned (missing passkey or subdomain)
 *   GREEN  - MDB active + Internet connected (fully operational)
 *   BLUE   - MDB active, no Internet
 *   RED    - MDB inactive / disabled
 *
 * The task also handles one-shot buzzer activation when BIT_EVT_BUZZER
 * is set (1-second pulse on PIN_BUZZER_PWR).
 */

#ifndef LED_STATUS_H
#define LED_STATUS_H

/**
 * @brief FreeRTOS task: LED status indicator and buzzer controller.
 *
 * Waits on xLedEventGroup for BIT_EVT_TRIGGER.  When triggered,
 * evaluates the current combination of event bits and sets the
 * WS2812 LED to the appropriate colour.  Also handles the buzzer.
 *
 * Priority order for LED colour selection:
 *   1. Not installed (missing passkey or subdomain) -> YELLOW
 *   2. MDB active + Internet -> GREEN
 *   3. MDB active only -> BLUE
 *   4. Default (inactive/disabled) -> RED
 *
 * @param pvParameters  Unused (NULL).
 *
 * @note Pinned to Core 0 at priority 1. Stack size: 2048 bytes.
 * @note This task never returns.
 */
void vTaskBitEvent(void *pvParameters);

#endif /* LED_STATUS_H */
