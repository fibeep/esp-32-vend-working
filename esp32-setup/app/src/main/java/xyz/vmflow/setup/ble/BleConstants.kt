package xyz.vmflow.setup.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("020012ac-4202-78b8-ed11-da4642c6bbb2")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("020012ac-4202-78b8-ed11-de46769cafc9")

    const val CMD_SET_SUBDOMAIN: Byte = 0x00
    const val CMD_SET_PASSKEY: Byte = 0x01
    const val CMD_SET_WIFI_SSID: Byte = 0x06
    const val CMD_SET_WIFI_PASS: Byte = 0x07

    const val DEVICE_SUFFIX = ".panamavendingmachines.com"
    const val UNCONFIGURED_NAME = "0.panamavendingmachines.com"

    const val PASSKEY_LENGTH = 18
    const val PROVISION_PAYLOAD_SIZE = 22
    const val WIFI_PASS_PAYLOAD_SIZE = 63
}
