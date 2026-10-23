package org.skepsun.kototoro.settings.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.settings.SettingsDestination
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

private val SettingsListPaneWidth = 360.dp

private const val TvDestinationFocusAttempts = 20

@Composable
fun SettingsAdaptiveShell(
    isTwoPane: Boolean,
    destination: SettingsDestination?,
    destinationKey: (SettingsDestination) -> String,
    modifier: Modifier = Modifier,
    searchActive: Boolean = false,
    rootContent: @Composable (Modifier) -> Unit,
    destinationContent: @Composable (SettingsDestination) -> Unit,
) {
    // Settings search results are a single list: on a TV the two-pane split would squeeze them
    // into the 360dp list column and leave the detail pane empty, so search switches to one pane.
    val useTwoPane = isTwoPane && !(searchActive && LocalUiPresentationConfig.current.isTv)
    if (useTwoPane) {
        SettingsTwoPaneShell(
            destination = destination ?: SettingsDestination.Root,
            modifier = modifier,
            rootContent = rootContent,
            destinationContent = destinationContent,
        )
    } else {
        SettingsSinglePaneShell(
            destination = destination ?: SettingsDestination.Root,
            destinationKey = destinationKey,
            modifier = modifier,
            destinationContent = destinationContent,
        )
    }
}

@Composable
private fun SettingsSinglePaneShell(
    destination: SettingsDestination?,
    destinationKey: (SettingsDestination) -> String,
    modifier: Modifier = Modifier,
    destinationContent: @Composable (SettingsDestination) -> Unit,
) {
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val saveableStateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = destination,
        modifier = modifier,
        transitionSpec = {
            if (isTvPresentation) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                // Settings pages are parent-child surfaces: a light FadeThrough so
                // sub-page open/close never snaps (matches the app motion system).
                (fadeIn(animationSpec = tween(220)) + scaleIn(
                    initialScale = 0.99f,
                    animationSpec = tween(280),
                )) togetherWith
                    (fadeOut(animationSpec = tween(180)) + scaleOut(
                        targetScale = 0.985f,
                        animationSpec = tween(240),
                    ))
            }
        },
        label = "settings_page",
    ) { targetDestination ->
        if (targetDestination != null) {
            SettingsSinglePaneContent(
                destination = targetDestination,
                destinationKey = destinationKey,
                modifier = Modifier.fillMaxSize(),
                saveableStateHolder = saveableStateHolder,
                destinationContent = destinationContent,
            )
        }
    }
}

@Composable
private fun SettingsSinglePaneContent(
    destination: SettingsDestination,
    destinationKey: (SettingsDestination) -> String,
    modifier: Modifier,
    saveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    destinationContent: @Composable (SettingsDestination) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
    ) {
        saveableStateHolder.SaveableStateProvider(destinationKey(destination)) {
            destinationContent(destination)
        }
    }
}

@Composable
private fun SettingsTwoPaneShell(
    destination: SettingsDestination,
    modifier: Modifier = Modifier,
    rootContent: @Composable (Modifier) -> Unit,
    destinationContent: @Composable (SettingsDestination) -> Unit,
) {
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val focusManager = LocalFocusManager.current
    var isListPaneFocused by remember { mutableStateOf(false) }
    if (isTvPresentation) {
        // 双栏在电视上等价于“左分类 / 右详情”：从列表打开一页后把焦点送进右侧，
        // 否则用户还要多按一次 RIGHT 才能操作刚打开的页面（原生 TV 设置也是直接进右侧）。
        var handledDestination by remember { mutableStateOf<SettingsDestination?>(null) }
        LaunchedEffect(destination, isListPaneFocused) {
            val openedFromList = destination != SettingsDestination.Root &&
                destination != handledDestination &&
                isListPaneFocused
            handledDestination = destination
            if (openedFromList) {
                repeat(TvDestinationFocusAttempts) {
                    withFrameNanos { }
                    if (focusManager.moveFocus(FocusDirection.Right)) {
                        return@LaunchedEffect
                    }
                }
            }
        }
    }
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(SettingsListPaneWidth)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom),
                )
                .then(
                    if (isTvPresentation) {
                        Modifier.onFocusChanged { isListPaneFocused = it.hasFocus }
                    } else {
                        Modifier
                    },
                ),
        ) {
            rootContent(Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom),
                ),
        ) {
            if (destination == SettingsDestination.Root) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            } else {
                destinationContent(destination)
            }
        }
    }
}
