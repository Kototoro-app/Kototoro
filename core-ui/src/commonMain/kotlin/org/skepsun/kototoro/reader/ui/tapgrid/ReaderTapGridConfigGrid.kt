package org.skepsun.kototoro.reader.ui.tapgrid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.domain.TapGridArea

private const val ACTION_TINT_ALPHA = 40 / 255f

private val tapGridRows = TapGridArea.entries.chunked(3)

/**
 * The reader-actions grid: one cell per tap area, tinted by its tap action and labelled with both actions. A click
 * edits the tap action, a long click the long-tap action; [cellModifier] lets a platform add more triggers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderTapGridConfigGrid(
    content: Map<TapGridArea, TapActions>,
    tapActionLabel: String,
    longTapActionLabel: String,
    actionName: @Composable (TapAction?) -> String,
    onTap: (TapGridArea) -> Unit,
    onLongTap: (TapGridArea) -> Unit,
    modifier: Modifier = Modifier,
    dividerColor: Color = MaterialTheme.colorScheme.outline,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    cellModifier: (TapGridArea) -> Modifier = { Modifier },
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                drawTapGridDividers(dividerColor)
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            tapGridRows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    row.forEach { area ->
                        val actions = content[area]
                        val tapAction = actions?.tapAction
                        val label = buildAnnotatedString {
                            append(tapActionLabel)
                            append('\n')
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(actionName(tapAction))
                            }
                            append('\n')
                            append('\n')
                            append(longTapActionLabel)
                            append('\n')
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(actionName(actions?.longTapAction))
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(tapAction?.let(::tapActionTint) ?: Color.Transparent)
                                .then(cellModifier(area))
                                .combinedClickable(
                                    onClick = { onTap(area) },
                                    onLongClick = { onLongTap(area) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = textStyle,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The translucent tint of an area whose tap does [action]. */
fun tapActionTint(action: TapAction): Color = Color(
    red = (action.color shr 16 and 0xFF) / 255f,
    green = (action.color shr 8 and 0xFF) / 255f,
    blue = (action.color and 0xFF) / 255f,
    alpha = ACTION_TINT_ALPHA,
)

private fun DrawScope.drawTapGridDividers(color: Color) {
    val strokeWidth = 1.dp.toPx()
    for (step in 1..2) {
        val x = size.width * step / 3f
        val y = size.height * step / 3f
        drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = strokeWidth)
        drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = strokeWidth)
    }
}
