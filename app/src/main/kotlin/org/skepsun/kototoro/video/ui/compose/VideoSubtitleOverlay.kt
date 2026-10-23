package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class VideoSubtitleOverlayState(
    val text: String? = null,
    val fontSizeSp: Float = 18f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontFamily: SubtitleFontFamily = SubtitleFontFamily.Default,
    val textColor: Int = android.graphics.Color.WHITE,
    val borderColor: Int = android.graphics.Color.BLACK,
    val borderSize: Float = 8f,
    val backgroundColor: Int = 0x66000000,
    val backgroundStyle: SubtitleBackgroundStyle = SubtitleBackgroundStyle.Default,
    val backgroundCornerRadiusDp: Int = 10,
    val backgroundPaddingHorizontalDp: Int = 12,
    val backgroundPaddingVerticalDp: Int = 6,
    val alignX: Int = 1,
    val bottomPositionDp: Int = 80,
)

@Composable
internal fun VideoSubtitleOverlay(state: VideoSubtitleOverlayState) {
    val text = state.text ?: return
    val textAlign = when (state.alignX) {
        0 -> TextAlign.Start
        2 -> TextAlign.End
        else -> TextAlign.Center
    }
    val paddingHorizontal = state.backgroundPaddingHorizontalDp.dp
    val paddingVertical = state.backgroundPaddingVerticalDp.dp
    val cornerRadius = state.backgroundCornerRadiusDp.dp
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = text,
            color = Color(state.textColor),
            fontSize = state.fontSizeSp.sp,
            fontFamily = state.fontFamily.toComposeFontFamily(),
            fontWeight = if (state.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (state.italic) FontStyle.Italic else FontStyle.Normal,
            textAlign = textAlign,
            style = TextStyle(
                shadow = state.borderSize.takeIf { it > 0f }?.let {
                    Shadow(color = Color(state.borderColor), blurRadius = it)
                },
            ),
            onTextLayout = { textLayout = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = state.bottomPositionDp.dp)
                .drawBehind {
                    // The padded area is exactly the room the background may use: the cue text is
                    // inset by the same padding, so hugging pills stay inside this node.
                    val insetX = paddingHorizontal.toPx()
                    val insetY = paddingVertical.toPx()
                    val layout = textLayout ?: return@drawBehind
                    val lines = (0 until layout.lineCount).mapNotNull { index ->
                        // An empty line in a multi-line cue has no words to cover.
                        val start = layout.getLineStart(index)
                        val end = layout.getLineEnd(index)
                        if (text.substring(start, end).isBlank()) return@mapNotNull null
                        SubtitleRect(
                            left = layout.getLineLeft(index) + insetX,
                            top = layout.getLineTop(index) + insetY,
                            right = layout.getLineRight(index) + insetX,
                            bottom = layout.getLineBottom(index) + insetY,
                        )
                    }
                    val radius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                    subtitleBackgroundBoxes(
                        style = state.backgroundStyle,
                        lines = lines,
                        containerWidth = size.width,
                        containerHeight = size.height,
                        paddingHorizontal = insetX,
                        paddingVertical = insetY,
                    ).forEach { box ->
                        drawRoundRect(
                            color = Color(state.backgroundColor),
                            topLeft = Offset(box.left, box.top),
                            size = Size(box.width, box.height),
                            cornerRadius = radius,
                        )
                    }
                }
                .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        )
    }
}

private fun SubtitleFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    SubtitleFontFamily.SANS -> FontFamily.SansSerif
    SubtitleFontFamily.SERIF -> FontFamily.Serif
    SubtitleFontFamily.MONOSPACE -> FontFamily.Monospace
}
