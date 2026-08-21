package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.bytedance.danmaku.render.engine.DanmakuView
import org.skepsun.kototoro.video.player.EnhancedVideoSurfaceView

/** The only Android View interoperability boundary in the Compose video player root. */
@Composable
internal fun VideoPlayerRenderLayer(
    onPlayerViewCreated: (PlayerView) -> Unit,
    onEnhancementViewCreated: (EnhancedVideoSurfaceView) -> Unit,
    onDanmakuViewCreated: (DanmakuView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                }.also(onPlayerViewCreated)
            },
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(
            factory = { context ->
                EnhancedVideoSurfaceView(context).apply {
                    // SurfaceView 必须保持 VISIBLE 才会创建解码 Surface；直出时用 SurfaceControl alpha 隐藏。
                    visibility = android.view.View.VISIBLE
                    alpha = 0f
                }
                    .also(onEnhancementViewCreated)
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
