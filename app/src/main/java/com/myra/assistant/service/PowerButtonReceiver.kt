package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val DOUBLE_PRESS_INTERVAL_MS = 600L
        private var lastPressTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val now = System.currentTimeMillis()
            val diff = now - lastPressTime
            lastPressTime = now

            if (diff in 1..DOUBLE_PRESS_INTERVAL_MS) {
                Log.d(TAG, "Power button double press detected! Triggering overlay...")
                if (Settings.canDrawOverlays(context)) {
                    val serviceIntent = Intent(context, MyraOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
