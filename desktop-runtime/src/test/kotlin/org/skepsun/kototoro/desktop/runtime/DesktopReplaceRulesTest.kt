package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.replace.ReplaceRule
import java.nio.file.Path

class DesktopReplaceRulesTest {
    @TempDir lateinit var directory: Path

    private fun rules(runtime: DesktopRuntime) = DesktopReplaceRules(runtime.database)

    @Test
    fun `rules survive a restart in the same shared table`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(
                ReplaceRule(id = 0, name = "drop ads", pattern = "ad", replacement = "", order = 2),
                ReplaceRule(id = 0, name = "fix title", pattern = "第(\\d+)章", replacement = "Chapter $1",
                    scopeTitle = true, scopeContent = false, order = 1),
            ))
            assertEquals(2, store.all().size)
        }
        DesktopRuntime.open(directory).use { runtime ->
            val stored = rules(runtime).all()
            // The table returns rules in their applied order, so a restart keeps the sequence the user sees.
            assertEquals(listOf("fix title", "drop ads"), stored.map { it.name })
            assertTrue(stored.all { it.id > 0 })
            assertEquals(stored.map { it.id }.distinct().size, stored.size)
        }
    }

    @Test
    fun `the observed flow follows every write and stays sorted by order`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            assertTrue(store.observeAll().first().isEmpty())
            store.saveAll(listOf(
                ReplaceRule(name = "late", pattern = "b", order = 9),
                ReplaceRule(name = "early", pattern = "a", order = 1),
            ))
            assertEquals(listOf("early", "late"), store.observeAll().first().map { it.name })
            val first = store.all().first()
            store.setEnabled(first, false)
            assertFalse(store.all().first { it.id == first.id }.isEnabled)
            store.setAllEnabled(true)
            assertTrue(store.all().all { it.isEnabled })
            store.delete(first.id)
            assertEquals(1, store.all().size)
        }
    }

    @Test
    fun `the export is the shared Legado shape and reimports exactly`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(
                ReplaceRule(id = 0, name = "strip", group = "清理", pattern = "\\s+$", replacement = "",
                    scopeFilter = "某本书", isRegex = true, order = 3),
            ))
            val json = store.exportToJson()
            assertTrue(json.contains("\"scope\""), json)
            assertTrue(json.contains("\"order\""), json)

            store.clear()
            assertTrue(store.all().isEmpty())
            assertEquals(1, store.importFromJson(json))
            val restored = store.all().single()
            assertEquals("strip", restored.name)
            assertEquals("清理", restored.group)
            assertEquals("某本书", restored.scopeFilter)
            assertEquals(3, restored.order)
        }
    }

    @Test
    fun `a broken document is refused instead of emptying the list`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.save(ReplaceRule(name = "keep", pattern = "a"))
            assertEquals(0, store.importFromJson("{not json"))
            assertEquals(1, store.all().size)
        }
    }

    @Test
    fun `the book filter only offers the rules that reach that book`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val store = rules(runtime)
            store.saveAll(listOf(
                ReplaceRule(name = "anywhere", pattern = "a"),
                ReplaceRule(name = "this book", pattern = "b", scopeFilter = "Some Book"),
                ReplaceRule(name = "elsewhere", pattern = "c", scopeFilter = "Another"),
                ReplaceRule(name = "never", pattern = "d", scopeTitle = false, scopeContent = false),
            ))

            val names = store.forBook("Some Book", "MIHON_1").map { it.name }

            assertEquals(listOf("anywhere", "this book"), names)
            assertNotNull(names)
        }
    }
}
