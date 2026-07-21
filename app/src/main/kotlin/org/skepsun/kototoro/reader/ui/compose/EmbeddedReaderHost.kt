package org.skepsun.kototoro.reader.ui.compose

import android.view.View
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import org.skepsun.kototoro.reader.ui.EmbeddedReaderFragment
import org.skepsun.kototoro.reader.ui.EmbeddedReaderRequest
import org.skepsun.kototoro.reader.ui.EmbeddedReaderCommands
import org.skepsun.kototoro.reader.ui.EmbeddedReaderCockpitState
import org.skepsun.kototoro.R
import kotlin.math.roundToInt

@Composable
fun EmbeddedReaderHost(
    request: EmbeddedReaderRequest,
    onClose: () -> Unit,
    onInstallCloseHandler: ((() -> Unit)?) -> Unit,
    onCloseCompleted: () -> Unit,
    onCommandsChanged: (EmbeddedReaderCommands?) -> Unit = {},
    onCockpitStateChanged: (EmbeddedReaderCockpitState) -> Unit = {},
    showInlineProgress: Boolean = true,
    showInlineToolbar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = remember { View.generateViewId() }
    val fragmentTag = remember(request.id) { "embedded_reader_${request.id}" }
    var readerFragment by remember(request.id) { mutableStateOf<EmbeddedReaderFragment?>(null) }
    var toolbarVisible by remember(request.id) { mutableStateOf(false) }
    var progressExpanded by remember(request.id) { mutableStateOf(false) }
    var cockpitState by remember(request.id) { mutableStateOf(EmbeddedReaderCockpitState()) }
    var pendingPage by remember(request.id) { mutableStateOf<Float?>(null) }
    var touchDownX by remember(request.id) { mutableStateOf(0f) }
    var touchDownY by remember(request.id) { mutableStateOf(0f) }
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    BackHandler(onBack = onClose)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> FragmentContainerView(context).apply { id = containerId } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            touchDownX = event.x
                            touchDownY = event.y
                        }
                        MotionEvent.ACTION_UP -> {
                            val isTap = kotlin.math.abs(event.x - touchDownX) < swipeThresholdPx / 2f &&
                                kotlin.math.abs(event.y - touchDownY) < swipeThresholdPx / 2f
                            if (isTap && showInlineToolbar) {
                                val visible = !toolbarVisible
                                toolbarVisible = visible
                                progressExpanded = visible
                            }
                        }
                    }
                    readerFragment?.view?.dispatchTouchEvent(event) == true
                },
        )
        AnimatedVisibility(
            visible = showInlineToolbar && toolbarVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_forward),
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.graphicsLayer { rotationZ = 180f },
                        )
                    }
                    Text(
                        text = stringResource(R.string.reader_settings),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (showInlineProgress) {
            val pageCount = cockpitState.pageCount.coerceAtLeast(1)
            val displayedPage = pendingPage ?: cockpitState.page.toFloat().coerceIn(0f, (pageCount - 1).toFloat())
            Surface(
            onClick = { progressExpanded = !progressExpanded },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = if (progressExpanded) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            } else {
                RoundedCornerShape(0.dp)
            },
            shadowElevation = 0.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (progressExpanded) 48.dp else 4.dp),
            ) {
                if (progressExpanded) {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    Text(
                        text = "${displayedPage.roundToInt() + 1} / $pageCount",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Slider(
                        value = displayedPage,
                        onValueChange = { pendingPage = it },
                        onValueChangeFinished = {
                            pendingPage?.roundToInt()?.let { readerFragment?.seekToPage(it) }
                            pendingPage = null
                        },
                        valueRange = 0f..(pageCount - 1).toFloat().coerceAtLeast(1f),
                        enabled = cockpitState.pageCount > 1,
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(
                                    ((cockpitState.page + 1f) / cockpitState.pageCount.coerceAtLeast(1))
                                        .coerceIn(0f, 1f),
                                )
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(readerFragment) {
        val fragment = readerFragment ?: return@LaunchedEffect
        fragment.observeCockpitState().collect { state ->
            cockpitState = state
            onCockpitStateChanged(state)
        }
    }
    DisposableEffect(activity, containerId, request.id) {
        activity.supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            replace(containerId, EmbeddedReaderFragment.newInstance(request.arguments), fragmentTag)
        }
        readerFragment = activity.supportFragmentManager.findFragmentByTag(fragmentTag) as? EmbeddedReaderFragment
        readerFragment?.let { fragment ->
            onCommandsChanged(
                EmbeddedReaderCommands(
                    previousChapter = { fragment.switchChapterBy(-1) },
                    nextChapter = { fragment.switchChapterBy(1) },
                    toggleBookmark = fragment::toggleBookmark,
                    seekToPage = fragment::seekToPage,
                    openChapters = fragment::openChapters,
                    openMore = fragment::openMore,
                ),
            )
        }
        onInstallCloseHandler {
            val fragment = activity.supportFragmentManager.findFragmentByTag(fragmentTag)
                as? EmbeddedReaderFragment
            fragment?.requestClose(onCloseCompleted) ?: onCloseCompleted()
        }
        onDispose {
            readerFragment = null
            onCommandsChanged(null)
            onInstallCloseHandler(null)
            activity.supportFragmentManager.findFragmentByTag(fragmentTag)?.let { fragment ->
                activity.supportFragmentManager.commitNow(allowStateLoss = true) { remove(fragment) }
            }
        }
    }
}
