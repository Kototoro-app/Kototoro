package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.HeroTransitionPhase

/**
 * State holder for the KototoroApp chrome: search overlay visibility, details chrome
 * transition flags and the shared-transition scope. Creating the [MutableState]s in
 * [rememberKototoroAppChromeState] preserves the previous per-composition
 * remember/rememberSaveable semantics while hoisting the declarations out of the
 * app-shell composable.
 */
class KototoroAppChromeState internal constructor(
    val isSearchOverlayVisible: MutableState<Boolean>,
    val isSearchOverlayMounted: MutableState<Boolean>,
    val searchOverlayInitialQuery: MutableState<String>,
    val isSearchOverlayQueryCommitted: MutableState<Boolean>,
    val isDetailsChromeTransitionPending: MutableState<Boolean>,
    val detailsBottomPanelExpansion: MutableState<Float>,
    val detailsBottomObstruction: MutableState<Dp>,
    val detailsBottomPanelRoute: MutableState<String?>,
    val materialTopBarScrollEnabled: MutableState<Boolean>,
    val lastChromeTopBarOwnerKey: MutableState<String?>,
    val lastHeroTransitionStartedAtMs: MutableState<Long>,
    val heroTransitionPhase: MutableState<HeroTransitionPhase>,
    val chromeSharedTransitionScope: MutableState<SharedTransitionScope?>,
)

@Composable
fun rememberKototoroAppChromeState(): KototoroAppChromeState {
    val isSearchOverlayVisible = rememberSaveable { mutableStateOf(false) }
    val isSearchOverlayMounted = rememberSaveable { mutableStateOf(false) }
    val searchOverlayInitialQuery = rememberSaveable { mutableStateOf("") }
    val isSearchOverlayQueryCommitted = rememberSaveable { mutableStateOf(false) }
    val isDetailsChromeTransitionPending = rememberSaveable { mutableStateOf(false) }
    val detailsBottomPanelExpansion = remember { mutableFloatStateOf(0f) }
    val detailsBottomObstruction = remember { mutableStateOf(0.dp) }
    val detailsBottomPanelRoute = remember { mutableStateOf<String?>(null) }
    val materialTopBarScrollEnabled = remember { mutableStateOf(true) }
    val lastChromeTopBarOwnerKey = rememberSaveable { mutableStateOf<String?>(null) }
    val lastHeroTransitionStartedAtMs = remember { mutableLongStateOf(0L) }
    val heroTransitionPhase = rememberSaveable { mutableStateOf(HeroTransitionPhase.Idle) }
    val chromeSharedTransitionScope = remember { mutableStateOf<SharedTransitionScope?>(null) }
    return KototoroAppChromeState(
        isSearchOverlayVisible = isSearchOverlayVisible,
        isSearchOverlayMounted = isSearchOverlayMounted,
        searchOverlayInitialQuery = searchOverlayInitialQuery,
        isSearchOverlayQueryCommitted = isSearchOverlayQueryCommitted,
        isDetailsChromeTransitionPending = isDetailsChromeTransitionPending,
        detailsBottomPanelExpansion = detailsBottomPanelExpansion,
        detailsBottomObstruction = detailsBottomObstruction,
        detailsBottomPanelRoute = detailsBottomPanelRoute,
        materialTopBarScrollEnabled = materialTopBarScrollEnabled,
        lastChromeTopBarOwnerKey = lastChromeTopBarOwnerKey,
        lastHeroTransitionStartedAtMs = lastHeroTransitionStartedAtMs,
        heroTransitionPhase = heroTransitionPhase,
        chromeSharedTransitionScope = chromeSharedTransitionScope,
    )
}
