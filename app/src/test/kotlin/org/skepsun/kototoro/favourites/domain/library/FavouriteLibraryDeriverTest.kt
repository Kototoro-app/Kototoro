package org.skepsun.kototoro.favourites.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.parsers.util.longHashCode
import java.util.Random

/**
 * Pure-function tests for the in-memory favourites derivation
 * (favourites-komikku-alignment plan, section 10.1). Everything here runs on plain
 * data — the deriver has no I/O, no context and no singletons.
 */
class FavouriteLibraryDeriverTest {

    private val actionTagId = 101L
    // Tag identity uses the deterministic TagEntity id ("key_source".longHashCode()),
    // so the seeded facet ids must be derived the same way the filter derives them.
    private val dramaTagId = "drama_TEST".longHashCode()

    private fun row(
        entityId: Long,
        title: String = "Work $entityId",
        altTitle: String? = null,
        coverUrl: String? = "https://example.com/$entityId.jpg",
        sourceName: String = "TEST",
        sourceGroupFlags: Int = 0,
        sourceOriginFlags: Int = 0,
        contentType: ContentType? = ContentType.MANGA,
        publicationState: ContentState? = null,
        isNsfw: Boolean = false,
        rating: Float = -1f,
        readingStatus: ScrobblingStatus = ScrobblingStatus.PLANNED,
        newChapters: Int = 0,
        lastChapterDate: Long = 0L,
        progressPercent: Float? = null,
        lastReadAt: Long? = null,
        projectionCount: Int = 1,
        projectionSourceNames: Set<String> = setOf(sourceName),
        tagIds: Set<Long> = emptySet(),
        displayTags: List<FavouriteCardTag> = emptyList(),
        isDownloaded: Boolean = false,
        hasBrokenProjection: Boolean = false,
        overrideTitle: String? = null,
        overrideCoverUrl: String? = null,
        metadataTrackingService: Int? = null,
        metadataTrackingTitle: String? = null,
        metadataTrackingCoverUrl: String? = null,
        isPinned: Boolean = false,
        createdAt: Long = entityId,
        updatedAt: Long = entityId,
        displayMangaId: Long? = entityId + 10_000L,
        localMangaIds: Set<Long> = setOf(entityId + 10_000L),
    ) = FavouriteCardRow(
        entityId = entityId,
        displayMangaId = displayMangaId,
        localMangaIds = localMangaIds,
        title = title,
        altTitle = altTitle,
        coverUrl = coverUrl,
        author = null,
        sourceName = sourceName,
        sourceGroupFlags = sourceGroupFlags,
        sourceOriginFlags = sourceOriginFlags,
        contentType = contentType,
        publicationState = publicationState,
        isNsfw = isNsfw,
        rating = rating,
        readingStatus = readingStatus,
        newChapters = newChapters,
        lastChapterDate = lastChapterDate,
        progressPercent = progressPercent,
        progressTotalChapters = null,
        lastReadAt = lastReadAt,
        projectionCount = projectionCount,
        projectionSourceNames = projectionSourceNames,
        tagIds = tagIds,
        displayTags = displayTags,
        isDownloaded = isDownloaded,
        hasBrokenProjection = hasBrokenProjection,
        overrideTitle = overrideTitle,
        overrideCoverUrl = overrideCoverUrl,
        metadataTrackingService = metadataTrackingService,
        metadataTrackingTitle = metadataTrackingTitle,
        metadataTrackingCoverUrl = metadataTrackingCoverUrl,
        isPinned = isPinned,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun membership(entityId: Long, categoryId: Long, pinned: Boolean = false, createdAt: Long = entityId) =
        FavouriteMembership(entityId = entityId, categoryId = categoryId, isPinned = pinned, sortKey = 0, createdAt = createdAt, updatedAt = createdAt)

    private fun snapshot(
        rows: List<FavouriteCardRow>,
        membershipsByCategory: Map<Long, List<FavouriteMembership>> = emptyMap(),
    ): FavouriteLibrarySnapshot {
        val rowsByEntityId = rows.associateBy { it.entityId }
        val memberships = membershipsByCategory.ifEmpty {
            // default: every row in category 10
            mapOf(10L to rows.map { membership(it.entityId, 10L, it.isPinned, it.createdAt) })
        }
        return FavouriteLibrarySnapshot(
            rowsByEntityId = rowsByEntityId,
            allEntityIds = rowsByEntityId.keys.sorted(),
            membershipsByCategory = memberships,
            quickFilterMetadata = FavouriteQuickFilterMetadata.Empty,
        )
    }

    // --------------------------------------------------------------- grouping

    @Test
    fun `all slice dedups and category slices use their own memberships`() {
        // entity 1 in two categories; the All slice must contain it once
        val snap = snapshot(
            rows = listOf(row(1), row(2)),
            membershipsByCategory = mapOf(
                10L to listOf(membership(1, 10), membership(2, 10)),
                11L to listOf(membership(1, 11), membership(2, 11)),
            ),
        )
        val state = deriveFavouriteLibraryState(snap, FavouriteLibraryDerivationInput())
        val all = state.visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId)
        assertEquals(2, all.size)
        assertEquals(setOf(1L, 2L), all.toSet())
        assertEquals(2, state.visibleIdsByCategory.getValue(10L).size)
        assertEquals(2, state.visibleIdsByCategory.getValue(11L).size)
    }

    @Test
    fun `pinned leads every order in both slices`() {
        val snap = snapshot(
            rows = listOf(
                row(1, createdAt = 100),
                row(2, createdAt = 50, isPinned = true), // oldest but pinned
                row(3, createdAt = 200),
            ),
            membershipsByCategory = mapOf(
                10L to listOf(membership(1, 10), membership(2, 10, pinned = true), membership(3, 10)),
            ),
        )
        for (order in ListSortOrder.FAVORITES) {
            val state = deriveFavouriteLibraryState(snap, FavouriteLibraryDerivationInput(defaultOrder = order))
            val first = state.visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).first()
            assertEquals(2L, first, "order $order: pinned first in all")
            assertEquals(2L, state.visibleIdsByCategory.getValue(10L).first(), "order $order: pinned first in category")
        }
    }

    @Test
    fun `pinned ids carry the membership flag of each slice`() {
        // Pinned is a property of the (entity, category) pair: the card mapping must see
        // the slice's own flag instead of the representative membership of the entity.
        val snap = snapshot(
            rows = listOf(row(1), row(2), row(3)),
            membershipsByCategory = mapOf(
                10L to listOf(membership(1, 10), membership(2, 10, pinned = true), membership(3, 10)),
                11L to listOf(membership(1, 11), membership(2, 11), membership(3, 11, pinned = true)),
            ),
        )
        val state = deriveFavouriteLibraryState(snap, FavouriteLibraryDerivationInput())
        assertEquals(
            emptySet<Long>(),
            state.pinnedIdsByCategory.getValue(FavouriteLibraryAllCategoryId),
            "the all slice follows the representative membership, which is unpinned here",
        )
        assertEquals(setOf(2L), state.pinnedIdsByCategory.getValue(10L))
        assertEquals(setOf(3L), state.pinnedIdsByCategory.getValue(11L))
    }

    @Test
    fun `pinned ids skip the entries quick filters hid`() {
        val snap = snapshot(
            rows = listOf(row(1, isDownloaded = true), row(2)),
            membershipsByCategory = mapOf(
                10L to listOf(membership(1, 10), membership(2, 10, pinned = true)),
            ),
        )
        val state = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(filters = setOf(ListFilterOption.Downloaded)),
        )
        assertEquals(listOf(1L), state.visibleIdsByCategory.getValue(10L))
        assertEquals(emptySet<Long>(), state.pinnedIdsByCategory.getValue(10L))
    }

    // ---------------------------------------------------------------- sorting

    @Test
    fun `every order produces the documented sequence`() {
        val rows = listOf(
            row(1, title = "Beta", rating = 0.2f, createdAt = 20, updatedAt = 20, progressPercent = 0.1f, lastReadAt = 100, newChapters = 1, lastChapterDate = 10),
            row(2, title = "alpha", rating = 0.9f, createdAt = 10, updatedAt = 30, progressPercent = 0.5f, lastReadAt = 300, newChapters = 5, lastChapterDate = 50),
            row(3, title = "Gamma", rating = 0.5f, createdAt = 30, updatedAt = 10, progressPercent = 0.9f, lastReadAt = 200, newChapters = 3, lastChapterDate = 90),
        )
        val snap = snapshot(rows)

        fun orderOf(order: ListSortOrder): List<Long> = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(defaultOrder = order),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId)

        assertEquals(listOf(2L, 3L, 1L), orderOf(ListSortOrder.RATING))
        assertEquals(listOf(3L, 1L, 2L), orderOf(ListSortOrder.NEWEST))
        assertEquals(listOf(2L, 1L, 3L), orderOf(ListSortOrder.OLDEST))
        assertEquals(listOf(3L, 2L, 1L), orderOf(ListSortOrder.PROGRESS))
        assertEquals(listOf(1L, 2L, 3L), orderOf(ListSortOrder.UNREAD))
        assertEquals(listOf(2L, 3L, 1L), orderOf(ListSortOrder.LAST_READ))
        assertEquals(listOf(1L, 3L, 2L), orderOf(ListSortOrder.LONG_AGO_READ))
        assertEquals(listOf(2L, 3L, 1L), orderOf(ListSortOrder.NEW_CHAPTERS)) // count, then date
        assertEquals(listOf(3L, 2L, 1L), orderOf(ListSortOrder.UPDATED))
        // case-insensitive alphabetic ("alpha" < "Beta" < "Gamma")
        assertEquals(listOf(2L, 1L, 3L), orderOf(ListSortOrder.ALPHABETIC))
        assertEquals(listOf(3L, 1L, 2L), orderOf(ListSortOrder.ALPHABETIC_REVERSE))
    }

    @Test
    fun `entity id breaks ties in every order`() {
        val rows = listOf(
            row(1, title = "Same", rating = 0.5f, createdAt = 5, updatedAt = 5),
            row(2, title = "Same", rating = 0.5f, createdAt = 5, updatedAt = 5),
            row(3, title = "Same", rating = 0.5f, createdAt = 5, updatedAt = 5),
        )
        val snap = snapshot(rows)
        for (order in ListSortOrder.FAVORITES) {
            val list = deriveFavouriteLibraryState(snap, FavouriteLibraryDerivationInput(defaultOrder = order))
                .visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId)
            // ties always resolve to ascending entity ids
            val expected = when (order) {
                ListSortOrder.OLDEST, ListSortOrder.ALPHABETIC -> listOf(1L, 2L, 3L)
                else -> listOf(1L, 2L, 3L)
            }
            assertEquals(expected, list, "order $order")
        }
    }

    @Test
    fun `comparator is transitive and deterministic`() {
        val random = Random(42)
        val rows = (1L..200L).map { id ->
            row(
                id,
                title = "T${random.nextInt(5)}",
                rating = random.nextFloat(),
                createdAt = random.nextLong(),
                updatedAt = random.nextLong(),
                progressPercent = random.nextFloat(),
                lastReadAt = random.nextLong(),
                newChapters = random.nextInt(10),
                lastChapterDate = random.nextLong(),
                isPinned = random.nextBoolean(),
            )
        }
        val snap = snapshot(rows)
        for (order in ListSortOrder.FAVORITES) {
            val sorted = deriveFavouriteLibraryState(snap, FavouriteLibraryDerivationInput(defaultOrder = order))
                .visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId)
            val resorted = sorted.shuffled(Random(7)).let {
                // re-sort the already sorted list through a second derivation with the
                // same input: output must be identical (determinism)
                deriveFavouriteLibraryState(snap, FavouriteLibraryDerivationInput(defaultOrder = order))
                    .visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId)
            }
            assertEquals(sorted, resorted, "order $order deterministic")
            // total order: no duplicates, all rows present
            assertEquals(rows.size, sorted.size)
            assertEquals(rows.size, sorted.toSet().size)
        }
    }

    // ---------------------------------------------------------------- filters

    @Test
    fun `quick filters match the documented semantics`() {
        val rows = listOf(
            row(1, isDownloaded = true),
            row(2, isNsfw = true),
            row(3, newChapters = 4),
            row(4, progressPercent = 1f),
            row(5, projectionCount = 3),
            row(6, hasBrokenProjection = true),
            row(7, publicationState = ContentState.ONGOING),
            row(8, readingStatus = ScrobblingStatus.ON_HOLD),
            row(9, tagIds = setOf(dramaTagId), displayTags = listOf(FavouriteCardTag(dramaTagId, "Drama"))),
            row(10, sourceName = "OTHER", projectionSourceNames = setOf("OTHER")),
            row(11, sourceName = "TEST", projectionSourceNames = setOf("TEST", "OTHER")),
        )
        val snap = snapshot(rows)
        fun idsOf(vararg options: ListFilterOption): Set<Long> = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(filters = options.toSet()),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()

        assertEquals(setOf(1L), idsOf(ListFilterOption.Downloaded))
        assertEquals(setOf(2L), idsOf(ListFilterOption.Macro.NSFW))
        assertEquals((1L..11L).toSet() - 2L, idsOf(ListFilterOption.SFW))
        assertEquals(setOf(3L), idsOf(ListFilterOption.Macro.NEW_CHAPTERS))
        assertEquals(setOf(4L), idsOf(ListFilterOption.Macro.COMPLETED))
        assertEquals(setOf(5L), idsOf(ListFilterOption.Macro.MULTI_PROJECTION))
        assertEquals(setOf(6L), idsOf(ListFilterOption.Macro.BROKEN_PROJECTION))
        assertEquals(setOf(7L), idsOf(ListFilterOption.PublicationState(ContentState.ONGOING)))
        assertEquals(setOf(8L), idsOf(ListFilterOption.ReadingStatus(ScrobblingStatus.ON_HOLD)))
        assertEquals(
            setOf(9L),
            idsOf(ListFilterOption.Tag(ContentTag(title = "Drama", key = "drama", source = TestContentSource))),
        )
        // source filter matches the display OR any bound projection
        assertEquals(
            setOf(10L, 11L),
            idsOf(ListFilterOption.Source(org.skepsun.kototoro.core.model.ContentSource("OTHER"))),
        )
    }

    @Test
    fun `publication states and reading statuses OR within their group`() {
        val rows = listOf(
            row(1, publicationState = ContentState.ONGOING),
            row(2, publicationState = ContentState.PAUSED),
            row(3, publicationState = ContentState.FINISHED),
            row(4, readingStatus = ScrobblingStatus.READING),
            row(5, readingStatus = ScrobblingStatus.COMPLETED),
        )
        val snap = snapshot(rows)
        val states = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(
                filters = setOf(
                    ListFilterOption.PublicationState(ContentState.ONGOING),
                    ListFilterOption.PublicationState(ContentState.PAUSED),
                ),
            ),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(1L, 2L), states)

        val statuses = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(
                filters = setOf(
                    ListFilterOption.ReadingStatus(ScrobblingStatus.READING),
                    ListFilterOption.ReadingStatus(ScrobblingStatus.COMPLETED),
                ),
            ),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(4L, 5L), statuses)
    }

    @Test
    fun `combined filters AND across groups`() {
        val rows = listOf(
            row(1, isDownloaded = true, newChapters = 2),
            row(2, isDownloaded = true, newChapters = 0),
            row(3, isDownloaded = false, newChapters = 2),
        )
        val snap = snapshot(rows)
        val ids = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(
                filters = setOf(ListFilterOption.Downloaded, ListFilterOption.Macro.NEW_CHAPTERS),
            ),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId)
        assertEquals(listOf(1L), ids)
    }

    // -------------------------------------------------------------- visibility

    @Test
    fun `space content types and source names filter the projection set`() {
        val rows = listOf(
            row(1, contentType = ContentType.MANGA, projectionSourceNames = setOf("TEST")),
            row(2, contentType = ContentType.NOVEL, projectionSourceNames = setOf("TEST")),
            row(3, contentType = ContentType.MANGA, projectionSourceNames = setOf("OTHER")),
        )
        val snap = snapshot(rows)

        val mangaSpace = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(allowedContentTypes = setOf(ContentType.MANGA)),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(1L, 3L), mangaSpace)

        val testSources = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(allowedSourceNames = setOf("TEST")),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(1L, 2L), testSources)

        val both = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(
                allowedContentTypes = setOf(ContentType.MANGA),
                allowedSourceNames = setOf("TEST"),
            ),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(1L), both)
    }

    @Test
    fun `source preset filters the displayed projection`() {
        val rows = listOf(
            row(1, sourceName = "TEST", projectionSourceNames = setOf("TEST", "OTHER")),
            row(2, sourceName = "OTHER", projectionSourceNames = setOf("OTHER")),
        )
        val snap = snapshot(rows)
        val ids = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(sourcePresetNames = setOf("TEST")),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        // the preset checks the DISPLAY source only (legacy semantics)
        assertEquals(setOf(1L), ids)
    }

    @Test
    fun `group tab matches persisted type or source group flags`() {
        val rows = listOf(
            row(1, contentType = ContentType.MANGA, sourceGroupFlags = 0),
            row(2, contentType = null, sourceGroupFlags = 1 shl org.skepsun.kototoro.core.jsonsource.ContentGroup.MANGA.ordinal),
            row(3, contentType = ContentType.NOVEL, sourceGroupFlags = 1 shl org.skepsun.kototoro.core.jsonsource.ContentGroup.MANGA.ordinal),
            row(4, contentType = null, sourceGroupFlags = 1 shl org.skepsun.kototoro.core.jsonsource.ContentGroup.NOVEL.ordinal),
        )
        val snap = snapshot(rows)
        val content = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(groupTab = BrowseGroupTab.Content),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        // OR semantics (legacy `!sourceGroupMatches && !typeMatches -> drop`): the
        // persisted MANGA type (1), the MANGA source flag (2) and even a NOVEL type
        // with a MANGA flag (3) all pass; only the NOVEL flag alone misses (4).
        assertEquals(setOf(1L, 2L, 3L), content)
    }

    @Test
    fun `nsfw exclusion and global tag blacklist apply`() {
        val rows = listOf(
            row(1),
            row(2, isNsfw = true),
            row(3, displayTags = listOf(FavouriteCardTag(actionTagId, "Hentai"))),
        )
        val snap = snapshot(rows)

        val sfw = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(excludeNsfw = true),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(1L, 3L), sfw)

        val noHentai = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(globalTagBlacklistTags = listOf("hentai")),
        ).visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).toSet()
        assertEquals(setOf(1L, 2L), noHentai)
    }

    @Test
    fun `per category order overrides the default`() {
        val rows = listOf(row(1, title = "B", createdAt = 1), row(2, title = "A", createdAt = 2))
        val snap = snapshot(
            rows,
            membershipsByCategory = mapOf(10L to listOf(membership(1, 10), membership(2, 10))),
        )
        val state = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(
                defaultOrder = ListSortOrder.NEWEST,
                ordersByCategory = mapOf(10L to ListSortOrder.ALPHABETIC),
            ),
        )
        assertEquals(listOf(2L, 1L), state.visibleIdsByCategory.getValue(10L))
        assertEquals(listOf(2L, 1L), state.visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId))
    }

    @Test
    fun `empty snapshot and single item derive without error`() {
        assertEquals(
            FavouriteLibraryDerivedState.Empty,
            deriveFavouriteLibraryState(FavouriteLibrarySnapshot.Empty, FavouriteLibraryDerivationInput()),
        )
        val single = snapshot(listOf(row(1)))
        val state = deriveFavouriteLibraryState(single, FavouriteLibraryDerivationInput())
        assertEquals(listOf(1L), state.allVisibleIds)
        assertEquals(listOf(1L), state.visibleIdsByCategory.getValue(10L))
    }

    @Test
    fun `ten thousand rows derive within budget`() {
        val rows = (1L..10_000L).map { id ->
            row(
                id,
                title = "Work ${id % 997}",
                rating = (id % 100) / 100f,
                createdAt = id,
                updatedAt = id,
                progressPercent = if (id % 3 == 0L) (id % 10) / 10f else null,
                newChapters = (id % 7).toInt(),
                isPinned = id % 50 == 0L,
                tagIds = if (id % 5 == 0L) setOf(actionTagId) else emptySet(),
                isDownloaded = id % 11 == 0L,
            )
        }
        val snap = snapshot(rows)
        // expected size: NEW_CHAPTERS requires id % 7 != 0 -> 10000 - 1429 + 1
        val expectedSize = (1L..10_000L).count { it % 7L != 0L }
        val t0 = System.nanoTime()
        val state = deriveFavouriteLibraryState(
            snap,
            FavouriteLibraryDerivationInput(
                filters = setOf(ListFilterOption.Macro.NEW_CHAPTERS),
                defaultOrder = ListSortOrder.NEWEST,
            ),
        )
        val deriveMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(expectedSize, state.visibleIdsByCategory.getValue(FavouriteLibraryAllCategoryId).size)
        // 10k derivation budget (section 11.2: filter + sort together <= 150 ms)
        assertTrue(deriveMs < 150, "derivation took ${deriveMs}ms")
    }

    @Test
    fun `category switches never touch the database input`() {
        // The deriver is pure: running the same input twice yields equal results and
        // there is no way to express a DB read through FavouriteLibraryDerivationInput.
        val snap = snapshot(listOf(row(1), row(2)))
        val input = FavouriteLibraryDerivationInput(defaultOrder = ListSortOrder.NEWEST)
        val first = deriveFavouriteLibraryState(snap, input)
        val second = deriveFavouriteLibraryState(snap, input)
        assertEquals(first, second)
        assertTrue(input.filters.isEmpty() && input.ordersByCategory.isEmpty())
    }
}
