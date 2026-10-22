package org.skepsun.kototoro.core.extensions

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.sync.google.domain.MemorySyncPreferences

class JarBrokenSourceCacheTest {

    @Test
    fun `broken sources are reused for an unchanged jar`(@TempDir dir: File) {
        val cache = JarBrokenSourceCache(MemorySyncPreferences())
        val jar = File(dir, "parsers.jar").apply { writeText("dex") }

        assertNull(cache.read(jar), "an unscanned jar must miss the cache")

        cache.write(jar, setOf("BROKEN_A", "BROKEN_B"))

        assertEquals(setOf("BROKEN_A", "BROKEN_B"), cache.read(jar))
    }

    @Test
    fun `a jar without broken sources is a cache hit`(@TempDir dir: File) {
        val cache = JarBrokenSourceCache(MemorySyncPreferences())
        val jar = File(dir, "parsers.jar").apply { writeText("dex") }

        cache.write(jar, emptySet())

        assertEquals(emptySet<String>(), cache.read(jar))
    }

    @Test
    fun `replacing the jar file invalidates the cached result`(@TempDir dir: File) {
        val cache = JarBrokenSourceCache(MemorySyncPreferences())
        val jar = File(dir, "parsers.jar").apply { writeText("old build") }
        cache.write(jar, setOf("OLD_BROKEN"))
        val replacedAt = jar.lastModified() + 60_000L

        jar.writeText("new build with different content")
        jar.setLastModified(replacedAt)

        assertNull(cache.read(jar))
    }

    @Test
    fun `retainOnly keeps live jars and drops entries of removed jars`(@TempDir dir: File) {
        val prefs = MemorySyncPreferences()
        val cache = JarBrokenSourceCache(prefs)
        val kept = File(dir, "kept.jar").apply { writeText("a") }
        val removed = File(dir, "removed.jar").apply { writeText("b") }
        cache.write(kept, setOf("KEPT_BROKEN"))
        cache.write(removed, setOf("REMOVED_BROKEN"))
        prefs.edit().putString("unrelated", "value").apply()

        cache.retainOnly(listOf(kept))

        assertEquals(setOf("KEPT_BROKEN"), cache.read(kept))
        assertNull(cache.read(removed))
        assertEquals(1, prefs.all.keys.count { it != "unrelated" }, "only live jar entries may remain")
        assertNotNull(prefs.getString("unrelated", null), "unrelated preferences must stay untouched")
    }
}
