@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)
package org.skepsun.kototoro.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.*
import platform.Foundation.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class IosBackupTest {
    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val chapter = SourceChapter(Long.MAX_VALUE, "第一话", 1f, 1, "/chapter/1", null, 1, "中文", source)
    private val content = SourceContent(Long.MIN_VALUE, "漫画", emptySet(), "/manga", "https://fixture.invalid/manga",
        0f, null, null, emptySet(), null, emptySet(), source, chapters = listOf(chapter))
    private val page = SourcePage(9007199254740993, "mihon://image", null, source)

    @Test
    fun exportedLibraryRestoresIntoAnotherDatabase() = runBlocking {
        withDirectory { dir ->
            val item = IosSourceContent(content)
            val first = IosLibrary("$dir/a.db")
            try {
                first.addCategory("待读")
                first.setFavourite(item, true)
                first.setCategories(item, listOf(first.categories().single { it.title == "待读" }.id))
                first.recordPosition(item, IosSourceChapter(chapter), 2, 4, 12f)
                first.toggleBookmark(item, IosSourceChapter(chapter), IosSourcePage(page), 2, 4, 12f)
                first.exportBackup("$dir/backup.zip")
                assertFailsWith<IllegalArgumentException> { first.exportBackup("$dir/backup.zip") }
            } finally { first.close() }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$dir/backup.zip.partial"))
            val second = IosLibrary("$dir/b.db")
            try {
                second.addCategory("待读")
                val preview = second.previewBackup("$dir/backup.zip")
                val counts = preview.counts.associate { it.section to it.count }
                assertEquals(1, counts["favourites"]); assertEquals(1, counts["history"]); assertEquals(1, counts["bookmarks"])
                assertTrue(preview.skippedSections.isEmpty())
                assertTrue(second.restoreBackup(preview) > 0)
                second.updateSources(listOf(IosSource(source.name, "漫画源", source.locale, source.contentType, "extension")))
                // Same-title categories merge instead of duplicating.
                assertEquals(listOf("待读", "收藏"), second.categories().map { it.title })
                val saved = second.favourites().single()
                assertEquals(content.id.toString(), saved.content.id)
                assertEquals(listOf(second.categories().first().id), saved.categoryIds)
                assertEquals(IosReadingPosition(chapter.id.toString(), 2, 12f, 0.75f),
                    second.position(IosSourceContent(content)))
                assertEquals(2, second.bookmarks(saved.content).single().index)
                // A second merge is idempotent.
                second.restoreBackup(second.previewBackup("$dir/backup.zip"))
                assertEquals(1, second.favourites().size)
                assertEquals(2, second.categories().size)
            } finally { second.close() }
        }
    }

    private class Reports : IosProgressListener {
        val all = mutableListOf<Triple<String, Long, Long>>()
        override fun onProgress(stage: String, completed: Long, total: Long) { all += Triple(stage, completed, total) }
    }

    @Test
    fun exportAndRestoreReportTheirProgress() = runBlocking {
        withDirectory { dir ->
            val item = IosSourceContent(content)
            val first = IosLibrary("$dir/a.db")
            val exported = Reports()
            try {
                first.setFavourite(item, true)
                first.recordPosition(item, IosSourceChapter(chapter), 2, 4, 12f)
                first.exportBackup("$dir/backup.zip", exported)
            } finally { first.close() }
            // One report per section as it starts, counted from the first; the export does not know the total.
            assertEquals("index", exported.all.first().first)
            assertEquals(exported.all.indices.map { it.toLong() }, exported.all.map { it.second })
            assertTrue(exported.all.all { it.third == 0L })
            assertTrue("favourites" in exported.all.map { it.first })

            val second = IosLibrary("$dir/b.db")
            val restored = Reports()
            try {
                val preview = second.previewBackup("$dir/backup.zip")
                second.restoreBackup(preview, restored)
                val total = preview.counts.sumOf { it.count.toLong() }
                assertTrue(restored.all.isNotEmpty())
                assertEquals(restored.all.sortedBy { it.second }, restored.all)
                assertEquals(total, restored.all.last().second)
                assertEquals(total, restored.all.last().third)
                // A later restore without a listener reports nothing and starts its count again.
                second.restoreBackup(preview)
                val again = Reports()
                second.restoreBackup(preview, again)
                assertEquals(total, again.all.last().second)
            } finally { second.close() }
        }
    }

    @Test
    fun androidStreamedArchiveRestoresAndReportsSkippedSections() = runBlocking {
        withDirectory { dir ->
            val path = "$dir/android.zip"
            write(path, Base64.decode(ANDROID_FIXTURE))
            val library = IosLibrary("$dir/a.db")
            try {
                val preview = library.previewBackup(path)
                assertEquals("org.skepsun.kototoro", preview.appId)
                assertEquals(listOf("settings"), preview.skippedSections)
                library.restoreBackup(preview)
                assertEquals(listOf("安卓分类"), library.categories().map { it.title })
                val entry = library.history().single()
                assertEquals("4242", entry.content.id)
                assertEquals(0.5f, entry.progress)
            } finally { library.close() }
        }
    }

    @Test
    fun corruptedOrForeignFilesAreRejectedBeforeWriting() = runBlocking {
        withDirectory { dir ->
            val bytes = Base64.decode(ANDROID_FIXTURE)
            // Flip one compressed byte inside the first entry payload: CRC or inflate must fail.
            write("$dir/bad.zip", bytes.copyOf().also { it[40] = (it[40].toInt() xor 0x55).toByte() })
            write("$dir/plain.txt", "not a zip at all, but long enough".encodeToByteArray())
            val library = IosLibrary("$dir/a.db")
            try {
                assertFails { library.previewBackup("$dir/bad.zip") }
                assertFailsWith<IllegalArgumentException> { library.previewBackup("$dir/plain.txt") }
                assertFailsWith<IllegalArgumentException> { library.previewBackup("$dir/missing.zip") }
                assertTrue(library.categories().isEmpty())
            } finally { library.close() }
        }
    }

    @Test
    fun categoriesRenameAndRemovalKeepOtherMemberships() = runBlocking {
        withDirectory { dir ->
            val library = IosLibrary("$dir/c.db")
            try {
                val item = IosSourceContent(content)
                library.addCategory("待读")
                library.addCategory("在读")
                val (later, reading) = library.categories().let { all -> all.first { it.title == "待读" } to all.first { it.title == "在读" } }
                library.setFavourite(item, true)
                library.setCategories(item, listOf(later.id, reading.id))
                library.renameCategory(later.id, "  稍后再读 ")
                assertEquals("稍后再读", library.categories().first { it.id == later.id }.title)
                assertFailsWith<IllegalArgumentException> { library.renameCategory(later.id, " ") }
                library.removeCategory(reading.id)
                assertTrue(library.categories().none { it.id == reading.id })
                // The work stays favourited through its remaining category.
                assertTrue(library.isFavourite(item))
                assertEquals(listOf(later.id), library.favourites().single().categoryIds)
            } finally {
                library.close()
            }
        }
    }

    @Test
    fun categoryLayoutPersistsAndRejectsStaleOrInvalidSnapshots() = runBlocking {
        withDirectory { dir ->
            val path = "$dir/layout.db"
            val library = IosLibrary(path)
            val ids = try {
                library.addCategory("待读")
                library.addCategory("在读")
                val ids = library.categories().map { it.id }
                val item = IosSourceContent(content)
                library.setCategories(item, ids)
                library.saveCategoryLayout(ids.reversed(), listOf(ids.last()))
                val saved = library.categories()
                assertEquals(ids.reversed(), saved.map { it.id })
                assertEquals(listOf(true, false), saved.map { it.isVisibleInLibrary })
                assertFailsWith<IllegalArgumentException> { library.saveCategoryLayout(listOf(ids.first()), ids) }
                assertFailsWith<IllegalArgumentException> {
                    library.saveCategoryLayout(listOf(ids.first(), ids.first()), ids)
                }
                assertFailsWith<IllegalArgumentException> { library.saveCategoryLayout(ids, listOf(Int.MIN_VALUE)) }
                assertEquals(saved, library.categories())
                // Renaming must keep the hidden flag, and hidden categories retain their memberships.
                library.renameCategory(ids.first(), "稍后阅读")
                assertEquals(false, library.categories().last().isVisibleInLibrary)
                assertEquals(ids.toSet(), library.favourites().single().categoryIds.toSet())
                library.exportBackup("$dir/layout.zip")
                ids
            } finally { library.close() }
            val reopened = IosLibrary(path)
            val restored = IosLibrary("$dir/restored.db")
            try {
                assertEquals(ids.reversed(), reopened.categories().map { it.id })
                assertEquals(listOf(true, false), reopened.categories().map { it.isVisibleInLibrary })
                restored.restoreBackup(restored.previewBackup("$dir/layout.zip"))
                assertEquals(listOf("在读", "稍后阅读"), restored.categories().map { it.title })
                assertEquals(listOf(true, false), restored.categories().map { it.isVisibleInLibrary })
                assertTrue(restored.isFavourite(IosSourceContent(content)))
                reopened.saveCategoryLayout(ids, ids)
                assertEquals(ids, reopened.categories().map { it.id })
                assertTrue(reopened.categories().all { it.isVisibleInLibrary })
            } finally { reopened.close(); restored.close() }
        }
    }

    @Test
    fun sourceUsageKeepsSwitchesOrderAndPinsAndSurvivesBackup() = runBlocking {
        withDirectory { dir ->
            val path = "$dir/usage.db"
            val otherSource = SourceRef("TSUNDOKU_2", "zh", "NOVEL")
            val other = IosSourceContent(content.copy(id = content.id + 1, source = otherSource, chapters = null))
            val second = IosSourceContent(content.copy(id = content.id + 2, url = "/second", chapters = null))
            val library = IosLibrary(path)
            val usedAt = try {
                library.setSourceOrder(listOf(source.name, otherSource.name), listOf(false, true))
                library.setSourcePinned(source.name, true)
                val before = library.sourceSettings().first { it.name == source.name }
                library.recordSourceUsage(source.name, true)
                val recorded = library.sourceSettings().first { it.name == source.name }
                assertEquals(false, recorded.enabled)
                assertEquals(before.sortKey, recorded.sortKey)
                assertTrue(recorded.pinned)
                assertTrue(recorded.lastUsedAt > 0)
                assertFailsWith<IllegalArgumentException> { library.recordSourceUsage(" ", true) }
                library.recordSourceUsage("UNARRANGED", false)
                val new = library.sourceSettings().first { it.name == "UNARRANGED" }
                assertEquals(false, new.enabled)
                assertEquals(false, new.pinned)
                assertTrue(new.sortKey > before.sortKey)
                library.setFavourite(IosSourceContent(content), true)
                library.setFavourite(second, true)
                library.setFavourite(other, true)
                // Reopening the same work does not inflate Android's distinct stored-work metric.
                library.saveDetails(IosSourceContent(content))
                val counts = library.sourceSettings().associate { it.name to it.storedContentCount }
                assertEquals(2, counts[source.name])
                assertEquals(1, counts[otherSource.name])
                assertEquals(0, counts["UNARRANGED"])
                library.setSourcesEnabled(listOf(source.name), true)
                library.exportBackup("$dir/usage.zip")
                recorded.lastUsedAt
            } finally { library.close() }
            val reopened = IosLibrary(path)
            val restored = IosLibrary("$dir/restored-usage.db")
            try {
                assertEquals(usedAt, reopened.sourceSettings().first { it.name == source.name }.lastUsedAt)
                restored.restoreBackup(restored.previewBackup("$dir/usage.zip"))
                val saved = restored.sourceSettings().first { it.name == source.name }
                assertEquals(usedAt, saved.lastUsedAt)
                assertEquals(2, saved.storedContentCount)
                assertEquals(true, saved.pinned)
                assertEquals(0, saved.sortKey)
                assertEquals(1, restored.sourceSettings().first { it.name == otherSource.name }.storedContentCount)
            } finally { reopened.close(); restored.close() }
        }
    }

    @Test
    fun sourceCatalogBaselineAndVersionsPreserveSettingsAndBackup() = runBlocking {
        withDirectory { dir ->
            val path = "$dir/catalog.db"
            val library = IosLibrary(path)
            try {
                library.setSourceOrder(listOf("OLD"), listOf(false))
                library.setSourcePinned("OLD", true)
                library.recordSourceUsage("OLD", true)
                val old = library.sourceSettings().single()
                library.registerSourceCatalog(listOf("BASELINE", "OLD"), listOf(true, true), 10, true)
                assertEquals(old, library.sourceSettings().first { it.name == "OLD" })
                assertEquals(0, library.sourceSettings().first { it.name == "BASELINE" }.addedIn)
                library.registerSourceCatalog(listOf("OLD", "NEW", "DISABLED"), listOf(true, true, false), 10, false)
                val rows = library.sourceSettings().associateBy { it.name }
                assertEquals(old, rows.getValue("OLD"))
                assertEquals(10, rows.getValue("NEW").addedIn)
                assertEquals(10, rows.getValue("DISABLED").addedIn)
                assertFalse(rows.getValue("DISABLED").enabled)
                assertTrue(rows.getValue("NEW").sortKey > rows.getValue("BASELINE").sortKey)
                assertTrue(rows.getValue("DISABLED").sortKey > rows.getValue("NEW").sortKey)
                // Refreshes and version changes do not overwrite an existing source's original version or switches.
                library.registerSourceCatalog(listOf("NEW", "DISABLED", "NEXT"), listOf(false, true, true), 20, false)
                assertEquals(rows.getValue("NEW"), library.sourceSettings().first { it.name == "NEW" })
                assertEquals(rows.getValue("DISABLED"), library.sourceSettings().first { it.name == "DISABLED" })
                assertEquals(20, library.sourceSettings().first { it.name == "NEXT" }.addedIn)
                val beforeInvalid = library.sourceSettings()
                assertFailsWith<IllegalArgumentException> {
                    library.registerSourceCatalog(listOf("INVALID", " "), listOf(true, true), 20, false)
                }
                assertFailsWith<IllegalArgumentException> {
                    library.registerSourceCatalog(listOf("INVALID", "INVALID"), listOf(true, true), 20, false)
                }
                assertFailsWith<IllegalArgumentException> {
                    library.registerSourceCatalog(listOf("INVALID"), emptyList(), 20, false)
                }
                assertFailsWith<IllegalArgumentException> {
                    library.registerSourceCatalog(listOf("INVALID"), listOf(true), 0, false)
                }
                assertEquals(beforeInvalid, library.sourceSettings())
                library.exportBackup("$dir/catalog.zip")
            } finally { library.close() }
            val reopened = IosLibrary(path)
            val restored = IosLibrary("$dir/restored-catalog.db")
            try {
                val saved = reopened.sourceSettings().associateBy { it.name }
                assertEquals(0, saved.getValue("BASELINE").addedIn)
                assertEquals(10, saved.getValue("NEW").addedIn)
                assertEquals(20, saved.getValue("NEXT").addedIn)
                restored.restoreBackup(restored.previewBackup("$dir/catalog.zip"))
                for (row in restored.sourceSettings()) assertEquals(saved.getValue(row.name).addedIn, row.addedIn)
                assertEquals(setOf("BASELINE", "NEW", "NEXT"), restored.sourceStates().keys)
            } finally { reopened.close(); restored.close() }
        }
    }

    @Test
    fun sourceSwitchesPersistInAndroidSourcesTable() = runBlocking {
        withDirectory { dir ->
            val library = IosLibrary("$dir/s.db")
            try {
                assertEquals(emptyMap(), library.sourceStates())
                library.setSourcesEnabled(listOf("MIHON_1", "MANGADEX", "MIHON_1"), true)
                library.setSourcesEnabled(listOf("MANGADEX"), false)
                assertEquals(mapOf("MIHON_1" to true, "MANGADEX" to false), library.sourceStates())
            } finally {
                library.close()
            }
            val reopened = IosLibrary("$dir/s.db")
            try {
                assertEquals(false, reopened.sourceStates()["MANGADEX"])
                // A manual order keeps each source's switch and adds rows for sources never touched before.
                reopened.setSourceOrder(listOf("NEW_ONE", "MANGADEX", "MIHON_1"), listOf(false, false, true))
                reopened.setSourcePinned("MIHON_1", true)
                val settings = reopened.sourceSettings().associateBy { it.name }
                assertEquals(listOf("NEW_ONE", "MANGADEX", "MIHON_1"), reopened.sourceSettings().sortedBy { it.sortKey }.map { it.name })
                assertEquals(false, settings.getValue("NEW_ONE").enabled)
                assertEquals(false, settings.getValue("MANGADEX").enabled)
                assertEquals(true, settings.getValue("MIHON_1").enabled)
                assertTrue(settings.getValue("MIHON_1").pinned)
            } finally { reopened.close() }
        }
    }

    /** Repositories, source labels and update checks travel with the library, as in Android backups. */
    @Test
    fun repositoriesOriginsAndUpdateChecksRoundTrip() = runBlocking {
        withDirectory { dir ->
            val item = IosSourceContent(content)
            val first = IosLibrary("$dir/a.db")
            try {
                first.setFavourite(item, true)
                val db = first.database
                db.getExternalExtensionRepoDao().upsert(org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity(
                    org.skepsun.kototoro.extensions.repo.ExternalExtensionType.MIHON, "https://repo.invalid/index.min.json",
                    "测试仓库", null, "https://repo.invalid", "ab", 1, 2, 3, null, "1"))
                db.getSourceOriginsDao().upsert(org.skepsun.kototoro.core.db.entity.SourceOriginEntity(
                    sourceKey = source.name, kind = "MIHON", displayName = "漫画源", updatedAt = 5))
                db.getTracksDao().upsert(org.skepsun.kototoro.tracker.data.TrackEntity(content.id, chapter.id, 3, 10, 20,
                    org.skepsun.kototoro.tracker.data.TrackEntity.RESULT_HAS_UPDATE, null))
                db.getTrackLogsDao().insert(org.skepsun.kototoro.tracker.data.TrackLogEntity(
                    mangaId = content.id, chapters = "第二话", createdAt = 30, isUnread = true))
                first.exportBackup("$dir/backup.zip")
            } finally { first.close() }
            val second = IosLibrary("$dir/b.db")
            try {
                val preview = second.previewBackup("$dir/backup.zip")
                val counts = preview.counts.associate { it.section to it.count }
                assertEquals(1, counts["extension_repos"]); assertEquals(1, counts["source_origins"])
                assertEquals(1, counts["tracks"]); assertEquals(1, counts["track_logs"])
                assertTrue(preview.skippedSections.isEmpty())
                second.restoreBackup(preview)
                val db = second.database
                assertEquals(listOf("测试仓库"), db.getExternalExtensionRepoDao().getAll().map { it.name })
                assertEquals("漫画源", db.getSourceOriginsDao().getByKey(source.name)?.displayName)
                assertEquals(3, db.getTracksDao().find(content.id)?.newChapters)
                // Merging the same archive again keeps one feed entry.
                second.restoreBackup(second.previewBackup("$dir/backup.zip"))
                assertEquals(1, db.getTrackLogsDao().dump().size)
            } finally { second.close() }
        }
    }

    private fun write(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(path, true))
    }

    private suspend fun withDirectory(block: suspend (String) -> Unit) {
        val dir = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(dir, true, null, null))
        try { block(dir) } finally { files.removeItemAtPath(dir, null) }
    }

    private companion object {
        /** Python `zipfile` on a non-seekable stream: DEFLATE with data descriptors, as Android's ZipOutputStream. */
        const val ANDROID_FIXTURE =
        "UEsDBBQACAAIANQAS10AAAAAAAAAAAAAAAAFAAAAaW5kZXhNjEEKg0AMRa8isy5SrSD0KqUMYQw6SJMhiW7EuzfVhc3fhP9e8toC" +
        "lBLzEJ5VYBlrnbHoQvXM5hEOt+owVhTNTK41bdN6aQKkhcXiiIQCdtKHI8UPkOUUNU2+/t12TpMgGA4R7Pesv1+zv79QSwcIah/Z" +
        "lmwAAACPAAAAUEsDBBQACAAIANQAS10AAAAAAAAAAAAAAAAKAAAAY2F0ZWdvcmllcxWMSwqAMBBDryKzduEHrXoVlaJ1lKK0UKeI" +
        "iHc3bkLeI6R/yEzCmw+3tgt1iUoTMoHhFj0JRA5x+iB65xuYAcXKweg0xGpuWmRZlcgiq4eojJoJo+h25y+nV8vHf5y/4wdQSwcI" +
        "YLrvWmEAAABtAAAAUEsDBBQACAAIANQAS10AAAAAAAAAAAAAAAAKAAAAZmF2b3VyaXRlc03N3QqCMBTA8VeRXUcjdVk+QV1UD6Ay" +
        "zuaywfxgbkKI796ZJXQzzvnxP6yYSQtdA1zXJI/SOI13EZHgVNPb9xezIFah1RwcAkNYj3Ceyf+h084oXEnpmTid8U1YUvrjMxal" +
        "z1giCEbempDQlh7COnhhtOQ/fTk3jDmlsNfdBEbXWyb7SdmtCjD23sr1s9v18rjztXLQjEhFtSzVB1BLBwi84aUynAAAANsAAABQ" +
        "SwMEFAAIAAgA1ABLXQAAAAAAAAAAAAAAAAcAAABoaXN0b3J5TY5NDoIwEIWvYro2/IM/J9CFegAgZCgVmxRoSsuGcHdnEKObybwv" +
        "7828fGYd9C1UsmHnXRIl0X7HuBFgRVOBRZYicLr5gYwcL9BWmE8qDJFoaAXuMa4jN4NSKALiwnDRUy7w6NT6DdXM/j9aaRXFWeHS" +
        "+njCGadx4bJnVBfukMY1oxaGjjK/80OS2tVK8mqjL2v1ePZ98GQ/gZLN18aHCYtuLgLj4LASqdv18rhXq8tCOyLKy2Up31BLBwiC" +
        "j5zHuAAAABQBAABQSwMEFAAIAAgA1ABLXQAAAAAAAAAAAAAAAAgAAABzZXR0aW5nc4uuVirJSM1NVbJSUEpJLMpWqo0FAFBLBwhL" +
        "+kBoFQAAABMAAABQSwECFAMUAAgACADUAEtdah/ZlmwAAACPAAAABQAAAAAAAAAAAAAAgAEAAAAAaW5kZXhQSwECFAMUAAgACADU" +
        "AEtdYLrvWmEAAABtAAAACgAAAAAAAAAAAAAAgAGfAAAAY2F0ZWdvcmllc1BLAQIUAxQACAAIANQAS1284aUynAAAANsAAAAKAAAA" +
        "AAAAAAAAAACAATgBAABmYXZvdXJpdGVzUEsBAhQDFAAIAAgA1ABLXYKPnMe4AAAAFAEAAAcAAAAAAAAAAAAAAIABDAIAAGhpc3Rv" +
        "cnlQSwECFAMUAAgACADUAEtdS/pAaBUAAAATAAAACAAAAAAAAAAAAAAAgAH5AgAAc2V0dGluZ3NQSwUGAAAAAAUABQAOAQAARAMA" +
        "AAAA"
    }
}
