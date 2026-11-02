package org.skepsun.kototoro.core.ui.extensions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/** The same counted source/repository/package tabs on Android and desktop. Labels come from the host catalog. */
data class ExtensionManagementTab(val label: String, val count: Int, val badgeCount: Int = 0)

@Composable
fun ExtensionManagementTabs(
    tabs: List<ExtensionManagementTab>,
    selectedTab: Int,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    isIosStyle: Boolean = false,
) {
    val scrollState = rememberScrollState()
    val bringIntoViewRequesters = remember(tabs.size) {
        List(tabs.size) { BringIntoViewRequester() }
    }
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val tabFocusRequesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }

    LaunchedEffect(selectedTab) {
        bringIntoViewRequesters.getOrNull(selectedTab)?.bringIntoView()
    }

    Surface(
        modifier = modifier
            .height(height)
            .wrapContentWidth(),
        shape = RoundedCornerShape(height / 2),
        color = if (isIosStyle) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
        },
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(3.dp)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val activeSurface = MaterialTheme.colorScheme.surface
            val baseBorderColor = MaterialTheme.colorScheme.outlineVariant
            val cornerRadius = (height - 6.dp) / 2
            val tabShape = RoundedCornerShape(cornerRadius)

            tabs.forEachIndexed { index, tab ->
                val selected = selectedTab == index
                val animatedBgColor by animateColorAsState(
                    targetValue = if (selected) activeSurface else activeSurface.copy(alpha = 0f),
                    animationSpec = tween(durationMillis = 200),
                    label = "tabBgColor",
                )
                val animatedTextColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 200),
                    label = "tabTextColor",
                )
                val animatedBorderColor by animateColorAsState(
                    targetValue = if (selected) baseBorderColor.copy(alpha = 0.35f) else baseBorderColor.copy(alpha = 0f),
                    animationSpec = tween(durationMillis = 200),
                    label = "tabBorderColor",
                )

                Box(
                    modifier = Modifier
                        .height(height - 6.dp)
                        .testTag("extensions-tab:$index")
                        .bringIntoViewRequester(bringIntoViewRequesters.getOrElse(index) { BringIntoViewRequester() })
                        .clip(tabShape)
                        .background(animatedBgColor)
                        .border(BorderStroke(0.5.dp, animatedBorderColor), tabShape)
                        .focusRequester(tabFocusRequesters[index])
                        .tvFocusable(shape = tabShape, addFocusTarget = false)
                        .onPreviewKeyEvent { event ->
                            val delta = when (event.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> 1
                                else -> 0
                            }
                            val target = index + delta
                            if (!isTvPresentation || delta == 0 || target !in tabs.indices) {
                                false
                            } else {
                                if (event.type == KeyEventType.KeyDown) {
                                    onTabClick(target)
                                    tabFocusRequesters[target].requestFocus()
                                }
                                true
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabClick(index) },
                        )
                        .semantics {
                            this.role = Role.Tab
                            this.selected = selected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = tab.label.let { label ->
                                if (selected) {
                                    label
                                } else {
                                    label.replace(Regex("\\s*[(\uFF08]\\s*${tab.count}\\s*[)\uFF09]"), "")
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = animatedTextColor,
                            maxLines = 1,
                        )
                        if (tab.badgeCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(percent = 50),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Text(
                                    text = tab.badgeCount.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 16.dp)
                                        .padding(horizontal = 4.dp, vertical = 0.5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
