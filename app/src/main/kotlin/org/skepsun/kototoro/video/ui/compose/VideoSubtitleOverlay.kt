package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
	val textColor: Int = android.graphics.Color.WHITE,
	val borderColor: Int = android.graphics.Color.BLACK,
	val borderSize: Float = 8f,
	val backgroundColor: Int = 0x66000000,
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
	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
		Text(
			text = text,
			color = Color(state.textColor),
			fontSize = state.fontSizeSp.sp,
			fontWeight = if (state.bold) FontWeight.Bold else FontWeight.Normal,
			fontStyle = if (state.italic) FontStyle.Italic else FontStyle.Normal,
			textAlign = textAlign,
			style = androidx.compose.ui.text.TextStyle(
				shadow = state.borderSize.takeIf { it > 0f }?.let {
					Shadow(color = Color(state.borderColor), blurRadius = it)
				},
			),
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 32.dp, end = 32.dp, bottom = state.bottomPositionDp.dp)
				.background(Color(state.backgroundColor))
				.padding(horizontal = 12.dp, vertical = 6.dp),
		)
	}
}
