package org.skepsun.kototoro.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle

@Composable
fun ReaderToolbarChrome(
	modifier: Modifier = Modifier,
) {
	val immersiveBaseColor = if (isSystemInDarkTheme()) Color.Black else Color.White
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS

	Box(modifier = modifier.fillMaxWidth().height(96.dp)) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(96.dp)
					.drawWithCache {
						val brush = Brush.verticalGradient(
							colorStops = arrayOf(
								0f to immersiveBaseColor.copy(alpha = 0.30f),
								0.36f to immersiveBaseColor.copy(alpha = 0.22f),
								0.68f to immersiveBaseColor.copy(alpha = 0.12f),
								0.88f to immersiveBaseColor.copy(alpha = 0.04f),
								1f to Color.Transparent,
							),
							startY = 0f,
					endY = 96.dp.toPx(),
						)
						onDrawBehind { drawRect(brush) }
					},
			)
			GlassSurface(
				modifier = if (isIosStyle) {
					Modifier
						.fillMaxWidth()
						.statusBarsPadding()
						.padding(horizontal = 14.dp, vertical = 4.dp)
						.height(64.dp)
				} else {
					Modifier.fillMaxWidth().height(96.dp)
				},
				shape = if (isIosStyle) RoundedCornerShape(25.dp) else androidx.compose.ui.graphics.RectangleShape,
				style = GlassDefaults.topBarChromeStyle(),
				componentRole = GlassComponentRole.TopBar,
			) { }
	}
}
