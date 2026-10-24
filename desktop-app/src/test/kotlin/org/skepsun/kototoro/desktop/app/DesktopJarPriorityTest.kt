package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parserhost.ParserPluginRegistry

/**
 * Android's `jar_priority_order` on the Windows side: the stored value names the parser plugins that may declare the
 * same source, and the parser host ranks them in that order. A fresh install has no stored value, so the registry's
 * own default (which matches Android's `DEFAULT_JAR_PRIORITY_ORDER_VALUE`) has to come out unchanged.
 */
class DesktopJarPriorityTest {

    @Test
    fun `an empty order leaves the registry default untouched`() {
        val priority = parserPluginPriority("")

        assertEquals(ParserPluginRegistry.DEFAULT_PRIORITY, priority)
    }

    @Test
    fun `the stored order is ranked before everything it does not name`() {
        val priority = parserPluginPriority("uma,kototoro-parsers")

        assertEquals(listOf("uma", "kototoro-parsers"), priority.take(2))
        assertTrue("kotatsu-parsers-redo" in priority.drop(2), priority.toString())
    }

    @Test
    fun `blank and repeated entries never change the ranking`() {
        val priority = parserPluginPriority(" uma ,, , UMA ")

        assertEquals("uma", priority.first())
        assertEquals(priority.size, priority.distinctBy { it.lowercase() }.size)
    }

    @Test
    fun `the stored text is Android's comma separated list`() {
        assertEquals(listOf("uma", "kotatsu-parsers-redo"), " uma, kotatsu-parsers-redo ".parseJarPriorityNames())
        assertEquals(emptyList<String>(), "  ,, ".parseJarPriorityNames())
    }
}
