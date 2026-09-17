package com.myra.assistant.model

data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap()
) {
    companion object {
        const val TYPE_OPEN_APP = "OPEN_APP"
        const val TYPE_CLOSE_APP = "CLOSE_APP"
        const val TYPE_CALL = "CALL"
        const val TYPE_SMS = "SMS"
        const val TYPE_WHATSAPP_MSG = "WHATSAPP_MSG"
        const val TYPE_WHATSAPP_CALL = "WHATSAPP_CALL"
        const val TYPE_PRIME_CALL = "PRIME_CALL"
        const val TYPE_PRIME_MSG = "PRIME_MSG"
        const val TYPE_VOLUME_UP = "VOLUME_UP"
        const val TYPE_VOLUME_DOWN = "VOLUME_DOWN"
        const val TYPE_FLASHLIGHT_ON = "FLASHLIGHT_ON"
        const val TYPE_FLASHLIGHT_OFF = "FLASHLIGHT_OFF"
        const val TYPE_WIFI_ON = "WIFI_ON"
        const val TYPE_WIFI_OFF = "WIFI_OFF"
        const val TYPE_BLUETOOTH_ON = "BLUETOOTH_ON"
        const val TYPE_BLUETOOTH_OFF = "BLUETOOTH_OFF"
    }
}
