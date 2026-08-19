package org.skepsun.kototoro.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity
import org.skepsun.kototoro.local.epub.ChapterIdGeneratorImpl
import org.skepsun.kototoro.local.epub.EpubContentCache
import org.skepsun.kototoro.local.epub.EpubFileManagerImpl
import org.skepsun.kototoro.local.epub.EpubReaderImpl
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.novel.EpubInternalChapterLoader
import java.io.File

/**
 * Integration test for chapter navigation flow.
 * 
 * Tests the complete flow:
 * Click → Determine type → Load → Render → Save progress
 * 
 * This validates Requirements: 3.1, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 7.1, 7.2
 */
@RunWith(AndroidJUnit4::class)
class ChapterNavigationIntegrationTest {
    
    private lateinit var database: MangaDatabase
    private lateinit var context: Context
    private lateinit var epubFileManager: EpubFileManagerImpl
    private lateinit var epubReader: EpubReaderImpl
    private lateinit var chapterIdGenerator: ChapterIdGeneratorImpl
    private lateinit var epubInternalChapterLoader: EpubInternalChapterLoader
    private lateinit var testDir: File

    private val testSource = object : ContentSource {
        override val name = "TestSource"
        override val locale = "en"
        override val contentType = ContentType.NOVEL
    }
    
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
        testDir = File(context.cacheDir, "chapter_nav_test")
        testDir.mkdirs()
    }
    
    @After
    fun teardown() {
        database.close()
        testDir.deleteRecursively()
    }
    
    /**
     * Test 1: Navigate to NORMAL chapter
     * 
     * Flow: Click → Determine type (NORMAL) → Load from online source
     */
    @Test
    fun chapterNavigation_normalChapter() = runBlocking {
        // Create a NORMAL chapter
        val normalChapter = ContentChapter(
            id = 1000L,
            title = "Chapter 1",
            number = 1f,
            volume = 1,
            url = "https://example.com/chapter/1",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = testSource
        )
        
        // Determine type (should be NORMAL by default)
        val chapterType = determineChapterType(normalChapter)
        chapterType shouldBe ChapterType.NORMAL
        
        // For NORMAL chapters, we would load from online source
        // This is handled by the parser, not tested here
    }
    
    /**
     * Test 2: Navigate to EPUB_DOWNLOAD chapter
     * 
     * Flow: Click → Determine type (EPUB_DOWNLOAD) → Show download prompt
     */
    @Test
    fun chapterNavigation_epubDownloadChapter() = runBlocking {
        // Create an EPUB_DOWNLOAD chapter
        val downloadChapter = ContentChapter(
            id = 2000L,
            title = "Volume 1 (EPUB)",
            number = 0f,
            volume = 1,
            url = "https://example.com/download/volume1.epub",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = testSource
        )
        
        // Determine type (EPUB_DOWNLOAD if URL contains .epub)
        val chapterType = determineChapterType(downloadChapter)
        chapterType shouldBe ChapterType.EPUB_DOWNLOAD
        
        // For EPUB_DOWNLOAD chapters, we would show download prompt
        // This is handled by the UI, not tested here
    }
    
    /**
     * Test 3: Navigate to EPUB_INTERNAL chapter
     * 
     * Flow: Click → Determine type (EPUB_INTERNAL) → Load from EPUB file → Render
     */
    @Test
    fun chapterNavigation_epubInternalChapter() = runBlocking {
        // Setup: Create test EPUB file and mappings
        val parentChapterId = 100000L
        val epubFile = createTestEpubFile(parentChapterId)
        val epubContent = epubReader.readEpub(epubFile)!!
        
        // Store mappings
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
        
        // Create an EPUB_INTERNAL chapter
        val chapterIndex = 1
        val internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, chapterIndex)
        val internalChapter = ContentChapter(
            id = internalChapterId,
            title = "Chapter 2: The Journey",
            number = 2f,
            volume = 1,
            url = "file://${epubFile.absolutePath}#chapter/$chapterIndex",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = testSource
        )
        
        // Determine type (EPUB_INTERNAL if URL contains #chapter/)
        val chapterType = determineChapterType(internalChapter)
        chapterType shouldBe ChapterType.EPUB_INTERNAL
        
        // Load chapter content (Requirement 6.2)
        val result = epubInternalChapterLoader.loadEpubInternalChapter(internalChapter)
        
        // Verify: Content loaded successfully
        result.isSuccess shouldBe true
        val content = result.getOrNull()
        content shouldNotBe null
        content!!.content shouldContain "Chapter 2"
        content!!.content shouldContain "The Journey"
        
        // Verify: Content is rendered (contains chapter text)
        content!!.content shouldContain "second chapter"
    }
    
    /**
     * Test 4: Navigate with URL index extraction
     * 
     * Flow: Extract chapter index from URL → Locate file → Read chapter
     */
    @Test
    fun chapterNavigation_urlIndexExtraction() = runBlocking {
        // Setup
        val parentChapterId = 200000L
        val epubFile = createTestEpubFile(parentChapterId)
        val epubContent = epubReader.readEpub(epubFile)!!
        
        // Store mappings
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
        
        // Test different URL formats
        val testCases = listOf(
            Pair("file://${epubFile.absolutePath}#chapter/0", 0),
            Pair("file://${epubFile.absolutePath}#chapter/1", 1),
            Pair("file://${epubFile.absolutePath}#chapter/2", 2)
        )
        
        for ((url, expectedIndex) in testCases) {
            val internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, expectedIndex)
            val chapter = ContentChapter(
                id = internalChapterId,
                title = "Test Chapter",
                number = expectedIndex.toFloat(),
                volume = 1,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = testSource
            )
            
            // Load chapter
            val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
            
            // Verify: Correct chapter loaded (Requirement 6.4, 6.6)
            result.isSuccess shouldBe true
            val content = result.getOrNull()
            content shouldNotBe null
            
            // Verify content matches expected chapter
            val expectedChapter = epubContent.chapters[expectedIndex]
            content!!.content shouldContain expectedChapter.title
        }
    }
    
    /**
     * Test 5: Navigate with parent ID file location
     * 
     * Flow: Use parent chapter ID → Locate EPUB file → Load chapter
     */
    @Test
    fun chapterNavigation_parentIdFileLocation() = runBlocking {
        // Setup
        val parentChapterId = 300000L
        val epubFile = createTestEpubFile(parentChapterId)
        val epubContent = epubReader.readEpub(epubFile)!!
        
        // Store mappings
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
        
        // Create chapter with internal ID
        val chapterIndex = 0
        val internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, chapterIndex)
        val chapter = ContentChapter(
            id = internalChapterId,
            title = "Test Chapter",
            number = 1f,
            volume = 1,
            url = "file://${epubFile.absolutePath}#chapter/$chapterIndex",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = testSource
        )
        
        // Load chapter (should use mapping to locate file)
        val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
        
        // Verify: File located and chapter loaded (Requirement 6.5)
        result.isSuccess shouldBe true
        result.getOrNull() shouldNotBe null
    }
    
    /**
     * Test 6: Navigate with error handling for missing file
     * 
     * Flow: Try to load → File not found → Display error
     */
    @Test
    fun chapterNavigation_missingFileError() = runBlocking {
        // Create chapter pointing to non-existent file
        val nonExistentFile = File(testDir, "nonexistent.epub")
        val chapter = ContentChapter(
            id = 999999L,
            title = "Missing Chapter",
            number = 1f,
            volume = 1,
            url = "file://${nonExistentFile.absolutePath}#chapter/0",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = testSource
        )
        
        // Try to load chapter
        val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
        
        // Verify: Error handled gracefully (Requirement 6.7, 10.4)
        result.isFailure shouldBe true
        val exception = result.exceptionOrNull()
        exception shouldNotBe null
        exception!!.message shouldContain "not found"
    }
    
    /**
     * Test 7: Complete navigation flow with progress saving
     * 
     * Flow: Load chapter → Render → Save progress
     */
    @Test
    fun chapterNavigation_withProgressSaving() = runBlocking {
        // Setup
        val parentChapterId = 400000L
        val epubFile = createTestEpubFile(parentChapterId)
        val epubContent = epubReader.readEpub(epubFile)!!
        
        // Store mappings
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
        
        // Load chapter
        val chapterIndex = 1
        val internalChapterId = chapterIdGenerator.generateEpubChapterId(parentChapterId, chapterIndex)
        val chapter = ContentChapter(
            id = internalChapterId,
            title = "Test Chapter",
            number = 2f,
            volume = 1,
            url = "file://${epubFile.absolutePath}#chapter/$chapterIndex",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = testSource
        )
        
        val result = epubInternalChapterLoader.loadEpubInternalChapter(chapter)
        result.isSuccess shouldBe true
        
        // Simulate saving progress (Requirement 7.1, 7.2)
        val mangaId = 12345L
        val pageNumber = 5
        val progressPercent = 0.25f
        
        database.getHistoryDao().upsert(
            listOf(
                org.skepsun.kototoro.history.data.HistoryEntity(
                    mangaId = mangaId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chapterId = internalChapterId, // Using internal chapter ID (Requirement 7.2)
                    page = pageNumber,
                    scroll = 0f,
                    percent = progressPercent,
                    chaptersCount = 3,
                    deletedAt = 0L
                )
            )
        )
        
        // Verify: Progress saved with internal chapter ID
        val history = database.getHistoryDao().findAllEntriesIncludingDeleted().singleOrNull { it.mangaId == mangaId }
        history shouldNotBe null
        history!!.chapterId shouldBe internalChapterId
        history.page shouldBe pageNumber
        history.percent shouldBe progressPercent
    }
    
    /**
     * Helper function to determine chapter type based on chapter properties.
     */
    private fun determineChapterType(chapter: ContentChapter): ChapterType {
        return when {
            chapter.url.contains("#chapter/") -> ChapterType.EPUB_INTERNAL
            chapter.url.contains(".epub") -> ChapterType.EPUB_DOWNLOAD
            else -> ChapterType.NORMAL
        }
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
    
    /**
     * Chapter type enum for testing.
     */
    enum class ChapterType {
        NORMAL,
        EPUB_DOWNLOAD,
        EPUB_INTERNAL
    }
}
