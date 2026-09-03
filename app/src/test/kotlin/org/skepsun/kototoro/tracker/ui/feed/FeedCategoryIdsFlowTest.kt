package org.skepsun.kototoro.tracker.ui.feed

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.tracker.domain.model.ContentTracking

class FeedCategoryIdsFlowTest {

    @Test
    fun `all subscriptions do not load favourite category membership`() = runTest {
        var subscriptions = 0

        val result = observeFeedCategoryIdsForSelection(flowOf(FavouriteCategory.NO_ID)) {
            subscriptions += 1
            flowOf(mapOf("source|url" to setOf(1L)))
        }.first()

        assertTrue(result.isEmpty())
        assertEquals(0, subscriptions)
    }

    @Test
    fun `specific category loads favourite category membership`() = runTest {
        var subscriptions = 0
        val expected = mapOf("source|url" to setOf(1L))

        val result = observeFeedCategoryIdsForSelection(flowOf(1L)) {
            subscriptions += 1
            flowOf(expected)
        }.first()

        assertEquals(expected, result)
        assertEquals(1, subscriptions)
    }

    @Test
    fun `disabled header does not load updated content`() = runTest {
        var subscriptions = 0

        val result = observeFeedHeaderContent(
            isHeaderEnabled = flowOf(false),
            filters = flowOf(emptySet()),
        ) {
            subscriptions += 1
            flowOf(emptyList<ContentTracking>())
        }.first()

        assertTrue(result.isEmpty())
        assertEquals(0, subscriptions)
    }

    @Test
    fun `enabled header loads updated content once`() = runTest {
        var subscriptions = 0

        observeFeedHeaderContent(
            isHeaderEnabled = flowOf(true),
            filters = flowOf(emptySet()),
        ) {
            subscriptions += 1
            flowOf(emptyList<ContentTracking>())
        }.first()

        assertEquals(1, subscriptions)
    }
}
