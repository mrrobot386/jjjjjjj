package com.myra.assistant.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE_BYTES = 1024
    }

    var onMicDataAvailable: ((ByteArray) -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.get()) return

        val minBufSize = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, CHUNK_SIZE_BYTES * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = Thread({
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val rms = calculateRms(buffer, read)
                        mainHandler.post { onAmplitudeChanged?.invoke(rms) }

                        // Echo suppression: Don't send mic audio while MYRA is speaking or muted
                        if (!isMuted.get() && !isSpeaking.get()) {
                            val chunk = buffer.copyOf(read)
                            onMicDataAvailable?.invoke(chunk)
                        }
                    }
                }
            }, "AudioRecordThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
        }
    }

    fun startPlayback() {
        if (isPlaying.get()) return

        val minBufSize = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, 4096)

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            isPlaying.set(true)

            playbackThread = Thread({
                var wasSpeaking = false
                while (isPlaying.get()) {
                    val chunk = playbackQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        if (!wasSpeaking) {
                            wasSpeaking = true
                            isSpeaking.set(true)
                            mainHandler.post { onSpeakingStarted?.invoke() }
                        }

                        // Calculate playback amplitude for orb animation
                        val rms = calculateRms(chunk, chunk.size)
                        mainHandler.post { onAmplitudeChanged?.invoke(rms) }

                        audioTrack?.write(chunk, 0, chunk.size)
                    } else {
                        if (wasSpeaking && playbackQueue.isEmpty()) {
                            wasSpeaking = false
                            isSpeaking.set(false)
                            mainHandler.post { onSpeakingStopped?.invoke() }
                        }
                    }
                }
            }, "AudioTrackThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack", e)
        }
    }

    fun queueAudio(pcmData: ByteArray) {
        if (pcmData.isNotEmpty()) {
            playbackQueue.offer(pcmData)
        }
    }

    fun interruptPlayback() {
        playbackQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack", e)
        }
        if (isSpeaking.get()) {
            isSpeaking.set(false)
            mainHandler.post { onSpeakingStopped?.invoke() }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    fun isMuted(): Boolean = isMuted.get()
    fun isSpeaking(): Boolean = isSpeaking.get()

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
        // Normalize 0..32767 to roughly 0..1
        val normalized = (rms / 8000f).coerceIn(0f, 1f)
        return normalized
    }

    fun release() {
        isRecording.set(false)
        isPlaying.set(false)
        playbackQueue.clear()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
        audioTrack = null

        recordingThread?.interrupt()
        playbackThread?.interrupt()
        recordingThread = null
        playbackThread = null
    }
}
