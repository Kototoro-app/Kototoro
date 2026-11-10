package org.skepsun.kototoro.core.parser.legado

internal object LegadoRuleClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
