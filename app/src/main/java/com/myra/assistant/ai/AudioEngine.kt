package com.myra.assistant.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MYRA AudioEngine
 *
 * Microphone:
 *  - 16kHz
 *  - Mono
 *  - PCM16
 *
 * Speaker:
 *  - 24kHz
 *  - Mono
 *  - PCM16
 *
 * Flow:
 *
 * MIC
 *   ↓
 * AudioRecord
 *   ↓
 * GeminiLiveClient
 *   ↓
 * Gemini
 *   ↓
 * AudioEngine.queueAudio()
 *   ↓
 * AudioOutputManager
 *   ↓
 * SPEAKER 🔊
 */
class AudioEngine(
    private val context: Context
) {

    companion object {

        private const val TAG = "AudioEngine"

        const val MIC_SAMPLE_RATE = 16000

        const val SPEAKER_SAMPLE_RATE = 24000

        const val CHUNK_SIZE_BYTES = 1024
    }

    // ---------------------------------------------------------
    // CALLBACKS
    // ---------------------------------------------------------

    var onMicDataAvailable: ((ByteArray) -> Unit)? = null

    var onAmplitudeChanged: ((Float) -> Unit)? = null

    var onSpeakingStarted: (() -> Unit)? = null

    var onSpeakingStopped: (() -> Unit)? = null

    var onError: ((String) -> Unit)? = null

    // ---------------------------------------------------------
    // MICROPHONE
    // ---------------------------------------------------------

    private var micRecord: AudioRecord? = null

    private var recordingThread: Thread? = null

    // ---------------------------------------------------------
    // AUDIO OUTPUT
    // ---------------------------------------------------------

    private val outputManager =
        AudioOutputManager(context)

    // ---------------------------------------------------------
    // STATES
    // ---------------------------------------------------------

    private val isRecording =
        AtomicBoolean(false)

    private val isMuted =
        AtomicBoolean(false)

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var loggedFirstPcm = false

    // ---------------------------------------------------------
    // INIT
    // ---------------------------------------------------------

    init {

        outputManager.onSpeakingStarted = {

            mainHandler.post {

                Log.d(
                    TAG,
                    "MYRA_SPEAKING_STARTED"
                )

                onSpeakingStarted?.invoke()
            }
        }

        outputManager.onSpeakingStopped = {

            mainHandler.post {

                Log.d(
                    TAG,
                    "MYRA_SPEAKING_STOPPED"
                )

                onSpeakingStopped?.invoke()
            }
        }

        outputManager.onAmplitudeChanged = { amp ->

            mainHandler.post {

                onAmplitudeChanged?.invoke(
                    amp
                )
            }
        }

        outputManager.onError = { error ->

            mainHandler.post {

                Log.e(
                    TAG,
                    "AUDIO_OUTPUT_ERROR: $error"
                )

                onError?.invoke(error)
            }
        }
    }

    // =========================================================
    // MICROPHONE
    // =========================================================

    @SuppressLint("MissingPermission")
    fun startRecording() {

        if (
            isRecording.get()
        ) {

            Log.d(
                TAG,
                "MIC_ALREADY_RUNNING"
            )

            return
        }

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minBufferSize <= 0
        ) {

            val error =
                "MIC_ERROR: Invalid AudioRecord buffer size"

            Log.e(
                TAG,
                error
            )

            mainHandler.post {

                onError?.invoke(
                    error
                )
            }

            return
        }

        val bufferSize =
            maxOf(
                minBufferSize,
                CHUNK_SIZE_BYTES * 4
            )

        try {

            // -------------------------------------------------
            // TRY VOICE_RECOGNITION
            // -------------------------------------------------

            micRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

            // -------------------------------------------------
            // FALLBACK TO MIC
            // -------------------------------------------------

            if (
                micRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                Log.w(
                    TAG,
                    "VOICE_RECOGNITION failed. Using MIC fallback."
                )

                try {

                    micRecord?.release()

                } catch (_: Exception) {
                }

                micRecord =
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        MIC_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
            }

            // -------------------------------------------------
            // FINAL CHECK
            // -------------------------------------------------

            if (
                micRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                val error =
                    "MIC_ERROR: AudioRecord initialization failed"

                Log.e(
                    TAG,
                    error
                )

                mainHandler.post {

                    onError?.invoke(
                        error
                    )
                }

                return
            }

            // -------------------------------------------------
            // START MIC
            // -------------------------------------------------

            micRecord?.startRecording()

            if (
                micRecord?.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {

                val error =
                    "MIC_ERROR: AudioRecord failed to start"

                Log.e(
                    TAG,
                    error
                )

                mainHandler.post {

                    onError?.invoke(
                        error
                    )
                }

                return
            }

            isRecording.set(true)

            loggedFirstPcm = false

            Log.d(
                TAG,
                "MIC_STARTED: 16kHz MONO PCM16"
            )

            // -------------------------------------------------
            // RECORDING THREAD
            // -------------------------------------------------

            recordingThread =
                Thread({

                    val buffer =
                        ByteArray(
                            CHUNK_SIZE_BYTES
                        )

                    var sentCount = 0

                    while (
                        isRecording.get()
                    ) {

                        try {

                            val read =
                                micRecord?.read(
                                    buffer,
                                    0,
                                    buffer.size
                                )
                                    ?: -1

                            if (
                                read <= 0
                            ) {

                                continue
                            }

                            // ---------------------------------
                            // AUDIO SIGNAL
                            // ---------------------------------

                            val hasSignal =
                                hasAudioSignal(
                                    buffer,
                                    read
                                )

                            val rms =
                                calculateRms(
                                    buffer,
                                    read
                                )

                            // ---------------------------------
                            // UI AMPLITUDE
                            // ---------------------------------

                            if (
                                !outputManager.isSpeaking()
                            ) {

                                mainHandler.post {

                                    onAmplitudeChanged
                                        ?.invoke(
                                            rms
                                        )
                                }
                            }

                            // ---------------------------------
                            // FIRST PCM LOG
                            // ---------------------------------

                            if (
                                hasSignal &&
                                !loggedFirstPcm
                            ) {

                                loggedFirstPcm = true

                                Log.d(
                                    TAG,
                                    "MIC_PCM_CAPTURED: " +
                                            "valid audio detected " +
                                            "rms=$rms"
                                )
                            }

                            // ---------------------------------
                            // INTERRUPTION
                            // ---------------------------------

                            if (
                                outputManager.isSpeaking() &&
                                rms > 0.15f
                            ) {

                                Log.d(
                                    TAG,
                                    "MYRA_INTERRUPTION_DETECTED"
                                )

                                mainHandler.post {

                                    interruptPlayback()
                                }
                            }

                            // ---------------------------------
                            // SEND TO GEMINI
                            // ---------------------------------

                            if (
                                !isMuted.get() &&
                                !outputManager.isSpeaking()
                            ) {

                                val chunk =
                                    buffer.copyOf(
                                        read
                                    )

                                sentCount++

                                if (
                                    sentCount % 20 == 1
                                ) {

                                    Log.d(
                                        TAG,
                                        "MIC_AUDIO_SENT " +
                                                "chunk=$sentCount " +
                                                "bytes=${chunk.size}"
                                    )
                                }

                                try {

                                    onMicDataAvailable
                                        ?.invoke(
                                            chunk
                                        )

                                } catch (
                                    e: Exception
                                ) {

                                    Log.e(
                                        TAG,
                                        "MIC_CALLBACK_ERROR",
                                        e
                                    )
                                }
                            }

                        } catch (
                            e: Exception
                        ) {

                            if (
                                isRecording.get()
                            ) {

                                Log.e(
                                    TAG,
                                    "MIC_RECORDING_LOOP_ERROR",
                                    e
                                )
                            }
                        }
                    }

                }, "MYRA-AudioRecordThread")

            recordingThread?.start()

        } catch (e: Exception) {

            val error =
                "MIC_ERROR starting AudioRecord: ${e.message}"

            Log.e(
                TAG,
                error,
                e
            )

            isRecording.set(false)

            mainHandler.post {

                onError?.invoke(
                    error
                )
            }
        }
    }

    // =========================================================
    // PLAYBACK
    // =========================================================

    /**
     * Start speaker manually.
     *
     * MainActivity can call this during setup.
     */
    fun startPlayback() {

        try {

            Log.d(
                TAG,
                "AUDIO_PLAYBACK_START_REQUEST"
            )

            outputManager.start()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "AUDIO_PLAYBACK_START_ERROR",
                e
            )

            mainHandler.post {

                onError?.invoke(
                    "Audio playback start failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Gemini audio arrives here.
     *
     * IMPORTANT FIX:
     *
     * Do NOT assume startPlayback() was called earlier.
     * Whenever Gemini sends audio, make sure the output
     * manager is running before queueing the PCM.
     */
    fun queueAudio(
        pcmData: ByteArray
    ) {

        if (
            pcmData.isEmpty()
        ) {

            Log.w(
                TAG,
                "GEMINI_AUDIO_EMPTY"
            )

            return
        }

        Log.d(
            TAG,
            "GEMINI_AUDIO_RECEIVED -> PLAYBACK " +
                    "${pcmData.size} bytes"
        )

        try {

            // ---------------------------------------------
            // ENSURE AUDIO OUTPUT IS STARTED
            // ---------------------------------------------

            outputManager.start()

            // ---------------------------------------------
            // QUEUE GEMINI PCM
            // ---------------------------------------------

            outputManager.queueAudio(
                pcmData.copyOf()
            )

            Log.d(
                TAG,
                "GEMINI_AUDIO_QUEUED_SUCCESS"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "GEMINI_AUDIO_PLAYBACK_ERROR",
                e
            )

            mainHandler.post {

                onError?.invoke(
                    "Gemini audio playback failed: ${e.message}"
                )
            }
        }
    }

    // =========================================================
    // INTERRUPT
    // =========================================================

    fun interruptPlayback() {

        try {

            Log.d(
                TAG,
                "MYRA_PLAYBACK_INTERRUPT"
            )

            outputManager.interruptPlayback()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "PLAYBACK_INTERRUPT_ERROR",
                e
            )
        }
    }

    // =========================================================
    // MUTE
    // =========================================================

    fun setMuted(
        muted: Boolean
    ) {

        isMuted.set(
            muted
        )

        Log.d(
            TAG,
            "MIC_MUTED=$muted"
        )
    }

    fun isMuted(): Boolean {

        return isMuted.get()
    }

    fun isSpeaking(): Boolean {

        return outputManager.isSpeaking()
    }

    // =========================================================
    // AUDIO SIGNAL
    // =========================================================

    private fun hasAudioSignal(
        buffer: ByteArray,
        length: Int
    ): Boolean {

        if (
            length <= 0
        ) {
            return false
        }

        for (
            i in 0 until length
        ) {

            if (
                buffer[i] != 0.toByte()
            ) {

                return true
            }
        }

        return false
    }

    // =========================================================
    // RMS
    // =========================================================

    private fun calculateRms(
        buffer: ByteArray,
        length: Int
    ): Float {

        if (
            length < 2
        ) {

            return 0f
        }

        var sum =
            0.0

        val sampleCount =
            length / 2

        for (
            i in 0 until length - 1 step 2
        ) {

            val sample =
                (buffer[i].toInt() and 0xFF) or
                        (buffer[i + 1].toInt() shl 8)

            val shortVal =
                sample.toShort()

            sum +=
                shortVal.toDouble() *
                        shortVal.toDouble()
        }

        if (
            sampleCount <= 0
        ) {

            return 0f
        }

        val mean =
            sum / sampleCount

        val rms =
            kotlin.math.sqrt(
                mean
            ).toFloat()

        return (
                rms / 8000f
                ).coerceIn(
                    0f,
                    1f
                )
    }

    // =========================================================
    // RELEASE
    // =========================================================

    fun release() {

        Log.d(
            TAG,
            "AUDIO_ENGINE_RELEASE"
        )

        isRecording.set(false)

        loggedFirstPcm = false

        // -----------------------------------------------------
        // STOP MICROPHONE
        // -----------------------------------------------------

        try {

            micRecord?.stop()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error stopping microphone",
                e
            )
        }

        try {

            micRecord?.release()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error releasing microphone",
                e
            )
        }

        micRecord = null

        // -----------------------------------------------------
        // STOP THREAD
        // -----------------------------------------------------

        try {

            recordingThread?.interrupt()

        } catch (_: Exception) {
        }

        recordingThread = null

        // -----------------------------------------------------
        // RELEASE SPEAKER
        // -----------------------------------------------------

        try {

            outputManager.release()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error releasing AudioOutputManager",
                e
            )
        }

        Log.d(
            TAG,
            "AUDIO_ENGINE_RELEASED"
        )
    }
}
