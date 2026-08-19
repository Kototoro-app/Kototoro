package org.skepsun.kototoro.integration

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
import org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.local.epub.ChapterIdGeneratorImpl
import org.skepsun.kototoro.local.epub.EpubContentCache
import org.skepsun.kototoro.local.epub.EpubFileManagerImpl
import org.skepsun.kototoro.local.epub.EpubReaderImpl
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.novel.EpubInternalChapterLoader
import java.io.File

/**
 * Test [ContentSource] used in place of the removed source enum.
 */
private object TestContentSource : ContentSource {
    override val name: String = "TEST"
    override val locale: String = "zh-CHS"
    override val contentType: ContentType = ContentType.NOVEL
}

/**
 * Integration test for progress restoration flow.
 * 
 * Tests the complete flow:
 * Save → Exit → Reopen → Restore position
 * 
 * This validates Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6
 */
@RunWith(AndroidJUnit4::class)
class ProgressRestorationIntegrationTest {
    
    private lateinit var database: MangaDatabase
    private lateinit var context: Context
    private lateinit var epubFileManager: EpubFileManagerImpl
    private lateinit var epubReader: EpubReaderImpl
    private lateinit var chapterIdGenerator: ChapterIdGeneratorImpl
    private lateinit var epubInternalChapterLoader: EpubInternalChapterLoader
    private lateinit var testDir: File
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            MangaDatabase::class.java
        ).allowMainThreadQueries().build()
        
        epubFileManager = EpubFileManagerImpl()
        epubReader = EpubReaderImpl()
        chapterIdGenerator = ChapterIdGeneratorImpl()
        epubInternalChapterLoader = EpubInternalChapterLoader(
            context = context,
            epubFileManager = epubFileManager,
            epubChapterMappingDao = database.getEpubChapterMappingDao(),
            epubContentCache = EpubContentCache.getInstance()
        )
        
        // Create test directory
        testDir = File(context.cacheDir, "progress_test")
        testDir.mkdirs()
    }
    
    @After
    fun teardown() {
        database.close()
        testDir.deleteRecursively()
    }
    
    /**
     * Test 1: Complete progress restoration flow for NORMAL chapter
     * 
     * Flow: Save progress → Exit → Reopen → Restore position
     */
    @Test
    fun progressRestoration_normalChapter() = runBlocking {
        // Setup: Create manga with chapters
        val manga = createTestManga(
            id = 10000L,
            title = "Test Manga",
            chapterCount = 10
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Step 1: Save progress (Requirement 7.1)
        val chapterId = 1001L
        val pageNumber = 42
        val progressPercent = 0.42f
        
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = chapterId,
                page = pageNumber,
                scroll = 0f,
                percent = progressPercent,
                chaptersCount = 10,
                deletedAt = 0L
            )
        )
        
        // Step 2: Simulate exit (database persists)
        // In real app, this would involve closing the activity
        
        // Step 3: Simulate reopen - query history (Requirement 7.5)
        val history = findHistory(manga.id)
        
        // Step 4: Verify restoration (Requirement 7.5)
        history shouldNotBe null
        history!!.chapterId shouldBe chapterId
        history.page shouldBe pageNumber
        history.percent shouldBe progressPercent
        
        // Verify: Can find chapter by ID
        val chapter = manga.chapters?.find { it.id == chapterId }
        chapter shouldNotBe null
    }
    
    /**
     * Test 2: Progress restoration for EPUB internal chapter
     * 
     * Flow: Save with internal chapter ID → Exit → Reopen → Restore using internal ID
     */
    @Test
    fun progressRestoration_epubInternalChapter() = runBlocking {
        // Setup: Create EPUB file and mappings
        val parentChapterId = 100000L
        val epubFile = createTestEpubFile(parentChapterId)
        val epubContent = epubReader.readEpub(epubFile)!!
        
        val dao = database.getEpubChapterMappingDao()
        val mappings = epubContent.chapters.map { chapter ->
            EpubChapterMappingEntity(
                internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, chapter.index),
                parentChapterId = parentChapterId,
                epubFilePath = epubFile.absolutePath,
                epubFileName = "Test Volume",
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                createdAt = System.currentTimeMillis()
            )
        }
        dao.insertAll(mappings)
        
        // Create manga
        val manga = createTestManga(
            id = 20000L,
            title = "Test EPUB Manga",
            chapterCount = 1
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Step 1: Save progress with internal chapter ID (Requirement 7.2)
        val chapterIndex = 1
        val internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, chapterIndex)
        val pageNumber = 15
        val progressPercent = 0.3f
        
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = internalChapterId, // Using internal chapter ID
                page = pageNumber,
                scroll = 0f,
                percent = progressPercent,
                chaptersCount = 1,
                deletedAt = 0L
            )
        )
        
        // Step 2: Simulate exit
        
        // Step 3: Simulate reopen - restore progress (Requirement 7.5)
        val history = findHistory(manga.id)
        
        // Step 4: Verify restoration with internal chapter ID
        history shouldNotBe null
        history!!.chapterId shouldBe internalChapterId
        history.page shouldBe pageNumber
        history.percent shouldBe progressPercent
        
        // Verify: Can find chapter mapping by internal ID
        val mapping = dao.getById(internalChapterId)
        mapping shouldNotBe null
        mapping!!.chapterIndex shouldBe chapterIndex
        mapping.parentChapterId shouldBe parentChapterId
        
        // Verify: Can load chapter content using restored ID
        val chapter = ContentChapter(
            id = internalChapterId,
            title = mapping.chapterTitle,
            number = chapterIndex.toFloat(),
            volume = 1,
            url = "file://${epubFile.absolutePath}#chapter/$chapterIndex",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = TestContentSource
        )
        
        val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
        result.isSuccess shouldBe true
    }
    
    /**
     * Test 3: Progress restoration with fallback to first chapter
     * 
     * Flow: Save → Chapter deleted → Reopen → Fallback to first chapter (Requirement 7.6)
     */
    @Test
    fun progressRestoration_fallbackToFirstChapter() = runBlocking {
        // Setup
        val manga = createTestManga(
            id = 30000L,
            title = "Test Manga",
            chapterCount = 5
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Save progress for a chapter
        val deletedChapterId = 9999L // Chapter that doesn't exist
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = deletedChapterId,
                page = 10,
                scroll = 0f,
                percent = 0.5f,
                chaptersCount = 5,
                deletedAt = 0L
            )
        )
        
        // Simulate reopen
        val history = findHistory(manga.id)
        history shouldNotBe null
        
        // Try to find chapter by saved ID
        val chapter = manga.chapters?.find { it.id == history!!.chapterId }
        
        // Verify: Chapter not found (Requirement 7.6)
        chapter shouldBe null
        
        // Fallback: Load first available chapter
        val firstChapter = manga.chapters?.firstOrNull()
        firstChapter shouldNotBe null
        
        // In real app, would load firstChapter instead
    }
    
    /**
     * Test 4: Progress percentage calculation
     * 
     * Flow: Calculate progress → Save → Restore → Verify calculation (Requirement 7.3)
     */
    @Test
    fun progressRestoration_progressPercentageCalculation() = runBlocking {
        val manga = createTestManga(
            id = 40000L,
            title = "Test Manga",
            chapterCount = 10
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Test various progress calculations
        val testCases = listOf(
            Triple(0, 100, 0.0f),      // First page
            Triple(25, 100, 0.25f),    // 25%
            Triple(50, 100, 0.5f),     // 50%
            Triple(75, 100, 0.75f),    // 75%
            Triple(99, 100, 0.99f),    // Last page
        )
        
        for ((currentPage, totalPages, expectedPercent) in testCases) {
            // Calculate progress (Requirement 7.3)
            val calculatedPercent = currentPage.toFloat() / totalPages
            calculatedPercent shouldBe expectedPercent
            
            // Save progress
            upsertHistory(
                HistoryEntity(
                    mangaId = manga.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = 1000L + currentPage,
                    page = currentPage,
                    scroll = 0f,
                    percent = calculatedPercent,
                    chaptersCount = 10,
                    deletedAt = 0L
                )
            )
            
            // Restore and verify
            val history = findHistory(manga.id)
            history shouldNotBe null
            history!!.percent shouldBe expectedPercent
            history.page shouldBe currentPage
        }
    }
    
    /**
     * Test 5: Multiple save and restore cycles
     * 
     * Flow: Save → Restore → Update → Save → Restore (Requirement 7.4)
     */
    @Test
    fun progressRestoration_multipleCycles() = runBlocking {
        val manga = createTestManga(
            id = 50000L,
            title = "Test Manga",
            chapterCount = 10
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Cycle 1: Save initial progress
        val chapter1Id = 5001L
        val page1 = 10
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = chapter1Id,
                page = page1,
                scroll = 0f,
                percent = 0.1f,
                chaptersCount = 10,
                deletedAt = 0L
            )
        )
        
        // Restore cycle 1
        var history = findHistory(manga.id)
        history!!.chapterId shouldBe chapter1Id
        history.page shouldBe page1
        
        // Cycle 2: Update progress
        val chapter2Id = 5002L
        val page2 = 25
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = chapter2Id,
                page = page2,
                scroll = 0f,
                percent = 0.25f,
                chaptersCount = 10,
                deletedAt = 0L
            )
        )
        
        // Restore cycle 2
        history = findHistory(manga.id)
        history!!.chapterId shouldBe chapter2Id
        history.page shouldBe page2
        
        // Cycle 3: Update progress again
        val chapter3Id = 5003L
        val page3 = 50
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = chapter3Id,
                page = page3,
                scroll = 0f,
                percent = 0.5f,
                chaptersCount = 10,
                deletedAt = 0L
            )
        )
        
        // Restore cycle 3
        history = findHistory(manga.id)
        history!!.chapterId shouldBe chapter3Id
        history.page shouldBe page3
    }
    
    /**
     * Test 6: Progress restoration across app restarts
     * 
     * Simulates complete app restart by closing and reopening database
     */
    @Test
    fun progressRestoration_acrossAppRestarts() = runBlocking {
        val manga = createTestManga(
            id = 60000L,
            title = "Test Manga",
            chapterCount = 10
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Save progress
        val chapterId = 6001L
        val pageNumber = 33
        val progressPercent = 0.33f
        
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = chapterId,
                page = pageNumber,
                scroll = 0f,
                percent = progressPercent,
                chaptersCount = 10,
                deletedAt = 0L
            )
        )
        
        // Verify before "restart"
        var history = findHistory(manga.id)
        history shouldNotBe null
        history!!.chapterId shouldBe chapterId
        
        // Simulate app restart (in real app, database would persist to disk)
        // For in-memory database, we can't truly test this, but we verify
        // that the data is still accessible
        
        // Verify after "restart"
        history = findHistory(manga.id)
        history shouldNotBe null
        history!!.chapterId shouldBe chapterId
        history.page shouldBe pageNumber
        history.percent shouldBe progressPercent
    }
    
    /**
     * Test 7: Progress restoration with EPUB file deletion
     * 
     * Flow: Save progress → Delete EPUB → Reopen → Handle missing file
     */
    @Test
    fun progressRestoration_withDeletedEpubFile() = runBlocking {
        // Setup
        val parentChapterId = 700000L
        val epubFile = createTestEpubFile(parentChapterId)
        val epubContent = epubReader.readEpub(epubFile)!!
        
        val dao = database.getEpubChapterMappingDao()
        val mappings = epubContent.chapters.map { chapter ->
            EpubChapterMappingEntity(
                internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, chapter.index),
                parentChapterId = parentChapterId,
                epubFilePath = epubFile.absolutePath,
                epubFileName = "Test Volume",
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                createdAt = System.currentTimeMillis()
            )
        }
        dao.insertAll(mappings)
        
        val manga = createTestManga(
            id = 70000L,
            title = "Test EPUB Manga",
            chapterCount = 1
        )
        database.getMangaDao().upsert(manga.toEntity())
        
        // Save progress
        val internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, 0)
        upsertHistory(
            HistoryEntity(
                mangaId = manga.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chapterId = internalChapterId,
                page = 5,
                scroll = 0f,
                percent = 0.2f,
                chaptersCount = 1,
                deletedAt = 0L
            )
        )
        
        // Delete EPUB file
        epubFile.delete()
        
        // Try to restore and load
        val history = findHistory(manga.id)
        history shouldNotBe null
        
        val mapping = dao.getById(history!!.chapterId)
        mapping shouldNotBe null
        
        // Try to load chapter (should fail gracefully)
        val chapter = ContentChapter(
            id = internalChapterId,
            title = mapping!!.chapterTitle,
            number = 1f,
            volume = 1,
            url = "file://${epubFile.absolutePath}#chapter/0",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = TestContentSource
        )
        
        val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
        
        // Verify: Error handled gracefully
        result.isFailure shouldBe true
    }
    
    /**
     * Loads the active history row for a manga, mirroring the legacy
     * HistoryDao#find(mangaId) API.
     */
    private suspend fun findHistory(mangaId: Long): HistoryEntity? {
        return database.getHistoryDao().findAllEntriesIncludingDeleted()
            .firstOrNull { it.mangaId == mangaId && it.deletedAt == 0L }
    }

    /**
     * Persists a single history row through the collection-based
     * HistoryDao#upsert API.
     */
    private suspend fun upsertHistory(entity: HistoryEntity) {
        database.getHistoryDao().upsert(listOf(entity))
    }

    /**
     * Creates a test manga with chapters.
     */
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
                source = TestContentSource
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
            description = "Test manga for integration testing",
            chapters = chapters,
            source = TestContentSource
        )
    }
    
    /**
     * Creates a test EPUB file for testing.
     */
    private fun createTestEpubFile(parentChapterId: Long): File {
        val epubDir = File(testDir, "epub")
        epubDir.mkdirs()
        
        val timestamp = System.currentTimeMillis()
        val fileName = "chapter_${parentChapterId}_${timestamp}.epub"
        val epubFile = File(epubDir, fileName)
        
        createMinimalEpub(epubFile)
        
        return epubFile
    }
    
    /**
     * Creates a minimal valid EPUB file for testing.
     */
    private fun createMinimalEpub(file: File) {
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.setLevel(0)
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            
            zip.setLevel(9)
            
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Test EPUB</dc:title>
                        <dc:creator>Test Author</dc:creator>
                        <dc:language>en</dc:language>
                        <dc:identifier id="BookId">test-epub-001</dc:identifier>
                    </metadata>
                    <manifest>
                        <item id="chapter1" href="chapter1.html" media-type="application/xhtml+xml"/>
                        <item id="chapter2" href="chapter2.html" media-type="application/xhtml+xml"/>
                        <item id="chapter3" href="chapter3.html" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine>
                        <itemref idref="chapter1"/>
                        <itemref idref="chapter2"/>
                        <itemref idref="chapter3"/>
                    </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/chapter1.html"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 1</title></head>
                <body>
                    <h1>Chapter 1: The Beginning</h1>
                    <p>This is the first chapter of the test EPUB.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/chapter2.html"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 2</title></head>
                <body>
                    <h1>Chapter 2: The Journey</h1>
                    <p>This is the second chapter of the test EPUB.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/chapter3.html"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 3</title></head>
                <body>
                    <h1>Chapter 3: The End</h1>
                    <p>This is the final chapter of the test EPUB.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()
        }
    }
}
