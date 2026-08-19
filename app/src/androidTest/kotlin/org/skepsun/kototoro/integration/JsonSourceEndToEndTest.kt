package org.skepsun.kototoro.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.prefs.AppSettings

/**
 * End-to-end integration test for JSON source functionality.
 * Tests the complete flow from import to usage.
 * 
 * **Feature: runtime-json-parser, Test: End-to-End Integration**
 * **Validates: Requirements 1.1, 1.2, 1.3, 2.1, 2.2, 5.1, 5.2, 6.1**
 */
@RunWith(AndroidJUnit4::class)
class JsonSourceEndToEndTest {

    private lateinit var context: Context
    private lateinit var database: MangaDatabase
    private lateinit var jsonSourceManager: JsonSourceManager
    private lateinit var sourceTypeIdentifier: SourceTypeIdentifier
    private lateinit var sourceGroupManager: SourceGroupManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // MangaDatabase.getInstance() was removed; other integration tests build an
        // in-memory Room database directly (see ProgressRestorationIntegrationTest).
        database = Room.inMemoryDatabaseBuilder(
            context,
            MangaDatabase::class.java,
        ).allowMainThreadQueries().build()

        val appSettings = AppSettings(context)
        // DynamicParserFactory was removed; JsonSourceManager now only requires the
        // DAO and AppSettings (optional JS/HTTP collaborators default to null).
        jsonSourceManager = JsonSourceManager(
            jsonSourceDao = database.getJsonSourceDao(),
            appSettings = appSettings,
        )

        sourceTypeIdentifier = SourceTypeIdentifier()
        sourceGroupManager = SourceGroupManager(
            sourceTypeIdentifier = sourceTypeIdentifier,
            jsonSourceManager = jsonSourceManager,
            json = Json { ignoreUnknownKeys = true; isLenient = true },
        )

        // Clean up any existing test data
        runBlocking {
            database.getJsonSourceDao().observeAll().first().forEach { source ->
                if (source.name.contains("Test")) {
                    database.getJsonSourceDao().deleteById(source.id)
                }
            }
        }
    }

    @After
    fun tearDown() {
        // Clean up test data
        runBlocking {
            database.getJsonSourceDao().observeAll().first().forEach { source ->
                if (source.name.contains("Test")) {
                    database.getJsonSourceDao().deleteById(source.id)
                }
            }
        }
        database.close()
    }

    @Test
    fun testCompleteImportFlow() = runBlocking {
        // Test complete import flow from JSON to database
        val legadoJson = """
            [{
                "bookSourceName": "Test Novel Source",
                "bookSourceUrl": "https://example.com",
                "bookSourceType": 0,
                "searchUrl": "https://example.com/search?q={{key}}",
                "ruleSearch": {
                    "bookList": "div.book-item",
                    "name": "h3.title@text",
                    "author": "span.author@text",
                    "bookUrl": "a@href",
                    "coverUrl": "img@src"
                },
                "ruleBookInfo": {
                    "name": "h1.book-title@text",
                    "author": "div.author@text",
                    "intro": "div.intro@text"
                },
                "ruleToc": {
                    "chapterList": "div.chapter-list a",
                    "chapterName": "@text",
                    "chapterUrl": "@href"
                },
                "ruleContent": {
                    "content": "div.content@text"
                }
            }]
        """.trimIndent()

        // Step 1: Import JSON
        val importResult = jsonSourceManager.importLegadoJson(legadoJson)
        assertTrue("Import should succeed", importResult.isSuccess)
        assertEquals("Should import 1 source", 1, importResult.getOrNull())

        // Step 2: Verify source is in database
        val allSources = database.getJsonSourceDao().observeAll().first()
        val testSource = allSources.find { it.name == "Test Novel Source" }
        assertNotNull("Source should be in database", testSource)
        assertEquals("Source type should be LEGADO", JsonSourceType.LEGADO, testSource?.type)
        assertTrue("Source should be enabled by default", testSource?.enabled == true)

        // Step 3: Verify source identifier format
        val sourceId = testSource?.id ?: ""
        assertTrue("Source ID should start with JSON_", sourceId.startsWith("JSON_"))
        assertTrue("Source type should be identified as JSON",
            sourceTypeIdentifier.isJsonSource(sourceId))

        // Step 4: Test source toggle
        jsonSourceManager.toggleSource(sourceId, false)
        val disabledSource = database.getJsonSourceDao().getById(sourceId)
        assertFalse("Source should be disabled", disabledSource?.enabled == true)

        jsonSourceManager.toggleSource(sourceId, true)
        val enabledSource = database.getJsonSourceDao().getById(sourceId)
        assertTrue("Source should be enabled again", enabledSource?.enabled == true)

        // Step 5: Verify source appears in enabled sources
        val enabledSources = database.getJsonSourceDao().observeEnabled().first()
        assertTrue("Enabled sources should contain test source",
            enabledSources.any { it.id == sourceId })

        // Step 6: Test source deletion
        jsonSourceManager.deleteSource(sourceId)
        val deletedSource = database.getJsonSourceDao().getById(sourceId)
        assertNull("Source should be deleted", deletedSource)
    }

    @Test
    fun testSourceGrouping() = runBlocking {
        // Import multiple sources of different types
        val novelJson = """
            [{
                "bookSourceName": "Test Novel Site",
                "bookSourceUrl": "https://novel.example.com",
                "bookSourceType": 0,
                "searchUrl": "https://novel.example.com/search"
            }]
        """.trimIndent()

        val mangaJson = """
            [{
                "bookSourceName": "Test Manga Site",
                "bookSourceUrl": "https://manga.example.com",
                "bookSourceType": 2,
                "searchUrl": "https://manga.example.com/search"
            }]
        """.trimIndent()

        // Import both sources
        jsonSourceManager.importLegadoJson(novelJson)
        jsonSourceManager.importLegadoJson(mangaJson)

        // Get all sources
        val allSources = database.getJsonSourceDao().observeAll().first()
        val novelSource = allSources.find { it.name == "Test Novel Site" }
        val mangaSource = allSources.find { it.name == "Test Manga Site" }

        assertNotNull("Novel source should exist", novelSource)
        assertNotNull("Manga source should exist", mangaSource)

        // Verify grouping logic
        // Note: Actual grouping depends on implementation details
        // This test verifies that sources can be retrieved and identified
        assertTrue("Novel source should be JSON type",
            sourceTypeIdentifier.isJsonSource(novelSource?.id ?: ""))
        assertTrue("Manga source should be JSON type",
            sourceTypeIdentifier.isJsonSource(mangaSource?.id ?: ""))
    }

    @Test
    fun testErrorScenarios() = runBlocking {
        // Test 1: Invalid JSON format
        val invalidJson = "{ this is not valid json }"
        val result1 = jsonSourceManager.importLegadoJson(invalidJson)
        assertTrue("Invalid JSON should fail", result1.isFailure)

        // Test 2: Missing required fields
        // The current validator only requires a non-blank bookSourceName, so provide a
        // source that omits it (bookSourceUrl alone) to keep the "missing fields" intent.
        val missingFieldsJson = """
            [{
                "bookSourceUrl": "https://incomplete.example.com"
            }]
        """.trimIndent()
        val result2 = jsonSourceManager.importLegadoJson(missingFieldsJson)
        assertTrue("Missing fields should fail", result2.isFailure)

        // Test 3: Empty JSON array
        // Current contract: an empty array is rejected as a validation failure
        // (previously the manager accepted it as "0 imported").
        val emptyJson = "[]"
        val result3 = jsonSourceManager.importLegadoJson(emptyJson)
        assertTrue("Empty array should fail", result3.isFailure)

        // Test 4: Delete non-existent source (should not crash)
        try {
            jsonSourceManager.deleteSource("NON_EXISTENT_ID")
            // Should complete without exception
        } catch (e: Exception) {
            fail("Deleting non-existent source should not throw exception")
        }

        // Test 5: Toggle non-existent source (should not crash)
        try {
            jsonSourceManager.toggleSource("NON_EXISTENT_ID", true)
            // Should complete without exception
        } catch (e: Exception) {
            fail("Toggling non-existent source should not throw exception")
        }
    }

    @Test
    fun testSearchAndBrowseFunctionality() = runBlocking {
        // Import a test source
        val testJson = """
            [{
                "bookSourceName": "Test Browse Source",
                "bookSourceUrl": "https://browse.example.com",
                "bookSourceType": 0,
                "searchUrl": "https://browse.example.com/search?q={{key}}"
            }]
        """.trimIndent()

        jsonSourceManager.importLegadoJson(testJson)

        // Verify source appears in repository
        val allSources = database.getJsonSourceDao().observeAll().first()
        val testSource = allSources.find { it.name == "Test Browse Source" }
        assertNotNull("Source should be available for browsing", testSource)

        // Verify enabled sources include JSON sources
        val enabledSources = database.getJsonSourceDao().observeEnabled().first()
        assertTrue("Enabled sources should include test source",
            enabledSources.any { it.name == "Test Browse Source" })
    }

    @Test
    fun testDuplicateSourceHandling() = runBlocking {
        // Import same source twice
        val testJson = """
            [{
                "bookSourceName": "Duplicate Test Source",
                "bookSourceUrl": "https://duplicate.example.com",
                "searchUrl": "https://duplicate.example.com/search"
            }]
        """.trimIndent()

        // First import
        val result1 = jsonSourceManager.importLegadoJson(testJson)
        assertTrue("First import should succeed", result1.isSuccess)

        // Second import (should replace)
        val result2 = jsonSourceManager.importLegadoJson(testJson)
        assertTrue("Second import should succeed", result2.isSuccess)

        // Verify only one source exists
        val allSources = database.getJsonSourceDao().observeAll().first()
        val duplicateSources = allSources.filter { it.name == "Duplicate Test Source" }
        assertEquals("Should have exactly one source", 1, duplicateSources.size)
    }

    @Test
    fun testSourcePersistenceAcrossRestarts() = runBlocking {
        // Import a source
        val testJson = """
            [{
                "bookSourceName": "Persistence Test Source",
                "bookSourceUrl": "https://persist.example.com",
                "searchUrl": "https://persist.example.com/search"
            }]
        """.trimIndent()

        jsonSourceManager.importLegadoJson(testJson)

        // Get source ID
        val sources = database.getJsonSourceDao().observeAll().first()
        val testSource = sources.find { it.name == "Persistence Test Source" }
        val sourceId = testSource?.id ?: ""

        // Disable the source
        jsonSourceManager.toggleSource(sourceId, false)

        // Simulate restart by creating a new manager instance against the same database
        val newManager = JsonSourceManager(
            jsonSourceDao = database.getJsonSourceDao(),
            appSettings = AppSettings(context),
        )

        // Verify source state persisted
        val persistedSource = database.getJsonSourceDao().getById(sourceId)
        assertNotNull("Source should persist", persistedSource)
        assertFalse("Disabled state should persist", persistedSource?.enabled == true)
        // The new manager instance observes the same persisted sources
        assertTrue(
            "New manager should observe the persisted source",
            newManager.observeAllJsonSources().first().any { it.id == sourceId },
        )
    }
}
