package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
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
 *
 * Exposes read-only [State] plus targeted setters so callers cannot mutate state
 * out from under the owner; [Stable] lets Compose skip redundant reads of the holder.
 */
@Stable
class KototoroAppChromeState internal constructor(
    private val _isSearchOverlayVisible: MutableState<Boolean>,
    private val _isSearchOverlayMounted: MutableState<Boolean>,
    private val _searchOverlayInitialQuery: MutableState<String>,
    private val _isSearchOverlayQueryCommitted: MutableState<Boolean>,
    private val _isDetailsChromeTransitionPending: MutableState<Boolean>,
    private val _detailsBottomPanelExpansion: MutableState<Float>,
    private val _detailsBottomObstruction: MutableState<Dp>,
    private val _detailsBottomPanelRoute: MutableState<String?>,
    private val _materialTopBarScrollEnabled: MutableState<Boolean>,
    private val _lastChromeTopBarOwnerKey: MutableState<String?>,
    private val _lastHeroTransitionStartedAtMs: MutableState<Long>,
    private val _heroTransitionPhase: MutableState<HeroTransitionPhase>,
    private val _chromeSharedTransitionScope: MutableState<SharedTransitionScope?>,
) {
    val isSearchOverlayVisible: State<Boolean> = _isSearchOverlayVisible
    val isSearchOverlayMounted: State<Boolean> = _isSearchOverlayMounted
    val searchOverlayInitialQuery: State<String> = _searchOverlayInitialQuery
    val isSearchOverlayQueryCommitted: State<Boolean> = _isSearchOverlayQueryCommitted
    val isDetailsChromeTransitionPending: State<Boolean> = _isDetailsChromeTransitionPending
    val detailsBottomPanelExpansion: State<Float> = _detailsBottomPanelExpansion
    val detailsBottomObstruction: State<Dp> = _detailsBottomObstruction
    val detailsBottomPanelRoute: State<String?> = _detailsBottomPanelRoute
    val materialTopBarScrollEnabled: State<Boolean> = _materialTopBarScrollEnabled
    val lastChromeTopBarOwnerKey: State<String?> = _lastChromeTopBarOwnerKey
    val lastHeroTransitionStartedAtMs: State<Long> = _lastHeroTransitionStartedAtMs
    val heroTransitionPhase: State<HeroTransitionPhase> = _heroTransitionPhase
    val chromeSharedTransitionScope: State<SharedTransitionScope?> = _chromeSharedTransitionScope

    fun setSearchOverlayVisible(value: Boolean) {
        _isSearchOverlayVisible.value = value
    }

    fun setSearchOverlayMounted(value: Boolean) {
        _isSearchOverlayMounted.value = value
    }

    fun setSearchOverlayInitialQuery(value: String) {
        _searchOverlayInitialQuery.value = value
    }

    fun setSearchOverlayQueryCommitted(value: Boolean) {
        _isSearchOverlayQueryCommitted.value = value
    }

    fun setDetailsChromeTransitionPending(value: Boolean) {
        _isDetailsChromeTransitionPending.value = value
    }

    fun setDetailsBottomPanelExpansion(value: Float) {
        _detailsBottomPanelExpansion.value = value
    }

    fun setDetailsBottomObstruction(value: Dp) {
        _detailsBottomObstruction.value = value
    }

    fun setDetailsBottomPanelRoute(value: String?) {
        _detailsBottomPanelRoute.value = value
    }

    fun setMaterialTopBarScrollEnabled(value: Boolean) {
        _materialTopBarScrollEnabled.value = value
    }

    fun setLastChromeTopBarOwnerKey(value: String?) {
        _lastChromeTopBarOwnerKey.value = value
    }

    fun setLastHeroTransitionStartedAtMs(value: Long) {
        _lastHeroTransitionStartedAtMs.value = value
    }

    fun setHeroTransitionPhase(value: HeroTransitionPhase) {
        _heroTransitionPhase.value = value
    }

    fun setChromeSharedTransitionScope(value: SharedTransitionScope?) {
        _chromeSharedTransitionScope.value = value
    }
}

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
        _isSearchOverlayVisible = isSearchOverlayVisible,
        _isSearchOverlayMounted = isSearchOverlayMounted,
        _searchOverlayInitialQuery = searchOverlayInitialQuery,
        _isSearchOverlayQueryCommitted = isSearchOverlayQueryCommitted,
        _isDetailsChromeTransitionPending = isDetailsChromeTransitionPending,
        _detailsBottomPanelExpansion = detailsBottomPanelExpansion,
        _detailsBottomObstruction = detailsBottomObstruction,
        _detailsBottomPanelRoute = detailsBottomPanelRoute,
        _materialTopBarScrollEnabled = materialTopBarScrollEnabled,
        _lastChromeTopBarOwnerKey = lastChromeTopBarOwnerKey,
        _lastHeroTransitionStartedAtMs = lastHeroTransitionStartedAtMs,
        _heroTransitionPhase = heroTransitionPhase,
        _chromeSharedTransitionScope = chromeSharedTransitionScope,
    )
}