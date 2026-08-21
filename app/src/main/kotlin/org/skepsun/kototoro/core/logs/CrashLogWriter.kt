package org.skepsun.kototoro.core.logs

import android.content.Context

/**
 * UncaughtExceptionHandler that writes crash logs to disk before
 * delegating to the previous handler (e.g. ACRA).
 */
class CrashLogWriter(
    private val context: Context,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        CrashLogManager.writeCrashLog(context, t, e)
        previousHandler?.uncaughtException(t, e)
    }

    companion object {

        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashLogWriter(context, previous))
        }
    }
}
