package org.skepsun.kototoro.extensions.runtime.tachiyomi

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.SourceTrackerMethod
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.isSourceTracker
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.sourceTrackerBoolean
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that the host's `eu.kanade.tachiyomi.source.*` ABI carries the real Tsundoku
 * extensions-lib 1.4/1.6 novel surface (T2A.1 of docs/architecture/tsundoku-extension-integration-plan):
 * novel markers on [Source], [NovelSource], [SourceTracker], [RefreshContext] and the novel
 * [Page] fields. All assertions run against host classes only — no real extension classloading.
 */
class TsundokuNovelAbiTest {

    /** Minimal Source that relies entirely on the host interface defaults. */
    private class DefaultSource : Source {
        override val id: Long = 1L
        override val name: String = "default-source"
    }

    /** SourceTracker whose every member comes from the host interface defaults. */
    private class DefaultTracker : SourceTracker

    /**
     * A fake Tsundoku novel source: exists purely in this test classpath (no real APK loading),
     * declares itself a novel via the marker interface AND the [Source.isNovelSource] property,
     * and also acts as a [SourceTracker].
     */
    @Suppress("DEPRECATION")
    private class FakeNovelSource : NovelSource, SourceTracker {
        override val id: Long = 42L
        override val name: String = "fake-novel"
        override val isNovelSource: Boolean = true
        override val supportsLatest: Boolean = true
        override val supportsChapterTracking: Boolean = true
        override val supportsFavoritesTracking: Boolean = true

        override suspend fun fetchPageText(page: Page): String = "body of ${page.url}"
    }

    // ============================== 1. Source defaults ==============================

    @Test
    fun `default Source novel markers are false`() {
        val source = DefaultSource()
        assertFalse(source.isNovelSource)
        assertFalse(source.supportsLatest)
    }

    @Test
    fun `MockK can model the host Source with the same default markers`() {
        val mocked = mockk<Source>(relaxed = true)
        every { mocked.id } returns 1L
        every { mocked.name } returns "mock-source"
        assertEquals("mock-source", mocked.name)
        // Unstubbed defaults on the relaxed mock coincide with the host interface defaults,
        // and the NovelSourceKt helper reads through the same property.
        assertFalse(mocked.isNovelSource)
        assertFalse(mocked.supportsLatest)
        assertFalse(mocked.isNovelSource())
    }

    @Test
    fun `default fetchPageText throws for non-novel sources`() {
        val page = Page(0, "https://example.org/chapter/1")
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { DefaultSource().fetchPageText(page) }
        }
    }

    // ============================== 2. Page novel fields ==============================

    @Test
    fun `Page text defaults to null, is assignable and number is index plus one`() {
        val page = Page(3, "chapter-url")
        assertNull(page.text)
        page.text = "hello novel body"
        assertEquals("hello novel body", page.text)
        assertEquals(4, page.number)
        assertEquals(3, page.index)
    }

    @Test
    fun `Page four-arg constructor and status progress defaults are preserved`() {
        val page = Page(0, "", null, null)
        assertNull(page.text)
        assertEquals(Page.State.Queue, page.status)
        assertEquals(0, page.progress)
        assertEquals(Page.State.Queue, page.statusFlow.value)
        assertEquals(0, page.progressFlow.value)
    }

    // ============================== 3. RefreshContext ==============================

    @Test
    fun `RefreshContext builds with forceRefresh defaulted to false`() {
        val context = RefreshContext(mangaId = 7L, existingChapters = emptyList(), lastFetchTime = 1234L)
        assertEquals(7L, context.mangaId)
        assertEquals(emptyList<SChapter>(), context.existingChapters)
        assertEquals(1234L, context.lastFetchTime)
        assertFalse(context.forceRefresh)
    }

    @Test
    fun `RefreshContext supports copy and positional construction with forceRefresh`() {
        val context = RefreshContext(9L, listOf(SChapter.create()), 42L, true)
        assertTrue(context.forceRefresh)
        assertEquals(9L, context.mangaId)

        val copied = context.copy(forceRefresh = false)
        assertFalse(copied.forceRefresh)
        assertEquals(context.existingChapters, copied.existingChapters)
    }

    // ============================== 4. SourceTracker ==============================

    @Test
    fun `SourceTracker defaults and suspend callbacks do not throw`() {
        val tracker = DefaultTracker()
        assertTrue(tracker.supportsChapterTracking)
        assertFalse(tracker.supportsFavoritesTracking)

        val manga = SManga.create()
        runBlocking {
            tracker.onChaptersRead(manga, emptyList(), emptyList(), emptyList())
            tracker.onChaptersUnread(manga, emptyList(), emptyList(), emptyList())
            tracker.onFavorited(manga, emptyList())
            tracker.onUnfavorited(manga, emptyList())
        }
        assertEquals(4, SourceTrackerMethod.entries.size)
    }

    @Test
    fun `SourceTracker can be modelled by MockK and invoked without throwing`() {
        val mocked = mockk<SourceTracker>(relaxed = true)
        every { mocked.supportsChapterTracking } returns true
        every { mocked.supportsFavoritesTracking } returns false
        assertTrue(mocked.supportsChapterTracking)
        assertFalse(mocked.supportsFavoritesTracking)

        runBlocking {
            mocked.onChaptersRead(SManga.create(), emptyList(), emptyList(), listOf("Default"))
            mocked.onChaptersUnread(SManga.create(), emptyList(), emptyList(), emptyList())
            mocked.onFavorited(SManga.create(), emptyList())
            mocked.onUnfavorited(SManga.create(), emptyList())
        }
    }

    // ============================== 5. Fake source + tracker ==============================

    @Suppress("DEPRECATION")
    @Test
    fun `FakeNovelSource is both a Source and a SourceTracker`() {
        val fake = FakeNovelSource()
        assertTrue(fake.isNovelSource)
        assertTrue(fake.supportsLatest)
        assertTrue(fake.isSourceTracker())
        assertTrue(fake.sourceTrackerBoolean("supportsChapterTracking", false))
        assertEquals("body of /ch/1", runBlocking { fake.fetchPageText(Page(0, "/ch/1")) })
    }

    @Test
    fun `FakeNovelSource is verifiable through a SourceTracker mock`() {
        val fake = FakeNovelSource()
        val trackerMock: SourceTracker = mockk<SourceTracker>()
        every { trackerMock.supportsChapterTracking } returns fake.supportsChapterTracking
        every { trackerMock.supportsFavoritesTracking } returns fake.supportsFavoritesTracking
        assertTrue(trackerMock.supportsChapterTracking)
        assertTrue(trackerMock.supportsFavoritesTracking)

        // The fake satisfies the host SourceTracker contract through the interface.
        val viaInterface: SourceTracker = fake
        assertTrue(viaInterface.supportsFavoritesTracking)
    }

    // ============================== 6. Fork chapter-list overload ==============================

    @Test
    fun `getChapterList RefreshContext fork defers to the plain overload`() {
        val manga = SManga.create()
        val chapters = listOf(SChapter.create())
        val source = object : Source {
            override val id: Long = 2L
            override val name: String = "chapter-source"

            override suspend fun getChapterList(manga: SManga): List<SChapter> = chapters
        }
        @Suppress("DEPRECATION")
        val viaFork = runBlocking {
            source.getChapterList(manga, RefreshContext(1L, chapters, 0L))
        }
        assertEquals(chapters, viaFork)
    }
}
