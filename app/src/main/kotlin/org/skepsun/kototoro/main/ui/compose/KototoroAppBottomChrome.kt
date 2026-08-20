package org.skepsun.kototoro.main.ui.compose


import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.ui.widgets.BottomNavState
import org.skepsun.kototoro.core.ui.widgets.KototoroBottomNav
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.Icon
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import coil3.compose.rememberAsyncImagePainter

@Composable
internal fun ContinueReadingFab(
    onClick: () -> Unit,
    action: MainResumeAction,
    coverModel: Any?,
    modifier: Modifier = Modifier,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val hasCover = coverModel != null
    if (isIosStyle) {
        GlassSurface(
            modifier = modifier.size(56.dp),
            shape = CircleShape,
            style = GlassDefaults.regularStyle(),
            componentRole = GlassComponentRole.Surface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                ResumeActionArtwork(
                    action = action,
                    coverModel = coverModel,
                    fallbackIconTint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.size(56.dp),
            shape = CircleShape,
            color = if (hasCover) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ResumeActionArtwork(
                    action = action,
                    coverModel = coverModel,
                    fallbackIconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ResumeActionArtwork(
    action: MainResumeAction,
    coverModel: Any?,
    fallbackIconTint: Color,
) {
    val hasCover = coverModel != null
    if (hasCover) {
        Image(
            painter = rememberAsyncImagePainter(coverModel),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.38f)),
        )
    }
    Icon(
        painter = painterResource(action.iconRes),
        contentDescription = stringResource(action.contentDescriptionRes),
        tint = if (hasCover) Color.White else fallbackIconTint,
        modifier = Modifier.align(Alignment.Center),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BoxScope.MainBottomChrome(
    isLandscapeNavigation: Boolean,
    isLayeredSurface: Boolean,
    chromeSharedTransitionScope: SharedTransitionScope?,
    heroTransitionInProgress: Boolean,
    isDetailsChromeTransitionPending: Boolean,
    effectiveBottomNavOffset: Float,
    onLandscapeRailInteractingChange: (Boolean) -> Unit,
    onBottomNavHeightMeasured: (Int) -> Unit,
    navStateFlow: StateFlow<BottomNavState>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    isResumeEnabled: Boolean,
    onResumeClick: () -> Unit,
    resumeAction: MainResumeAction,
    resumeCoverModel: Any?,
    railHeaderContent: (@Composable () -> Unit)?,
    adjacentAction: (@Composable () -> Unit)?,
) {
    Box(
        modifier = Modifier
            .align(if (isLandscapeNavigation) Alignment.CenterStart else Alignment.BottomCenter)
            .renderChromeInSharedTransitionOverlay(
                sharedTransitionScope = chromeSharedTransitionScope,
                zIndexInOverlay = 1f,
                renderInOverlay = {
                    heroTransitionInProgress || isDetailsChromeTransitionPending
                },
            )
            .then(
                if (isLandscapeNavigation) {
                    Modifier.pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> onLandscapeRailInteractingChange(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> onLandscapeRailInteractingChange(false)
                        }
                        false
                    }
                } else {
                    Modifier
                }
            )
            .offset {
                if (isLandscapeNavigation) {
                    androidx.compose.ui.unit.IntOffset((-effectiveBottomNavOffset).toInt(), 0)
                } else {
                    androidx.compose.ui.unit.IntOffset(0, effectiveBottomNavOffset.toInt())
                }
            }
            .onGloballyPositioned { coords ->
                val newHeight = if (isLandscapeNavigation) coords.size.width else coords.size.height
                onBottomNavHeightMeasured(newHeight)
            },
    ) {
        val bottomNavContent: @Composable () -> Unit = {
            KototoroBottomNav(
                state = navStateFlow,
                onItemSelected = onItemSelected,
                onItemReselected = onItemReselected,
                railHeaderContent = railHeaderContent,
                adjacentAction = adjacentAction,
                showContinueReadingButton = isLandscapeNavigation && isResumeEnabled,
                onContinueReadingClick = onResumeClick,
                continueReadingIconRes = resumeAction.iconRes,
                continueReadingContentDescriptionRes = resumeAction.contentDescriptionRes,
                continueReadingCoverModel = resumeCoverModel,
            )
        }
        if (isLayeredSurface && !isLandscapeNavigation) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                bottomNavContent()
            }
        } else {
            bottomNavContent()
        }
    }
}

