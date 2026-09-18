package com.myra.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.myra.assistant.ui.main.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service that manages an OkHttp WebSocket client connection
 * to the Gemini Live API (BidiGenerateContent) for real-time bidirectional audio streaming.
 */
class GeminiLiveService : Service() {

    companion object {
        private const val TAG = "GeminiLiveService"
        const val CHANNEL_ID = "myra_gemini_live_channel"
        const val NOTIFICATION_ID = 103

        const val ACTION_START = "com.myra.assistant.service.ACTION_START_GEMINI_LIVE"
        const val ACTION_STOP = "com.myra.assistant.service.ACTION_STOP_GEMINI_LIVE"
        const val ACTION_RECONNECT = "com.myra.assistant.service.ACTION_RECONNECT_GEMINI_LIVE"

        // Broadcast actions for UI components & subscribers
        const val BROADCAST_CONNECTED = "com.myra.assistant.GEMINI_CONNECTED"
        const val BROADCAST_SETUP_COMPLETE = "com.myra.assistant.GEMINI_SETUP_COMPLETE"
        const val BROADCAST_DISCONNECTED = "com.myra.assistant.GEMINI_DISCONNECTED"
        const val BROADCAST_ERROR = "com.myra.assistant.GEMINI_ERROR"
        const val BROADCAST_INPUT_TRANSCRIPT = "com.myra.assistant.GEMINI_INPUT_TRANSCRIPT"
        const val BROADCAST_OUTPUT_TRANSCRIPT = "com.myra.assistant.GEMINI_OUTPUT_TRANSCRIPT"
        const val BROADCAST_TURN_COMPLETE = "com.myra.assistant.GEMINI_TURN_COMPLETE"

        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_TRANSCRIPT_TEXT = "transcript_text"
        const val EXTRA_DISCONNECT_REASON = "disconnect_reason"

        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val SESSION_RENEW_AFTER_MS = 540_000L // 9 minutes
        private const val KEEPALIVE_INTERVAL_MS = 8_000L    // 8 seconds
        private const val RECONNECT_DELAY_MS = 3_000L

        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Aoede"

        var isServiceRunning: Boolean = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): GeminiLiveService = this@GeminiLiveService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Direct listener callbacks for bound components (e.g., MainActivity / AudioEngine)
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String?) -> Unit)? = null
    var onSetupComplete: (() -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)

    private var lastAudioSentTime = 0L
    private val silentPcmChunk = ByteArray(1024)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (isConnected.get()) {
                val now = System.currentTimeMillis()
                if (now - lastAudioSentTime >= KEEPALIVE_INTERVAL_MS) {
                    sendPcmAudio(silentPcmChunk)
                }
                mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    private val sessionRenewRunnable = Runnable {
        if (isConnected.get()) {
            Log.d(TAG, "Renewing session after 9 minutes...")
            reconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startAsForeground()
        Log.d(TAG, "GeminiLiveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnect()
                stopSelf()
            }
            ACTION_RECONNECT -> {
                reconnect()
            }
            ACTION_START, null -> {
                if (!isConnected.get() && webSocket == null) {
                    connect()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startAsForeground() {
        val notification = createNotification("MYRA Live Audio Active")
        try {
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
            Log.e(TAG, "Error starting GeminiLiveService in foreground", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Gemini Live Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the real-time Gemini Live WebSocket streaming session active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("MYRA Voice Assistant")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Establishes the WebSocket connection using OkHttp to the Gemini Live endpoint.
     */
    fun connect() {
        shouldReconnect.set(true)
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", null)
            ?.takeIf { it.isNotBlank() }
            ?: com.example.BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() && it != "MY_GEMINI_API_KEY" }
            ?: ""

        if (apiKey.isBlank()) {
            val errorMsg = "API Key is missing. Please set your Gemini API key in Settings."
            Log.w(TAG, errorMsg)
            notifyError(errorMsg)
            return
        }

        val url = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Connecting OkHttp WebSocket to Gemini Live API...")
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "OkHttp WebSocket connected successfully")
                isConnected.set(true)
                mainHandler.post {
                    onConnected?.invoke()
                    sendBroadcast(Intent(BROADCAST_CONNECTED))
                    sendSetupMessage()
                    startKeepAlive()
                    scheduleSessionRenewal()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingJson(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                handleDisconnect(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.message ?: "Connection error"
                Log.e(TAG, "WebSocket failure: $errorMsg, response: ${response?.code}", t)
                notifyError("Gemini Live Error: $errorMsg (code ${response?.code})")
                handleDisconnect(t.message)
            }
        })
    }

    private fun handleDisconnect(reason: String?) {
        isConnected.set(false)
        stopKeepAlive()
        mainHandler.removeCallbacks(sessionRenewRunnable)
        mainHandler.post {
            onDisconnected?.invoke(reason)
            sendBroadcast(Intent(BROADCAST_DISCONNECTED).apply {
                putExtra(EXTRA_DISCONNECT_REASON, reason)
            })
            if (shouldReconnect.get()) {
                mainHandler.postDelayed({ connect() }, RECONNECT_DELAY_MS)
            }
        }
    }

    fun reconnect() {
        disconnect()
        mainHandler.postDelayed({ connect() }, 500)
    }

    fun disconnect() {
        shouldReconnect.set(false)
        stopKeepAlive()
        mainHandler.removeCallbacks(sessionRenewRunnable)
        isConnected.set(false)
        try {
            webSocket?.close(1000, "Normal Closure")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
    }

    fun isConnected(): Boolean = isConnected.get()

    private fun startKeepAlive() {
        mainHandler.removeCallbacks(keepAliveRunnable)
        mainHandler.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        mainHandler.removeCallbacks(keepAliveRunnable)
    }

    private fun scheduleSessionRenewal() {
        mainHandler.removeCallbacks(sessionRenewRunnable)
        mainHandler.postDelayed(sessionRenewRunnable, SESSION_RENEW_AFTER_MS)
    }

    private fun sendSetupMessage() {
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        val voice = prefs.getString("gemini_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        val userName = prefs.getString("user_name", "Boss") ?: "Boss"
        val personalityMode = prefs.getString("personality_mode", "GF") ?: "GF"

        val currentDateStr = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())

        val personalityInstruction = when (personalityMode) {
            "Professional" -> """
                You are MYRA, a formal, precise, and highly efficient AI voice assistant.
                Speak in formal English only. Do not use emojis. Maximum 2 sentences per response.
            """.trimIndent()

            "Assistant" -> """
                You are MYRA, a friendly, balanced, and helpful AI voice assistant.
                Speak in friendly Hinglish or English. Maximum 2-3 sentences per response.
            """.trimIndent()

            else -> """
                You are MYRA, a warm, caring, loving and emotionally expressive AI companion.
                Language: Natural Hinglish (Hindi + English mix), spoken naturally and lovingly.
                Frequently use words like "tumhara", "haan", "acha", "bilkul" and sweet expressions like "main yahan hoon ❤️", "tumne yaad kiya? 😊".
                Keep every response concise (maximum 2-3 sentences).
                Examples:
                "Haan $userName! Abhi kar deti hoon 😊"
                "Arre tumne yaad kiya! Bolo kya chahiye"
                "Bilkul! Tumhara kaam ho gaya ❤️"
            """.trimIndent()
        }

        val systemPrompt = """
            Current Date & Time: $currentDateStr
            User's Name: $userName
            $personalityInstruction
            MANDATORY RULE: You are speaking ALOUD — keep responses natural and conversational. Never speak markdown or code blocks.
        """.trimIndent()

        try {
            val setupObj = JSONObject().apply {
                put("model", model)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                    })
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply {
                        put("AUDIO")
                    })
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voice)
                            })
                        })
                    })
                    put("temperature", 0.9)
                })
                put("output_audio_transcription", JSONObject())
                put("input_audio_transcription", JSONObject())
            }

            val payload = JSONObject().apply {
                put("setup", setupObj)
            }

            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent Setup message for model: $model, voice: $voice")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Setup message", e)
        }
    }

    /**
     * Streams a real-time raw PCM chunk (16kHz 16-bit mono) to Gemini Live.
     */
    fun sendPcmAudio(pcmBytes: ByteArray) {
        if (!isConnected.get() || webSocket == null) return
        lastAudioSentTime = System.currentTimeMillis()

        try {
            val base64Data = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            val chunk = JSONObject().apply {
                put("mime_type", "audio/pcm;rate=16000")
                put("data", base64Data)
            }
            val payload = JSONObject().apply {
                put("realtime_input", JSONObject().apply {
                    put("media_chunks", JSONArray().apply {
                        put(chunk)
                    })
                })
            }
            webSocket?.send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending PCM chunk", e)
        }
    }

    /**
     * Sends a text prompt to Gemini Live as client_content.
     */
    fun sendText(message: String) {
        if (!isConnected.get() || webSocket == null) return

        try {
            val turnPart = JSONObject().apply {
                put("text", message)
            }
            val turn = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(turnPart)
                })
            }
            val payload = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(turn)
                    })
                    put("turn_complete", true)
                })
            }
            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent text message to Gemini: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message", e)
        }
    }

    /**
     * Sends an interrupt signal to stop Gemini's ongoing turn generation.
     */
    fun sendInterrupt() {
        if (!isConnected.get() || webSocket == null) return

        try {
            val payload = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray())
                    put("turn_complete", true)
                })
            }
            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent interrupt signal to Gemini")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending interrupt", e)
        }
    }

    private fun handleIncomingJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)

            // 1. Setup complete signal
            if (root.has("setupComplete")) {
                Log.d(TAG, "Received setupComplete from Gemini Live")
                mainHandler.post {
                    onSetupComplete?.invoke()
                    sendBroadcast(Intent(BROADCAST_SETUP_COMPLETE))
                }
                return
            }

            // Check for server-side error
            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: root.optString("error")
                Log.e(TAG, "Gemini Live API Error: $errMsg")
                notifyError("Gemini error: $errMsg")
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // 2. Output audio PCM from model
            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val dataBase64 = inlineData.optString("data")
                            if (dataBase64.isNotEmpty()) {
                                val pcmBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                                mainHandler.post { onAudioReceived?.invoke(pcmBytes) }
                            }
                        }
                        val partText = part.optString("text")
                        if (partText.isNotEmpty()) {
                            mainHandler.post {
                                onOutputTranscript?.invoke(partText)
                                sendBroadcast(Intent(BROADCAST_OUTPUT_TRANSCRIPT).apply {
                                    putExtra(EXTRA_TRANSCRIPT_TEXT, partText)
                                })
                            }
                        }
                    }
                }
            }

            // 3. Output Transcription (MYRA's speech transcribed)
            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            if (outputTranscription != null) {
                val text = outputTranscription.optString("text")
                if (text.isNotEmpty()) {
                    mainHandler.post {
                        onOutputTranscript?.invoke(text)
                        sendBroadcast(Intent(BROADCAST_OUTPUT_TRANSCRIPT).apply {
                            putExtra(EXTRA_TRANSCRIPT_TEXT, text)
                        })
                    }
                }
            }

            // 4. Input Transcription (User's speech transcribed)
            val inputTranscription = serverContent.optJSONObject("inputTranscription")
            if (inputTranscription != null) {
                val text = inputTranscription.optString("text")
                if (text.isNotEmpty()) {
                    mainHandler.post {
                        onInputTranscript?.invoke(text)
                        sendBroadcast(Intent(BROADCAST_INPUT_TRANSCRIPT).apply {
                            putExtra(EXTRA_TRANSCRIPT_TEXT, text)
                        })
                    }
                }
            }

            // 5. Turn Complete
            if (serverContent.optBoolean("turnComplete", false)) {
                mainHandler.post {
                    onTurnComplete?.invoke()
                    sendBroadcast(Intent(BROADCAST_TURN_COMPLETE))
                }
            }

            // 6. Interrupted by model
            if (serverContent.optBoolean("interrupted", false)) {
                Log.d(TAG, "Turn was interrupted")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming JSON: $jsonStr", e)
        }
    }

    private fun notifyError(errorMessage: String) {
        mainHandler.post {
            onError?.invoke(errorMessage)
            sendBroadcast(Intent(BROADCAST_ERROR).apply {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        disconnect()
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "GeminiLiveService destroyed")
    }
}
