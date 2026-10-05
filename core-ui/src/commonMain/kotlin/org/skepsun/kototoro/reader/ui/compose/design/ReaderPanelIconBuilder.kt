package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** One `<path>` of an Android vector drawable. */
internal class IconPath(val data: String, val evenOdd: Boolean)

/** An Android vector drawable as an [ImageVector]; the fill is black and tinted by `Icon` like `?colorControlNormal`. */
internal fun readerPanelIcon(
    name: String,
    width: Float,
    height: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    autoMirror: Boolean,
    paths: List<IconPath>,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = width.dp,
    defaultHeight = height.dp,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    autoMirror = autoMirror,
).apply {
    paths.forEach { path ->
        addPath(
            pathData = addPathNodes(path.data),
            pathFillType = if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        )
    }
}.build()
