package org.skepsun.kototoro.reader.novel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.IOException

/**
 * Property-based test for reading progress persistence round-trip.
 * 
 * Feature: epub-reader-improvements
 * Property 28: Progress Persistence Round-Trip
 * Validates: Requirements 7.4, 7.5
 * 
 * For any reading state saved on exit, reopening SHALL restore the same chapter and page position
 * 
 * Note: This is an instrumented test because it requires Room database access.
 */
@RunWith(AndroidJUnit4::class)
class ProgressPersistenceRoundTripPropertyTest {
    
    private lateinit var database: MangaDatabase
    private lateinit var context: Context
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Create an in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            MangaDatabase::class.java
        ).allowMainThreadQueries().build()
    }
    
    @After
    @Throws(IOException::class)
    fun tearDown() {
        database.close()
    }
    
    /**
     * Property 28: Progress Persistence Round-Trip
     * 
     * Test that saving and restoring reading progress works correctly.
     * This validates the complete flow:
     * 1. Save progress (chapter ID + page number)
     * 2. Query history
     * 3. Verify restored values match saved values
     */
    @Test
    fun progressPersistenceRoundTrip_normalChapter() = runBlocking {
        // Create test manga with chapters
        val manga = createTestManga(
            id = 12345L,
            title = "Test Manga",
            chapterCount = 10
        )
        
        // Save manga to database
        database.getMangaDao().upsert(manga.toEntity())
        
        // Test data
        val chapterId = 100L
        val pageNumber = 42
        val scrollPosition = 0
        val progressPercent = 0.5f
        
        // Save progress
        database.getHistoryDao().upsert(
            listOf(
                HistoryEntity(
                    mangaId = manga.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = chapterId,
                    page = pageNumber,
                    scroll = scrollPosition.toFloat(),
                    percent = progressPercent,
                    chaptersCount = 10,
                    deletedAt = 0L
                )
            )
        )
        
        // Restore progress
        val history = database.getHistoryDao().findAllEntriesIncludingDeleted().singleOrNull { it.mangaId == manga.id }
        
        // Verify round-trip
        history shouldNotBe null
        history!!.chapterId shouldBe chapterId
        history.page shouldBe pageNumber
        history.scroll shouldBe scrollPosition.toFloat()
        history.percent shouldBe progressPercent
    }
    
    /**
     * Test round-trip with EPUB internal chapter ID
     * 
     * This is the critical test for Requirement 7.2:
     * "WHEN saving progress for an EPUB internal chapter THEN the system SHALL use the internal chapter ID"
     */
    @Test
    fun progressPersistenceRoundTrip_epubInternalChapter() = runBlocking {
        // Create test manga
        val manga = createTestManga(
            id = 67890L,
            title = "Test EPUB Manga",
            chapterCount = 1
        )
        
        // Save manga to database
        database.getMangaDao().upsert(manga.toEntity())
        
        // Test data - using internal chapter ID (generated from parent + index)
        val parentChapterId = 100000L
        val chapterIndex = 5
        val internalChapterId = parentChapterId + (chapterIndex * 1_000_000L) + 1  // Formula from ChapterIdGenerator
        val pageNumber = 15
        val scrollPosition = 0
        val progressPercent = 0.3f
        
        // Save progress with internal chapter ID
        database.getHistoryDao().upsert(
            listOf(
                HistoryEntity(
                    mangaId = manga.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = internalChapterId,  // Using internal chapter ID, not parent
                    page = pageNumber,
                    scroll = scrollPosition.toFloat(),
                    percent = progressPercent,
                    chaptersCount = 1,
                    deletedAt = 0L
                )
            )
        )
        
        // Restore progress
        val history = database.getHistoryDao().findAllEntriesIncludingDeleted().singleOrNull { it.mangaId == manga.id }
        
        // Verify round-trip - the internal chapter ID should be preserved
        history shouldNotBe null
        history!!.chapterId shouldBe internalChapterId
        history.page shouldBe pageNumber
        
        // Verify we can extract parent ID from the saved internal chapter ID
        val extractedParentId = (internalChapterId - 1) / 1_000_000 * 1_000_000
        extractedParentId shouldBe parentChapterId
    }
    
    /**
     * Test multiple round-trips (updating progress multiple times)
     */
    @Test
    fun progressPersistenceRoundTrip_multipleUpdates() = runBlocking {
        val manga = createTestManga(
            id = 11111L,
            title = "Test Manga Multiple Updates",
            chapterCount = 5
        )
        
        database.getMangaDao().upsert(manga.toEntity())
        
        // First save
        val chapterId1 = 200L
        val page1 = 10
        database.getHistoryDao().upsert(
            listOf(
                HistoryEntity(
                    mangaId = manga.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = chapterId1,
                    page = page1,
                    scroll = 0f,
                    percent = 0.2f,
                    chaptersCount = 5,
                    deletedAt = 0L
                )
            )
        )
        
        // Verify first save
        var history = database.getHistoryDao().findAllEntriesIncludingDeleted().singleOrNull { it.mangaId == manga.id }
        history!!.chapterId shouldBe chapterId1
        history.page shouldBe page1
        
        // Second save (different chapter and page)
        val chapterId2 = 201L
        val page2 = 25
        database.getHistoryDao().upsert(
            listOf(
                HistoryEntity(
                    mangaId = manga.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = chapterId2,
                    page = page2,
                    scroll = 0f,
                    percent = 0.4f,
                    chaptersCount = 5,
                    deletedAt = 0L
                )
            )
        )
        
        // Verify second save overwrote first
        history = database.getHistoryDao().findAllEntriesIncludingDeleted().singleOrNull { it.mangaId == manga.id }
        history!!.chapterId shouldBe chapterId2
        history.page shouldBe page2
    }
    
    /**
     * Test that progress is correctly calculated and persisted
     */
    @Test
    fun progressPersistenceRoundTrip_progressCalculation() = runBlocking {
        val manga = createTestManga(
            id = 22222L,
            title = "Test Progress Calculation",
            chapterCount = 10
        )
        
        database.getMangaDao().upsert(manga.toEntity())
        
        // Test various progress values
        val testCases = listOf(
            Triple(0, 100, 0.0f),      // First page
            Triple(50, 100, 0.5f),     // Middle
            Triple(99, 100, 0.99f),    // Last page
            Triple(0, 1, 0.0f),        // Single page
        )
        
        for ((currentPage, totalPages, expectedPercent) in testCases) {
            val chapterId = 300L + currentPage
            val calculatedPercent = if (totalPages > 0) {
                currentPage.toFloat() / totalPages
            } else {
                0f
            }
            
            // Verify our calculation matches expected
            calculatedPercent shouldBe expectedPercent
            
            // Save with calculated percent
            database.getHistoryDao().upsert(
                listOf(
                    HistoryEntity(
                        mangaId = manga.id,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        chapterId = chapterId,
                        page = currentPage,
                        scroll = 0f,
                        percent = calculatedPercent,
                        chaptersCount = 10,
                        deletedAt = 0L
                    )
                )
            )
            
            // Verify round-trip
            val history = database.getHistoryDao().findAllEntriesIncludingDeleted().singleOrNull { it.mangaId == manga.id }
            history!!.percent shouldBe calculatedPercent
        }
    }
    
    private val testSource = object : ContentSource {
        override val name = "TestSource"
        override val locale = "en"
        override val contentType = ContentType.NOVEL
    }

    private fun createTestManga(id: Long, title: String, chapterCount: Int): Content {
        val chapters = (1..chapterCount).map { index ->
            ContentChapter(
                id = id + index,
                title = "Chapter $index",
                number = index.toFloat(),
                volume = 0,
                url = "https://example.com/chapter/$index",
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = testSource
            )
        }
        
        return Content(
            id = id,
            title = title,
            altTitle = null,
            url = "https://example.com/manga/$id",
            publicUrl = "",
            rating = 0f,
            isNsfw = false,
            coverUrl = "",
            tags = emptySet(),
            state = ContentState.FINISHED,
            author = "Test Author",
            largeCoverUrl = null,
            description = "Test manga for property testing",
            chapters = chapters,
            source = testSource
        )
    }
}
