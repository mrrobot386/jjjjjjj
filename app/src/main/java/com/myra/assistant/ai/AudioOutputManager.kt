package com.myra.assistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Dedicated AudioOutputManager for Gemini Live real-time audio playback.
 * - Format: PCM 16-bit Mono, 24000 Hz, STREAM_MUSIC / USAGE_MEDIA
 * - Audio Focus: Requests transient focus with USAGE_MEDIA / CONTENT_TYPE_SPEECH
 * - Routing: Checks and logs active audio output device (e.g., Bluetooth Headset vs Built-in Speaker)
 * - Low-Latency: Concurrent LinkedBlockingQueue + dedicated AudioTrackThread writing chunks immediately.
 */
class AudioOutputManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioOutputManager"
        const val SPEAKER_SAMPLE_RATE = 24000
    }

    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var audioTrack: AudioTrack? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val isPlaying = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (isPlaying.get()) return

        requestAudioFocus()
        logActiveAudioDevice()

        val minBufSize = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, 4096)

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AUDIO_OUTPUT_ERROR: AudioTrack initialization failed")
                mainHandler.post { onError?.invoke("AUDIO_OUTPUT_ERROR: AudioTrack state not initialized") }
                return
            }

            try {
                // Ensure natural audible level without forcing system distortion
                audioTrack?.setVolume(1.0f)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set volume on AudioTrack", e)
            }

            audioTrack?.play()
            isPlaying.set(true)
            Log.d(TAG, "AUDIO_OUTPUT_INITIALIZED (24kHz Mono PCM16, USAGE_MEDIA)")

            playbackThread = Thread({
                var wasSpeaking = false
                while (isPlaying.get()) {
                    val chunk = playbackQueue.poll(80, TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        if (!wasSpeaking) {
                            wasSpeaking = true
                            isSpeaking.set(true)
                            Log.d(TAG, "AUDIO_PLAYBACK_STARTED")
                            mainHandler.post { onSpeakingStarted?.invoke() }
                        }

                        val rms = calculateRms(chunk, chunk.size)
                        mainHandler.post { onAmplitudeChanged?.invoke(rms) }

                        val written = audioTrack?.write(chunk, 0, chunk.size) ?: -1
                        if (written > 0) {
                            Log.v(TAG, "AUDIO_PLAYBACK_CHUNK written: $written bytes")
                        }
                    } else {
                        if (wasSpeaking && playbackQueue.isEmpty()) {
                            wasSpeaking = false
                            isSpeaking.set(false)
                            Log.d(TAG, "AUDIO_PLAYBACK_FINISHED")
                            mainHandler.post { onSpeakingStopped?.invoke() }
                        }
                    }
                }
            }, "AudioOutputThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "AUDIO_OUTPUT_ERROR starting AudioTrack", e)
            mainHandler.post { onError?.invoke("AUDIO_OUTPUT_ERROR: ${e.message}") }
        }
    }

    fun queueAudio(pcmData: ByteArray) {
        if (pcmData.isNotEmpty()) {
            Log.d(TAG, "GEMINI_AUDIO_BYTES queued for output: ${pcmData.size} bytes")
            playbackQueue.offer(pcmData)
        }
    }

    fun isSpeaking(): Boolean = isSpeaking.get()

    fun interruptPlayback() {
        playbackQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            Log.d(TAG, "AUDIO_PLAYBACK interrupted and flushed")
        } catch (e: Exception) {
            Log.w(TAG, "Error interrupting AudioTrack", e)
        }
        if (isSpeaking.get()) {
            isSpeaking.set(false)
            mainHandler.post { onSpeakingStopped?.invoke() }
        }
    }

    private fun requestAudioFocus() {
        if (audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus changed: $focusChange")
                    }
                    .build()
                val result = audioManager.requestAudioFocus(focusRequest!!)
                Log.d(TAG, "Audio focus request result: $result")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    { focusChange -> Log.d(TAG, "Audio focus changed: $focusChange") },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                Log.d(TAG, "Audio focus request (pre-O) result: $result")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AUDIO_OUTPUT_ERROR requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        if (audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        }
    }

    private fun logActiveAudioDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            try {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val deviceNames = devices.joinToString { device ->
                    val type = when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADSET"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
                        else -> "TYPE_${device.type}"
                    }
                    "$type(${device.productName})"
                }
                Log.d(TAG, "AUDIO_OUTPUT_DEVICES available: $deviceNames")
            } catch (e: Exception) {
                Log.w(TAG, "Error querying audio devices", e)
            }
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        val sampleCount = length / 2
        for (i in 0 until length - 1 step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortVal = sample.toShort()
            sum += shortVal * shortVal
        }
        val mean = sum / sampleCount
        val rms = sqrt(mean).toFloat()
        return (rms / 8000f).coerceIn(0f, 1f)
    }

    fun release() {
        isPlaying.set(false)
        isSpeaking.set(false)
        playbackQueue.clear()

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
        audioTrack = null

        playbackThread?.interrupt()
        playbackThread = null
        abandonAudioFocus()
        Log.d(TAG, "AudioOutputManager released")
    }
}
