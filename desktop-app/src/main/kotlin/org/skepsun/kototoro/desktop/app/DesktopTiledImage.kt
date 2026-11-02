package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.core.VisibleImageTiles
import kotlin.math.roundToInt

/** Only current intersecting tiles are composed; page geometry/history remain scene-owned. */
@Composable
internal fun DesktopTiledImage(image: DesktopReaderImage, node: VisibleNode?, displayScale: Float = 1f,
    modifier: Modifier) {
    val tiles = remember(image, node, displayScale) { node?.let { VisibleImageTiles.resolve(it,
        org.skepsun.kototoro.reader.core.IntSize(image.width, image.height), displayScale) }.orEmpty() }
    val style = LocalDesktopReaderPageStyle.current
    Box(modifier) {
        val density = LocalDensity.current
        tiles.forEach { tile ->
            key(image.path, tile.key) {
                var failed by remember { mutableStateOf(false) }
                var retry by remember { mutableIntStateOf(0) }
                val bitmap by produceState<ImageBitmap?>(null, image.path, tile.key, retry) {
                    try {
                        value = withContext(DesktopDispatchers.decode) {
                            val decoded = DesktopTileDecoder.decode(image.path, tile)
                            try { decoded.toComposeImageBitmap() } finally { decoded.flush() }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                }
                val pixels = bitmap
                if (pixels != null) Canvas(Modifier.fillMaxSize().testTag("reader-tile:${tile.key.row}:${tile.key.col}")) {
                    val x = size.width / image.width
                    val y = size.height / image.height
                    val bounds = tile.logicalRect
                    val region = tile.decodeRegion
                    // Anti-aliased clips on fractional edges leave each shared edge half transparent, which drew a
                    // grid over the page (issue #602); reaching into the neighbour's seam padding keeps it opaque.
                    val right = bounds.right * x + if (bounds.right < image.width) TILE_SEAM_OVERLAP_PX else 0f
                    val bottom = bounds.bottom * y + if (bounds.bottom < image.height) TILE_SEAM_OVERLAP_PX else 0f
                    clipRect(bounds.left * x, bounds.top * y, right, bottom) {
                        val left = (region.left * x).roundToInt()
                        val top = (region.top * y).roundToInt()
                        drawImage(pixels, dstOffset = IntOffset(left, top), dstSize = IntSize(
                            ((region.right * x).roundToInt() - left).coerceAtLeast(1),
                            ((region.bottom * y).roundToInt() - top).coerceAtLeast(1)), filterQuality = FilterQuality.High,
                            colorFilter = style.colorFilter)
                    }
                } else if (failed) TextButton({ failed = false; retry++ }, modifier = Modifier.offset(y = with(density) {
                    ((node?.visibleRegion?.top ?: 0f) - (node?.sceneBounds?.top ?: 0f)).toDp()
                })) { Text(desktopText("error.tileDecodeFailed"), color = Color.White) }
            }
        }
    }
}

/** Stays inside [org.skepsun.kototoro.reader.core.TileGrid.DEFAULT_SEAM_PADDING_PX] at any chosen sample size. */
private const val TILE_SEAM_OVERLAP_PX = 1f

internal val DesktopReaderImage.tiled: Boolean get() = regionSupported &&
    (height > 4096 || width.toLong() * height > DesktopImageDecoder.MAXIMUM_PIXELS)
