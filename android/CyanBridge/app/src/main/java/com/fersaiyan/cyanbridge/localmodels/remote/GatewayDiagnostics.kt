package com.fersaiyan.cyanbridge.localmodels.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Ships this app's own logcat to the glasses-gateway so the operator's agent can debug
 * without adb. Only runs when the remote server is a Tailscale (*.ts.net) host.
 * Apps can always read their own process's log lines; no special permission is needed.
 */
object GatewayDiagnostics {
    private const val TAG = "GatewayDiagnostics"
    private const val PERIOD_MS = 30_000L
    private const val MAX_LINES = 3_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var lastEpoch: String? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            ship(app, "startup", includeEnvironment = true)
            while (true) {
                delay(PERIOD_MS)
                ship(app, "periodic")
            }
        }
    }

    /** Ship immediately, e.g. right after a failure, with the phone's speech setup attached. */
    fun shipNow(context: Context, reason: String) {
        val app = context.applicationContext
        scope.launch { ship(app, reason, includeEnvironment = true) }
    }

    private fun gatewayBase(context: Context): String? {
        val base = RemoteOpenAiPrefs.getBaseUrl(context).trim().trimEnd('/')
        val host = runCatching { URI(base).host }.getOrNull().orEmpty()
        return base.takeIf { host.endsWith(".ts.net") }
    }

    private suspend fun ship(context: Context, reason: String, includeEnvironment: Boolean = false) {
        val base = gatewayBase(context) ?: return
        lock.withLock {
            val lines = readOwnLog()
            if (lines.isEmpty() && !includeEnvironment) return
            lines.lastOrNull()?.trim()?.substringBefore(' ')?.takeIf { it.contains('.') }?.let { lastEpoch = it }
            val body = buildString {
                if (includeEnvironment) append(environment(context)).append('\n')
                lines.forEach { append(it).append('\n') }
            }
            post(base, reason, body)
        }
    }

    private fun readOwnLog(): List<String> {
        val cmd = mutableListOf("logcat", "-d", "-v", "epoch", "--pid=${Process.myPid()}")
        lastEpoch?.let { cmd += listOf("-T", it) }
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            // -T includes the line at that timestamp, which we already sent
            (if (lastEpoch != null) out.drop(1) else out).takeLast(MAX_LINES)
        } catch (error: Exception) {
            listOf("logcat read failed: $error")
        }
    }

    private fun environment(context: Context): String {
        val resolver = context.contentResolver
        fun secure(key: String) = runCatching { Settings.Secure.getString(resolver, key) }.getOrNull()
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return buildString {
            appendLine("=== environment ===")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("voice_recognition_service=${secure("voice_recognition_service")}")
            appendLine("assistant=${secure("assistant")} voice_interaction_service=${secure("voice_interaction_service")}")
            appendLine("recognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(context)}")
            if (Build.VERSION.SDK_INT >= 31) {
                appendLine("onDeviceRecognitionAvailable=${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
            }
            appendLine("RECORD_AUDIO=${granted(Manifest.permission.RECORD_AUDIO)}")
            if (Build.VERSION.SDK_INT >= 31) {
                appendLine("BLUETOOTH_CONNECT=${granted(Manifest.permission.BLUETOOTH_CONNECT)}")
            }
            appendLine("remote=${RemoteOpenAiPrefs.getBaseUrl(context)} model=${RemoteOpenAiPrefs.getModel(context)} active=${RemoteOpenAiPrefs.isActive(context)}")
            appendLine("=== log ===")
        }
    }

    private fun post(base: String, reason: String, body: String) {
        try {
            val conn = URL("$base/diag").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("X-Reason", reason)
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (error: Exception) {
            Log.w(TAG, "diag upload failed: $error")
        }
    }
}
