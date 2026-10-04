package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DesktopDownloadStoreTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val revision = "a".repeat(64)
    private val chapter = SourceChapter(Long.MAX_VALUE, "章节", 1f, 0, "/1", null, 0, null, source)
    private fun record(chapter: SourceChapter = this.chapter, count: Int = 2, completed: Int = count) =
        SourceChapterDownload(Long.MIN_VALUE, chapter, revision, (0 until count).map { index ->
            val page = SourcePage(Long.MIN_VALUE + index, "/page/$index", null, source,
                requestContext = SourcePageRequestContext(index, "/original", "/image#opaque"))
            if (index >= completed) SourceDownloadPage(page) else SourceDownloadPage(page,
                SourceImageArtifact(source, page.id, "$revision.img", revision, 100, "image/png"), 400, 600)
        }, "作品")

    @Test
    fun `legacy partial records migrate without edits and current publications win after reopening`() {
        val previous = record(count = 5, completed = 2)
        val key = DesktopDownloadStore.key(previous.contentId, previous.chapter)
        FileSourcePreferenceStore(directory).use { preferences ->
            val legacy = preferences.open("desktop_downloads")
            legacy.edit(SourcePreferenceEdit(changes = mapOf(key to SourcePreferenceValue.Text(
                SourceProtocolJson.encodeToString(previous)))))
            val original = legacy.snapshot()
            val store = DesktopDownloadStore(preferences)
            assertTrue(store.errors.isEmpty())
            assertEquals(previous, store.records.value.single())
            assertEquals(original, legacy.snapshot())
            store.save(record(count = 5))
            assertEquals(original, legacy.snapshot())
        }
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertEquals(record(count = 5), store.records.value.single())
            assertTrue(store.records.value.single().isComplete)
            assertTrue(store.errors.isEmpty())
        }
    }

    @Test
    fun `chapter sets beyond four MiB persist through actual per document capped preference snapshots`() {
        val opaque = "https://fixture.invalid/" + "x".repeat(12_000)
        val records = (0..4).map { chapterIndex ->
            record(chapter.copy(id = chapter.id - chapterIndex, url = "/$chapterIndex"),
                count = if (chapterIndex == 4) 448 else 128).let { item ->
                item.copy(pages = item.pages.map { it.copy(page = it.page.copy(url = "$opaque/$chapterIndex/${it.page.id}")) })
            }
        }
        assertTrue(records.sumOf { SourceProtocolJson.encodeToString(it).toByteArray().size.toLong() } > 4L * 1024 * 1024)
        assertTrue(SourceProtocolJson.encodeToString(records.last()).toByteArray().size > 4 * 1024 * 1024)
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            records.forEach(store::save)
            assertEquals(5, store.records.value.size)
            assertThrows(IllegalArgumentException::class.java) {
                preferences.open("desktop_downloads").edit(SourcePreferenceEdit(changes = records.associate {
                    DesktopDownloadStore.key(it.contentId, it.chapter) to
                        SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(it))
                }))
            }
        }
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertTrue(store.errors.isEmpty())
            assertEquals(records.toSet(), store.records.value.toSet())
            assertTrue(store.records.value.all { it.isComplete })
        }
        Files.list(directory).use { files ->
            val snapshots = files.filter { it.fileName.toString().endsWith(".json") }.toList()
            assertTrue(snapshots.sumOf(Files::size) > 4L * 1024 * 1024)
            assertTrue(snapshots.all { Files.size(it) <= 4L * 1024 * 1024 })
        }
    }

    @Test
    fun `migration publication failure keeps the legacy record usable and retries on real store reopening`() {
        val previous = record(count = 5, completed = 2)
        val key = DesktopDownloadStore.key(previous.contentId, previous.chapter)
        FileSourcePreferenceStore(directory).use { preferences ->
            val legacy = preferences.open("desktop_downloads")
            legacy.edit(SourcePreferenceEdit(changes = mapOf(key to
                SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(previous)))))
            val snapshot = legacy.snapshot()
            val faulted = object : SourcePreferenceStore {
                override fun open(namespace: String): SourcePreferences {
                    val original = preferences.open(namespace)
                    return if (!namespace.startsWith("desktop_download_pages_v2_")) original
                        else object : SourcePreferences by original {
                            override fun edit(edit: SourcePreferenceEdit) = false
                        }
                }
            }
            val store = DesktopDownloadStore(faulted)
            assertEquals(previous, store.records.value.single())
            assertEquals(snapshot, legacy.snapshot())
            assertTrue(store.errors.single().contains("迁移失败"))
        }
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertTrue(store.errors.isEmpty())
            assertEquals(previous, store.records.value.single())
        }
    }

    @Test
    fun `failed chunk root and discovery edits never publish success or trust failed memory snapshots on retry`() {
        for (prefix in listOf("desktop_download_pages_v2_", "desktop_download_chapter_v2_", "desktop_download_index_v2_")) {
            val root = directory.resolve(prefix)
            FileSourcePreferenceStore(root).use { preferences ->
                var fail = false
                var edits = 0
                val views = mutableMapOf<String, SourcePreferences>()
                val faulted = object : SourcePreferenceStore {
                    override fun open(namespace: String): SourcePreferences = views.getOrPut(namespace) {
                        val original = preferences.open(namespace)
                        if (!namespace.startsWith(prefix)) original else object : SourcePreferences by original {
                            var failedMemory: Map<String, SourcePreferenceValue>? = null
                            override fun snapshot() = failedMemory ?: original.snapshot()
                            override fun edit(edit: SourcePreferenceEdit): Boolean {
                                edits++
                                if (fail) {
                                    fail = false
                                    failedMemory = (if (edit.clear) emptyMap() else snapshot()).toMutableMap().also { values ->
                                        edit.changes.forEach { (key, value) ->
                                            if (value == null) values.remove(key) else values[key] = value
                                        }
                                    }
                                    return false
                                }
                                val saved = original.edit(edit)
                                if (saved) failedMemory = null
                                return saved
                            }
                        }
                    }
                }
                val store = DesktopDownloadStore(faulted)
                val previous = if (prefix == "desktop_download_index_v2_") null else record(completed = 0)
                previous?.let(store::save)
                val identity = DesktopDownloadStore.key(record().contentId, record().chapter)
                val manifestPath = root.resolve(imageKey("desktop_download_chapter_v2_$identity".toByteArray()) + ".json")
                val oldBytes = previous?.let { Files.readAllBytes(manifestPath) }
                edits = 0
                fail = true
                assertThrows(IOException::class.java) { store.save(record()) }
                assertEquals(previous?.let { listOf(it) } ?: emptyList<SourceChapterDownload>(), store.records.value)
                if (oldBytes != null) assertArrayEquals(oldBytes, Files.readAllBytes(manifestPath))
                else assertFalse(Files.exists(root.resolve(
                    imageKey("desktop_download_index_v2_${identity.first()}".toByteArray()) + ".json")))
                store.save(record())
                assertEquals(2, edits)
                assertEquals(record(), store.records.value.single())
            }
            FileSourcePreferenceStore(root).use { preferences ->
                assertEquals(record(), DesktopDownloadStore(preferences).records.value.single())
            }
        }
    }

    @Test
    fun `corrupted chunk rejects its chapter without reviving legacy progress or losing healthy chapters`() {
        val damaged = record(completed = 1)
        val healthy = record(chapter.copy(id = 4, url = "/healthy"))
        val identity = DesktopDownloadStore.key(damaged.contentId, damaged.chapter)
        FileSourcePreferenceStore(directory).use { preferences ->
            preferences.open("desktop_downloads").edit(SourcePreferenceEdit(changes = mapOf(identity to
                SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(damaged)))))
            val store = DesktopDownloadStore(preferences)
            store.save(healthy)
            val manifest = SourceProtocolJson.decodeFromString<SourceDownloadManifest>(
                (preferences.open("desktop_download_chapter_v2_$identity").snapshot()["manifest"]
                    as SourcePreferenceValue.Text).value)
            val chunk = preferences.open("desktop_download_pages_v2_${manifest.chunks.single()}")
            val text = (chunk.snapshot()["pages"] as SourcePreferenceValue.Text).value
            chunk.edit(SourcePreferenceEdit(changes = mapOf("pages" to SourcePreferenceValue.Text(text + " "))))
        }
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertEquals(listOf(healthy), store.records.value)
            assertEquals(1, store.errors.size)
            assertNull(store.find(damaged.contentId, damaged.chapter))
        }
        // A broken retired namespace cannot make valid new chapters inaccessible.
        Files.writeString(directory.resolve(imageKey("desktop_downloads".toByteArray()) + ".json"), "{broken")
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertEquals(listOf(healthy), store.records.value)
            assertTrue(store.errors.any { it.contains("旧版下载索引读取失败") })
        }
    }
}
