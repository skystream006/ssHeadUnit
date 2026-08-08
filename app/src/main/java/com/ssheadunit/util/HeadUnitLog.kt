package com.ssheadunit.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central switch for the diagnostic logging of the projection stack.
 *
 * Detailed logging (USB descriptors, protocol traffic, session phases) is off by default and can
 * be turned on from the settings dialog when a connection needs to be debugged. Warnings and
 * errors are always logged, so an unexpected message or a lost link is never invisible.
 */
object HeadUnitLog {

    const val PREFERENCES_NAME = "settings"
    const val PREFERENCE_LOGGING = "debug_logging"

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var logFile: File? = null

    /** Loads the persisted setting; call once when the process starts. */
    fun load(context: Context) {
        val appContext = context.applicationContext
        enabled = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFERENCE_LOGGING, false)
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
    }

    /** Persists and applies the setting. */
    fun setEnabled(context: Context, value: Boolean) {
        val appContext = context.applicationContext
        enabled = value
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_LOGGING, value)
            .apply()
        Log.i(TAG, "Debug logging ${if (value) "enabled" else "disabled"}")
        write(TAG, "I", "Debug logging ${if (value) "enabled" else "disabled"}")
    }

    fun d(tag: String, message: String) {
        if (enabled) {
            Log.d(tag, message)
            write(tag, "D", message)
        }
    }

    fun i(tag: String, message: String) {
        if (enabled) {
            Log.i(tag, message)
            write(tag, "I", message)
        }
    }

    /** Always logged: unexpected but non fatal conditions. */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        write(tag, "W", message)
    }

    /** Always logged. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        write(tag, "E", message, error)
    }

    /** Returns the app-private diagnostic log, or an empty string before the first record. */
    fun read(context: Context): String = synchronized(lock) {
        val file = logFile ?: File(context.applicationContext.filesDir, LOG_FILE_NAME)
        if (file.isFile) file.readText() else ""
    }

    private fun write(tag: String, level: String, message: String, error: Throwable? = null) = synchronized(lock) {
        val file = logFile ?: return
        runCatching {
            if (file.length() > MAX_LOG_SIZE_BYTES) {
                file.writeText("--- Previous debug log rotated after reaching the size limit ---\n")
            }
            val throwable = error?.let {
                StringWriter().also { writer -> it.printStackTrace(PrintWriter(writer)) }.toString()
            }.orEmpty()
            file.appendText("${timestamp().format(Date())} $level/$tag: $message\n$throwable")
        }
    }

    private const val TAG = "HeadUnitLog"
    private const val LOG_FILE_NAME = "debug.log"
    private const val MAX_LOG_SIZE_BYTES = 1_000_000L
    private val lock = Any()
    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}
