package de.visionlab.visionpush

import android.util.Log

/**
 * Internal structured logger for the VisionPush SDK.
 *
 * All log calls are gated on the configured [VisionPushConfig.LogLevel] so
 * that production builds produce no output at WARN level and above by default.
 * This class is intentionally not exposed as part of the public API.
 */
internal object VisionPushLogger {

    /** The current minimum log level; mutated once during [VisionPush.initialize]. */
    @Volatile
    var minLevel: VisionPushConfig.LogLevel = VisionPushConfig.LogLevel.WARN

    fun v(tag: String, message: String) {
        if (isLoggable(VisionPushConfig.LogLevel.VERBOSE)) Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        if (isLoggable(VisionPushConfig.LogLevel.DEBUG)) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (isLoggable(VisionPushConfig.LogLevel.INFO)) Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (isLoggable(VisionPushConfig.LogLevel.WARN)) {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isLoggable(VisionPushConfig.LogLevel.ERROR)) {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    private fun isLoggable(level: VisionPushConfig.LogLevel): Boolean =
        level.ordinal >= minLevel.ordinal
}
