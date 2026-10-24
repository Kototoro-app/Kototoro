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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.settings.SettingsDestination
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvTakeFocusWhenEmpty

private val SettingsListPaneWidth = 360.dp

/**
 * Bumped every time the two-pane shell falls back to the root destination on TV, so the list can put
 * the focus back on the row that opened the page (see [SettingsRootScreen]).
 */
internal val LocalSettingsListReturnToken = compositionLocalOf { 0 }

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
    // The root destination has the same problem - nothing is selected yet, so the detail pane stays
    // empty, half of a 1080p screen goes dark, and the remote can walk RIGHT into that void.
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val atRoot = destination == null || destination == SettingsDestination.Root
    val useTwoPane = isTwoPane && !(isTvPresentation && (searchActive || atRoot))
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
    var hasOpenedPage by remember { mutableStateOf(false) }
    var returnToken by remember { mutableStateOf(0) }
    if (isTvPresentation) {
        LaunchedEffect(destination) {
            if (destination != SettingsDestination.Root) {
                hasOpenedPage = true
                return@LaunchedEffect
            }
            // 从子页 BACK 回来时右栏已经被清空，焦点停在顶栏返回键上，而在没有候选的右栏方向按
            // RIGHT 只会被光束搜索吞掉。这里只负责发一个"回来了"的信号，由列表把焦点还给当初
            // 打开子页的那一行；冷启动时 hasOpenedPage 还是 false，交给列表自己的首行焦点请求。
            if (!hasOpenedPage) return@LaunchedEffect
            withFrameNanos { }
            returnToken++
        }
    }
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(SettingsListPaneWidth)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom),
                ),
        ) {
            CompositionLocalProvider(LocalSettingsListReturnToken provides returnToken) {
                rootContent(Modifier.fillMaxSize())
            }
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
                // 双栏在电视上等价于"左分类 / 右详情"：打开一页后焦点要落进右侧，否则用户还得再按
                // 一次 RIGHT 才能操作刚打开的页面。用"没焦点就一直要"的写法而不是一次 moveFocus：
                // 右侧内容比状态变化晚几帧才组合出来，一次请求会落空，页面就以"没有环"的样子打开。
                .then(
                    if (isTvPresentation) {
                        Modifier.tvTakeFocusWhenEmpty(destination)
                    } else {
                        Modifier
                    },
                )
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
