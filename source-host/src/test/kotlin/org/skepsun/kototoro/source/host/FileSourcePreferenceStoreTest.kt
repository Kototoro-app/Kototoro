package org.skepsun.kototoro.source.host

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileSourcePreferenceStoreTest {
    @TempDir lateinit var root: Path

    @Test
    fun `all types survive reopen with original namespaces and isolated sets`() {
        val external = mutableSetOf("字", "图")
        val values = mapOf(
            "text" to SourcePreferenceValue.Text("中文 📚"),
            "bool" to SourcePreferenceValue.Toggle(false),
            "int" to SourcePreferenceValue.Integer(Int.MAX_VALUE),
            "long" to SourcePreferenceValue.LongInteger(Long.MIN_VALUE),
            "float" to SourcePreferenceValue.FloatBits(0x7fc00001),
            "set" to SourcePreferenceValue.TextSet(external),
        )
        FileSourcePreferenceStore(root).use { store ->
            val first = store.open("source_9007199254740993")
            assertSame(first, store.open("source_9007199254740993"))
            assertTrue(first.edit(SourcePreferenceEdit(changes = values)))
            external.clear()
            (first.snapshot().getValue("set") as SourcePreferenceValue.TextSet).values.let {
                (it as MutableSet).clear()
            }
            assertEquals(setOf("字", "图"), (first.snapshot().getValue("set") as SourcePreferenceValue.TextSet).values)
            assertTrue(store.open("../source_9007199254740993").edit(SourcePreferenceEdit(changes =
                mapOf("text" to SourcePreferenceValue.Text("different")))))
        }
        FileSourcePreferenceStore(root).use { store ->
            assertEquals(values + ("set" to SourcePreferenceValue.TextSet(setOf("字", "图"))),
                store.open("source_9007199254740993").snapshot())
            assertEquals(SourcePreferenceValue.Text("different"), store.open("../source_9007199254740993")
                .snapshot()["text"])
        }
        Files.list(root).use { files ->
            assertTrue(files.allMatch { it.fileName.toString() == ".owner.lock" ||
                it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json")) })
        }
    }

    @Test
    fun `disk inventory verifies identities and confirmed removal retires cached views`() {
        FileSourcePreferenceStore(root).use { store ->
            val old = store.open("retired")
            old.edit(SourcePreferenceEdit(changes = mapOf("k" to SourcePreferenceValue.Text("v"))))
            val snapshot = store.snapshots().single()
            assertEquals("retired", snapshot.namespace)
            assertEquals(old.snapshot(), store.readStored(snapshot))
            assertTrue(store.removeSnapshot(snapshot))
            assertFalse(store.removeSnapshot(snapshot))
            assertThrows(IllegalStateException::class.java) { old.snapshot() }
            assertThrows(IllegalStateException::class.java) { old.edit(SourcePreferenceEdit()) }
            assertTrue(store.open("retired").snapshot().isEmpty())
            assertTrue(store.open("retired").edit(SourcePreferenceEdit(changes = mapOf("new" to SourcePreferenceValue.Toggle(true)))))
        }
        FileSourcePreferenceStore(root).use { store ->
            assertEquals(mapOf("new" to SourcePreferenceValue.Toggle(true)), store.open("retired").snapshot())
        }
    }

    @Test
    fun `inventory blocks malformed documents and removal rejects snapshots changed after preview`() {
        FileSourcePreferenceStore(root).use { store ->
            val prefs = store.open("changing")
            prefs.edit(SourcePreferenceEdit(changes = mapOf("k" to SourcePreferenceValue.Text("a"))))
            val snapshot = store.snapshots().single()
            prefs.edit(SourcePreferenceEdit(changes = mapOf("k" to SourcePreferenceValue.Text("b"))))
            assertThrows(java.io.IOException::class.java) { store.removeSnapshot(snapshot) }
            assertEquals(SourcePreferenceValue.Text("b"), prefs.snapshot()["k"])
            val path = Files.list(root).use { files -> files.filter { it.toString().endsWith(".json") }.findFirst().get() }
            Files.writeString(path, "{broken")
            assertThrows(Exception::class.java) { store.snapshots() }
            assertEquals("{broken", Files.readString(path))
        }
    }

    @Test
    fun `clear applies before key changes and callbacks can reenter after atomic edit`() {
        FileSourcePreferenceStore(root).use { store ->
            val prefs = store.open("source_1")
            prefs.edit(SourcePreferenceEdit(changes = mapOf("old" to SourcePreferenceValue.Integer(1))))
            val observed = mutableListOf<Set<String>>()
            val callback = SourcePreferenceListener { keys ->
                observed += keys.keys
                if ("new" in keys.keys) prefs.edit(SourcePreferenceEdit(changes =
                    mapOf("callback" to SourcePreferenceValue.Toggle(true))))
            }
            prefs.addListener(callback)
            prefs.edit(SourcePreferenceEdit(clear = true, changes = mapOf(
                "old" to null, "new" to SourcePreferenceValue.Integer(2))))
            assertEquals(listOf(setOf("new"), setOf("callback")), observed)
            prefs.removeListener(callback)
            prefs.edit(SourcePreferenceEdit(changes = mapOf("new" to SourcePreferenceValue.Integer(3))))
            assertEquals(2, observed.size)
        }
    }

    @Test
    fun `one directory owner and stale objects release Windows handles on idempotent close`() {
        val store = FileSourcePreferenceStore(root)
        val prefs = store.open("source_1")
        prefs.edit(SourcePreferenceEdit(changes = mapOf("k" to SourcePreferenceValue.Text("v"))))
        assertThrows(OverlappingFileLockException::class.java) { FileSourcePreferenceStore(root) }
        store.close()
        store.close()
        assertThrows(IllegalStateException::class.java) { prefs.snapshot() }
        val moved = root.resolveSibling("moved preferences")
        Files.move(root, moved)
        FileSourcePreferenceStore(moved).use { assertEquals(SourcePreferenceValue.Text("v"),
            it.open("source_1").snapshot()["k"]) }
        Files.move(moved, root)
    }

    @Test
    fun `malformed and unsupported snapshots are preserved without silent reset`() {
        FileSourcePreferenceStore(root).use { it.open("source_1").edit(SourcePreferenceEdit(changes =
            mapOf("k" to SourcePreferenceValue.Text("v")))) }
        val path = Files.list(root).use { it.filter { file -> file.toString().endsWith(".json") }.findFirst().get() }
        val original = Files.readString(path)
        for (bad in listOf("{broken", original.replace("\"version\":1", "\"version\":99"),
            original.replace("source_1", "source_2"))) {
            Files.writeString(path, bad)
            FileSourcePreferenceStore(root).use { store -> assertThrows(Exception::class.java) { store.open("source_1") } }
            assertEquals(bad, Files.readString(path))
        }
    }

    @Test
    fun `disk failure reports false keeps published memory and retries latest full snapshot`() {
        FileSourcePreferenceStore(root).use { store ->
            val prefs = store.open("source_1")
            prefs.edit(SourcePreferenceEdit(changes = mapOf("old" to SourcePreferenceValue.Integer(1))))
            val path = Files.list(root).use { it.filter { file -> file.toString().endsWith(".json") }.findFirst().get() }
            val saved = path.resolveSibling("preserved.json")
            Files.move(path, saved)
            Files.createDirectory(path)
            val observed = mutableListOf<Set<String>>()
            prefs.addListener { observed += it.keys }
            assertFalse(prefs.edit(SourcePreferenceEdit(changes = mapOf("new" to SourcePreferenceValue.Integer(2)))))
            assertEquals(SourcePreferenceValue.Integer(2), prefs.snapshot()["new"])
            assertEquals(listOf(setOf("new")), observed)
            Files.move(path, root.resolve("obstruction"))
            Files.move(saved, path)
            assertTrue(prefs.edit(SourcePreferenceEdit()))
        }
        FileSourcePreferenceStore(root).use { assertEquals(SourcePreferenceValue.Integer(2),
            it.open("source_1").snapshot()["new"]) }
    }

    @Test
    fun `concurrent editor deltas preserve unrelated keys and bounded snapshots reject before publication`() {
        FileSourcePreferenceStore(root, 4096).use { store ->
            val prefs = store.open("source_1")
            val executor = Executors.newFixedThreadPool(4)
            try {
                val jobs = (0..39).map { index -> executor.submit<Boolean> {
                    prefs.edit(SourcePreferenceEdit(changes = mapOf("$index" to SourcePreferenceValue.Integer(index))))
                } }
                jobs.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            } finally { executor.shutdownNow() }
            assertEquals(40, prefs.snapshot().size)
            assertThrows(IllegalArgumentException::class.java) { prefs.edit(SourcePreferenceEdit(changes =
                mapOf("oversized" to SourcePreferenceValue.Text("x".repeat(4096))))) }
            assertFalse(prefs.snapshot().containsKey("oversized"))
        }
    }
}
