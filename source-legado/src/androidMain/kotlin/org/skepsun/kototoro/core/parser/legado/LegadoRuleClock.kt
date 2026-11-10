package org.skepsun.kototoro.core.parser.legado

internal object LegadoRuleClock {
    fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}
