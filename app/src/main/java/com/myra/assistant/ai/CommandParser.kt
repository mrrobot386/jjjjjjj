package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import java.util.Locale

object CommandParser {

    fun parse(rawText: String?): AppCommand? {
        if (rawText.isNullOrBlank()) return null
        val text = rawText.lowercase(Locale.getDefault()).trim()

        // 1. Prime Calls & Messages
        if (text.contains("second contact") && (text.contains("call") || text.contains("phone"))) {
            return AppCommand(AppCommand.TYPE_PRIME_CALL, mapOf("index" to "1"))
        }
        if (text.contains("close friend") || text.contains("bestie") || text.contains("special friend")) {
            if (text.contains("msg") || text.contains("message") || text.contains("bhejo")) {
                return AppCommand(AppCommand.TYPE_PRIME_MSG, mapOf("index" to "0"))
            }
            if (text.contains("call") || text.contains("phone") || text.contains("lagao")) {
                return AppCommand(AppCommand.TYPE_PRIME_CALL, mapOf("index" to "0"))
            }
        }
        if (text.contains("meri jaan") || text.contains("my love") || text.contains("jaan ko")) {
            if (text.contains("msg") || text.contains("message") || text.contains("bhejo")) {
                return AppCommand(AppCommand.TYPE_PRIME_MSG, mapOf("index" to "0"))
            }
            if (text.contains("call") || text.contains("phone") || text.contains("lagao")) {
                return AppCommand(AppCommand.TYPE_PRIME_CALL, mapOf("index" to "0"))
            }
        }

        // 2. Flashlight
        if (text.contains("torch on") || text.contains("flashlight on") || text.contains("torch jalao") || text.contains("light on karo") || text.contains("torch chalu")) {
            return AppCommand(AppCommand.TYPE_FLASHLIGHT_ON)
        }
        if (text.contains("torch off") || text.contains("flashlight off") || text.contains("torch band") || text.contains("torch bujhao") || text.contains("light band karo")) {
            return AppCommand(AppCommand.TYPE_FLASHLIGHT_OFF)
        }

        // 3. Volume
        if (text.contains("volume up") || text.contains("volume badhao") || text.contains("awaz badhao") || text.contains("increase volume")) {
            return AppCommand(AppCommand.TYPE_VOLUME_UP)
        }
        if (text.contains("volume down") || text.contains("volume kam karo") || text.contains("awaz kam karo") || text.contains("decrease volume")) {
            return AppCommand(AppCommand.TYPE_VOLUME_DOWN)
        }

        // 4. WiFi
        if (text.contains("wifi on") || text.contains("wifi chalu")) {
            return AppCommand(AppCommand.TYPE_WIFI_ON)
        }
        if (text.contains("wifi off") || text.contains("wifi band")) {
            return AppCommand(AppCommand.TYPE_WIFI_OFF)
        }

        // 5. Bluetooth
        if (text.contains("bluetooth on") || text.contains("bluetooth chalu")) {
            return AppCommand(AppCommand.TYPE_BLUETOOTH_ON)
        }
        if (text.contains("bluetooth off") || text.contains("bluetooth band")) {
            return AppCommand(AppCommand.TYPE_BLUETOOTH_OFF)
        }

        // 6. Close App
        if (text.startsWith("close ") || text.endsWith(" band karo") || text.contains("app band karo") || text.contains("close this app")) {
            val appName = text.replace("close", "")
                .replace("band karo", "")
                .replace("app", "")
                .trim()
            return AppCommand(AppCommand.TYPE_CLOSE_APP, mapOf("app_name" to appName))
        }

        // 7. WhatsApp Call / Msg
        if (text.contains("whatsapp")) {
            if (text.contains("call")) {
                val target = extractTarget(text, listOf("whatsapp call karo", "whatsapp call", "ko", "call"))
                return AppCommand(AppCommand.TYPE_WHATSAPP_CALL, mapOf("name" to target))
            } else {
                val target = extractTarget(text, listOf("whatsapp karo", "whatsapp message", "whatsapp msg", "ko", "message", "bhejo"))
                return AppCommand(AppCommand.TYPE_WHATSAPP_MSG, mapOf("name" to target, "message" to "Hello!"))
            }
        }

        // 8. SMS
        if (text.contains("sms") || (text.contains("message") && text.contains("bhejo"))) {
            val target = extractTarget(text, listOf("sms bhejo", "sms", "message bhejo", "ko", "send sms to"))
            return AppCommand(AppCommand.TYPE_SMS, mapOf("name" to target, "message" to "Hello!"))
        }

        // 9. Phone Call
        if (text.contains("call karo") || text.contains("phone karo") || text.contains("ko call") || text.startsWith("call ")) {
            val target = extractTarget(text, listOf("call karo", "phone karo", "ko call", "call", "ko phone lagao", "lagao", "ko"))
            if (target.isNotBlank()) {
                return AppCommand(AppCommand.TYPE_CALL, mapOf("name" to target))
            }
        }

        // 10. Open App
        if (text.startsWith("open ") || text.endsWith(" kholo") || text.contains(" open karo") || text.endsWith(" chalao")) {
            val appName = text.replace("open", "")
                .replace("kholo", "")
                .replace("karo", "")
                .replace("chalao", "")
                .replace("app", "")
                .trim()
            if (appName.isNotBlank()) {
                return AppCommand(AppCommand.TYPE_OPEN_APP, mapOf("app_name" to appName))
            }
        }

        return null
    }

    private fun extractTarget(raw: String, stopWords: List<String>): String {
        var clean = raw
        for (stop in stopWords) {
            clean = clean.replace(stop, " ")
        }
        return clean.replace(Regex("\\s+"), " ").trim()
    }
}
