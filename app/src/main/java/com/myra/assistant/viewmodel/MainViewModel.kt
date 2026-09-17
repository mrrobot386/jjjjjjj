package com.myra.assistant.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myra.assistant.model.AppCommand
import com.myra.assistant.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"

        private val APP_PACKAGE_MAP = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "play store" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.meetings",
            "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "linkedin" to "com.linkedin.android"
        )
    }

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    fun executeCommand(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            when (command.type) {
                AppCommand.TYPE_OPEN_APP -> {
                    val appName = command.params["app_name"] ?: ""
                    openApp(appName)
                }
                AppCommand.TYPE_CLOSE_APP -> {
                    closeApp()
                }
                AppCommand.TYPE_CALL -> {
                    val target = command.params["name"] ?: ""
                    makeCall(target)
                }
                AppCommand.TYPE_SMS -> {
                    val target = command.params["name"] ?: ""
                    val message = command.params["message"] ?: "Hello"
                    sendSms(target, message)
                }
                AppCommand.TYPE_WHATSAPP_MSG -> {
                    val target = command.params["name"] ?: ""
                    val message = command.params["message"] ?: "Hello"
                    sendWhatsApp(target, message, false)
                }
                AppCommand.TYPE_WHATSAPP_CALL -> {
                    val target = command.params["name"] ?: ""
                    sendWhatsApp(target, "", true)
                }
                AppCommand.TYPE_PRIME_CALL -> {
                    val index = command.params["index"]?.toIntOrNull() ?: 0
                    primeCall(index)
                }
                AppCommand.TYPE_PRIME_MSG -> {
                    val index = command.params["index"]?.toIntOrNull() ?: 0
                    val message = command.params["message"] ?: "Hey!"
                    primeMsg(index, message)
                }
                AppCommand.TYPE_VOLUME_UP -> {
                    adjustVolume(AudioManager.ADJUST_RAISE)
                }
                AppCommand.TYPE_VOLUME_DOWN -> {
                    adjustVolume(AudioManager.ADJUST_LOWER)
                }
                AppCommand.TYPE_FLASHLIGHT_ON -> {
                    toggleFlashlight(true)
                }
                AppCommand.TYPE_FLASHLIGHT_OFF -> {
                    toggleFlashlight(false)
                }
                AppCommand.TYPE_WIFI_ON, AppCommand.TYPE_WIFI_OFF -> {
                    openSystemPanel("wifi")
                }
                AppCommand.TYPE_BLUETOOTH_ON, AppCommand.TYPE_BLUETOOTH_OFF -> {
                    openSystemPanel("bluetooth")
                }
            }
        }
    }

    fun openApp(appName: String) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val cleanName = appName.lowercase().trim()

        var pkg = APP_PACKAGE_MAP[cleanName]
        if (pkg == null) {
            for ((key, value) in APP_PACKAGE_MAP) {
                if (cleanName.contains(key) || key.contains(cleanName)) {
                    pkg = value
                    break
                }
            }
        }

        // Fallback: Scan installed apps
        if (pkg == null) {
            try {
                val installed = pm.getInstalledApplications(0)
                for (app in installed) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label.contains(cleanName) || cleanName.contains(label)) {
                        pkg = app.packageName
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning installed packages", e)
            }
        }

        if (pkg != null) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _commandResult.postValue("Haan, $appName open kar diya hai!")
                return
            }
        }

        _commandResult.postValue("Sorry, $appName nahi mila phone mein.")
    }

    fun closeApp() {
        val helper = AccessibilityHelperService.instance
        if (helper != null) {
            helper.closeCurrentApp()
            _commandResult.postValue("App band kar diya gaya hai.")
        } else {
            _commandResult.postValue("App close karne ke liye Accessibility permission enable karein.")
        }
    }

    @SuppressLint("MissingPermission")
    fun makeCall(nameOrNumber: String) {
        val context = getApplication<Application>()
        val number = resolveContactNumber(nameOrNumber) ?: nameOrNumber.filter { it.isDigit() || it == '+' }

        if (number.isNotBlank()) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                _commandResult.postValue("$nameOrNumber ko call lagaya ja raha hai.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating call", e)
                _commandResult.postValue("Call karne mein problem aayi.")
            }
        } else {
            _commandResult.postValue("$nameOrNumber ka phone number nahi mila.")
        }
    }

    fun sendSms(nameOrNumber: String, message: String) {
        val context = getApplication<Application>()
        val number = resolveContactNumber(nameOrNumber) ?: nameOrNumber.filter { it.isDigit() || it == '+' }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            _commandResult.postValue("SMS taiyar hai, bhej diya.")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            _commandResult.postValue("SMS nahi bheja ja saka.")
        }
    }

    fun sendWhatsApp(nameOrNumber: String, message: String, isCall: Boolean) {
        val context = getApplication<Application>()
        val number = resolveContactNumber(nameOrNumber) ?: nameOrNumber.filter { it.isDigit() || it == '+' }
        val cleanNumber = number.replace("+", "").replace(" ", "").replace("-", "")

        val url = if (isCall) {
            "https://wa.me/$cleanNumber"
        } else {
            "https://wa.me/$cleanNumber?text=${Uri.encode(message)}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            _commandResult.postValue("WhatsApp khol diya.")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp", e)
            _commandResult.postValue("WhatsApp open nahi ho saka.")
        }
    }

    fun primeCall(index: Int) {
        val contact = getPrimeContact(index)
        if (contact != null) {
            makeCall(contact.second)
        } else {
            _commandResult.postValue("Settings mein koi Prime Contact set nahi hai.")
        }
    }

    fun primeMsg(index: Int, message: String) {
        val contact = getPrimeContact(index)
        if (contact != null) {
            sendWhatsApp(contact.second, message, false)
        } else {
            _commandResult.postValue("Settings mein Prime Contact set nahi hai.")
        }
    }

    private fun getPrimeContact(index: Int): Pair<String, String>? {
        val prefs = getApplication<Application>().getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("prime_contacts_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                if (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val name = obj.optString("name", "")
                    val number = obj.optString("number", "")
                    if (number.isNotBlank()) return Pair(name, number)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing prime contacts JSON", e)
            }
        }

        // Legacy migration check
        val legacyName = prefs.getString("prime_name", null)
        val legacyNumber = prefs.getString("prime_number", null)
        if (!legacyNumber.isNullOrBlank()) {
            return Pair(legacyName ?: "Close Friend", legacyNumber)
        }

        return null
    }

    private fun resolveContactNumber(name: String): String? {
        val context = getApplication<Application>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) {
                        return it.getString(numberIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts for: $name", e)
        }
        return null
    }

    fun toggleFlashlight(enable: Boolean) {
        val context = getApplication<Application>()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                _commandResult.postValue(if (enable) "Torch on ho gayi!" else "Torch band kar di.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting flashlight", e)
            _commandResult.postValue("Flashlight toggle nahi ho saki.")
        }
    }

    fun adjustVolume(direction: Int) {
        val context = getApplication<Application>()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        _commandResult.postValue(if (direction == AudioManager.ADJUST_RAISE) "Volume badha diya." else "Volume kam kar diya.")
    }

    private fun openSystemPanel(panel: String) {
        val context = getApplication<Application>()
        val action = when (panel) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(intent)
            _commandResult.postValue("$panel settings open kar di hai.")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening panel: $panel", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun acceptCall() {
        val context = getApplication<Application>()
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                telecomManager?.acceptRingingCall()
                _commandResult.postValue("Call utha li hai.")
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting call", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun rejectCall() {
        val context = getApplication<Application>()
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                telecomManager?.endCall()
                _commandResult.postValue("Call cut kar di.")
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting call", e)
            }
        }
    }
}
