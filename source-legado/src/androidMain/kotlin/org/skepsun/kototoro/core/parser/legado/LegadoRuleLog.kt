package org.skepsun.kototoro.core.parser.legado

import android.util.Log

internal object LegadoRuleLog {
    fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun warn(tag: String, message: String, cause: Throwable? = null) {
        if (cause == null) Log.w(tag, message) else Log.w(tag, message, cause)
    }

    fun error(tag: String, message: String, cause: Exception) {
        Log.e(tag, message, cause)
    }

    fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun failure(tag: String, message: String, cause: Throwable?) {
        if (cause == null) Log.e(tag, message) else Log.e(tag, message, cause)
    }
}
