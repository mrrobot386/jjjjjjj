package com.myra.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.myra.assistant.ui.main.MainActivity

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val CHANNEL_ID = "myra_call_monitor_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        var isRunning = false
            private set
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification = createNotification("Monitoring incoming calls")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
        initTelephony()
    }

    private fun initTelephony() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, null)
                }
            }
            try {
                telephonyManager?.registerTelephonyCallback(mainExecutor, telephonyCallback as TelephonyCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering telephony callback", e)
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleCallState(state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Incoming call ringing, number: $incomingNumber")
                val callerName = resolveCallerName(incomingNumber) ?: "Unknown Caller"
                val callIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("INCOMING_CALL", true)
                    putExtra("CALLER_NAME", callerName)
                }
                startActivity(callIntent)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call ended or idle")
                sendBroadcast(Intent(ACTION_CALL_ENDED))
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call in progress")
            }
        }
    }

    private fun resolveCallerName(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        return it.getString(nameIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving contact name", e)
        }
        return phoneNumber
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors incoming calls for voice announcements"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Call Monitor")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
