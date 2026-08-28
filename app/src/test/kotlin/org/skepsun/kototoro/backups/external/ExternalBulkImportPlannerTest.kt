package org.skepsun.kototoro.backups.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.ContentType

class ExternalBulkImportPlannerTest {

    private fun record(
        title: String,
        url: String,
        sourceName: String = "MIHON_1",
        contentType: ContentType = ContentType.MANGA,
        isFavorite: Boolean = true,
        favoriteTimestamp: Long? = 100L,
        favoriteCategoryOrders: List<Long> = emptyList(),
        historyTimestamp: Long? = null,
        tags: List<String> = emptyList(),
        chaptersCount: Int = 0,
    ): ExternalBackupContentRecord = ExternalBackupContentRecord(
        app = ExternalBackupApp.MIHON,
        sourceName = sourceName,
        contentType = contentType,
        url = url,
        title = title,
        authors = null,
        description = null,
        tags = tags,
        coverUrl = null,
        publicUrl = url,
        state = null,
        isFavorite = isFavorite,
        favoriteTimestamp = favoriteTimestamp,
        favoriteCategoryOrders = favoriteCategoryOrders,
        chaptersCount = chaptersCount,
        readEntriesCount = 0,
        progressPercent = null,
        historyChapterUrl = historyTimestamp?.let { "$url#c1" },
        historyTimestamp = historyTimestamp,
    )

    private fun existingEntity(
        id: Long,
        primaryName: String,
        nameHash: Long = computeNameHash(primaryName),
        contentType: String? = ContentType.MANGA.name,
    ): EntityRecord = EntityRecord(
        id = id,
        type = EntityType.WORK.name,
        contentType = contentType,
        primaryName = primaryName,
        nameHash = nameHash,
        aliases = null,
        createdAt = 0L,
        lastAccessed = 0L,
        accessCount = 1,
    )

    @Test
    fun `duplicate records merge with union semantics`() {
        val first = BulkImportEntry(
            initialRecord = record(
                title = "Same Manga",
                url = "https://a/x",
                favoriteCategoryOrders = listOf(1L),
                historyTimestamp = 500L,
                tags = listOf("Action", "Drama"),
                chaptersCount = 10,
            ),
            mangaId = 1L,
        )
        first.mergeFrom(
            record(
                title = "Same Manga",
                url = "https://a/x",
                favoriteTimestamp = 200L,
                favoriteCategoryOrders = listOf(2L, 1L),
                historyTimestamp = 900L,
                tags = listOf("Drama", "Comedy"),
                chaptersCount = 12,
            ),
        )

        assertTrue(first.record.isFavorite)
        assertEquals(listOf(1L, 2L), first.record.favoriteCategoryOrders)
        assertEquals(900L, first.record.historyTimestamp)
        assertEquals(12, first.record.chaptersCount)
        assertEquals(listOf("Action", "Drama", "Comedy"), first.record.tags)
        // earliest positive favourite timestamp wins
        assertEquals(100L, first.record.favoriteTimestamp)
    }

    @Test
    fun `merge keeps history of the newer record even when first record had none`() {
        val first = BulkImportEntry(record("T", "u1", isFavorite = false), mangaId = 1L)
        first.mergeFrom(record("T", "u1", isFavorite = false, historyTimestamp = 42L))
        assertEquals(42L, first.record.historyTimestamp)
    }

    @Test
    fun `fresh entries get clean hash and deterministic provisional entities`() {
        val a = BulkImportEntry(record("Alpha", "https://a/alpha"), mangaId = 1L)
        val b = BulkImportEntry(record("Beta", "https://a/beta"), mangaId = 2L)

        val newEntities = planWorkEntityAssignment(listOf(a, b), emptyMap(), now = 7L)

        assertEquals(2, newEntities.size)
        assertTrue(a.isNewEntity)
        assertTrue(b.isNewEntity)
        assertEquals(newEntities[0].id, 0L) // not yet inserted; ids assigned by caller
        assertEquals(computeNameHash("Alpha"), newEntities[0].nameHash)
        assertEquals(EntityType.WORK.name, newEntities[0].type)
        assertEquals(ContentType.MANGA.name, newEntities[0].contentType)
        assertEquals(7L, newEntities[0].createdAt)
        assertNotEquals(newEntities[0].syncId, newEntities[1].syncId)
    }

    @Test
    fun `within-batch same-title entries get salted hash but identical primary name`() {
        val a = BulkImportEntry(record("Same", "https://s/1", sourceName = "MIHON_1"), mangaId = 1L)
        val b = BulkImportEntry(record("Same", "https://s/2", sourceName = "MIHON_2"), mangaId = 2L)

        val newEntities = planWorkEntityAssignment(listOf(a, b), emptyMap(), now = 7L)

        assertEquals(2, newEntities.size)
        assertEquals(computeNameHash("Same"), newEntities[0].nameHash)
        assertNotEquals(newEntities[0].nameHash, newEntities[1].nameHash)
        assertEquals("Same", newEntities[1].primaryName)
        assertTrue(a.isNewEntity && b.isNewEntity)
    }

    @Test
    fun `exact normalized-name match attaches to existing entity`() {
        val existing = existingEntity(id = 99L, primaryName = "One Piece")
        val entry = BulkImportEntry(record("one   piece!", "https://a/op"), mangaId = 1L)

        val newEntities = planWorkEntityAssignment(
            entries = listOf(entry),
            existingEntitiesByHash = mapOf(computeNameHash("One Piece") to listOf(existing)),
            now = 7L,
        )

        assertTrue(newEntities.isEmpty())
        assertFalse(entry.isNewEntity)
        assertNull(entry.newEntityRecord)
        assertEquals(99L, entry.entityId)
    }

    @Test
    fun `content type mismatch prevents attach and creates a new entity`() {
        val existing = existingEntity(id = 99L, primaryName = "Fate", contentType = ContentType.MANGA.name)
        val entry = BulkImportEntry(
            record("Fate", "https://v/fate", contentType = ContentType.VIDEO),
            mangaId = 1L,
        )

        val newEntities = planWorkEntityAssignment(
            entries = listOf(entry),
            existingEntitiesByHash = mapOf(computeNameHash("Fate") to listOf(existing)),
            now = 7L,
        )

        assertEquals(1, newEntities.size)
        assertTrue(entry.isNewEntity)
        assertEquals(0L, entry.entityId)
    }

    @Test
    fun `non matching hash never attaches`() {
        val existing = existingEntity(id = 99L, primaryName = "Berserk")
        val entry = BulkImportEntry(record("Vagabond", "https://a/v"), mangaId = 1L)

        val newEntities = planWorkEntityAssignment(
            entries = listOf(entry),
            existingEntitiesByHash = mapOf(computeNameHash("Berserk") to listOf(existing)),
            now = 7L,
        )

        assertEquals(1, newEntities.size)
        assertTrue(entry.isNewEntity)
    }

    @Test
    fun `existing local binding takes precedence over title attach and new entity`() {
        val existingByName = existingEntity(id = 77L, primaryName = "Frieren")
        val boundOwner = existingEntity(id = 88L, primaryName = "Sousou no Frieren (old title)")
        val entry = BulkImportEntry(record("Frieren", "https://a/frieren"), mangaId = 1L)

        val newEntities = planWorkEntityAssignment(
            entries = listOf(entry),
            existingEntitiesByHash = mapOf(computeNameHash("Frieren") to listOf(existingByName)),
            now = 7L,
            localBindingByMangaId = mapOf(1L to 88L),
        )

        assertTrue(newEntities.isEmpty())
        assertFalse(entry.isNewEntity)
        // local binding owner wins over the exact-name attach target
        assertEquals(88L, entry.entityId)
    }
}
