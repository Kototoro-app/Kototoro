@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.*
import platform.Foundation.*
import kotlin.math.abs
import kotlin.test.*

class IosProductTest {
    @Test
    fun bookmarkTextPreviewUsesTheSharedPlainTextPolicy() {
        fun bookmark(preview: String) = IosBookmark(org.skepsun.kototoro.core.library.SourceBookmark(
            1, 2, 3, 4, 0, 5, 0.5f, preview,
        ))
        assertEquals("晨光 正文", bookmark("<p>晨光</p><p>正文</p>").textPreview)
        assertEquals("hello", bookmark("data:text/plain;base64,aGVsbG8=").textPreview)
        assertEquals("", bookmark("https://example.invalid/page.jpg").textPreview)
        assertEquals("", bookmark("file:///private/page.jpg").textPreview)
        assertEquals(5L, bookmark("摘录").createdAt)
        assertEquals(0.5f, bookmark("摘录").percent)
    }

    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val chapter = SourceChapter(Long.MAX_VALUE, "第一话", 1f, 1, "/chapter/1", null, 1, "中文", source,
        sourceData = """{"chapterToken":"opaque"}""")
    private val otherBranch = chapter.copy(id = 222, branch = "英文")
    private val content = SourceContent(Long.MIN_VALUE, "漫画", emptySet(), "/manga", "https://fixture.invalid/manga",
        0f, null, null, emptySet(), null, emptySet(), source, chapters = listOf(chapter, otherBranch),
        sourceData = """{"contentToken":"opaque"}""")
    private val page = SourcePage(9007199254740993, "mihon://image", null, source,
        headers = mapOf("Referer" to "opaque"),
        requestContext = SourcePageRequestContext(2, "https://fixture.invalid/c", "https://fixture.invalid/i#key"))

    @Test
    fun recordsKeepIdentityAndPositionAcrossReopening() = runBlocking {
        withDatabase { path ->
            val item = IosSourceContent(content)
            val first = IosLibrary(path)
            try {
                first.setFavourite(item, true)
                first.recordPosition(item, IosSourceChapter(chapter), 2, 4, 12f)
                first.toggleBookmark(item, IosSourceChapter(chapter), IosSourcePage(page), 2, 4, 12f)
                first.addCategory("待读")
                val category = first.categories().first { it.title == "待读" }
                first.setCategories(item, listOf(category.id))
            } finally { first.close() }
            val second = IosLibrary(path)
            try {
                // Source metadata comes from the installed catalog, as on the desktop/Android hosts.
                second.updateSources(listOf(IosSource(source.name, "漫画源", source.locale, source.contentType, "extension")))
                val saved = second.favourites().single().content
                assertEquals(content.copy(chapters = null), saved.value)
                assertEquals(content, second.storedDetails(saved)?.value)
                assertEquals(IosReadingPosition(chapter.id.toString(), 2, 12f, 0.75f), second.position(saved))
                assertEquals(2, second.bookmarks(saved).single().index)
                assertEquals(content.id.toString(), second.history().single().content.id)
                assertFailsWith<IllegalArgumentException> {
                    second.saveDetails(IosSourceContent(content.copy(source = source.copy(name = "OTHER"))))
                }
                second.removeBookmark(saved, second.bookmarks(saved).single())
                second.setFavourite(saved, false)
                second.removeHistory(saved)
                assertTrue(second.favourites().isEmpty())
                assertTrue(second.history().isEmpty())
                assertTrue(second.bookmarks(saved).isEmpty())
            } finally { second.close() }
        }
    }

    @Test
    fun storedDetailsReturnSavedChaptersForAListItem() = runBlocking {
        withDatabase { path ->
            val library = IosLibrary(path)
            try {
                library.updateSources(listOf(IosSource(source.name, "漫画源", source.locale, source.contentType, "extension")))
                // Browse results carry no chapters; the details screen looks the work up by its identity.
                val listed = IosSourceContent(content.copy(chapters = null))
                assertNull(library.storedDetails(listed))
                assertEquals(0, listed.chapterCount)
                library.saveDetails(listed)
                assertNull(library.storedDetails(listed), "a row without chapters is not usable details")
                library.saveDetails(IosSourceContent(content))
                val stored = assertNotNull(library.storedDetails(listed))
                assertEquals(content.chapters, stored.value.chapters)
                assertEquals(2, stored.chapterCount)
            } finally { library.close() }
        }
    }

    @Test
    fun updateChecksUseReadBranchAndKeepSeenSeparateFromReading() = runBlocking {
        withDatabase { path ->
            val library = IosLibrary(path)
            var fresh = content
            val runtime = IosSources(object : IosSourceTransport {
                override fun exchange(requestJson: String): String {
                    val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
                    assertTrue(request.call is SourceCall.Details)
                    assertEquals(SourceDetailsFetchMode.FORCE_REFRESH, (request.call as SourceCall.Details).fetchMode)
                    return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, SourceResult.Details(fresh)))
                }
                override fun close() {}
            })
            try {
                val item = IosSourceContent(content)
                library.setFavourite(item, true)
                library.recordPosition(item, IosSourceChapter(chapter), 0, 4, 0f)
                assertEquals(0, library.checkUpdates(runtime).newChapters)
                fresh = content.copy(chapters = listOf(chapter, otherBranch, chapter.copy(id = 333, number = 2f, url = "/chapter/333"),
                    otherBranch.copy(id = 444, number = 2f, url = "/chapter/444")))
                assertEquals(1, library.checkUpdates(runtime).newChapters)
                assertEquals(1, library.updates().single().newChapters)
                library.markUpdatesSeen(item)
                assertEquals(1, library.updates().single().newChapters)
                val updated = IosSourceContent(fresh)
                library.recordPosition(updated, updated.chapters.single { it.id == "333" }, 3, 4, 0f)
                assertTrue(library.updates().isEmpty())
            } finally { runtime.close(); library.close() }
        }
    }

    @Test
    fun readerCoreOwnsSpreadGeometryAndContinuousProgress() {
        val reader = IosReader()
        reader.configure(4, 800, 600, 1, true, true, 0)
        assertEquals(listOf(0, 1, 3), reader.slots().map { it.anchor })
        val pair = reader.slots()[1].placements
        assertTrue(pair[0].x > pair[1].x)
        reader.setImageSize(1, 1600, 600)
        assertEquals(1, reader.slots()[1].placements.size)
        reader.configure(2, 800, 600, 1, false, false, 0, true)
        assertEquals(3, reader.slots().size)
        assertEquals(listOf(0, 2, 1), reader.slots().map { it.placements.single().segment })
        assertEquals(listOf(0, 1, 1), reader.slots().map { it.anchor })
        reader.configure(4, 800, 600, 3, false, false, 0)
        reader.setImageSize(0, 800, 1200)
        assertEquals(IosReaderProgress(0, 500f), reader.progress(0f, 500f))
        assertEquals(500f, reader.scrollOrigin(0, 500f))
        assertEquals(1200f, reader.scrollOrigin(1, 0f))
        assertTrue(reader.autoScrollDistance(1f, 16) > reader.autoScrollDistance(0.1f, 16))
        assertEquals(1, reader.progress(0f, 1300f).index)
        // Zooming the strip widens its layout; positions stay in unzoomed pixels so saved progress is unchanged.
        reader.setZoom(2f)
        assertEquals(1600f, reader.slots()[0].placements.single().width)
        assertEquals(2400f, reader.slots()[1].placements.single().y)
        assertEquals(IosReaderProgress(0, 500f), reader.progress(0f, 1000f))
        assertEquals(1000f, reader.scrollOrigin(0, 500f))
        reader.setZoom(10f)
        assertEquals(3200f, reader.slots()[0].placements.single().width)
        reader.setZoom(1f)
        assertEquals(800f, reader.slots()[0].placements.single().width)
        assertEquals(0f, reader.clampPan(100f, 0f, 800f, 800f, 1f))
        val tallSize = reader.decodeSize(800, 12000)
        assertTrue(tallSize.height > 4096 && tallSize.width.toLong() * tallSize.height <= IosReader.MAXIMUM_PIXELS)
        assertTrue(reader.isTiled(800, 12000))
        val tiles = reader.visibleTiles(0f, 0f, 0, 2000, 12000, 1f)
        assertTrue(tiles.isNotEmpty() && tiles.all { it.decodeHeight < 12000 })
        val pixels = IntArray(400 * 400) { index ->
            val x = index % 400
            val y = index / 400
            if (x in 50 until 350 && y in 40 until 360) 0xFF112233.toInt() else -1
        }
        assertEquals(IosReaderRect(50, 40, 300, 320), reader.contentBounds(400, 400, 1, 400, 400, pixels))
    }

    @Test
    fun chapterWindowKeepsLocalIdentityAndMeasuredSizesAfterPromotion() {
        val reader = IosReader()
        val first = (0..4).map { IosReaderPage("first", it) }
        val second = (0..2).map { IosReaderPage("second", it) }
        val initial = reader.chapterWindow(emptyList(), first, second, 2)
        assertEquals(first + second.take(2), initial)
        reader.setPages(initial)
        reader.configure(initial.size, 600, 800, 3, false, false, 0)
        reader.setImageSize(4, 600, 3000)
        reader.setImageSize(5, 600, 1200)
        val promoted = reader.chapterWindow(first, second, emptyList(), 2)
        assertEquals(first.takeLast(2) + second, promoted)
        reader.setPages(promoted)
        assertEquals(3000f, reader.slots()[1].placements.single().height)
        assertEquals(1200f, reader.slots()[2].placements.single().height)
        val origin = reader.scrollOrigin(2, 150f)
        assertEquals(IosReaderProgress(2, 150f), reader.progress(0f, origin))
    }

    @Test
    fun continuousWindowPrependsAndTrimsChaptersWithoutMovingTheReadPage() {
        val previous = (0..2).map { IosReaderPage("previous", it) }
        val current = (0..3).map { IosReaderPage("current", it) }
        val next = (0..1).map { IosReaderPage("next", it) }
        for ((mode, reversed) in listOf(3 to false, 4 to false, 4 to true)) {
            val reader = IosReader()
            val initial = reader.chapterWindow(emptyList(), current, emptyList(), Int.MAX_VALUE)
            reader.setPages(initial)
            reader.configure(initial.size, 600, 800, mode, false, false, 0, false, reversed)
            repeat(initial.size) { reader.setImageSize(it, 600, 800) }
            fun point(origin: Float) = if (mode == 3) 0f to origin else origin to 0f
            val (x, y) = point(reader.scrollOrigin(2, 120f))
            val measured = reader.slots()[2].placements.single()
            // The previous chapter joins before the page being read: the strip moves by exactly its extent.
            val prepended = reader.chapterWindow(previous, current, emptyList(), Int.MAX_VALUE)
            val moved = assertNotNull(reader.setPages(prepended, x, y))
            assertEquals(IosReaderProgress(5, 120f), reader.progress(moved.x, moved.y))
            assertEquals(reader.scrollOrigin(5, 120f), if (mode == 3) moved.y else moved.x)
            // Measured sizes follow the page, and a window that drops the previous chapter moves the strip back.
            reader.slots()[5].placements.single().let {
                assertEquals(measured.width to measured.height, it.width to it.height)
            }
            val trimmed = reader.chapterWindow(emptyList(), current, next, Int.MAX_VALUE)
            val back = assertNotNull(reader.setPages(trimmed, moved.x, moved.y))
            assertEquals(IosReaderProgress(2, 120f), reader.progress(back.x, back.y))
        }
    }

    @Test
    fun continuousResourceWindowCoversTheScreenFirstAndOnlyReplansOnChange() {
        val reader = IosReader()
        val pages = (0..19).map { IosReaderPage("chapter", it) }
        reader.setPages(pages)
        reader.configure(pages.size, 600, 800, 3, false, false, 0)
        repeat(pages.size) { reader.setImageSize(it, 600, 800) }
        val origin = reader.scrollOrigin(5, 400f)
        val window = assertNotNull(reader.resourceWindow(0f, origin, 0f, 0f, false, false))
        assertEquals(listOf(5, 6), window.filter { it.priority == 0 }.map { it.index })
        assertTrue(window.all { it.priority > 0 || it.decode })
        assertTrue(window.any { it.index < 5 } && window.any { it.index > 8 })
        assertEquals(window.map { it.index }.toSet().size, window.size)
        assertNull(reader.resourceWindow(0f, origin + 10f, 0f, 0f, false, false))
        assertNotNull(reader.resourceWindow(0f, origin + 10f, 0f, 0f, false, true))
        // A fling forward reaches further ahead than the idle window.
        val fling = assertNotNull(reader.resourceWindow(0f, origin, 0f, 6000f, false, true))
        assertTrue(fling.maxOf { it.index } > window.maxOf { it.index })
        reader.configure(pages.size, 600, 800, 0, false, false, 0)
        assertNull(reader.resourceWindow(0f, 0f, 0f, 0f, false, true))
    }

    @Test
    fun continuousUnknownPagesTakeTheChapterRatioWithoutMovingTheReadPage() {
        val reader = IosReader()
        val pages = (0..9).map { IosReaderPage("chapter", it) }
        reader.setPages(pages)
        reader.configure(pages.size, 600, 800, 3, false, false, 0)
        fun height(index: Int) = reader.slots()[index].placements.single().height
        // Unknown pages are a screen tall, and one decoded page is not a chapter ratio yet.
        assertEquals(800f, height(5))
        reader.setImageSize(0, 600, 1800)
        assertEquals(800f, height(5))
        // Two pages agree on 1:3: every unknown page takes it, while the page being read stays where it is.
        val origin = reader.scrollOrigin(1, 100f)
        val moved = assertNotNull(reader.setImageSize(1, 600, 1800, 0f, origin))
        assertTrue(abs(height(5) - 1800f) <= 1f, "unknown page height ${height(5)}")
        assertEquals(IosReaderProgress(1, 100f), reader.progress(moved.x, moved.y))
        // A new scene starts the ratio over.
        reader.reset()
        reader.setPages(pages)
        reader.configure(pages.size, 600, 800, 3, false, false, 0)
        assertEquals(800f, height(5))
    }

    @Test
    fun continuousPagesDecodeWholeUnlessAndroidsPlannerWouldTileThem() {
        val reader = IosReader()
        // A common long strip page: one 17 MB bitmap within the 8192 px edge.
        assertFalse(reader.plansTiled(720, 6000, 720, 6000))
        // Past the 8192 px edge it is tiled.
        assertTrue(reader.plansTiled(1290, 12000, 1290, 12000))
        assertFalse(reader.plansTiled(0, 100, 1, 1))
    }

    @Test
    fun lowPowerModePlansAndroidsReducedContinuousWindow() {
        val reader = IosReader()
        val pages = (0..39).map { IosReaderPage("chapter", it) }
        reader.setPages(pages)
        reader.configure(pages.size, 600, 800, 3, false, false, 0)
        repeat(pages.size) { reader.setImageSize(it, 600, 800) }
        val origin = reader.scrollOrigin(10, 0f)
        val full = assertNotNull(reader.resourceWindow(0f, origin, 0f, 0f, false, true))
        assertTrue(reader.setPreloadReduction(true))
        assertFalse(reader.setPreloadReduction(true))
        val reduced = assertNotNull(reader.resourceWindow(0f, origin, 0f, 0f, false, true))
        assertTrue(reduced.size < full.size, "reduced ${reduced.size} vs full ${full.size}")
        assertTrue(reader.setPreloadReduction(false))
        assertEquals(full.size, assertNotNull(reader.resourceWindow(0f, origin, 0f, 0f, false, true)).size)
    }

    @Test
    fun continuousWindowAppendsTheNextChapterWithoutMovingTheStripAndReportsItsShortTail() {
        val first = (0..1).map { IosReaderPage("first", it) }
        val second = listOf(IosReaderPage("second", 0))
        for ((mode, reversed) in listOf(3 to false, 4 to false, 4 to true)) {
            val reader = IosReader()
            reader.setPages(reader.chapterWindow(emptyList(), first, emptyList(), Int.MAX_VALUE))
            reader.configure(2, 600, 800, mode, false, false, 0, false, reversed)
            repeat(2) { reader.setImageSize(it, 600, 800) }
            val origin = reader.scrollOrigin(0, 100f)
            val appended = reader.chapterWindow(emptyList(), first, second, Int.MAX_VALUE)
            assertEquals(first + second, appended)
            reader.setPages(appended)
            if (mode == 3) reader.setImageSize(2, 600, 200) else reader.setImageSize(2, 150, 800)
            // Appending keeps the physical strip start only in forward-growing layouts.
            assertEquals(!reversed, origin == reader.scrollOrigin(0, 100f))
            // A next chapter shorter than the screen never becomes the first visible page; the strip end shows it.
            val end = if (reversed) 0f else reader.scrollLimit()
            val x = if (mode == 3) 0f else end
            val y = if (mode == 3) end else 0f
            assertEquals(1, reader.progress(x, y).index)
            assertEquals(2, reader.lastVisible(x, y))
        }
    }

    @Test
    fun adjacentChapterSpreadsNeverPairAcrossTheBoundaryAndRetainCoverParity() {
        val reader = IosReader()
        reader.setPages(listOf(IosReaderPage("previous", 3), IosReaderPage("previous", 4),
            IosReaderPage("current", 0), IosReaderPage("current", 1), IosReaderPage("current", 2)))
        reader.configure(5, 800, 1000, 0, true, false, 0)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)),
            reader.slots().map { it.placements.map { placement -> placement.index } })
        reader.configure(5, 800, 1000, 1, true, true, 0)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)),
            reader.slots().map { it.placements.map { placement -> placement.index } })
    }

    @Test
    fun retinaDensityDoesNotMovePagedVisibleRegion() {
        val reader = IosReader()
        reader.configure(1, 500, 700, 0, false, false, 1)
        reader.setImageSize(0, 2000, 12000)
        val normal = reader.visibleTiles(0f, 0f, 0, 2000, 12000, 1f)
        val retina = reader.visibleTiles(0f, 0f, 0, 2000, 12000, 3f)
        // Fit-width centres the 3000-point page in the 700-point viewport: source pixels 4600..7400 are visible.
        assertTrue(normal.minOf { it.logicalTop } <= 4600)
        assertTrue(retina.minOf { it.logicalTop } <= 4600)
        assertTrue(retina.maxOf { it.logicalTop + it.logicalHeight } >= 7400)
        assertTrue(retina.all { it.sampleSize == 1 })
    }

    @Test
    fun continuousStripCanZoomBelowFitAndKeepSemanticProgress() {
        val reader = IosReader()
        reader.configure(6, 800, 600, 3, false, false, 0)
        repeat(6) { reader.setImageSize(it, 800, 1200) }
        reader.setZoom(0.5f)
        assertEquals(400f, reader.slots()[0].placements.single().width)
        assertEquals(600f, reader.slots()[1].placements.single().y)
        assertEquals(IosReaderProgress(1, 200f), reader.progress(0f, 700f))
        assertEquals(700f, reader.scrollOrigin(1, 200f))
        reader.setZoom(0.1f)
        assertEquals(400f, reader.slots()[0].placements.single().width)
        reader.setZoom(1f)
        assertEquals(800f, reader.slots()[0].placements.single().width)
        assertEquals(1400f, reader.scrollOrigin(1, 200f))
    }

    @Test
    fun horizontalContinuousStartsInAndroidsDefaultReadingDirection() {
        val reader = IosReader()
        reader.configure(3, 400, 600, 4, false, false, 0, false)
        repeat(3) { reader.setImageSize(it, 1200, 600) }
        assertEquals(0f, reader.slots()[0].placements.single().x)
        assertEquals(0f, reader.scrollOrigin(0, 0f))
        assertEquals(IosReaderProgress(0, 200f), reader.progress(200f, 0f))
    }

    @Test
    fun horizontalDirectionAndZoomPreserveSemanticOffsets() {
        val reader = IosReader()
        for (reversed in listOf(false, true)) {
            reader.configure(3, 400, 600, 4, false, false, 0, false, reversed)
            repeat(3) { reader.setImageSize(it, 1200, 600) }
            for (zoom in listOf(0.5f, 1f, 2f, 4f)) {
                reader.setZoom(zoom)
                repeat(3) { index ->
                    val origin = reader.scrollOrigin(index, 150f)
                    assertEquals(IosReaderProgress(index, 150f), reader.progress(origin, 0f))
                    assertEquals(600f * zoom, reader.slots()[index].placements.single().height)
                }
            }
            reader.setZoom(1f)
            val origins = (0..2).map { reader.scrollOrigin(it, 0f) }
            assertTrue(if (reversed) origins[0] > origins[1] else origins[0] < origins[1])
        }
    }

    @Test
    fun lateHorizontalImageSizePreservesTheRtlReadingAnchor() {
        val reader = IosReader()
        reader.configure(3, 400, 600, 4, false, false, 0, false, true)
        repeat(3) { reader.setImageSize(it, 1200, 600) }
        val origin = reader.scrollOrigin(1, 200f)
        val compensated = assertNotNull(reader.setImageSize(2, 2400, 600, origin, 0f))
        assertEquals(origin + 1200f, compensated.x)
        assertEquals(IosReaderProgress(1, 200f), reader.progress(compensated.x, compensated.y))
    }

    @Test
    fun horizontalTilesUseTheSamePhysicalViewportInBothDirections() {
        for (reversed in listOf(false, true)) {
            val reader = IosReader()
            reader.configure(1, 400, 600, 4, false, false, 0, false, reversed)
            reader.setImageSize(0, 10000, 1000)
            val tiles = reader.visibleTiles(2000f, 0f, 0, 10000, 1000, 3f)
            assertTrue(tiles.isNotEmpty())
            assertTrue(tiles.minOf { it.logicalLeft } <= 3333)
            assertTrue(tiles.maxOf { it.logicalLeft + it.logicalWidth } >= 4000)
        }
    }

    @Test
    fun lateImageSizePreservesTheContinuousReadingAnchor() {
        val reader = IosReader()
        reader.configure(3, 500, 700, 3, false, false, 0)
        reader.setImageSize(1, 500, 1400)
        val before = reader.progress(0f, 1200f)
        assertEquals(IosReaderProgress(1, 500f), before)
        val compensated = assertNotNull(reader.setImageSize(0, 500, 2100, 0f, 1200f))
        assertEquals(2600f, compensated.y)
        assertEquals(before, reader.progress(compensated.x, compensated.y))
        assertNull(reader.setImageSize(0, 500, 2100, 0f, 2600f))
    }

    @Test
    fun pixelDensityAndCameraZoomIndependentlySelectPagedTiles() {
        val reader = IosReader()
        reader.configure(1, 500, 700, 0, false, false, 1)
        reader.setImageSize(0, 2000, 12000)
        val normal = reader.visibleTiles(0f, 0f, 0, 2000, 12000, 1f, 2f)
        val retina = reader.visibleTiles(0f, 0f, 0, 2000, 12000, 3f, 2f)
        assertTrue(normal.all { it.sampleSize == 2 })
        assertTrue(retina.all { it.sampleSize == 1 })
        // A 2x camera shows the middle half of the fit-width viewport, independent of Retina density.
        assertTrue(retina.minOf { it.logicalTop } <= 5300)
        assertTrue(retina.maxOf { it.logicalTop + it.logicalHeight } >= 6700)
    }

    @Test
    fun offlinePageArchiveKeepsHeadersContextAndExactIds() {
        val codec = IosPageArchive()
        assertEquals(page, codec.decode(codec.encode(listOf(IosSourcePage(page)))).single().value)
    }

    @Test
    fun downloadArchivePreservesSourceOwnedFieldsAndRejectsMismatchedSources() {
        val codec = IosDownloadArchive()
        val encoded = codec.encode(IosSourceContent(content), IosSourceChapter(chapter))
        val restored = codec.decode(encoded)
        assertEquals(content.copy(chapters = null), restored.content.value)
        assertEquals(chapter, restored.chapter.value)
        assertEquals(Long.MIN_VALUE.toString(), restored.content.id)
        assertEquals(Long.MAX_VALUE.toString(), restored.chapter.id)
        val storedContent = content.copy(source = source.copy(locale = ""))
        val mixed = codec.decode(codec.encode(IosSourceContent(storedContent), IosSourceChapter(chapter)))
        assertEquals(storedContent.copy(chapters = null), mixed.content.value)
        assertEquals(chapter, mixed.chapter.value)
        assertFailsWith<IllegalArgumentException> {
            codec.encode(IosSourceContent(content), IosSourceChapter(chapter.copy(source = source.copy(name = "OTHER"))))
        }
        val unsupported = assertFailsWith<IllegalArgumentException> {
            codec.decode(encoded.dropLast(1) + ""","version":2}""")
        }
        assertEquals("下载任务版本尚不支持", unsupported.message)
    }

    @Test
    fun novelChaptersDownloadButVideoIsRefused() {
        val codec = IosDownloadArchive()
        val novel = content.copy(source = source.copy(contentType = "NOVEL"))
        val novelChapter = chapter.copy(source = novel.source)
        assertEquals(novel.copy(chapters = null), codec.decode(codec.encode(IosSourceContent(novel), IosSourceChapter(novelChapter))).content.value)
        val video = content.copy(source = source.copy(contentType = "VIDEO"))
        assertFailsWith<IllegalArgumentException> {
            codec.encode(IosSourceContent(video), IosSourceChapter(chapter.copy(source = video.source)))
        }
    }

    @Test
    fun chapterTextArchiveKeepsHtmlPicturesAndHeaders() {
        val text = IosChapterText(
            SourceChapterContent(
                "<p>第一段</p><img src=\"https://img.invalid/1.jpg\">",
                listOf(SourceContentImage("https://img.invalid/1.jpg", mapOf("Referer" to "https://novel.invalid/"))),
            ),
        )
        val archive = IosChapterTextArchive()
        val restored = archive.decode(archive.encode(text))
        assertEquals(text.html, restored.html)
        assertEquals(listOf("https://img.invalid/1.jpg"), restored.images)
        assertEquals(mapOf("Referer" to "https://novel.invalid/"), restored.headers("https://img.invalid/1.jpg"))
        assertEquals(emptyMap(), restored.headers("https://img.invalid/other.jpg"))
        val stored = archive.encode(text)
        assertFailsWith<IllegalArgumentException> { archive.decode(stored.dropLast(1) + ""","version":2}""") }
    }

    @Test
    fun repositoryDecoderPersistsAndRejectsIdentityChange() = runBlocking {
        withDatabase { path ->
            val library = IosLibrary(path)
            try {
                val repositories = IosRepositories(library)
                val bytes = """[{"name":"Mihon: 漫画","pkg":"test.extension","version":"1.6.1","code":7,"apk":"test.apk","sources":[{"id":"9007199254740993","name":"漫画","lang":"zh"}]}]""".encodeToByteArray()
                val catalog = repositories.decode(bytes, false, "https://fixture.invalid/repo/index.min.json", "MIHON")
                assertEquals("9007199254740993", catalog.rows.single().sources.single().id.toString())
                assertEquals("apk/test.apk", catalog.extensions.single().url)
                assertEquals(listOf("漫画"), catalog.extensions.single().sourceNames)
                assertEquals("icon/test.extension.png", catalog.extensions.single().iconUrl)
                repositories.save(catalog, "a".repeat(64))
                repositories.save(catalog, "a".repeat(64))
                assertEquals(1, repositories.saved().size)
                assertFailsWith<IllegalArgumentException> { repositories.save(catalog, "b".repeat(64)) }
                assertEquals("a".repeat(64), repositories.saved().single().fingerprint)

                val repoJson = """{"meta":{"name":"Keiyoushi","shortName":"KEI","website":"https://keiyoushi.github.io","signingKeyFingerprint":"${"c".repeat(64)}"},"index_v2":"index.min.json"}""".encodeToByteArray()
                val headerCatalog = repositories.decode(repoJson, false, "https://fixture.invalid/repo/repo.json", "MIHON")
                assertEquals("index.min.json", headerCatalog.externalList)
                val fullCatalog = repositories.external(headerCatalog, bytes, false, "https://fixture.invalid/repo/index.min.json")
                assertEquals(1, fullCatalog.extensions.size)
                assertEquals("https://fixture.invalid/repo/", fullCatalog.resourceBaseUrl)
            } finally { library.close() }
        }
    }

    @Test
    fun legacyRepositoryRefreshCanUpgradeUrlIdentityToSigningFingerprint() = runBlocking {
        withDatabase { path ->
            val library = IosLibrary(path)
            try {
                val repositories = IosRepositories(library)
                val baseUrl = "https://raw.githubusercontent.com/yuzono/anime-repo/repo"
                val indexUrl = "$baseUrl/index.min.json"
                val bytes = """
                    [{"name":"Aniyomi: Anime1","pkg":"test.anime1","version":"14.4",
                      "code":4,"apk":"anime1.apk","sources":[]}]
                """.trimIndent().encodeToByteArray()
                val legacy = repositories.decode(bytes, false, indexUrl, "ANIYOMI")
                // Swift hashes the normalized URL when a legacy array has no signing metadata.
                val urlFingerprint = IosRepositories.sha256Hex(legacy.url)
                repositories.save(legacy, urlFingerprint)
                repositories.save(legacy, urlFingerprint)

                val signingKey = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
                val header = """{"meta":{"name":"Yūzōnō","signingKeyFingerprint":"$signingKey"}}"""
                val catalog = repositories.external(
                    repositories.decode(header.encodeToByteArray(), false, "$baseUrl/repo.json", "ANIYOMI"),
                    bytes, false, indexUrl,
                )
                // Refresh probes repo.json after the original index URL was normalized for storage.
                repositories.save(catalog, signingKey)
                repositories.save(catalog, signingKey)
                assertEquals(signingKey, repositories.saved().single().fingerprint)
                assertEquals(baseUrl, repositories.saved().single().url)

                val changed = repositories.decode(
                    header.replace(signingKey, "d".repeat(64)).encodeToByteArray(),
                    false, "$baseUrl/repo.json", "ANIYOMI",
                )
                assertFailsWith<IllegalArgumentException> { repositories.save(changed, "d".repeat(64)) }
                assertFailsWith<IllegalArgumentException> { repositories.save(legacy, urlFingerprint) }
                assertEquals(signingKey, repositories.saved().single().fingerprint)
            } finally { library.close() }
        }
    }

    @Test
    fun pagingPropagatesTransientFailureAndResumesNextPage() = runBlocking {
        var failSecondPage = true
        val secondContent = content.copy(id = 42L, title = "第二页漫画", url = "/manga-2")
        val runtime = IosSources(object : IosSourceTransport {
            override fun exchange(requestJson: String): String {
                val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
                val result: SourceResult = when (val call = request.call) {
                    is SourceCall.Describe -> SourceResult.Descriptor(
                        SourceDescriptor(
                            source = source,
                            sortOrders = setOf("ALPHABETICAL"),
                            defaultSortOrder = "ALPHABETICAL",
                            filterCapabilities = SourceFilterCapabilities(
                                isMultipleTagsSupported = false,
                                isTagsExclusionSupported = false,
                                isSearchSupported = true,
                                isSearchWithFiltersSupported = false,
                                isYearSupported = false,
                                isYearRangeSupported = false,
                                isOriginalLocaleSupported = false,
                                isAuthorSearchSupported = false,
                            ),
                            pagingMode = SourcePagingMode.PAGE_INDEX,
                        ),
                    )
                    is SourceCall.ListContent -> when (call.offset) {
                        0 -> SourceResult.ListContent(listOf(content))
                        1 -> if (failSecondPage) {
                            throw IllegalStateException("HTTP 503")
                        } else {
                            SourceResult.ListContent(listOf(secondContent))
                        }
                        else -> SourceResult.ListContent(emptyList())
                    }
                    else -> error("Unexpected call: $call")
                }
                return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, result))
            }
            override fun close() {}
        })
        try {
            val firstPage = runtime.page(source.name, page = 0, loadedCount = 0, query = null, tag = null)
            assertEquals(listOf(content.id.toString()), firstPage.map { it.id })
            assertFailsWith<Exception> {
                runtime.page(source.name, page = 1, loadedCount = firstPage.size, query = null, tag = null)
            }
            failSecondPage = false
            val secondPage = runtime.page(source.name, page = 1, loadedCount = firstPage.size, query = null, tag = null)
            assertEquals(listOf(secondContent.id.toString()), secondPage.map { it.id })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun spreadProgressAndNovelBookmarksPersist() = runBlocking {
        withDatabase { path ->
            val library = IosLibrary(path)
            try {
                val mangaItem = IosSourceContent(content)
                library.recordPosition(mangaItem, IosSourceChapter(chapter), index = 2, count = 4, lastVisible = 3, scroll = 0f)
                assertEquals(IosReadingPosition(chapter.id.toString(), 2, 0f, 1.0f), library.position(mangaItem))

                val novelSource = SourceRef("TSUNDOKU_NOVEL", "zh", "NOVEL")
                val novelChapter = chapter.copy(source = novelSource)
                val novelContent = content.copy(id = 77L, source = novelSource, chapters = listOf(novelChapter))
                val novelItem = IosSourceContent(novelContent)
                library.toggleNovelBookmark(novelItem, IosSourceChapter(novelChapter), index = 1, count = 3, preview = "第二段正文内容")
                val bookmark = library.bookmarks(novelItem).single()
                assertEquals(1, bookmark.index)
                assertEquals("第二段正文内容", bookmark.preview)
                library.toggleNovelBookmark(novelItem, IosSourceChapter(novelChapter), index = 1, count = 3, preview = "第二段正文内容")
                assertTrue(library.bookmarks(novelItem).isEmpty())
            } finally { library.close() }
        }
    }

    @Test
    fun downloadedBooksListTheirChaptersAndRecordThemWithTheDownload() = runBlocking {
        val text = IosChapterText(SourceChapterContent("<p>序章</p>\n<p>第一章</p>\n",
            sections = listOf(SourceChapterSection("序章", 0), SourceChapterSection("第一章", 10))))
        val archive = IosChapterTextArchive()
        val restored = archive.decode(archive.encode(text))
        assertEquals(listOf("序章", "第一章"), restored.sections)
        assertEquals("<p>序章</p>\n", restored.section(0)?.html)
        assertEquals("<p>第一章</p>\n", restored.section(1)?.html)
        assertNull(restored.section(2))

        val novelSource = SourceRef("TSUNDOKU_NOVEL", "zh", "NOVEL")
        val book = chapter.copy(id = 1000, title = "第一卷", number = 1f, url = "/book/1", source = novelSource)
        val after = chapter.copy(id = 2000, title = "第二卷", number = 2f, url = "/book/2", source = novelSource)
        val plain = IosSourceContent(content.copy(id = 78L, source = novelSource, chapters = listOf(book, after)))
        val item = plain.withBooks(mapOf(book.id.toString() to restored.sections))
        assertEquals(listOf("序章", "第一章", "第二卷"), item.chapters.map { it.title })
        // Android's internal chapter ids, so history written on either side matches.
        assertEquals(listOf("1001", "1001001", "2000"), item.chapters.map { it.id })
        val second = item.chapters[1]
        assertEquals("第一卷", item.bookOf(second)?.title)
        assertEquals(1, item.bookIndex(second))
        assertNull(item.bookOf(item.chapters[2]))
        assertEquals("第二卷", item.adjacentChapter(second, forward = true)?.title)
        assertEquals("序章", item.listedChapter(IosSourceChapter(book)).title)
        // Reading statistics place a book's chapter instead of failing on it (a crash when opened from Swift).
        IosReadingTracker(item).update(second, index = 0, count = 2, lastVisible = 0, scroll = 0f, active = true)

        withDatabase { path ->
            val library = IosLibrary(path)
            try {
                library.recordPosition(item, second, index = 0, count = 2, scroll = 0f)
                val position = assertNotNull(library.position(item))
                assertEquals(second.id, position.chapterId)
                // The second of two chapters in the first of two downloads, half read.
                assertEquals(0.375f, position.progress)
                // Without the book listed, reading resumes at its download.
                assertEquals(book.id.toString(), library.position(plain)?.chapterId)
                library.toggleNovelBookmark(item, second, index = 1, count = 2, preview = "第一章")
                assertEquals(second.id, library.bookmarks(item).single().chapterId)
            } finally { library.close() }
        }
    }

    @Test
    fun downloadsListAWorkOfflineAndAnEmptyAnswerKeepsTheSavedChapters() = runBlocking {
        val novelSource = SourceRef("TSUNDOKU_NOVEL", "zh", "NOVEL")
        val first = chapter.copy(id = 10, title = "第一卷", number = 1f, url = "/v/1", source = novelSource)
        val second = chapter.copy(id = 20, title = "第二卷", number = 2f, url = "/v/2", source = novelSource)
        val work = content.copy(id = 79L, source = novelSource, chapters = listOf(first, second))
        val codec = IosDownloadArchive()
        val item = IosSourceContent(work)
        val offline = assertNotNull(codec.offlineContent(listOf(
            codec.encode(item, IosSourceChapter(second)), codec.encode(item, IosSourceChapter(first)), "broken",
        )))
        assertEquals(work.id.toString(), offline.id)
        assertEquals(listOf("第一卷", "第二卷"), offline.chapters.map { it.title })
        assertNull(codec.offlineContent(listOf("broken")))

        val empty = IosSourceContent(work.copy(chapters = emptyList()))
        assertEquals(listOf("第一卷", "第二卷"), empty.withChaptersOf(item).chapters.map { it.title })
        withDatabase { path ->
            val library = IosLibrary(path)
            try {
                library.saveDetails(item)
                library.saveDetails(empty)
                assertEquals(2, library.storedDetails(item)?.chapterCount)
            } finally { library.close() }
        }
    }

    private suspend fun withDatabase(block: suspend (String) -> Unit) {
        val dir = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(dir, true, null, null))
        try { block("$dir/library.db") } finally { files.removeItemAtPath(dir, null) }
    }
}
