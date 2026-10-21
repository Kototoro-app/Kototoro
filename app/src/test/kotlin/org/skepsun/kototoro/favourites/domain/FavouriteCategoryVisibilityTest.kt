package org.skepsun.kototoro.favourites.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.list.domain.ListSortOrder
import java.time.Instant

/**
 * The per-Space category filter (issue #570) is display-only: it must never empty a Space's
 * strip, never hide something the user is looking at, and must be a no-op for a Space that
 * was never configured.
 */
class FavouriteCategoryVisibilityTest {

    private fun category(id: Long, title: String = "Category $id") = FavouriteCategory(
        id = id,
        title = title,
        sortKey = id.toInt(),
        order = ListSortOrder.NEWEST,
        createdAt = Instant.EPOCH,
        isTrackingEnabled = false,
        isVisibleInLibrary = true,
    )

    private val categories = listOf(category(1), category(2), category(3))

    @Test
    fun `unconfigured space shows every category`() {
        val result = filterCategoriesForSpace(categories, hiddenIds = emptySet())

        assertEquals(categories, result)
    }

    @Test
    fun `hidden categories are dropped`() {
        val result = filterCategoriesForSpace(categories, hiddenIds = setOf(2L))

        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test
    fun `checked categories stay listed even when the space hides them`() {
        val result = filterCategoriesForSpace(categories, hiddenIds = setOf(2L), keepIds = setOf(2L))

        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }

    @Test
    fun `a space never hides its own last visible category`() {
        val result = filterCategoriesForSpace(
            categories,
            hiddenIds = setOf(1L, 2L, 3L),
        )

        assertEquals(categories, result)
    }

    @Test
    fun `fallback only rescues an empty result, not a partly hidden list`() {
        val result = filterCategoriesForSpace(
            categories,
            hiddenIds = setOf(1L, 2L, 3L),
            keepIds = setOf(1L),
        )

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `hidden ids that no longer exist are ignored`() {
        val result = filterCategoriesForSpace(categories, hiddenIds = setOf(99L))

        assertEquals(categories, result)
    }

    @Test
    fun `picker lists categories holding favourites first`() {
        val result = orderCategoriesForPicker(categories, favouriteCounts = mapOf(1L to 0, 2L to 7, 3L to 0))

        assertEquals(listOf(2L, 1L, 3L), result.map { it.id })
    }

    @Test
    fun `picker keeps the configured order inside each group`() {
        val result = orderCategoriesForPicker(
            categories = listOf(category(1), category(2), category(3), category(4)),
            favouriteCounts = mapOf(1L to 0, 2L to 3, 3L to 0, 4L to 1),
        )

        assertEquals(listOf(2L, 4L, 1L, 3L), result.map { it.id })
    }

    @Test
    fun `picker leaves the list alone until the counts arrive`() {
        val result = orderCategoriesForPicker(categories, favouriteCounts = emptyMap())

        assertEquals(categories, result)
    }
}
