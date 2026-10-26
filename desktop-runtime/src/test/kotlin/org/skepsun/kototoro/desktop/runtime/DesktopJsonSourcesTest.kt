package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

class DesktopJsonSourcesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `removing a source repository keeps unrelated sources after reopening storage`() {
        val entries = listOf("one", "two", "other").map { DesktopJsonSource(it, it, "{}") }
        FileSourcePreferenceStore(directory).use { store ->
            val sources = DesktopJsonSources(store.open("json_sources"))
            assertTrue(sources.add(entries))
            assertTrue(sources.removeAll(setOf("one", "two")))
            assertEquals(listOf(entries.last()), sources.all())
        }
        FileSourcePreferenceStore(directory).use { store ->
            assertEquals(listOf(entries.last()), DesktopJsonSources(store.open("json_sources")).all())
        }
    }
}
