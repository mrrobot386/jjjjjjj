package com.myra.assistant.ui.main

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: MainViewModel
    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null

    // UI elements
    private lateinit var mainRoot: View
    private lateinit var redOverlay: View
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var micButton: ImageButton
    private lateinit var micHintText: TextView

    private lateinit var chatAdapter: ChatAdapter

    // Transcripts buffers
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    // Call mode
    private var isInCallMode = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusUpdateRunnable: Runnable? = null

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallMonitorService.ACTION_CALL_ENDED) {
                isInCallMode = false
                orbView.setState(OrbAnimationView.OrbState.LISTENING)
                statusText.text = "Sun rahi hoon..."
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            initGeminiLive()
        } else {
            Toast.makeText(this, "Microphone permission is required for MYRA", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        initViews()
        setupChat()
        observeViewModel()
        checkPermissions()
        startSystemServices()
        startStatusUpdates()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED))
        }

        handleIncomingCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun initViews() {
        mainRoot = findViewById(R.id.mainRoot)
        redOverlay = findViewById(R.id.redOverlay)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        timeText = findViewById(R.id.timeText)
        settingsBtn = findViewById(R.id.settingsBtn)
        orbView = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        chatRecycler = findViewById(R.id.chatRecycler)
        micButton = findViewById(R.id.micButton)
        micHintText = findViewById(R.id.micHintText)

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Mic Button Click -> Toggle Mute
        micButton.setOnClickListener {
            val engine = audioEngine ?: return@setOnClickListener
            val isNowMuted = !engine.isMuted()
            engine.setMuted(isNowMuted)
            if (isNowMuted) {
                micButton.setImageResource(R.drawable.ic_mic_off)
                statusText.text = "Muted 🔇"
                orbView.setState(OrbAnimationView.OrbState.IDLE)
            } else {
                micButton.setImageResource(R.drawable.ic_mic_on)
                statusText.text = "Sun rahi hoon..."
                orbView.setState(OrbAnimationView.OrbState.LISTENING)
            }
        }

        // Mic Button Long Press -> Interrupt
        micButton.setOnLongClickListener {
            interruptMyra()
            Toast.makeText(this, "Stopped speaking", Toast.LENGTH_SHORT).show()
            true
        }

        orbView.setOnClickListener {
            if (geminiLive == null) {
                initGeminiLive()
            }
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = chatAdapter
    }

    private fun observeViewModel() {
        viewModel.commandResult.observe(this) { result ->
            if (!result.isNullOrBlank()) {
                chatAdapter.addMessage(ChatMessage(result, isUser = false))
                chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
                // Instruct Gemini to speak confirmation
                geminiLive?.sendText("User action completed: $result. Confirm in one warm short sentence aloud.")
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            mainHandler.postDelayed({ initGeminiLive() }, 300)
        }
    }

    private fun startSystemServices() {
        // Start Call Monitor Service
        try {
            val monitorIntent = Intent(this, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(monitorIntent)
            } else {
                startService(monitorIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start CallMonitorService", e)
        }

        // Overlay Permission Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Can be requested when user toggles overlay
        }
    }

    private fun initGeminiLive() {
        if (geminiLive != null) return

        statusText.text = getString(R.string.connecting_status)
        orbView.setState(OrbAnimationView.OrbState.THINKING)

        audioEngine = AudioEngine(this)
        geminiLive = GeminiLiveClient(this)

        // Gemini Live Callbacks
        geminiLive?.onConnected = {
            Log.d(TAG, "Gemini Live connected")
            statusText.text = "Connected. Initializing..."
        }

        geminiLive?.onSetupComplete = {
            Log.d(TAG, "Gemini Live setup complete")
            audioEngine?.startRecording()
            audioEngine?.startPlayback()
            micButton.setImageResource(R.drawable.ic_mic_on)
            orbView.setState(OrbAnimationView.OrbState.LISTENING)
            statusText.text = "Sun rahi hoon..."

            // Send Greeting
            mainHandler.postDelayed({
                sendGreeting()
            }, 600)
        }

        geminiLive?.onAudioReceived = { pcmBytes ->
            audioEngine?.queueAudio(pcmBytes)
        }

        geminiLive?.onInputTranscript = { text ->
            inputBuffer.append(text)
        }

        geminiLive?.onOutputTranscript = { text ->
            outputBuffer.append(text)
        }

        geminiLive?.onTurnComplete = {
            val userText = inputBuffer.toString().trim()
            val myraText = outputBuffer.toString().trim()

            if (userText.isNotEmpty()) {
                chatAdapter.addMessage(ChatMessage(userText, isUser = true))
                chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)

                // Parse voice commands
                val command = CommandParser.parse(userText)
                if (command != null) {
                    Log.d(TAG, "Executing parsed command: ${command.type}")
                    viewModel.executeCommand(command)
                }
            }

            if (myraText.isNotEmpty()) {
                chatAdapter.addMessage(ChatMessage(myraText, isUser = false))
                chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }

            inputBuffer.setLength(0)
            outputBuffer.setLength(0)
        }

        geminiLive?.onError = { errMsg ->
            statusText.text = "Tap to connect"
            orbView.setState(OrbAnimationView.OrbState.IDLE)
            Toast.makeText(this, errMsg, Toast.LENGTH_SHORT).show()
        }

        geminiLive?.onDisconnected = { reason ->
            statusText.text = "Reconnecting..."
            orbView.setState(OrbAnimationView.OrbState.THINKING)
        }

        // AudioEngine Callbacks
        audioEngine?.onMicDataAvailable = { chunk ->
            if (!isInCallMode) {
                geminiLive?.sendPcmAudio(chunk)
            }
        }

        audioEngine?.onAmplitudeChanged = { rms ->
            waveformView.setAmplitude(rms)
            orbView.setAmplitude(rms)
        }

        audioEngine?.onSpeakingStarted = {
            orbView.setState(OrbAnimationView.OrbState.SPEAKING)
            statusText.text = getString(R.string.speaking_status)
            animateRedOverlay(true)
        }

        audioEngine?.onSpeakingStopped = {
            orbView.setState(OrbAnimationView.OrbState.LISTENING)
            statusText.text = getString(R.string.listening_status)
            animateRedOverlay(false)
        }

        geminiLive?.connect()
    }

    private fun sendGreeting() {
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "Boss") ?: "Boss"
        val personality = prefs.getString("personality_mode", "GF") ?: "GF"

        val greeting = when (personality) {
            "Professional" -> "Good day $name. MYRA is online and ready to assist you."
            "Assistant" -> "Hello $name! Main MYRA hoon. Kaise help karun aapki?"
            else -> "Hey $name! Main aa gayi hoon. Kya help chahiye tumhe?"
        }

        geminiLive?.sendText(greeting)
    }

    private fun interruptMyra() {
        audioEngine?.interruptPlayback()
        geminiLive?.sendInterrupt()
        orbView.setState(OrbAnimationView.OrbState.LISTENING)
        statusText.text = getString(R.string.listening_status)
        animateRedOverlay(false)
    }

    private fun animateRedOverlay(active: Boolean) {
        val startAlpha = redOverlay.alpha
        val endAlpha = if (active) 0.08f else 0.0f
        val duration = if (active) 300L else 500L

        ValueAnimator.ofFloat(startAlpha, endAlpha).apply {
            this.duration = duration
            addUpdateListener {
                redOverlay.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent == null) return
        val isCall = intent.getBooleanExtra("INCOMING_CALL", false)
        if (!isCall) return

        val callerName = intent.getStringExtra("CALLER_NAME") ?: "Caller"
        announceCall(callerName)
    }

    private fun announceCall(callerName: String) {
        isInCallMode = true
        orbView.setState(OrbAnimationView.OrbState.SPEAKING)
        statusText.text = "Call announcement: $callerName"

        // 1. Send announcement to Gemini to speak aloud in native voice
        val announceMsg = "Sir, $callerName ka call aa raha hai. Uthau ya reject karu?"
        geminiLive?.sendText(announceMsg)

        // 2. After 4.5 seconds: Listen for accept/reject decision via STT
        mainHandler.postDelayed({
            startCallDecisionStt()
        }, 4500)
    }

    private fun startCallDecisionStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            isInCallMode = false
            return
        }

        statusText.text = "Uthau ya reject karu? Boliye..."
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    isInCallMode = false
                    statusText.text = "Sun rahi hoon..."
                    orbView.setState(OrbAnimationView.OrbState.LISTENING)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val decision = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                    Log.d(TAG, "Call decision recognized: $decision")

                    if (decision.contains("uthao") || decision.contains("haan") || decision.contains("accept") || decision.contains("receive")) {
                        viewModel.acceptCall()
                        geminiLive?.sendText("Call utha li hai.")
                    } else if (decision.contains("reject") || decision.contains("nahi") || decision.contains("mat") || decision.contains("cut")) {
                        viewModel.rejectCall()
                        geminiLive?.sendText("Call reject kar di.")
                    }

                    isInCallMode = false
                    statusText.text = "Sun rahi hoon..."
                    orbView.setState(OrbAnimationView.OrbState.LISTENING)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            startListening(intent)
        }
    }

    private fun startStatusUpdates() {
        statusUpdateRunnable = object : Runnable {
            override fun run() {
                updateSystemStats()
                mainHandler.postDelayed(this, 30_000L)
            }
        }
        updateSystemStats()
        mainHandler.postDelayed(statusUpdateRunnable!!, 30_000L)
    }

    private fun updateSystemStats() {
        // Battery
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 90
        batteryText.text = "BAT $batLevel%"

        // RAM
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        ramText.text = String.format(Locale.getDefault(), "RAM %.1fG", availGb)

        // Time
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        timeText.text = timeStr
    }

    override fun onResume() {
        super.onResume()
        audioEngine?.setMuted(false)
        updateSystemStats()
    }

    override fun onPause() {
        super.onPause()
        audioEngine?.setMuted(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            unregisterReceiver(callEndedReceiver)
        } catch (e: Exception) {
            // Ignored if not registered
        }
        speechRecognizer?.destroy()
        geminiLive?.disconnect()
        audioEngine?.release()
    }
}
