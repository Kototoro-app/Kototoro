package org.skepsun.kototoro.core.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

/**
 * Debug-only probe for bisecting the shell's never-settling idle redraws: the history and
 * favourites screens keep rendering at display rate while untouched, and every component-level
 * guess so far was disproved, so the search space is cut in half here instead.
 *
 * It renders [ITEM_COUNT] static items with no view model, database, navigation or shell chrome.
 * A frame counter on an untouched screen then separates two otherwise indistinguishable cases:
 *
 * - probe churns -> the root layer drives frames (theme, window, insets, system-bar overrides),
 *   nothing the list screens compose;
 * - probe settles -> the root layer is innocent and the driver is inside the list screens.
 *
 * `--es mode box` is the floor reference: one Text, no grid, no lazy list at all.
 *
 * Measure with `dumpsys gfxinfo <pkg>` frames-rendered deltas over a few idle seconds; the feed
 * screen is the in-app control that settles at 0.
 */
class IdleProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isGrid = intent?.getStringExtra(EXTRA_MODE) != MODE_BOX
        setContent {
            KototoroTheme {
                if (isGrid) {
                    ProbeGrid()
                } else {
                    ProbeBox()
                }
            }
        }
    }

    @Composable
    private fun ProbeGrid() {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(ITEMS, key = { it }) { index ->
                ProbeItem(index)
            }
        }
    }

    @Composable
    private fun ProbeItem(index: Int) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "item $index")
        }
    }

    @Composable
    private fun ProbeBox() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "probe")
        }
    }

    private companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BOX = "box"
    }
}

private const val ITEM_COUNT = 240

private val ITEMS = (0 until ITEM_COUNT).toList()
