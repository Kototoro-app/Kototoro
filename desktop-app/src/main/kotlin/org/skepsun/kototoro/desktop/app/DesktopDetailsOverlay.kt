package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.adaptive.tabletLayoutClass
import org.skepsun.kototoro.core.ui.adaptive.tabletPreviewCardWidth
import org.skepsun.kototoro.core.ui.preview.TabletPreviewSurface

/** Keep the owning list composed underneath details so its scroll and search draft survive dismissal. */
@Composable
internal fun DesktopDetailsOverlay(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.content?.id) { focus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent {
        if (enabled && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            controller.dismissDetails()
            true
        } else false
    }.focusable().testTag("details-overlay")) {
        val windowWidth = LocalDesktopWindowWidth.current
        val floating = tabletLayoutClass(windowWidth.value.toInt(), true) == TabletLayoutClass.EXPANDED &&
            !state.detailsExpanded
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f))
            .testTag("details-dismiss").clickable(enabled = enabled) { controller.dismissDetails() })
        val panel = if (floating) Modifier.align(Alignment.CenterEnd).padding(12.dp)
            .width(tabletPreviewCardWidth(windowWidth)).fillMaxHeight()
            else Modifier.fillMaxSize()
        if (floating) TabletPreviewSurface(panel.testTag("details-preview")) {
            DesktopTabletPreview(controller, state, enabled)
        }
        else Surface(panel.testTag("details-full"),
            shape = RoundedCornerShape(28.dp), elevation = 8.dp, color = MaterialTheme.colors.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("作品详情", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton({ controller.dismissDetails() }, enabled = enabled,
                        modifier = Modifier.testTag("details-close")) { Text("关闭") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { DesktopTabletDetails(controller, state) }
            }
        }
    }
}
