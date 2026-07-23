package org.skepsun.kototoro.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.HazePositionStrategy
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassVisualTreatment
import org.skepsun.kototoro.core.ui.glass.LocalHazeState

@Composable
fun ReaderToolbarChrome(
	modifier: Modifier = Modifier,
) {
	val hazeState = remember {
		HazeState().apply {
			positionStrategy = HazePositionStrategy.Screen
		}
	}
	val backdropBackground = MaterialTheme.colorScheme.background
	val backdrop = rememberLayerBackdrop {
		drawRect(backdropBackground)
		drawContent()
	}
	val immersiveBaseColor = if (isSystemInDarkTheme()) Color.Black else Color.White

	CompositionLocalProvider(
		LocalHazeState provides hazeState,
		LocalLiquidGlassBackdrop provides backdrop,
		LocalLiquidGlassLayerBackdrop provides backdrop,
	) {
		Box(modifier = modifier.fillMaxWidth().height(96.dp)) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(96.dp)
					.layerBackdrop(backdrop)
					.hazeSource(hazeState)
					.drawWithCache {
						val brush = Brush.verticalGradient(
							colorStops = arrayOf(
								0f to immersiveBaseColor.copy(alpha = 0.96f),
								0.36f to immersiveBaseColor.copy(alpha = 0.78f),
								0.68f to immersiveBaseColor.copy(alpha = 0.42f),
								0.88f to immersiveBaseColor.copy(alpha = 0.16f),
								1f to Color.Transparent,
							),
							startY = 0f,
					endY = 96.dp.toPx(),
						)
						onDrawBehind { drawRect(brush) }
					},
			)
			GlassSurface(
				modifier = Modifier.fillMaxWidth().height(96.dp),
				shape = androidx.compose.ui.graphics.RectangleShape,
				style = GlassDefaults.topBarChromeStyle(),
				visualTreatment = GlassVisualTreatment.TopBarPrototype,
				componentRole = GlassComponentRole.TopBar,
			) { }
		}
	}
}
