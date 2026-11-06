package org.skepsun.kototoro.main.ui.compose

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.list.ui.compose.ContentCollectionGrid
import org.skepsun.kototoro.main.ui.navigation3.DetailsNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.MainNavState
import org.skepsun.kototoro.main.ui.navigation3.rememberMainNavState

/** Exercises the shell's real navigation container and saved grid state without changing library data. */
@RunWith(AndroidJUnit4::class)
class MainShellScrollPositionTest {

    @Test
    fun tabletGridKeepsItsViewportAfterDetailsReturn() = verifyViewport()

    @Test
    fun tabletWithLeftNavigationKeepsItsViewportAfterDetailsReturn() =
        verifyViewport(edge = NavigationInsetEdge.LEFT)

    @Test
    fun tabletWithPageTurnKeepsItsViewportAfterDetailsReturn() =
        verifyViewport(transition = ListToDetailsTransition.LEGACY_SLIDE)

    @Test
    fun phoneGridKeepsItsViewportAfterDetailsReturn() = verifyViewport(isTablet = false)

    private fun verifyViewport(
        isTablet: Boolean = true,
        edge: NavigationInsetEdge = NavigationInsetEdge.RIGHT,
        transition: ListToDetailsTransition = ListToDetailsTransition.HERO_EXPAND,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val navigation = AtomicReference<MainNavState>()
        val grid = AtomicReference<LazyGridState>()
        val intent = Intent(instrumentation.targetContext, IdleProbeActivity::class.java)
            .putExtra("scene_recovery", true)
        ActivityScenario.launch<IdleProbeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        val navState = rememberMainNavState(FavoritesNavKey)
                        SideEffect { navigation.set(navState) }
                        Box(Modifier.requiredSize(720.dp, 480.dp)) {
                            MainRouteScene(
                                navState = navState,
                                isLandscapeNavigation = isTablet,
                                landscapeNavigationPadding = 80.dp,
                                navigationInsetEdge = edge,
                                detailsTransitionStyle = transition,
                            ) { key ->
                                if (key == FavoritesNavKey) {
                                    val state = rememberLazyGridState(25, 48)
                                    SideEffect { grid.set(state) }
                                    ContentCollectionGrid(
                                        count = 120,
                                        key = { it },
                                        columns = GridCells.Adaptive(120.dp),
                                        state = state,
                                        modifier = Modifier.fillMaxSize(),
                                    ) { index ->
                                        Box(Modifier.height(160.dp)) { Text("Work $index") }
                                    }
                                } else {
                                    Text("Details")
                                }
                            }
                        }
                    }
                }
            }
            val before = settledViewport(grid)
            assertTrue("Start away from the first row", before.first >= 20)
            instrumentation.runOnMainSync { navigation.get().push(DetailsNavKey(30)) }
            SystemClock.sleep(900)
            instrumentation.runOnMainSync { assertTrue(navigation.get().pop()) }
            SystemClock.sleep(900)
            assertEquals("Opening details must preserve the tablet grid index and pixel offset",
                before, settledViewport(grid))
        }
    }

    private fun settledViewport(grid: AtomicReference<LazyGridState>): Pair<Int, Int> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: Pair<Int, Int>? = null
        var unchanged = 0
        while (SystemClock.uptimeMillis() < deadline) {
            var current: Pair<Int, Int>? = null
            instrumentation.runOnMainSync {
                grid.get()?.takeIf { it.layoutInfo.visibleItemsInfo.isNotEmpty() }?.let {
                    current = it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset
                }
            }
            unchanged = if (current != null && current == previous) unchanged + 1 else 0
            if (unchanged >= 5) return checkNotNull(current)
            previous = current
            SystemClock.sleep(50)
        }
        error("Grid viewport did not settle: $previous")
    }
}
