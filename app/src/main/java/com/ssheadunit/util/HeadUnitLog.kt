package com.ssheadunit.util

import android.content.Context
import android.util.Log

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

    /** Loads the persisted setting; call once when the process starts. */
    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFERENCE_LOGGING, false)
    }

    /** Persists and applies the setting. */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_LOGGING, value)
            .apply()
        Log.i(TAG, "Debug logging ${if (value) "enabled" else "disabled"}")
    }

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    /** Always logged: unexpected but non fatal conditions. */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    /** Always logged. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }

    private const val TAG = "HeadUnitLog"
}
