@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.library.SourceTracking
import org.skepsun.kototoro.core.library.withTrackingMetadata
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.tracking.discovery.TrackingMetadata
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*

class IosTrackingTest {
    @Test
    fun duplicateAnimeAndMangaRemoteIdsCannotOverwriteTheExistingMetadataCache() = withDatabase { path ->
        val library = IosLibrary(path)
        try {
            val manga = sourceWork()
            val anime = IosSourceContent(manga.value.copy(id = 2, url = "/anime", source = SourceRef("VIDEO_SOURCE", "zh", "VIDEO")))
            library.saveDetails(manga)
            library.saveDetails(anime)
            val store = SourceTracking(library.database)
            store.bind(manga.value, metadata(3, 1, "漫画资料").copy(url = "https://myanimelist.net/manga/1").toEntity())
            assertFailsWith<IllegalArgumentException> {
                store.bind(anime.value, metadata(3, 1, "动画资料").copy(url = "https://myanimelist.net/anime/1", mediaKind = "VIDEO").toEntity())
            }
            assertEquals("漫画资料", library.tracking.decorate(manga).title)
            assertTrue(store.bindings(anime.value.id).isEmpty())
        } finally { library.close() }
    }

    @Test
    fun accountAssociationDoesNotReplaceMetadataAndReplacingItsSelectedTargetRestoresTheSource() = withDatabase { path ->
        val library = IosLibrary(path)
        try {
            val work = sourceWork()
            library.saveDetails(work)
            val store = SourceTracking(library.database)
            store.bind(work.value, metadata(5, 901, "账号关联").toEntity(), useMetadata = false)
            assertNull(store.selected(work.value.id))
            assertEquals(work.title, library.tracking.decorate(work).title)
            store.select(work.value, 5, 901)
            store.bind(work.value, metadata(5, 902, "替换账号关联").toEntity(), useMetadata = false)
            assertNull(store.selected(work.value.id), "A replaced link cannot leave an unbound metadata authority")
            store.bind(work.value, metadata(2, 903, "保留其他网站资料").toEntity())
            store.bind(work.value, metadata(5, 904, "只更新账号关联").toEntity(), useMetadata = false)
            assertEquals(903L, store.selected(work.value.id)?.remoteId)
        } finally { library.close() }
    }

    @Test
    fun stoppingTrackingPreservesMetadataSourceAndReadingPosition() = withDatabase { path ->
        val library = IosLibrary(path)
        try {
            val work = sourceWork()
            library.saveDetails(work)
            library.recordPosition(work, work.chapters.single(), 2, 10, 50f)
            SourceTracking(library.database).bind(work.value, metadata(5, 905, "保留网站资料").toEntity())
            val dao = library.database.getScrobblingDao()
            dao.upsert(org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity(5, 905, work.value.id,
                905, "3", 1, null, 0f))
            assertTrue(library.tracking.accounts.isTracked(work, library.tracking.bindings(work).single()))
            library.tracking.accounts.stopTracking(work, 5)
            assertNull(dao.findByLocalManga(5, work.value.id))
            assertEquals("保留网站资料", library.tracking.decorate(work).title)
            assertEquals(2, library.position(work)?.index)
            assertEquals(1, library.tracking.accounts.localProgress(work))
        } finally { library.close() }
    }

    @Test
    fun searchResultBindingFetchesFullDetailsAndNetworkFailurePreservesPreviousBinding() = withDatabase { path ->
        val library = IosLibrary(path)
        try {
            val work = sourceWork()
            val full = metadata(5, 901, "完整网站资料")
            var requests = 0
            val tracking = IosTracking(library) { site, id, kind ->
                assertEquals(5, site.id)
                assertEquals("MANGA", kind)
                requests++
                if (id != full.remoteId) throw IllegalStateException("network unavailable")
                full
            }
            val searchResult = IosTrackingItem(full.copy(description = null, coverUrl = null))
            tracking.bind(work, searchResult)
            assertEquals(full.description, tracking.decorate(work).summary)
            assertEquals(full.coverUrl, tracking.decorate(work).coverUrl)
            assertEquals(1, requests)
            val error = assertFailsWith<IllegalStateException> {
                tracking.bind(work, IosTrackingItem(full.copy(remoteId = 902, title = "失败的检索结果")))
            }
            assertEquals("暂时无法读取作品资料，请检查网络或切换网站", error.message)
            assertEquals("901", tracking.selected(work)?.remoteId)
            val preview = tracking.details(searchResult, "MANGA")
            val before = requests
            tracking.bind(work, preview)
            assertEquals(before, requests, "Applying a preview reuses the already loaded detail")
        } finally { library.close() }
    }

    @Test
    fun bindingReopensWithoutChangingSourceRequestsChaptersOrProgress() = withDatabase { path ->
        val original = sourceWork()
        val target = metadata(5, 101, "网站作品 D")
        IosLibrary(path).let { library ->
            try {
                library.setFavourite(original, true)
                library.recordPosition(original, original.chapters.single(), 3, 10, 2000f)
                val page = IosSourcePage(SourcePage(99, "https://source.invalid/page", "保留书签", original.value.source))
                library.toggleBookmark(original, original.chapters.single(), page, 3, 10, 2000f)
                val store = SourceTracking(library.database)
                store.bind(original.value, target.toEntity())
                val shown = library.tracking.decorate(original)
                assertEquals("网站作品 D", shown.title)
                assertEquals(target.description, shown.summary)
                assertEquals(target.coverUrl, shown.coverUrl)
                assertEquals("Bangumi", shown.metadataSiteTitle)
                assertTrue(shown.usesTrackingCover)
                assertEquals(0.85f, shown.rating)
                assertEquals(listOf("作者 D"), shown.authors)
                assertEquals(original.value, shown.value, "Source execution must receive the original model")
                assertEquals(original.chapters.single().id, shown.chapters.single().id)
                library.saveDetails(shown)
                assertEquals("源作品 B", library.storedDetails(shown)?.title)
            } finally { library.close() }
        }
        val reopened = IosLibrary(path)
        try {
            val shown = reopened.favourites().single().content
            assertEquals("网站作品 D", shown.title)
            assertEquals("网站作品 D", reopened.history().single().content.title)
            assertEquals(original.id, shown.id)
            assertEquals(original.url, shown.url)
            assertEquals(original.sourceName, shown.sourceName)
            assertEquals(3, reopened.position(original)?.index)
            assertEquals(2000f, reopened.position(original)?.scroll)
            assertEquals(1, reopened.bookmarks(original).size)
            assertEquals(1, reopened.tracking.bindings(original).size)
            reopened.tracking.select(original, null)
            val restored = reopened.tracking.decorate(original)
            assertEquals(original.title, restored.title)
            assertEquals(original.coverUrl, restored.coverUrl)
            assertEquals(original.summary, restored.summary)
            assertFalse(restored.usesTrackingCover)
            assertEquals(1, reopened.tracking.bindings(original).size, "Restoring display retains the association")
            assertEquals(3, reopened.position(original)?.index)
        } finally { reopened.close() }
    }

    @Test
    fun replacingOneSiteAndRemovingInactiveLinksKeepsOtherSitesAndReaderPreferences() = withDatabase { path ->
        val library = IosLibrary(path)
        try {
            val work = sourceWork()
            library.saveDetails(work)
            val store = SourceTracking(library.database)
            val first = metadata(5, 201, "Bangumi 旧条目").toEntity().copy(updatedAt = 100, cachedAt = 100)
            store.bind(work.value, first)
            val prefs = library.database.getPreferencesDao().find(work.value.id)!!
            library.database.getPreferencesDao().upsert(prefs.copy(mode = 7, cfBrightness = 0.3f, titleOverride = "手动标题"))
            store.bind(work.value, first.copy(updatedAt = 200))
            assertEquals(100L, library.database.getTrackingSiteDao().findLinksByManga(5, work.value.id).single().createdAt)
            store.bind(work.value, metadata(5, 202, "Bangumi 新条目").toEntity())
            store.bind(work.value, metadata(2, 303, "AniList 条目").toEntity())
            assertEquals(setOf(202L, 303L), store.bindings(work.value.id).map { it.remoteId }.toSet())
            assertEquals(303L, store.selected(work.value.id)?.remoteId)
            store.unbind(work.value, 5)
            assertEquals(303L, store.selected(work.value.id)?.remoteId)
            store.unbind(work.value, 2)
            assertNull(store.selected(work.value.id))
            assertTrue(store.bindings(work.value.id).isEmpty())
            val retained = library.database.getPreferencesDao().find(work.value.id)!!
            assertEquals(7, retained.mode)
            assertEquals(0.3f, retained.cfBrightness)
            assertEquals("手动标题", retained.titleOverride)
        } finally { library.close() }
    }

    @Test
    fun invalidOrUnboundSelectionsDoNotChangeExistingAuthority() = withDatabase { path ->
        val library = IosLibrary(path)
        try {
            val work = sourceWork()
            library.saveDetails(work)
            val store = SourceTracking(library.database)
            store.bind(work.value, metadata(5, 401, "已有资料").toEntity())
            assertFailsWith<IllegalArgumentException> { store.select(work.value, 5, 999) }
            assertFailsWith<IllegalArgumentException> { store.bind(work.value, metadata(5, -1, "错误条目").toEntity()) }
            assertFailsWith<IllegalArgumentException> {
                store.bind(work.value.copy(url = "/collision"), metadata(2, 402, "错误身份").toEntity())
            }
            assertNull(library.database.getTrackingSiteDao().findItem(2, 402))
            assertEquals(401L, store.selected(work.value.id)?.remoteId)
            assertEquals(401L, store.bindings(work.value.id).single().remoteId)
        } finally { library.close() }
    }

    @Test
    fun sparseMetadataFallsBackToSourceAndNeverChangesOpaqueFields() {
        val work = sourceWork().value
        val sparse = TrackingMetadata(5, 501, "稀疏条目", url = "https://bgm.tv/subject/501", mediaKind = "MANGA")
        val shown = work.withTrackingMetadata(sparse.toEntity())
        assertEquals(work.coverUrl, shown.coverUrl)
        assertEquals(work.description, shown.description)
        assertEquals(work.authors, shown.authors)
        assertEquals(work.rating, shown.rating)
        assertEquals(work.sourceData, shown.sourceData)
        assertEquals(work.chapters, shown.chapters)
        assertEquals(work.source, shown.source)
    }

    private fun sourceWork(): IosSourceContent {
        val source = SourceRef("SOURCE_A", "zh", "MANGA")
        val chapter = SourceChapter(11, "原章节", 1f, 0, "/chapter", null, 0, null, source,
            sourceData = "opaque chapter")
        return IosSourceContent(SourceContent(1, "源作品 B", setOf("别名 B"), "/work-b", "https://source.invalid/b",
            0.5f, null, "https://source.invalid/cover.png", emptySet(), "ONGOING", setOf("原作者"), source,
            description = "原简介", chapters = listOf(chapter), sourceData = "opaque source"))
    }

    private fun metadata(service: Int, id: Long, title: String) = TrackingMetadata(service, id, title,
        listOf("别名 D"), "https://tracking.invalid/$id.png", "追踪网站简介", 8.5f, 10f,
        listOf("冒险"), listOf("作者 D"), url = "https://tracking.invalid/$id", mediaKind = "MANGA")

    private fun withDatabase(block: suspend (String) -> Unit) = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        try { block("$directory/library.db") } finally { files.removeItemAtPath(directory, null) }
    }
}
