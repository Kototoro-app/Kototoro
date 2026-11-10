package org.skepsun.kototoro.core.parser.legado

import java.util.logging.Level
import java.util.logging.Logger

internal object LegadoRuleLog {
    fun debug(tag: String, message: String) {
        Logger.getLogger(tag).fine(message)
    }

    fun warn(tag: String, message: String, cause: Throwable? = null) {
        if (cause == null) Logger.getLogger(tag).warning(message)
        else Logger.getLogger(tag).log(Level.WARNING, message, cause)
    }

    fun error(tag: String, message: String, cause: Exception) {
        Logger.getLogger(tag).log(Level.WARNING, message, cause)
    }

    fun info(tag: String, message: String) {
        // Android logs engine lifecycle at info; a desktop console would print it for every source operation.
        Logger.getLogger(tag).fine(message)
    }

    fun failure(tag: String, message: String, cause: Throwable?) {
        if (cause == null) Logger.getLogger(tag).severe(message)
        else Logger.getLogger(tag).log(Level.SEVERE, message, cause)
    }
}
