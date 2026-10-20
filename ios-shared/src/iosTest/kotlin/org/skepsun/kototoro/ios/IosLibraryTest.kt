@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.db.DATABASE_VERSION
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class IosLibraryTest {
    @Test
    fun recoveryCountsFavouritesAndHistoryAndRechecksInstalledIdentities() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val old = migrationWork(501, "MIHON_9007199254740993", listOf(1f))
        val historyOnly = migrationWork(502, "MIHON_9007199254740993", listOf(1f))
        val other = migrationWork(503, "ANIYOMI_9007199254740993", listOf(1f))
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                library.recovery.remember(listOf(IosSource(old.sourceName, "可读的原来源", "zh", "NOVEL", "extension",
                    group = "fixture.original")))
                library.setFavourite(old, true)
                library.recordPosition(old, old.chapters.single(), 0, 1, 0f)
                library.recordPosition(historyOnly, historyOnly.chapters.single(), 0, 1, 0f)
                library.setFavourite(other, true)
                val missing = library.recovery.missing(listOf(other.sourceName)).single()
                assertEquals(old.sourceName, missing.key)
                assertEquals(2, missing.works)
                assertEquals(1, missing.favourites)
                assertEquals(2, missing.history)
                assertEquals("可读的原来源", missing.title)
                assertEquals("MIHON:fixture.original", missing.packageKey)
                assertEquals(listOf("MIHON:bundle"), library.recovery.recommended(listOf(missing),
                    listOf(IosRecoveryPackage("MIHON:bundle", listOf(old.sourceName)),
                        IosRecoveryPackage("ANIYOMI:wrong", listOf(other.sourceName)))))
                assertTrue(library.recovery.missing(listOf(old.sourceName, other.sourceName)).isEmpty())
            } finally { library.close() }
            val reopened = IosLibrary("$directory/library.db")
            try {
                val missing = reopened.recovery.missing(emptyList())
                assertEquals(2, missing.size)
                assertEquals("可读的原来源", missing.first { it.key == old.sourceName }.title)
            }
            finally { reopened.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun batchFailuresDoNotUndoSuccessfulWorksAndRetryDoesNotRepeatTheirMigration() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val first = migrationWork(601, "OLD", listOf(1f))
        val second = migrationWork(602, "OLD", listOf(1f))
        val target = migrationWork(603, "NEW", listOf(1f))
        val invalid = migrationWork(604, "NEW", emptyList())
        val repaired = migrationWork(604, "NEW", listOf(1f))
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                library.setFavourite(first, true)
                library.setFavourite(second, true)
                library.changeSource(first, target, false)
                assertFailsWith<IllegalArgumentException> { library.changeSource(second, invalid, false) }
                assertEquals(setOf(target.id, second.id), library.favourites().map { it.content.id }.toSet())
                library.changeSource(second, repaired, false)
            } finally { library.close() }
            val reopened = IosLibrary("$directory/library.db")
            try { assertEquals(setOf(target.id, repaired.id), reopened.favourites().map { it.content.id }.toSet()) }
            finally { reopened.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun catalogKeysKeepLongIdsAndEcosystemsAndNeverGuessByDisplayNames() {
        val item = IosExtension(org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex.Extension(sources = listOf(
            org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex.Source(9007199254740993L, "同名源", "zh"),
            org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex.Source(0L, "只有名称", "zh"),
        )))
        assertEquals(listOf("MIHON_9007199254740993"), item.sourceKeys("MIHON"))
        assertEquals(listOf("ANIYOMI_9007199254740993"), item.sourceKeys("ANIYOMI"))
        assertTrue(item.sourceKeys("JAR").isEmpty())
    }

    @Test
    fun pluginProviderAndReadableNameSurviveUninstallationAndDatabaseReopen() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val content = migrationWork(701, "PARSER_FIXTURE", listOf(1f))
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                library.recovery.remember(listOf(IosSource(content.sourceName, "小说插件来源", "zh", "NOVEL",
                    "extension", group = "org.fixture.parsers")))
                library.setFavourite(content, true)
                assertTrue(library.recovery.missing(listOf(content.sourceName)).isEmpty())
            } finally { library.close() }
            val reopened = IosLibrary("$directory/library.db")
            try {
                val missing = reopened.recovery.missing(emptyList()).single()
                assertEquals("小说插件来源", missing.title)
                assertEquals("JAR:org.fixture.parsers", missing.packageKey)
                // Unsupported ecosystem metadata must never be guessed as a parser plugin by its source key.
                val dao = reopened.database.getSourceOriginsDao()
                dao.upsert(requireNotNull(dao.getByKey(content.sourceName)).copy(kind = "IREADER"))
                assertEquals("IREADER:org.fixture.parsers", reopened.recovery.missing(emptyList()).single().packageKey)
            } finally { reopened.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun matchingRequiresOneExactCandidateAndNeverChoosesAnAmbiguousOrWrongKindWork() {
        val original = migrationWork(801, "OLD", listOf(1f))
        val exact = migrationWork(802, "NEW", listOf(1f))
        val another = migrationWork(803, "OTHER", listOf(1f))
        val video = migrationWork(804, "VIDEO", listOf(1f), "VIDEO")
        assertEquals(exact.id, IosMigrationMatching.uniqueExactMatch(original, listOf(exact, exact, video))?.id)
        assertNull(IosMigrationMatching.uniqueExactMatch(original, listOf(exact, another)))
        assertNull(IosMigrationMatching.uniqueExactMatch(original, listOf(video, original)))
    }

    private fun migrationWork(id: Long, name: String, numbers: List<Float>, type: String = "NOVEL",
        title: String = "换源测试"): IosSourceContent {
        val source = SourceRef(name, "zh", type)
        return IosSourceContent(SourceContent(id, title, emptySet(), "/$id", "https://fixture.invalid/$id",
            0f, null, null, emptySet(), null, emptySet(), source, chapters = numbers.mapIndexed { index, number ->
                SourceChapter(id * 10 + index, "第 $number 章", number, 1, "/$id/$index", null, 0, null, source)
            }))
    }

    @Test
    fun duplicatesGroupTraditionalTitlesAndMergeKeepsFurthestProgressAndCategories() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val further = migrationWork(701, "OLD", listOf(1f, 2f, 3f), title = "间谍过家家")
        val kept = migrationWork(702, "NEW", listOf(1f, 2f, 3f, 4f), title = "間諜過家家")
        val video = migrationWork(703, "NEW", listOf(1f), type = "VIDEO", title = "间谍过家家")
        val near = migrationWork(704, "THIRD", listOf(1f), title = "間諜過佳家")
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                library.addCategory("甲"); library.addCategory("乙")
                val ids = library.categories().map { it.id }
                library.setCategories(further, listOf(ids[0])); library.setCategories(kept, listOf(ids[1]))
                library.setFavourite(video, true)
                library.setFavourite(near, true)
                library.recordPosition(further, further.chapters[2], 1, 10, 1000f)
                kotlinx.coroutines.delay(10)
                library.recordPosition(kept, kept.chapters[0], 0, 10, 0f)
                val group = library.duplicates.groups(emptyList(), false, 0.85).single()
                assertEquals(setOf(further.id, kept.id), group.entries.map { it.id }.toSet())
                // Neither source is in the catalog, so the furthest read is recommended.
                assertEquals(further.id, group.recommendedId)
                assertTrue(library.duplicates.groups(listOf(group.key), false, 0.85).isEmpty())
                assertFalse(group.fuzzy)
                // 繁/简 folded first, then 4 of 5 characters agree (0.8).
                val fuzzy = library.duplicates.groups(emptyList(), true, 0.8).single()
                assertEquals(setOf(further.id, kept.id, near.id), fuzzy.entries.map { it.id }.toSet())
                assertTrue(fuzzy.fuzzy)
                assertEquals(2, library.duplicates.groups(emptyList(), true, 0.9).single().entries.size)
                val keep = group.entries.first { it.id == kept.id }
                assertTrue(library.duplicates.needsChapters(group, keep))
                library.duplicates.merge(group, keep, library.storedDetails(kept))
                assertEquals(setOf(kept.id, video.id, near.id), library.favourites().map { it.content.id }.toSet())
                assertEquals(ids.toSet(), library.favouriteCategories(kept).toSet())
                assertEquals(kept.chapters[2].id, library.position(kept)?.chapterId)
                assertNull(library.position(further))
                assertTrue(library.duplicates.groups(emptyList(), false, 0.85).isEmpty())
            } finally { library.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun sourceReplacementMapsProgressAndCategoriesAndPreservesOriginalBookmarksAfterReopening() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val old = migrationWork(201, "OLD", listOf(1f, 2f, 3f))
        val target = migrationWork(202, "NEW", listOf(2f, 3f))
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                library.addCategory("在读")
                library.setCategories(old, listOf(library.categories().single().id))
                library.recordPosition(old, old.chapters[1], 2, 10, 2000f)
                library.toggleNovelBookmark(old, old.chapters[1], 2, 10, "原作品书签")
                library.changeSource(old, target, false)
                assertFalse(library.isFavourite(old))
                assertNull(library.position(old))
                assertEquals(target.chapters[0].id, library.position(target)?.chapterId)
                assertEquals(2000f, library.position(target)?.scroll)
                assertEquals("原作品书签", library.bookmarks(old).single().preview)
            } finally { library.close() }
            val reopened = IosLibrary("$directory/library.db")
            try {
                assertEquals(target.id, reopened.favourites().single().content.id)
                assertEquals(target.id, reopened.history().single().content.id)
                assertEquals(1, reopened.favouriteCategories(target).size)
                assertEquals(1, reopened.bookmarks(old).size)
                assertEquals(3, reopened.storedDetails(old)?.chapterCount)
            } finally { reopened.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun sourceCopyMergesCategoriesAndRetainsTargetsNewerProgress() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                val old = migrationWork(301, "OLD", listOf(1f, 2f, 3f))
                val target = migrationWork(302, "NEW", listOf(1f, 2f))
                library.addCategory("原分类"); library.addCategory("目标分类")
                val ids = library.categories().map { it.id }
                library.setCategories(old, listOf(ids[0])); library.setCategories(target, listOf(ids[1]))
                library.recordPosition(old, old.chapters[2], 1, 10, 1000f)
                // Make the newer target unambiguous even if both writes share a clock tick.
                kotlinx.coroutines.delay(10)
                library.recordPosition(target, target.chapters[0], 5, 10, 5000f)
                library.changeSource(old, target, true)
                assertTrue(library.isFavourite(old))
                assertEquals(old.chapters[2].id, library.position(old)?.chapterId)
                assertEquals(target.chapters[0].id, library.position(target)?.chapterId)
                assertEquals(5000f, library.position(target)?.scroll)
                assertEquals(ids.toSet(), library.favouriteCategories(target).toSet())
                assertEquals(setOf(old.id, target.id), library.history().map { it.content.id }.toSet())
            } finally { library.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun failedSourceChangesLeaveOriginalAndTargetRecordsUntouched() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        try {
            val library = IosLibrary("$directory/library.db")
            try {
                val old = migrationWork(401, "OLD", listOf(1f))
                val target = migrationWork(402, "NEW", listOf(1f))
                library.setFavourite(old, true)
                library.recordPosition(old, old.chapters[0], 1, 10, 1000f)
                val collision = migrationWork(402, "OTHER", listOf(1f))
                library.saveDetails(collision)
                for (invalid in listOf(target, old, migrationWork(403, "NEW", emptyList()),
                        migrationWork(404, "VIDEO", listOf(1f), "VIDEO"))) {
                    assertFailsWith<IllegalArgumentException> { library.changeSource(old, invalid, false) }
                }
                assertEquals(listOf(old.id), library.favourites().map { it.content.id })
                assertEquals(listOf(old.id), library.history().map { it.content.id })
                assertEquals("OTHER", library.storedDetails(collision)?.sourceName)
            } finally { library.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }

    @Test
    fun categoriesSurviveReopeningAndBlankTitlesDoNotWrite() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val path = "$directory/library.db"
        try {
            val first = IosLibrary(path)
            val savedId = try {
                assertEquals(DATABASE_VERSION, first.schemaVersion)
                assertEquals(emptyList(), first.categories())
                first.addCategory("  我的漫画  ")
                assertFailsWith<IllegalArgumentException> { first.addCategory(" \n ") }
                first.categories().single().id
            } finally {
                first.close()
            }
            val reopened = IosLibrary(path)
            try {
                assertEquals(listOf(IosCategory(savedId, "我的漫画")), reopened.categories())
                reopened.addCategory("待读")
                assertEquals(listOf("我的漫画", "待读"), reopened.categories().map { it.title })
            } finally {
                reopened.close()
            }
        } finally {
            files.removeItemAtPath(directory, null)
        }
    }

    @Test
    fun relativePathsAreRejected() {
        assertFailsWith<IllegalArgumentException> { IosLibrary("library.db") }
    }

    @Test
    fun bulkCategoriesPreserveDetailsAndUntouchedMembershipsAndRollBackCollisions() = runBlocking {
        val files = NSFileManager.defaultManager
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        check(files.createDirectoryAtPath(directory, true, null, null))
        val path = "$directory/library.db"
        val source = SourceRef("MIHON_test", "zh", "MANGA")
        val first = SourceContent(101, "第一部", emptySet(), "/first", "https://fixture.invalid/first",
            0f, null, null, emptySet(), null, emptySet(), source, description = "保留完整详情")
        val second = first.copy(id = 102, title = "第二部", url = "/second", publicUrl = "https://fixture.invalid/second")
        try {
            val library = IosLibrary(path)
            try {
                for (title in listOf("待读", "在读", "漫画")) library.addCategory(title)
                val ids = library.categories().map { it.id }
                library.setCategories(IosSourceContent(first), listOf(ids[0]))
                library.setCategories(IosSourceContent(second), listOf(ids[1]))
                val sparse = listOf(first, second).map { IosSourceContent(it.copy(description = null)) }
                library.updateCategories(sparse, listOf(ids[2]), emptyList())
                assertEquals(setOf(ids[0], ids[2]), library.favouriteCategories(sparse[0]).toSet())
                assertEquals(setOf(ids[1], ids[2]), library.favouriteCategories(sparse[1]).toSet())
                assertEquals("保留完整详情", library.favourites().first { it.content.id == "101" }.content.summary)
                assertFailsWith<IllegalArgumentException> {
                    library.updateCategories(sparse, listOf(Int.MAX_VALUE), emptyList())
                }
                val new = first.copy(id = 103, url = "/new", publicUrl = "https://fixture.invalid/new")
                val collision = first.copy(source = SourceRef("MIHON_other", "zh", "MANGA"))
                assertFailsWith<IllegalArgumentException> {
                    library.updateCategories(listOf(IosSourceContent(new), IosSourceContent(collision)),
                        listOf(ids[2]), emptyList())
                }
                assertFalse(library.isFavourite(IosSourceContent(new)), "The first batch row must roll back")
                library.updateCategories(sparse, emptyList(), listOf(ids[0]))
                assertEquals(listOf(ids[2]), library.favouriteCategories(sparse[0]))
                assertEquals(setOf(ids[1], ids[2]), library.favouriteCategories(sparse[1]).toSet())
                library.updateCategories(listOf(sparse[0]), emptyList(), listOf(ids[2]))
                assertFalse(library.isFavourite(sparse[0]))
            } finally { library.close() }
            val reopened = IosLibrary(path)
            try {
                assertEquals("102", reopened.favourites().single().content.id)
                assertEquals(2, reopened.favourites().single().categoryIds.size)
            } finally { reopened.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }
}
