package com.fersaiyan.cyanbridge.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Speech capture that does not depend on Android's SpeechRecognizer.
 *
 * Records the question from the active (glasses) microphone route with simple energy-based
 * end-of-speech detection, then sends the WAV to the glasses-gateway, which transcribes it
 * with whisper.cpp on the operator's own hardware.
 */
object WhisperCapture {
    private const val TAG = "WhisperCapture"
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 20
    private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    private const val CALIBRATION_FRAMES = 10
    private const val SPEECH_START_FRAMES = 3
    private const val END_SILENCE_MS = 1_300
    private const val MIN_THRESHOLD = 350.0

    /** Whisper capture is used whenever the remote gateway is the active backend. */
    fun isEnabled(context: Context): Boolean = RemoteOpenAiPrefs.isActive(context)

    /**
     * Records until the speaker pauses, then returns the transcript.
     * Returns null when no speech was heard or transcription failed.
     */
    suspend fun listenAndTranscribe(
        context: Context,
        noSpeechTimeoutMs: Long = 7_000,
        maxDurationMs: Long = 15_000,
    ): String? {
        val wav = record(context, noSpeechTimeoutMs, maxDurationMs) ?: return null
        return transcribe(context, wav)
    }

    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO before starting a voice question
    suspend fun record(
        context: Context,
        noSpeechTimeoutMs: Long,
        maxDurationMs: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, FRAME_SAMPLES * 2 * 10),
            )
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord construction failed", error)
            return@withContext null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized state=${recorder.state}")
            recorder.release()
            return@withContext null
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?.let { recorder.setPreferredDevice(it) }

        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        val frameBytes = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0
        var noiseSum = 0.0
        var threshold = MIN_THRESHOLD
        var loudRun = 0
        var speechStarted = false
        var silenceMs = 0
        var peakRms = 0.0

        try {
            recorder.startRecording()
            Log.i(TAG, "Recording started device=${recorder.routedDevice?.productName} type=${recorder.routedDevice?.type}")
            while (coroutineContext.isActive) {
                val read = recorder.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
                frames++
                var sum = 0.0
                for (i in 0 until read) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / read)
                peakRms = max(peakRms, rms)

                frameBytes.clear()
                for (i in 0 until read) frameBytes.putShort(frame[i])
                pcm.write(frameBytes.array(), 0, read * 2)

                val elapsedMs = frames * FRAME_MS
                if (frames <= CALIBRATION_FRAMES) {
                    noiseSum += rms
                    if (frames == CALIBRATION_FRAMES) {
                        threshold = max(MIN_THRESHOLD, noiseSum / CALIBRATION_FRAMES * 2.5)
                        Log.i(TAG, "Noise floor=${noiseSum / CALIBRATION_FRAMES} threshold=$threshold")
                    }
                    continue
                }

                if (rms > threshold) {
                    loudRun++
                    silenceMs = 0
                    if (!speechStarted && loudRun >= SPEECH_START_FRAMES) {
                        speechStarted = true
                        Log.i(TAG, "Speech started at ${elapsedMs}ms")
                    }
                } else {
                    loudRun = 0
                    if (speechStarted) silenceMs += FRAME_MS
                }

                if (speechStarted && silenceMs >= END_SILENCE_MS) {
                    Log.i(TAG, "End of speech at ${elapsedMs}ms")
                    break
                }
                if (!speechStarted && elapsedMs >= noSpeechTimeoutMs) {
                    Log.i(TAG, "No speech after ${elapsedMs}ms peakRms=$peakRms threshold=$threshold")
                    break
                }
                if (elapsedMs >= maxDurationMs) {
                    Log.i(TAG, "Max duration reached")
                    break
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Recording failed", error)
            return@withContext null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (!speechStarted) return@withContext null
        val data = pcm.toByteArray()
        Log.i(TAG, "Captured ${data.size / 2} samples (${data.size / 2 / (SAMPLE_RATE / 1000)}ms) peakRms=$peakRms")
        wavHeader(data.size) + data
    }

    suspend fun transcribe(context: Context, wav: ByteArray): String? = withContext(Dispatchers.IO) {
        val base = RemoteOpenAiPrefs.getBaseUrl(context).trim().trimEnd('/')
        if (base.isBlank()) return@withContext null
        val started = System.currentTimeMillis()
        try {
            val conn = URL("$base/audio/transcriptions/raw").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            val apiKey = RemoteOpenAiPrefs.getApiKey(context)
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(wav) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.e(TAG, "Transcription HTTP $code: ${body.take(300)}")
                return@withContext null
            }
            val text = JSONObject(body).optString("text").trim()
            Log.i(TAG, "Transcribed in ${System.currentTimeMillis() - started}ms chars=${text.length}")
            text.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            Log.e(TAG, "Transcription failed", error)
            null
        }
    }

    private fun wavHeader(dataSize: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
}
