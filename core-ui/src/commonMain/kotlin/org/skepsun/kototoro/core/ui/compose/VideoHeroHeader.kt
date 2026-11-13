package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import kotlin.math.roundToInt

private const val VideoCollapsedTitleMaxLines = 3
private const val VideoExpandedTitleMaxLines = 6

/**
 * Centered cinema hero header shared between Android and desktop video details pages.
 * Leaves a clear poster focal zone at the top (`heroClearanceHeight`), followed by centered title,
 * condensed metadata line, source/tracking chips, primary play button, progress bar and quick actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoHeroHeaderContent(
    title: String,
    alternateTitlesText: String,
    metadataSummary: String,
    modifier: Modifier = Modifier,
    heroClearanceHeight: Dp = 172.dp,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progressPercent: Float? = null,
    onCoverClick: (() -> Unit)? = null,
    onHeroBoundsChanged: ((topPx: Float, bottomPx: Float) -> Unit)? = null,
    posterSlot: (@Composable () -> Unit)? = null,
    sourceChips: @Composable () -> Unit = {},
    primaryPlayAction: @Composable () -> Unit = {},
    quickActions: @Composable () -> Unit = {},
) {
    var isTitleExpanded by rememberSaveable(title) { mutableStateOf(false) }
    var canExpandTitle by remember(title) { mutableStateOf(false) }
    val coverLabel = uiString("details_cover")

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (heroClearanceHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroClearanceHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .tvFocusable(
                            shape = RoundedCornerShape(16.dp),
                            enabled = onCoverClick != null,
                            addFocusTarget = false,
                        )
                        .then(
                            if (onCoverClick != null) {
                                Modifier
                                    .semantics { contentDescription = coverLabel }
                                    .clickable(role = Role.Button, onClick = onCoverClick)
                            } else {
                                Modifier
                            },
                        )
                        .testTag("video-hero-cover-tap"),
                    contentAlignment = Alignment.Center,
                ) {
                    posterSlot?.invoke()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .then(
                        if (onHeroBoundsChanged != null) {
                            Modifier.onGloballyPositioned { coords ->
                                val bounds = coords.boundsInRoot()
                                onHeroBoundsChanged(bounds.top, bounds.bottom)
                            }
                        } else {
                            Modifier
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = titleColor,
                    textAlign = TextAlign.Center,
                    maxLines = if (isTitleExpanded) VideoExpandedTitleMaxLines else VideoCollapsedTitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .then(
                            if (canExpandTitle && !isTitleExpanded) {
                                Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startY = size.height * 0.62f,
                                                endY = size.height,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                            } else {
                                Modifier
                            },
                        )
                        .tvFocusable(shape = RoundedCornerShape(8.dp), enabled = canExpandTitle, addFocusTarget = false)
                        .clickable(enabled = canExpandTitle) { isTitleExpanded = !isTitleExpanded }
                        .testTag("video-hero-title"),
                    onTextLayout = { result ->
                        val overflows = result.hasVisualOverflow || result.lineCount > VideoCollapsedTitleMaxLines
                        if (canExpandTitle != overflows) canExpandTitle = overflows
                    },
                )

                if (alternateTitlesText.isNotBlank()) {
                    Text(
                        text = alternateTitlesText,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (metadataSummary.isNotBlank()) {
                    Text(
                        text = metadataSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .testTag("video-hero-metadata-line"),
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sourceChips()
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                primaryPlayAction()
            }
        }

        if (progressPercent != null && progressPercent > 0f) {
            val clamped = progressPercent.coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { clamped },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    text = "${(clamped * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor,
                    maxLines = 1,
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickActions()
        }
        Spacer(Modifier.height(2.dp))
    }
}
