package org.skepsun.kototoro.desktop.player

import java.awt.image.BufferedImage
import java.io.Closeable

/** Window embedding remains the default; macOS presents render API frames in its existing Swing surface. */
enum class MpvRenderMode { NONE, SOFTWARE, MACOS_OPENGL }

internal interface MpvFrameRenderer : Closeable {
    fun frame(width: Int, height: Int): BufferedImage
}
