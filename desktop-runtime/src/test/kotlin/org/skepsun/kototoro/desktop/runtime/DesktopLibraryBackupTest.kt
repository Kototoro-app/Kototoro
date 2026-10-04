package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.backups.data.model.SourceBackup
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.source.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DesktopLibraryBackupTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val chapter = SourceChapter(Long.MIN_VALUE, "章节", 1f, 0, "/chapter", null, 0, null, source)
    private val content = SourceContent(Long.MAX_VALUE, "中文备份", emptySet(), "/work", "https://fixture.invalid/work",
        .5f, null, null, setOf(SourceTag("标签", "tag", source)), null, emptySet(), source,
        description = "本地详情", chapters = listOf(chapter), sourceData = "opaque")
    private val index = """[{"app_id":"org.skepsun.kototoro.debug","app_version":999,"transport_generation":3,"semantic_schema_version":4,"created_at":12}]"""
    private val categories = """[{"category_id":1,"created_at":10,"sort_key":4,"title":"传入分类","track":false}]"""

    @Test
    fun `large remote category ids are mapped locally so later favourites and another backup remain valid`() = runBlocking<Unit> {
        val remote = zip("index" to index,
            "categories" to categories.replace("\"category_id\":1", "\"category_id\":2147483647"),
            "favourites" to favourite(7).replace("\"category_id\":1", "\"category_id\":2147483647"))
        val file = directory.resolve("second-export.zip")
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(remote).use { backups.restore(it) }
            assertEquals(1, runtime.database.getFavouriteCategoriesDao().findAll().single().categoryId)
            DesktopLibrary(runtime.database) { source }.addFavourite(content.copy(id = 9, url = "/new",
                publicUrl = "https://fixture.invalid/new"))
            assertEquals(setOf(1, 2), runtime.database.getFavouriteCategoriesDao().findAll().map { it.categoryId }.toSet())
            backups.export(file)
        }
        DesktopRuntime.open(directory.resolve("second")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { backups.restore(it) }
            assertEquals(setOf(7L, 9L), runtime.database.getFavouritesDao().findAllActiveEntries().map { it.mangaId }.toSet())
        }
    }

    @Test
    fun `CRC corruption is rejected before decoding or touching the library`() = runBlocking<Unit> {
        val file = Files.createTempFile(directory, "crc-", ".zip")
        val bytes = index.toByteArray()
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            output.putNextEntry(ZipEntry("index").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(bytes) }.value
            })
            output.write(bytes); output.closeEntry()
        }
        val encoded = Files.readAllBytes(file)
        val header = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val offset = 30 + (header.getShort(26).toInt() and 0xffff) + (header.getShort(28).toInt() and 0xffff)
        encoded[offset] = (encoded[offset].toInt() xor 1).toByte()
        Files.write(file, encoded)
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            assertThrows(java.util.zip.ZipException::class.java) {
                runBlocking { DesktopLibraryBackup(runtime.database).preview(file).use { } }
            }
            assertTrue(runtime.database.getFavouriteCategoriesDao().findAll().isEmpty())
        }
    }

    @Test
    fun `stream export budget refuses excess bytes without writing them`() {
        val bytes = java.io.ByteArrayOutputStream()
        val output = BackupLimitedOutput(bytes, 4)
        output.write(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { output.write(byteArrayOf(4, 5)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
        output.write(4)
        assertThrows(IllegalArgumentException::class.java) { output.write(5) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.toByteArray())
    }

    @Test
    fun `native ZIP uses Android wire fields and library survives restore and reopening without installed source`() = runBlocking<Unit> {
        val file = directory.resolve("中文备份.zip")
        val originalRoot = directory.resolve("original")
        var originalCreated = 0L
        DesktopRuntime.open(originalRoot).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            library.addFavourite(content)
            library.recordPage(content, chapter, 2, 5, scroll = 118f)
            originalCreated = requireNotNull(library.progress(content.id)).createdAt
            runtime.database.getSourcesDao().upsert(SourceBackup(source.name, 7, 100, 2, true).toEntity())
            DesktopLibraryBackup(runtime.database).export(file)
        }
        ZipFile(file.toFile()).use { zip ->
            assertEquals(setOf("index", "categories", "favourites", "history", "sources", "projections", "bookmarks", "statistics"),
                zip.entries().asSequence().map { it.name }.toSet())
            val history = zip.getInputStream(zip.getEntry("history")).bufferedReader().use { it.readText() }
            assertTrue(history.contains("\"manga_id\":9223372036854775807"))
            assertTrue(history.contains("\"chapter_id\":-9223372036854775808"))
            assertTrue(history.contains("\"scroll\":118.0"))
            assertFalse(history.contains("opaque"))
        }
        val restoredRoot = directory.resolve("restored")
        DesktopRuntime.open(restoredRoot).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { preview ->
                assertEquals(1, preview.counts[BackupSection.FAVOURITES])
                assertTrue(preview.otherEntries.isEmpty())
                backups.restore(preview)
            }
        }
        DesktopRuntime.open(restoredRoot).use { runtime ->
            val library = DesktopLibrary(runtime.database) { null }
            val restored = library.favourites().single()
            assertEquals(content.id, restored.id)
            assertEquals(source.name, restored.source.name)
            assertEquals(content.title, restored.title)
            val progress = requireNotNull(library.progress(content.id))
            assertEquals(chapter.id, progress.chapterId)
            assertEquals(2, progress.page)
            assertEquals(118f, progress.scroll)
            assertEquals(originalCreated, progress.createdAt)
            assertTrue(runtime.database.getSourcesDao().find(source.name)?.isPinned == true)
        }
    }

    @Test
    fun `hand authored Android archive restores regardless of entry order and reports other sections`() = runBlocking<Unit> {
        val file = zip("history" to history(7, 200), "settings" to "[{\"theme\":\"dark\"}]",
            "favourites" to favourite(7), "categories" to categories, "index" to index)
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { preview ->
                assertEquals(listOf("settings"), preview.otherEntries)
                assertEquals(999, preview.index.appVersion)
                assertEquals(3, backups.restore(preview))
            }
            assertEquals(200L, runtime.database.getHistoryDao().find(7)?.updatedAt)
            assertEquals(7L, runtime.database.getFavouritesDao().findAllActiveEntries().single().mangaId)
        }
    }

    @Test
    fun `merge remaps colliding categories retains local details and does not resurrect newer history tombstone`() = runBlocking<Unit> {
        val incoming = content.copy(id = 7)
        val file = zip("index" to index, "categories" to categories, "favourites" to favourite(7), "history" to history(7, 20))
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            library.addFavourite(incoming)
            library.recordPage(incoming, chapter, 0, 4)
            val tombstone = requireNotNull(library.progress(7)).copy(updatedAt = 500, deletedAt = 500)
            runtime.database.getHistoryDao().upsertSync(tombstone)
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { backups.restore(it) }
            assertEquals(setOf("收藏", "传入分类"), runtime.database.getFavouriteCategoriesDao().findAll().map { it.title }.toSet())
            assertEquals(2, runtime.database.getFavouritesDao().findAllActiveEntries().size)
            assertEquals(tombstone, runtime.database.getHistoryDao().findIncludingDeleted(7))
            assertEquals("opaque", runtime.database.getMangaDao().find(7)?.manga?.sourceData)
            assertEquals(listOf(chapter), library.find(7)?.chapters)
        }
    }

    @Test
    fun `late identity collision rolls back preceding categories and contents`() = runBlocking<Unit> {
        val file = zip("index" to index, "categories" to categories,
            "projections" to "[${manga(99)},${manga(7, "OTHER_SOURCE")}]")
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            library.addFavourite(content.copy(id = 7))
            val before = library.favourites()
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { preview ->
                assertThrows(IllegalArgumentException::class.java) { runBlocking { backups.restore(preview) } }
            }
            assertNull(runtime.database.getMangaDao().find(99))
            assertEquals(listOf("收藏"), runtime.database.getFavouriteCategoriesDao().findAll().map { it.title })
            assertEquals(before, library.favourites())
        }
    }

    @Test
    fun `invalid history embedded ids and duplicate tag ids roll back the entire merge`() = runBlocking<Unit> {
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            for (bad in listOf(history(7, 200).replace("\"page\":2", "\"page\":-1"),
                history(7, 200).replace("\"manga_id\":7", "\"manga_id\":8"),
                history(7, 200).replace(manga(7), manga(7).dropLast(1) +
                    """, "tags":[{"id":1,"title":"A","key":"a","source":"s"},{"id":1,"title":"B","key":"b","source":"s"}]}"""))) {
                val file = zip("index" to index, "categories" to categories, "favourites" to favourite(7), "history" to bad)
                backups.preview(file).use { preview ->
                    assertThrows(IllegalArgumentException::class.java) { runBlocking { backups.restore(preview) } }
                }
                assertTrue(runtime.database.getFavouriteCategoriesDao().findAll().isEmpty())
                assertTrue(runtime.database.getFavouritesDao().findAllActiveEntries().isEmpty())
                assertNull(runtime.database.getMangaDao().find(7))
            }
        }
    }

    @Test
    fun `preview rejects duplicates malformed payload missing index and future schema before any writes`() = runBlocking<Unit> {
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            val invalid = listOf(
                zip("index" to index, "INDEX" to index, "categories" to categories),
                zip("index" to index, "history" to "[{"),
                zip("categories" to categories),
                zip("index" to index.replace("\"semantic_schema_version\":4", "\"semantic_schema_version\":5"), "categories" to categories),
                zip("index" to index, "../categories" to categories),
            )
            for (file in invalid) assertThrows(Exception::class.java) { runBlocking { backups.preview(file).use { } } }
            assertTrue(runtime.database.getFavouriteCategoriesDao().findAll().isEmpty())
        }
    }

    @Test
    fun `confirmed preview uses private bytes even after original changes and a closed preview cannot restore`() = runBlocking<Unit> {
        val file = zip("index" to index, "categories" to categories, "favourites" to favourite(7))
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            val preview = backups.preview(file)
            Files.writeString(file, "changed after preview")
            preview.use { backups.restore(it) }
            assertEquals(7L, runtime.database.getFavouritesDao().findAllActiveEntries().single().mangaId)
            assertThrows(IllegalStateException::class.java) { runBlocking { backups.restore(preview) } }
            val existing = Files.writeString(directory.resolve("keep.zip"), "original backup")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { backups.export(existing) } }
            assertEquals("original backup", Files.readString(existing))
            assertEquals(0, Files.list(directory).use { paths -> paths.filter { it.fileName.toString().startsWith(".kototoro-backup-") }.count().toInt() })
        }
    }

    @Test
    fun `legacy work rows restore zero and negative anchors without rebuilding an entity graph`() = runBlocking<Unit> {
        val file = zip("index" to index, "categories" to categories, "projections" to "[${manga(0)},${manga(-9)}]",
            "entity_graph_entities" to "[{\"entity_id\":999}]",
            "work_favourites" to """[{"entity_id":999,"category_id":1,"anchor_manga_id":0,"created_at":20}]""",
            "work_history" to """[{"entity_id":999,"anchor_manga_id":-9,"created_at":20,"updated_at":40,"chapter_id":-22,"page":3,"scroll":9,"percent":0.7,"chapters":10}]""")
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { preview ->
                assertEquals(listOf("entity_graph_entities"), preview.otherEntries)
                backups.restore(preview)
            }
            assertEquals(0L, runtime.database.getFavouritesDao().findAllActiveEntries().single().mangaId)
            assertEquals(-22L, runtime.database.getHistoryDao().find(-9)?.chapterId)
            assertEquals(3, runtime.database.getHistoryDao().find(-9)?.page)
        }
    }

    private fun manga(id: Long, name: String = source.name) =
        """{"id":$id,"title":"Android 内容","url":"/work","public_url":"https://fixture.invalid/work","cover_url":"","source":"$name","future_field":"ignored"}"""

    private fun favourite(id: Long) =
        """[{"manga_id":$id,"category_id":1,"created_at":10,"manga":${manga(id)}}]"""

    private fun history(id: Long, updated: Long) =
        """[{"manga_id":$id,"created_at":10,"updated_at":$updated,"chapter_id":-22,"page":2,"scroll":118,"percent":0.6,"chapters":10,"manga":${manga(id)}}]"""

    private fun zip(vararg entries: Pair<String, String>): Path {
        val file = Files.createTempFile(directory, "fixture-", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            for ((name, text) in entries) {
                output.putNextEntry(ZipEntry(name)); output.write(text.toByteArray()); output.closeEntry()
            }
        }
        return file
    }
}
