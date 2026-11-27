package org.skepsun.kototoro.explore.data

import androidx.core.content.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ExplorePage
import org.skepsun.kototoro.core.util.ext.getEnumValue
import org.skepsun.kototoro.core.util.ext.putEnumValue
import org.skepsun.kototoro.sync.google.domain.MemorySyncPreferences
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryFeedConfigRepositoryTest {

    private fun createRepository(
        prefs: MemorySyncPreferences = MemorySyncPreferences(),
    ): DiscoveryFeedConfigRepository {
        val idCounter = AtomicInteger(0)
        return DiscoveryFeedConfigRepository(
            prefs = prefs,
            idGenerator = { "row-${idCounter.incrementAndGet()}" },
        )
    }

    @Test
    fun `default config is empty list`() = runTest {
        val repository = createRepository()

        assertEquals(emptyList<DiscoveryFeedRow>(), repository.getRows())
        assertEquals(emptyList<DiscoveryFeedRow>(), repository.observeRows().first())
    }

    @Test
    fun `addRow appends sequential sortOrder and normalizes fields`() {
        val repository = createRepository()

        val first = repository.addRow(
            sourceName = "  MANGADEX  ",
            displayName = "  Popular Action  ",
            savedFilterId = 42,
            query = "   ",
        )
        val second = repository.addRow(
            sourceName = "DMZJ",
            displayName = "   ",
            query = "  frieren  ",
            isHidden = true,
        )

        assertEquals(
            DiscoveryFeedRow(
                id = "row-1",
                sourceName = "MANGADEX",
                savedFilterId = 42,
                query = null,
                displayName = "Popular Action",
                sortOrder = 0,
                isHidden = false,
            ),
            first,
        )
        assertEquals(
            DiscoveryFeedRow(
                id = "row-2",
                sourceName = "DMZJ",
                savedFilterId = null,
                query = "frieren",
                displayName = "DMZJ",
                sortOrder = 1,
                isHidden = true,
            ),
            second,
        )
        assertEquals(listOf(first, second), repository.getRows())
    }

    @Test
    fun `addRow rejects blank sourceName`() {
        val repository = createRepository()
        assertThrows(IllegalArgumentException::class.java) {
            repository.addRow(sourceName = "   ", displayName = "Test")
        }
    }

    @Test
    fun `updateRow updates displayName filter query and optional source while preserving order and hidden`() {
        val repository = createRepository()
        val added = repository.addRow(
            sourceName = "SOURCE_A",
            displayName = "Initial",
            savedFilterId = 10,
            isHidden = true,
        )

        val updated = repository.updateRow(
            rowId = added.id,
            displayName = "  Renamed Query  ",
            savedFilterId = null,
            query = "  isekai  ",
            sourceName = "SOURCE_B",
        )

        assertEquals(
            DiscoveryFeedRow(
                id = added.id,
                sourceName = "SOURCE_B",
                savedFilterId = null,
                query = "isekai",
                displayName = "Renamed Query",
                sortOrder = 0,
                isHidden = true,
            ),
            updated,
        )
        assertEquals(listOf(updated), repository.getRows())
        assertNull(repository.updateRow(rowId = "missing", displayName = "Nope"))
    }

    @Test
    fun `reorderRows updates sortOrder ignores unknown ids and keeps unlisted rows at end`() {
        val repository = createRepository()
        val r1 = repository.addRow(sourceName = "S1", displayName = "One")
        val r2 = repository.addRow(sourceName = "S2", displayName = "Two")
        val r3 = repository.addRow(sourceName = "S3", displayName = "Three")
        val r4 = repository.addRow(sourceName = "S4", displayName = "Four")

        val reordered = repository.reorderRows(listOf(r3.id, "unknown", r1.id, r3.id))

        assertEquals(listOf(r3.id, r1.id, r2.id, r4.id), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), reordered.map { it.sortOrder })
        assertEquals(reordered, repository.getRows())
    }

    @Test
    fun `toggleRowHidden flips visibility and deleteRow removes and reindexes`() {
        val repository = createRepository()
        val r1 = repository.addRow(sourceName = "S1", displayName = "One")
        val r2 = repository.addRow(sourceName = "S2", displayName = "Two")
        val r3 = repository.addRow(sourceName = "S3", displayName = "Three")

        val hidden = repository.toggleRowHidden(r2.id)
        assertTrue(hidden!!.isHidden)
        assertTrue(repository.getRows().first { it.id == r2.id }.isHidden)

        val unhidden = repository.toggleRowHidden(r2.id)
        assertFalse(unhidden!!.isHidden)
        assertNull(repository.toggleRowHidden("non-existent"))

        assertTrue(repository.deleteRow(r1.id))
        assertFalse(repository.deleteRow(r1.id))

        val remaining = repository.getRows()
        assertEquals(listOf(r2.id, r3.id), remaining.map { it.id })
        assertEquals(listOf(0, 1), remaining.map { it.sortOrder })
    }

    @Test
    fun `json serialization round-trips all fields ignores unknown keys and handles malformed payload`() {
        val prefs = MemorySyncPreferences()
        val repository = createRepository(prefs)
        val rows = listOf(
            DiscoveryFeedRow(
                id = "id-1",
                sourceName = "MANGADEX",
                savedFilterId = 12345,
                query = null,
                displayName = "Saved Filter",
                sortOrder = 0,
                isHidden = false,
            ),
            DiscoveryFeedRow(
                id = "id-2",
                sourceName = "COPYMANGA",
                savedFilterId = null,
                query = "mecha",
                displayName = "Mecha Search",
                sortOrder = 1,
                isHidden = true,
            ),
        )

        val encoded = repository.encodeRows(rows)
        val decoded = repository.decodeRows(encoded)
        assertEquals(rows, decoded)

        // Forward compatibility: unknown JSON fields are ignored and sort_order sorts output.
        val withFutureField = """
            [
              {"id":"b","source_name":"SB","display_name":"B","sort_order":2,"future_flag":true},
              {"id":"a","source_name":"SA","display_name":"A","sort_order":0,"is_hidden":true}
            ]
        """.trimIndent()
        val parsedFuture = repository.decodeRows(withFutureField)
        assertEquals(listOf("a", "b"), parsedFuture.map { it.id })
        assertTrue(parsedFuture[0].isHidden)
        assertFalse(parsedFuture[1].isHidden)

        // Malformed JSON safely degrades to emptyList.
        prefs.edit { putString(DiscoveryFeedConfigRepository.KEY_ROWS, "{not valid json") }
        assertEquals(emptyList<DiscoveryFeedRow>(), repository.getRows())
    }

    @Test
    fun `observeRows emits changes on mutations and prefs clear`() = runTest {
        val prefs = MemorySyncPreferences()
        val repository = createRepository(prefs)
        val emissions = mutableListOf<List<DiscoveryFeedRow>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeRows().collect { emissions += it }
        }

        val added = repository.addRow(sourceName = "S1", displayName = "Row 1")
        repository.toggleRowHidden(added.id)
        prefs.edit { clear() }

        assertEquals(4, emissions.size)
        assertEquals(emptyList<DiscoveryFeedRow>(), emissions[0])
        assertEquals(listOf(added), emissions[1])
        assertTrue(emissions[2].single().isHidden)
        assertEquals(emptyList<DiscoveryFeedRow>(), emissions[3])
        collector.cancel()
    }

    @Test
    fun `explore page preference defaults to SOURCES and persists DISCOVERY`() {
        val prefs = MemorySyncPreferences()
        assertEquals(ExplorePage.SOURCES, prefs.getEnumValue(AppSettings.KEY_EXPLORE_PAGE, ExplorePage.SOURCES))

        prefs.edit { putEnumValue(AppSettings.KEY_EXPLORE_PAGE, ExplorePage.DISCOVERY) }
        assertEquals(ExplorePage.DISCOVERY, prefs.getEnumValue(AppSettings.KEY_EXPLORE_PAGE, ExplorePage.SOURCES))
    }
}
