package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import java.nio.file.Path

class DesktopDictionaryRulesTest {
    @TempDir lateinit var directory: Path

    private fun rules(runtime: DesktopRuntime) = DesktopDictionaryRules(runtime.database)

    @Test
    fun `a fresh data root starts with the rules the build ships`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val stored = rules(runtime).all()

            // The same asset Android seeds from, copied into the Windows resources by the build.
            assertTrue(stored.isNotEmpty()) { "no bundled dictionary rules were seeded" }
            assertEquals(listOf(0, 1), stored.map { it.sortNumber }.take(2))
            assertTrue(stored.all { it.urlRule.isNotBlank() })
        }
    }

    @Test
    fun `rules survive a restart with their order and enable flags`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(
                DictionaryRule(name = "本地词典", urlRule = "data:text/plain,{{key}}", enabled = false),
                DictionaryRule(name = "海词英文", urlRule = "https://apii.dict.cn/mini.php?q={{key}}"),
            ))
            assertEquals(listOf("本地词典", "海词英文"), store.all().map { it.name })
        }
        DesktopRuntime.open(directory).use { runtime ->
            val stored = rules(runtime).all()

            assertEquals(listOf("本地词典", "海词英文"), stored.map { it.name })
            assertEquals(listOf(false, true), stored.map { it.enabled })
            assertEquals(listOf(0, 1), stored.map { it.sortNumber })
            assertEquals(listOf("海词英文"), rules(runtime).enabled().map { it.name })
        }
    }

    @Test
    fun `the observed flow follows writes and a toggle keeps the rest untouched`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(
                DictionaryRule(name = "a", urlRule = "https://a.example/{{key}}"),
                DictionaryRule(name = "b", urlRule = "https://b.example/{{key}}"),
            ))
            val first = store.observeAll().first()
            assertEquals(listOf("a", "b"), first.map { it.name })

            store.setEnabled(first[1], false)

            val updated = store.observeAll().first()
            assertEquals(listOf(true, false), updated.map { it.enabled })
            assertEquals(listOf("a"), store.enabled().map { it.name })
        }
    }

    @Test
    fun `saving an existing name replaces it in place and a new name is appended`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(
                DictionaryRule(name = "a", urlRule = "https://a.example/{{key}}"),
                DictionaryRule(name = "b", urlRule = "https://b.example/{{key}}"),
            ))

            store.save(DictionaryRule(name = "b", urlRule = "https://b.example/?q={{key}}", showRule = "tag.body@all"))
            store.save(DictionaryRule(name = "c", urlRule = "https://c.example/{{key}}"))

            val stored = store.all()
            assertEquals(listOf("a", "b", "c"), stored.map { it.name })
            assertEquals("https://b.example/?q={{key}}", stored[1].urlRule)
            assertEquals("tag.body@all", stored[1].showRule)
        }
    }

    @Test
    fun `export round-trips through import and a broken document changes nothing`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(DictionaryRule(name = "a", urlRule = "https://a.example/{{key}}")))
            val exported = store.exportToJson()
            assertTrue(exported.contains("\"a\"")) { exported }
            assertEquals(1, store.all().size)

            // Importing the document that was just exported changes nothing: the name is the primary key.
            assertEquals(1, store.importFromJson(exported))
            assertEquals(listOf("a"), store.all().map { it.name })

            assertEquals(0, store.importFromJson("not json"))
            assertEquals(listOf("a"), store.all().map { it.name })

            val merged = store.importFromJson(
                """
                [{"name": "a", "urlRule": "https://a2.example/{{key}}"}, {"name": "b", "urlRule": "u"}]
                """.trimIndent(),
            )
            assertEquals(2, merged)
            assertEquals(listOf("a", "b"), store.all().map { it.name })
            assertEquals("https://a2.example/{{key}}", store.all().first().urlRule)
        }
    }

    @Test
    fun `deleting every rule brings the bundled defaults back, as on Android`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(DictionaryRule(name = "a", urlRule = "https://a.example/{{key}}")))
            assertEquals(1, store.all().size)

            store.saveAll(emptyList())

            // Both hosts seed an empty table on read, so "delete everything" means "back to the shipped rules".
            val reseeded = store.all()
            assertTrue(reseeded.isNotEmpty()) { "the defaults were not seeded again" }
            assertTrue(reseeded.none { it.name == "a" })
        }
    }
}
