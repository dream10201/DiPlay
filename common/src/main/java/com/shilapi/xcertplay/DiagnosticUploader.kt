package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

internal object DiagnosticUploader {
    private const val ENDPOINT = "http://81.68.132.73:1315/upload"
    private const val MIN_INTERVAL_MILLIS = 60_000L
    private const val TAG = "DiPlay-Upload"
    @Volatile private var lastAutomaticUploadAt = 0L
    @Volatile private var crashHandlerInstalled = false

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).getBoolean("auto_upload_reports", true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putBoolean("auto_upload_reports", value).apply()
    }

    /** Automatic trigger: only when enabled, at most once per minute. */
    fun uploadIfEnabled(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!enabled(appContext)) return
        val now = System.currentTimeMillis()
        if (now - lastAutomaticUploadAt < MIN_INTERVAL_MILLIS) return
        lastAutomaticUploadAt = now
        Thread({ runCatching { send(appContext, reason, build(appContext)) }.onFailure { Log.w(TAG, "upload failed", it) } }, "diplay-upload").start()
    }

    /** Manual trigger from Settings; reports the outcome. */
    fun uploadNow(context: Context, onDone: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        Thread({ onDone(runCatching { send(appContext, "manual", build(appContext)) }) }, "diplay-upload").start()
    }

    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (enabled(appContext)) {
                    val trace = "--- Uncaught exception on ${thread.name} ---\n${Log.getStackTraceString(error)}\n\n"
                    send(appContext, "crash", trace + build(appContext))
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun build(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        return buildString {
            append(DiagnosticReport.build(context, version, null))
            appendLine()
            appendLine("--- logcat (this process) ---")
            appendLine(recentLogcat())
        }
    }

    /** Own-process logcat keeps protocol frames; secrets are masked before leaving the device. */
    private fun recentLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "6000")
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().useLines { lines ->
            lines.joinToString("\n") { line ->
                if (SECRET.containsMatchIn(line)) SECRET_VALUE.replace(line, "$1[redacted]") else line
            }
        }
        process.waitFor()
        text
    }.getOrElse { "logcat unavailable: $it" }

    private val SECRET = Regex("(?i)passphrase|password|linkkey|identity_private|private key")
    private val SECRET_VALUE = Regex("(?i)((?:passphrase|password|linkkey|identity_private|private key)\\S*[:=]?\\s*)\\S.*")

    private fun send(context: Context, reason: String, report: String): String {
        val body = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(report.toByteArray()) } }.toByteArray()
        val device = "${Build.MODEL}-${Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"}"
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.setRequestProperty("X-Device", device)
            connection.setRequestProperty("X-Reason", reason)
            connection.setRequestProperty("X-Android", Build.VERSION.SDK_INT.toString())
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val response = connection.inputStream.bufferedReader().readText().trim()
            if (code != 200) throw IllegalStateException("HTTP $code")
            Log.i(TAG, "uploaded reason=$reason as $response")
            return response
        } finally {
            connection.disconnect()
        }
    }
}
