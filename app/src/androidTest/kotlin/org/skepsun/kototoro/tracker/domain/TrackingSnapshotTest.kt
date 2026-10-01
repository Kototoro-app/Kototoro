package org.skepsun.kototoro.tracker.domain

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.text.Html
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import javax.inject.Provider
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.StoredContentIdentityResolver
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.details.domain.DetailsLoadUseCase
import org.skepsun.kototoro.details.domain.hasCompleteDetailsSnapshot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.explore.domain.RecoverContentUseCase
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.novel.LocalNovelRepository
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.domain.feed.FeedCardMapper
import org.skepsun.kototoro.tracker.domain.feed.FeedSnapshotStore
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates

/** Uses production writes/reads with an isolated Room database, never the user's library. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TrackingSnapshotTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var progressUpdateUseCase: ProgressUpdateUseCase

    @Inject
    lateinit var repositoryFactory: ContentRepository.Factory

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var contentRepository: ContentDataRepository
    private lateinit var repository: TrackingRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        contentRepository = ContentDataRepository(
            db,
            Provider { error("Link resolution is outside this test") },
            Provider { error("Shortcuts are outside this test") },
            StoredContentIdentityResolver(db),
        )
        repository = TrackingRepository(db, settings, progressUpdateUseCase, contentRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun feedUpdateAndCachedDetailsContainTheSameNewChapter() = runTest {
        val old = content(101, 1)
        contentRepository.updateContentSnapshot(old)
        val refreshed = content(101, 2)

        repository.updateTrack(refreshed, success(refreshed, refreshed.chapters.orEmpty().takeLast(1)))

        val feed = db.getTrackerReadDao().observeFeedLogRows().first().single()
        assertTrue(feed.chapters.contains("Chapter 2"))
        val details = requireNotNull(contentRepository.findContentById(feed.anchorMangaId, withChapters = true))
        assertTrue(details.hasCompleteDetailsSnapshot())
        assertEquals("Feed reports Chapter 2 but the cached details must also contain it", listOf(1L, 2L),
            details.chapters.orEmpty().map { it.id })
        assertEquals(old.url, details.url)
        assertEquals(old.source, details.source)
    }

    @Test
    fun successfulCheckWithoutNewChaptersStillRefreshesTheSnapshot() = runTest {
        val old = content(101, 1)
        contentRepository.updateContentSnapshot(old)
        val refreshed = old.copy(description = "Updated description")

        repository.updateTrack(refreshed, success(refreshed, emptyList()))

        assertEquals(refreshed.description, contentRepository.findContentById(101, true)?.description)
        assertTrue(db.getTrackerReadDao().observeFeedLogRows().first().isEmpty())
    }

    @Test
    fun openingTheFeedCardOnlineLoadsUpdatedChaptersWithoutManualRefresh() = runTest {
        contentRepository.updateContentSnapshot(content(101, 1))
        val refreshed = content(101, 2)
        repository.updateTrack(refreshed, success(refreshed, refreshed.chapters.orEmpty().takeLast(1)))
        val context = isolatedStorageContext()
        val isolatedSettings = AppSettings(context)
        // Exercise the online cache branch without changing the user's settings or using a live source.
        isolatedSettings.isOfflineCheckDisabled = true
        val networkState = NetworkState(context.getSystemService(ConnectivityManager::class.java), isolatedSettings)
        assertTrue("This regression must exercise the online branch", !networkState.isOfflineOrRestricted())
        val storage = LocalStorageManager(context, isolatedSettings)
        lateinit var localRepository: LocalMangaRepository
        val index = LocalContentIndex(
            contentRepository, db, context,
            Provider { localRepository },
            Provider { LocalNovelRepository(storage) },
            storage,
        )
        localRepository = LocalMangaRepository(
            storage, db, index, MutableSharedFlow(), isolatedSettings, ContentLock(), Provider { repositoryFactory },
        )
        val snapshot = FeedSnapshotStore(db, sourceGroupManager).observe().first()
        val card = FeedCardMapper(context).map(snapshot.rows, FeedCardMapper.Request("Broken")).single()
        assertEquals("Feed cards deliberately contain no chapter payload", null, card.manga.chapters)
        assertEquals("", card.manga.url)
        val loader = DetailsLoadUseCase(
            contentRepository, localRepository, repositoryFactory,
            RecoverContentUseCase(contentRepository, repositoryFactory),
            Html.ImageGetter { error("No inline images in this fixture") }, networkState, db,
        )

        val emissions = loader(ContentIntent.of(card.manga), force = false).toList()

        assertTrue(emissions.last().isLoaded)
        assertEquals(listOf(1L, 2L), emissions.last().allChapters.map { it.id })
        assertEquals(refreshed.url, emissions.last().toContent().url)
        assertEquals(refreshed.source, emissions.last().toContent().source)
    }

    @Test
    fun failedTrackWriteRollsBackTheSnapshotAndFeedTogether() = runTest {
        val old = content(101, 1)
        contentRepository.updateContentSnapshot(old)
        // Force a write failure after the snapshot update, only in this in-memory database.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_track BEFORE INSERT ON tracks " +
                "BEGIN SELECT RAISE(ABORT, 'Injected track write failure'); END",
        )
        val refreshed = content(101, 2)

        val result = runCatching {
            repository.updateTrack(refreshed, success(refreshed, refreshed.chapters.orEmpty().takeLast(1)))
        }

        assertTrue("The injected write failure must occur", result.isFailure)
        assertEquals(listOf(1L), contentRepository.findContentById(101, true)?.chapters?.map { it.id })
        assertEquals(null, db.getTracksDao().find(101))
        assertTrue(db.getTrackerReadDao().observeFeedLogRows().first().isEmpty())
    }

    @Test
    fun failedCheckPreservesTheCompleteSnapshot() = runTest {
        val old = content(101, 2)
        contentRepository.updateContentSnapshot(old)
        val stub = old.copy(description = null, chapters = null)

        repository.updateTrack(stub, MangaUpdates.Failure(stub, 101, 101, IllegalStateException("Offline")))

        val details = requireNotNull(contentRepository.findContentById(101, true))
        assertEquals(old.description, details.description)
        assertEquals(listOf(1L, 2L), details.chapters.orEmpty().map { it.id })
        assertEquals(TrackEntity.RESULT_FAILED, db.getTracksDao().find(101)?.lastResult)
    }

    @Test
    fun remoteCheckForLocalAnchorDoesNotReplaceLocalIdentityOrChapters() = runTest {
        val local = content(101, 1, LocalMangaSource).copy(url = "file:///local.cbz", publicUrl = "")
        contentRepository.updateContentSnapshot(local)
        db.getChaptersDao().replaceAll(101, local.chapters.orEmpty().withIndex().toEntities(101))
        // CheckNewChaptersUseCase copies a remote result to the tracked local owner's id.
        val remote = content(101, 2)

        repository.updateTrack(remote, success(remote, remote.chapters.orEmpty().takeLast(1)))

        val stored = requireNotNull(contentRepository.findContentById(101, true))
        assertEquals(LocalMangaSource, stored.source)
        assertEquals(local.url, stored.url)
        assertEquals(listOf(1L), stored.chapters.orEmpty().map { it.id })
        assertEquals(1, db.getTracksDao().find(101)?.newChapters)
    }

    private fun success(content: Content, chapters: List<ContentChapter>) = MangaUpdates.Success(
        manga = content,
        entityId = content.id,
        anchorMangaId = content.id,
        branch = null,
        newChapters = chapters,
        isValid = true,
    )

    private fun isolatedStorageContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "tracking-snapshot-${System.nanoTime()}"
        return object : ContextWrapper(base) {
            override fun getFilesDir() = File(base.cacheDir, namespace)

            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    private fun content(id: Long, chapterCount: Int, source: ContentSource = TestContentSource) = Content(
        id = id,
        title = "Tracked title",
        altTitles = emptySet(),
        url = "/comic/$id",
        publicUrl = "https://example.org/comic/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = "https://example.org/cover.jpg",
        largeCoverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        description = "Complete description",
        chapters = (1..chapterCount).map { number ->
            ContentChapter(
                id = number.toLong(),
                title = "Chapter $number",
                number = number.toFloat(),
                volume = 0,
                url = "/chapter/$number",
                scanlator = null,
                uploadDate = number * 1000L,
                branch = null,
                source = source,
            )
        },
        source = source,
    )
}
