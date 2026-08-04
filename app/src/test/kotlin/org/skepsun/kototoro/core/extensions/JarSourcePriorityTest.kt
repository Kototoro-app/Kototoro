package org.skepsun.kototoro.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JarSourcePriorityTest {

    @Test
    fun `same source is deduplicated across parser architectures`() {
        val selected = selectPreferredJarSources(
            sources = listOf(
                TestSource("MANGADEX", "uma.jar", "tsuki"),
                TestSource("MANGADEX", "kotatsu-parsers-redo.jar", "kotatsu"),
                TestSource("WEBTOONS", "uma.jar", "tsuki"),
            ),
            priorityOrder = "kotatsu-parsers-redo,uma",
            sourceName = TestSource::name,
            jarName = TestSource::jarName,
        )

        assertEquals(
            listOf(
                TestSource("MANGADEX", "kotatsu-parsers-redo.jar", "kotatsu"),
                TestSource("WEBTOONS", "uma.jar", "tsuki"),
            ),
            selected,
        )

        val umaFirst = selectPreferredJarSources(
            sources = selected + TestSource("MANGADEX", "uma.jar", "tsuki"),
            priorityOrder = "uma,kotatsu-parsers-redo",
            sourceName = TestSource::name,
            jarName = TestSource::jarName,
        )
        assertEquals("uma.jar", umaFirst.first { it.name == "MANGADEX" }.jarName)
    }

    @Test
    fun `newly installed uma is appended to saved order`() {
        assertEquals(
            listOf("kotatsu-parsers-redo.jar", "uma.jar"),
            resolveJarPriorityOrder(
                installedJarNames = listOf("uma.jar", "kotatsu-parsers-redo.jar"),
                savedOrder = "kotatsu-parsers-redo",
            ),
        )
    }

    @Test
    fun `unconfigured jars have deterministic alphabetical priority`() {
        val comparator = jarPriorityComparator("")

        assertEquals("alpha.jar", listOf("zeta.jar", "alpha.jar").minWith(comparator))
    }

    private data class TestSource(
        val name: String,
        val jarName: String,
        val architecture: String,
    )
}
