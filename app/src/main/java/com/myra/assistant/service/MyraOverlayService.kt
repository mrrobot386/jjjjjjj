package com.myra.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import com.example.R
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.ui.main.OrbAnimationView
import kotlin.math.abs

class MyraOverlayService : Service() {

    companion object {
        private const val TAG = "MyraOverlayService"
        const val CHANNEL_ID = "myra_overlay_channel"
        const val NOTIFICATION_ID = 102
        var isRunning = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var orbView: OrbAnimationView? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
        showOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating MYRA orb"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Voice Assistant")
            .setContentText("Overlay active. Double tap or tap orb to talk.")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays: permission not granted")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_orb, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val sizePx = (160 * density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        orbView = overlayView?.findViewById(R.id.overlayOrbView)
        orbView?.setState(OrbAnimationView.OrbState.IDLE)

        val closeBtn = overlayView?.findViewById<ImageButton>(R.id.overlayCloseBtn)
        closeBtn?.setOnClickListener {
            stopSelf()
        }

        // Draggable touch listener
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // Launch MainActivity
                            val intent = Intent(this@MyraOverlayService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view", e)
            }
            overlayView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
