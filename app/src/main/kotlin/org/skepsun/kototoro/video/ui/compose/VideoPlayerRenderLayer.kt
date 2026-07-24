package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bytedance.danmaku.render.engine.DanmakuView
import org.skepsun.kototoro.video.player.CustomMpvView

/** The only Android View interoperability boundary in the Compose video player root. */
@Composable
internal fun VideoPlayerRenderLayer(
	onMpvViewCreated: (CustomMpvView) -> Unit,
	onDanmakuViewCreated: (DanmakuView) -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(modifier = modifier) {
		AndroidView(
			factory = { context ->
				CustomMpvView(context).also(onMpvViewCreated)
			},
			modifier = Modifier.fillMaxSize(),
		)
		AndroidView(
			factory = { context ->
				DanmakuView(context).also(onDanmakuViewCreated)
			},
			modifier = Modifier.fillMaxSize(),
		)
	}
}
