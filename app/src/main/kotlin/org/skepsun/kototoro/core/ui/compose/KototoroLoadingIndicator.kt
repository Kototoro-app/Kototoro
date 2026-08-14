package org.skepsun.kototoro.core.ui.compose

import android.view.ContextThemeWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.android.awaitFrame
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings

private const val IndicatorProgressMax = 10_000
private const val PullRefreshResistance = 0.52f
private const val PullRefreshMaxDistanceMultiplier = 1.65f
private val PullRefreshThreshold = 80.dp

@Composable
fun KototoroLoadingIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    style: AppSettings.LoadingCircleStyle? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent,
) {
    val resolvedStyle = rememberLoadingIndicatorStyle(style)
    key(resolvedStyle) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                CircularProgressIndicator(
                    ContextThemeWrapper(context, resolvedStyle.themeOverlayResId),
                    null,
                    com.google.android.material.R.attr.circularProgressIndicatorStyle,
                ).apply {
                    max = IndicatorProgressMax
                }
            },
            update = { indicator ->
                indicator.setIndicatorColor(color.toArgb())
                indicator.trackColor = trackColor.toArgb()
                val determinateProgress = progress?.invoke()?.coerceIn(0f, 1f)
                if (determinateProgress == null) {
                    indicator.isIndeterminate = true
                } else {
                    indicator.isIndeterminate = false
                    indicator.setProgressCompat((determinateProgress * IndicatorProgressMax).roundToInt(), false)
                }
            },
        )
    }
}

@Composable
fun KototoroLinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    style: AppSettings.LoadingCircleStyle? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
) {
    val resolvedStyle = rememberLoadingIndicatorStyle(style)
    key(resolvedStyle) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                LinearProgressIndicator(
                    ContextThemeWrapper(context, resolvedStyle.themeOverlayResId),
                    null,
                    com.google.android.material.R.attr.linearProgressIndicatorStyle,
                ).apply {
                    max = IndicatorProgressMax
                }
            },
            update = { indicator ->
                indicator.setIndicatorColor(color.toArgb())
                indicator.trackColor = trackColor.toArgb()
                val determinateProgress = progress?.invoke()?.coerceIn(0f, 1f)
                if (determinateProgress == null) {
                    indicator.isIndeterminate = true
                } else {
                    indicator.isIndeterminate = false
                    indicator.setProgressCompat((determinateProgress * IndicatorProgressMax).roundToInt(), false)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: PullToRefreshState = rememberPullToRefreshState(),
    indicatorTopInset: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(
            modifier = modifier,
            contentAlignment = contentAlignment,
            content = content,
        )
        return
    }

    val density = LocalDensity.current
    val thresholdPx = with(density) { PullRefreshThreshold.toPx() }
    val maxPullDistancePx = thresholdPx * PullRefreshMaxDistanceMultiplier
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    val latestIsRefreshing by rememberUpdatedState(isRefreshing)
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var settledDistancePx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var refreshRequestVersion by remember { mutableIntStateOf(0) }
    val indicatorOffsetFactor = 1f
    val displayedDistancePx by animateFloatAsState(
        targetValue = if (isDragging) pullDistancePx else settledDistancePx,
        animationSpec = KototoroMotion.PullRefresh,
        label = "kototoro_pull_refresh_distance",
    )
    val indicatorProgress = (displayedDistancePx / thresholdPx).coerceIn(0f, 1f)
    val latestIndicatorProgress by rememberUpdatedState(indicatorProgress)
    val indicatorState = remember {
        object : PullToRefreshState {
            override val distanceFraction: Float
                get() = latestIndicatorProgress

            override val isAnimating: Boolean
                get() = false

            override suspend fun animateToThreshold() = Unit

            override suspend fun animateToHidden() = Unit

            override suspend fun snapTo(targetValue: Float) = Unit
        }
    }

    LaunchedEffect(isRefreshing, thresholdPx) {
        if (isRefreshing) {
            isDragging = false
            pullDistancePx = thresholdPx
            settledDistancePx = thresholdPx
        } else {
            isDragging = false
            pullDistancePx = 0f
            settledDistancePx = 0f
        }
    }

    LaunchedEffect(refreshRequestVersion) {
        if (refreshRequestVersion == 0) {
            return@LaunchedEffect
        }
        awaitFrame()
        if (!latestIsRefreshing) {
            isDragging = false
            pullDistancePx = 0f
            settledDistancePx = 0f
        }
    }

    val nestedScrollConnection = remember(thresholdPx, maxPullDistancePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || latestIsRefreshing || pullDistancePx <= 0f) {
                    return Offset.Zero
                }
                if (available.y >= 0f) {
                    return Offset.Zero
                }
                isDragging = true
                val consumedY = available.y.coerceAtLeast(-pullDistancePx)
                pullDistancePx = (pullDistancePx + consumedY).coerceAtLeast(0f)
                settledDistancePx = pullDistancePx
                return Offset(x = 0f, y = consumedY)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || latestIsRefreshing || available.y <= 0f) {
                    return Offset.Zero
                }
                isDragging = true
                val consumedY = available.y
                pullDistancePx = (pullDistancePx + consumedY)
                    .coerceAtMost(maxPullDistancePx)
                settledDistancePx = pullDistancePx
                return Offset(x = 0f, y = consumedY)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                settlePullRefresh()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settlePullRefresh()
                return Velocity.Zero
            }

            private fun settlePullRefresh() {
                if (!isDragging && pullDistancePx <= 0f) {
                    return
                }
                val shouldRefresh = pullDistancePx >= thresholdPx && !latestIsRefreshing
                isDragging = false
                if (shouldRefresh) {
                    settledDistancePx = thresholdPx
                    pullDistancePx = thresholdPx
                    refreshRequestVersion += 1
                    latestOnRefresh()
                } else if (!latestIsRefreshing) {
                    settledDistancePx = 0f
                    pullDistancePx = 0f
                }
            }
        }
    }

    Box(
        modifier = modifier.nestedScroll(nestedScrollConnection),
        contentAlignment = contentAlignment,
    ) {
        content()

        if (isRefreshing || displayedDistancePx > 0.5f) {
            PullToRefreshDefaults.Indicator(
                state = indicatorState,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(
                        y = indicatorTopInset.calculateTopPadding() +
                            with(density) { (displayedDistancePx * indicatorOffsetFactor).toDp() },
                    ),
            )
        }
    }
}

@Composable
private fun rememberLoadingIndicatorStyle(style: AppSettings.LoadingCircleStyle?): AppSettings.LoadingCircleStyle {
    if (style != null) {
        return style
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    var resolvedStyle by remember(settings) { mutableStateOf(settings.loadingCircleStyle) }
    DisposableEffect(settings, lifecycleOwner) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == AppSettings.KEY_LOADING_CIRCLE_STYLE) {
                resolvedStyle = settings.loadingCircleStyle
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resolvedStyle = settings.loadingCircleStyle
            }
        }
        settings.subscribe(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            settings.unsubscribe(listener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return resolvedStyle
}

private val AppSettings.LoadingCircleStyle.themeOverlayResId: Int
    get() = when (this) {
        AppSettings.LoadingCircleStyle.THICK_STRAIGHT -> R.style.ThemeOverlay_Kototoro_Loading_ThickStraight
        AppSettings.LoadingCircleStyle.THICK_WAVY -> R.style.ThemeOverlay_Kototoro_Loading_ThickWavy
        AppSettings.LoadingCircleStyle.THIN_STRAIGHT -> R.style.ThemeOverlay_Kototoro_Loading_ThinStraight
        AppSettings.LoadingCircleStyle.THIN_WAVY -> R.style.ThemeOverlay_Kototoro_Loading_ThinWavy
    }
