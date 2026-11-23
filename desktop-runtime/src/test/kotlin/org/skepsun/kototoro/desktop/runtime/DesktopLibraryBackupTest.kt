package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            // The shared LibraryBackup writer also exports source origins, extension repositories and tracking.
            assertEquals(setOf("index", "categories", "favourites", "history", "sources", "projections", "bookmarks", "statistics",
                "source_origins", "extension_repos", "tracks", "track_logs"),
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

    @Test
    fun `Android history with out-of-range percent restores with normalized progress`() = runBlocking<Unit> {
        // Real Android backups carry float overshoot and novel offsets in percent; page/scroll stay as written.
        val rows = listOf(-9L to "1.0000001", -10L to "18844.0").joinToString(",") { (id, percent) ->
            """{"entity_id":${-id},"anchor_manga_id":$id,"created_at":20,"updated_at":40,"chapter_id":-22,"page":94219,"scroll":10000.0,"percent":$percent,"chapters":1}"""
        }
        val file = zip("index" to index, "categories" to categories, "projections" to "[${manga(-9)},${manga(-10).replace("/work", "/novel")}]",
            "work_history" to "[$rows]")
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { backups.restore(it) }
            val history = runtime.database.getHistoryDao()
            assertEquals(1f, history.find(-9)?.percent)
            assertEquals(-1f, history.find(-10)?.percent)
            assertEquals(94219, history.find(-10)?.page)
        }
    }

    @Test
    fun `an Android export with non-finite progress restores instead of failing the whole preview`() = runBlocking<Unit> {
        // Android writes its backup with allowSpecialFloatingPointValues, so a history row whose progress was never
        // computed reaches the file as NaN or Infinity. The reader has to take that the way the writer meant it: the
        // progress is unknown, the row is not.
        val dirty = history(7, 200)
            .replace("\"scroll\":118", "\"scroll\":Infinity")
            .replace("\"percent\":0.6", "\"percent\":NaN")
        val bookmarks = """[{"manga":${manga(7)},"bookmarks":[{"manga_id":7,"chapter_id":-22,"page_id":1,"page":2,
            |"scroll":3,"percent":NaN,"created_at":30}]}]""".trimMargin()
        val statistics = """[{"manga_id":7,"started_at":100,"duration":60,"pages":2,"session":{"end_at":160,
            |"start_chapter_id":-22,"start_page":0,"start_scroll":0,"end_chapter_id":-22,"end_page":2,"end_scroll":0,
            |"start_percent":NaN,"end_percent":Infinity}}]""".trimMargin()
        val file = zip("index" to index, "categories" to categories, "favourites" to favourite(7),
            "history" to dirty, "bookmarks" to bookmarks, "statistics" to statistics)
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(file).use { backups.restore(it) }
            val progress = runtime.database.getHistoryDao().find(7)
            assertEquals(0f, progress?.scroll)
            assertEquals(-1f, progress?.percent)
            val bookmark = runtime.database.getBookmarksDao().find(7, 1)
            assertEquals(-1f, bookmark?.percent)
            // The row also omits image_url the way a pre-snapshot writer leaves it out.
            assertEquals("", bookmark?.imageUrl)
            val session = runtime.database.getReadingRecordDao().findAtStart(7, 100)
            assertEquals(0f, session?.startPercent)
            assertEquals(0f, session?.endPercent)
        }
    }

    @Test
    fun `extension group round trip installs packages and restores enabled order and switched off sources`() = runBlocking<Unit> {
        val file = directory.resolve("extensions.zip")
        val original = ExtensionHost(
            packages = listOf(DesktopExtensionPackage("pkg.a", "https://fixture.invalid/index.json", "mihon", "1.2", 12)),
            repos = listOf(DesktopExtensionRepo("MIHON", "https://fixture.invalid/index.json", "Fixture repo")),
            order = listOf("SOURCE_B", "SOURCE_A", "SOURCE_C"),
            disabled = setOf("SOURCE_D"),
            priority = listOf("plugin.b", "plugin.a"),
        )
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            DesktopLibraryBackup(runtime.database, original).export(file)
        }
        // The section keeps Android's field names and the host's real organization, not placeholder empty arrays.
        val group = ZipFile(file.toFile()).use { zip ->
            val text = zip.getInputStream(zip.getEntry(BackupSection.EXTENSION_GROUPS.entryName))
                .bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonArray.single().jsonObject
        }
        assertEquals(2, group.getValue("schema").jsonPrimitive.int)
        assertEquals(listOf("SOURCE_B", "SOURCE_A", "SOURCE_C"),
            group.getValue("enabledSources").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, group.getValue("enabledSortStart").jsonPrimitive.int)
        assertTrue(group.getValue("enabledSortGaps").jsonArray.isEmpty())
        assertTrue(group.getValue("enabledUnsorted").jsonArray.isEmpty())
        val states = group.getValue("sourceStates").jsonArray.map { it.jsonObject }
        assertEquals(listOf("SOURCE_D"), states.map { it.getValue("source").jsonPrimitive.content })
        assertFalse(states.single().getValue("enabled").jsonPrimitive.boolean)
        assertEquals(listOf("plugin.b", "plugin.a"),
            group.getValue("jarPriority").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("pkg.a", group.getValue("packages").jsonArray.single().jsonObject.getValue("pkg").jsonPrimitive.content)

        val restored = ExtensionHost()
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database, restored)
            backups.preview(file).use { backups.restore(it) }
        }
        assertTrue(restored.restored)
        assertEquals(setOf("pkg.a"), restored.packages.keys)
        assertEquals(listOf("SOURCE_B", "SOURCE_A", "SOURCE_C"), restored.order)
        assertEquals(setOf("SOURCE_D"), restored.disabled)
        assertEquals(listOf("plugin.b", "plugin.a"), restored.priority)
    }

    @Test
    fun `legacy archives without extension groups or ordering fields restore without failing`() = runBlocking<Unit> {
        // An archive from before extension groups existed: no section at all, the extension host is never asked.
        val plain = zip("index" to index, "categories" to categories)
        val untouched = ExtensionHost()
        DesktopRuntime.open(directory.resolve("plain")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database, untouched)
            backups.preview(plain).use { preview ->
                assertEquals(1, backups.restore(preview))
                assertTrue(preview.otherEntries.isEmpty())
            }
            assertEquals(listOf("传入分类"), runtime.database.getFavouriteCategoriesDao().findAll().map { it.title })
        }
        assertFalse(untouched.restored)

        // A schema 1 group: packages yes, but sourceStates/sort fields absent. Missing fields mean "nothing to restore".
        val legacyGroup = """[{"schema":1,"id":"old","name":"Old PC","createdAt":5,"repos":[],"packages":[{"pkg":"legacy.pkg","kind":"mihon","versionCode":3}]}]"""
        val legacy = zip("index" to index, "categories" to categories, "extension_groups" to legacyGroup)
        val target = ExtensionHost()
        DesktopRuntime.open(directory.resolve("legacy")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database, target)
            backups.preview(legacy).use { preview -> assertEquals(1, backups.restore(preview)) }
            assertEquals(listOf("传入分类"), runtime.database.getFavouriteCategoriesDao().findAll().map { it.title })
        }
        assertTrue(target.restored)
        assertEquals(setOf("legacy.pkg"), target.packages.keys)
        assertTrue(target.order.isEmpty())
        assertTrue(target.disabled.isEmpty())
        assertTrue(target.priority.isEmpty())
    }

    @Test
    fun `Android schema 2 group decodes sort keys gaps unsorted sources and disabled states`() = runBlocking<Unit> {
        // What Android's SourceGroupSerializer writes: compact plain sources with a non-zero start and a gap, sortKey 0
        // for unsorted ones, full records for the sources it switched off, and the JAR order.
        val androidGroup = """[{"schema":2,"id":"android","name":"Phone","createdAt":9,"repos":[],"packages":[],
            |"sourceStates":[{"source":"OFF_SOURCE","enabled":false,"pinned":false,"sortKey":1}],
            |"enabledSources":["FIRST","SECOND"],"enabledSortStart":2,"enabledSortGaps":[1,3],
            |"enabledUnsorted":["UNSORTED"],"jarPriority":["plugin.z"]}]""".trimMargin()
        val file = zip("index" to index, "categories" to categories, "extension_groups" to androidGroup)
        val target = ExtensionHost()
        DesktopRuntime.open(directory.resolve("android")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database, target)
            backups.preview(file).use { backups.restore(it) }
        }
        assertEquals(listOf("FIRST", "SECOND", "UNSORTED"), target.order)
        assertEquals(setOf("OFF_SOURCE"), target.disabled)
        assertEquals(listOf("plugin.z"), target.priority)
    }

    @Test
    fun `a restored extension group writes the backed-up sequence without changing the chosen sort`() = runBlocking<Unit> {
        // Android's applySourceOrder on a group: sortKey follows the backup, the chosen sort stays the user's own.
        val group = """[{"schema":2,"id":"android","name":"Phone","createdAt":9,"repos":[],"packages":[],
            |"sourceStates":[],"enabledSources":["MIHON_9","MIHON_1"],"enabledSortStart":1,
            |"enabledSortGaps":[],"enabledUnsorted":[],"jarPriority":[]}]""".trimMargin()
        val file = zip("index" to index, "categories" to categories, "extension_groups" to group)
        var settings = DesktopSourceSettings(
            sort = DesktopSourceSort.LANGUAGE,
            order = listOf("MIHON_1", "MIHON_9"),
        )
        val host = object : DesktopExtensionBackup {
            override suspend fun export(): String? = null
            override suspend fun restore(groups: List<JsonObject>) {
                val plan = DesktopExtensionGroups.decode(groups)
                settings = settings.appliedOrder(plan.enabledOrder, setOf("MIHON_1", "MIHON_9", "MIHON_2"))
            }
        }
        DesktopRuntime.open(directory.resolve("order")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database, host)
            backups.preview(file).use { backups.restore(it) }
        }
        assertEquals(listOf("MIHON_9", "MIHON_1"), settings.order)
        assertEquals(DesktopSourceSort.LANGUAGE, settings.sort)
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

    /**
     * An extension host on the shared wire format, standing in for the Windows host's install and order writes: it
     * exports its packages and organization, and a restore installs what is missing, keeps the switches and applies the
     * sequence and JAR priority it read, exactly the behavior the real bridge delegates to [DesktopExtensionGroups].
     */
    private class ExtensionHost(
        packages: List<DesktopExtensionPackage> = emptyList(),
        private val repos: List<DesktopExtensionRepo> = emptyList(),
        order: List<String> = emptyList(),
        disabled: Set<String> = emptySet(),
        priority: List<String> = emptyList(),
    ) : DesktopExtensionBackup {
        val packages = LinkedHashMap<String, DesktopExtensionPackage>().apply { packages.forEach { put(it.pkg, it) } }
        var order: List<String> = order
            private set
        val disabled = LinkedHashSet(disabled)
        var priority: List<String> = priority
            private set
        var restored = false
            private set

        override suspend fun export(): String? = if (packages.isEmpty()) null else DesktopExtensionGroups.encode(
            DesktopExtensionExport(
                id = "desktop", name = "Windows", createdAt = 1, repos = repos,
                packages = packages.values.toList(), enabledSources = order,
                disabledSources = disabled.toList(), jarPriority = priority,
            ),
        )

        override suspend fun restore(groups: List<JsonObject>) {
            val plan = DesktopExtensionGroups.decode(groups)
            for (pkg in plan.packages) packages.putIfAbsent(pkg.pkg, pkg)
            disabled += plan.disabledSources
            if (plan.enabledOrder.isNotEmpty()) order = plan.enabledOrder.filterNot { it in disabled }
            if (plan.jarPriority.isNotEmpty()) priority = plan.jarPriority
            restored = true
        }
    }
}
