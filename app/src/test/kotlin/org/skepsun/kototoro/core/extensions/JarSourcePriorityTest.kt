package org.skepsun.kototoro.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `newer build of one package wins over the alphabetical order`() {
        val selected = selectPreferredJarSources(
            sources = listOf(
                TestSource("MIDARA", "org.skepsun.kototoro.parsers.jar", "kototoro"),
                TestSource("MIDARA", "org.skepsun.kototoro.parsers.v703.jar", "kototoro"),
            ),
            priorityOrder = DEFAULT_JAR_PRIORITY_ORDER_VALUE,
            sourceName = TestSource::name,
            jarName = TestSource::jarName,
        )

        assertEquals("org.skepsun.kototoro.parsers.v703.jar", selected.single().jarName)
    }

    @Test
    fun `configured priority order still beats the version`() {
        val selected = selectPreferredJarSources(
            sources = listOf(
                TestSource("MANGADEX", "uma-1.9.9.jar", "tsuki"),
                TestSource("MANGADEX", "kotatsu-parsers-redo.jar", "kotatsu"),
            ),
            priorityOrder = "kotatsu-parsers-redo,uma",
            sourceName = TestSource::name,
            jarName = TestSource::jarName,
        )

        assertEquals("kotatsu-parsers-redo.jar", selected.single().jarName)
    }

    @Test
    fun `package key ignores a version suffix but not a sibling package`() {
        assertEquals("org.skepsun.kototoro.parsers", "org.skepsun.kototoro.parsers.jar".jarPackageKey())
        assertEquals("org.skepsun.kototoro.parsers", "org.skepsun.kototoro.parsers.v703.jar".jarPackageKey())
        assertEquals("org.skepsun.kototoro.parsers", "org.skepsun.kototoro.parsers-1.9.9.jar".jarPackageKey())
        assertEquals("org.skepsun.kototoro.parsers", "org.skepsun.kototoro.parsers_134.jar".jarPackageKey())
        assertEquals("org.skepsun.kototoro.parsers.extra", "org.skepsun.kototoro.parsers.extra.jar".jarPackageKey())
        assertEquals("kotatsu-parsers-redo", "kotatsu-parsers-redo.jar".jarPackageKey())
        assertEquals("uma", "uma.jar".jarPackageKey())
    }

    @Test
    fun `version code follows the version marker of the file name`() {
        assertTrue("x.v703.jar".jarVersionCode() > "x.v134.jar".jarVersionCode())
        assertTrue("x-1.9.9.jar".jarVersionCode() > "x-1.9.8.jar".jarVersionCode())
        assertEquals(0L, "uma.jar".jarVersionCode())
        assertEquals(0L, "org.skepsun.kototoro.parsers.jar".jarVersionCode())
    }

    @Test
    fun `duplicate jars of one package collapse to the newest build`() {
        val result = deduplicateJarPackages(
            plugins = listOf(
                "org.skepsun.kototoro.parsers.jar",
                "org.skepsun.kototoro.parsers.v703.jar",
                "uma.jar",
            ),
            jarName = { it },
        )

        assertEquals(
            listOf("org.skepsun.kototoro.parsers.v703.jar", "uma.jar"),
            result.kept,
        )
        assertEquals(listOf("org.skepsun.kototoro.parsers.jar"), result.dropped)
    }

    @Test
    fun `distinct packages are never collapsed`() {
        val result = deduplicateJarPackages(
            plugins = listOf("uma.jar", "kotatsu-parsers-redo.jar", "org.skepsun.kototoro.parsers.extra.jar"),
            jarName = { it },
        )

        assertEquals(0, result.dropped.size)
    }

    private data class TestSource(
        val name: String,
        val jarName: String,
        val architecture: String,
    )
}
