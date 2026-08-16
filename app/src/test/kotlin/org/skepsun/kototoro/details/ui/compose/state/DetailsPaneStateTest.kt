package org.skepsun.kototoro.details.ui.compose.state

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailsPaneStateTest {

    @Test
    fun `drag ownership follows settlement target before visual anchor settles`() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val state = createState(scope)

        assertTrue(state.isPaneSurfaceDragEnabled)
        assertFalse(state.isPaneTopBarDragEnabled)

        state.animateTo(CompactDetailsPaneAnchor.Full)

        assertEquals(CompactDetailsPaneAnchor.Collapsed, state.anchor)
        assertFalse(state.isPaneSurfaceDragEnabled)
        assertTrue(state.isPaneTopBarDragEnabled)
        scope.cancel()
    }

    @Test
    fun `grid controls disable both drag regions`() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val state = createState(scope)

        state.showGridSizeControls()

        assertFalse(state.isPaneSurfaceDragEnabled)
        assertFalse(state.isPaneTopBarDragEnabled)
        scope.cancel()
    }

    private fun createState(scope: CoroutineScope) = DetailsPaneState(
        density = Density(1f),
        coroutineScope = scope,
        collapsedHeight = 120.dp,
        paneHeight = 800.dp,
        hoveredHeight = 420.dp,
        initialPageGridSizeValue = 100f,
        initialPageThumbnailAspectRatio = 1f,
        initialSelectedTabId = 0,
    )
}
