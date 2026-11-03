package org.skepsun.kototoro.video.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Material icons the shared player controls draw, as shared vectors: Google's own Material Icons (Filled, 24dp;
 * google/material-design-icons, Apache 2.0, copied from the Android SDK's `icons/material/materialicons` baseline
 * vectors), the glyphs `androidx.compose.material.icons` draws on Android, so the shared module needs no icon library.
 * Names follow the `Icons.Filled` names they replace; auto-mirrored ones flip in right-to-left layouts.
 */
object VideoPlayerIcons {
    val ArrowBack: ImageVector by lazy { playerIcon("ArrowBack", true, "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val Subtitles: ImageVector by lazy { playerIcon("Subtitles", false, "M20,4L4,4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2L4,14v-2zM14,18L4,18v-2h10v2zM20,18h-4v-2h4v2zM20,14L10,14v-2h10v2z") }
    val Audiotrack: ImageVector by lazy { playerIcon("Audiotrack", false, "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z") }
    val Pause: ImageVector by lazy { playerIcon("Pause", false, "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }
    val PlayArrow: ImageVector by lazy { playerIcon("PlayArrow", false, "M8,5v14l11,-7z") }
    val SkipPrevious: ImageVector by lazy { playerIcon("SkipPrevious", false, "M6,6h2v12L6,18zM9.5,12l8.5,6L18,6z") }
    val SkipNext: ImageVector by lazy { playerIcon("SkipNext", false, "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val GridView: ImageVector by lazy { playerIcon("GridView", false, "M3,3v8h8L11,3L3,3zM9,9L5,9L5,5h4v4zM3,13v8h8v-8L3,13zM9,19L5,19v-4h4v4zM13,3v8h8L21,3h-8zM19,9h-4L15,5h4v4zM13,13v8h8v-8h-8zM19,19h-4v-4h4v4z") }
    val Lock: ImageVector by lazy { playerIcon("Lock", false, "M18,8h-1L17,6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2L6,8c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L20,10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8L8.9,8L8.9,6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z") }
    val Replay10: ImageVector by lazy { playerIcon("Replay10", false, "M11.99,5V1l-5,5l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6s-6,-2.69 -6,-6h-2c0,4.42 3.58,8 8,8s8,-3.58 8,-8S16.41,5 11.99,5z M10.89,16h-0.85v-3.26l-1.01,0.31v-0.69l1.77,-0.63h0.09V16z M15.17,14.24c0,0.32 -0.03,0.6 -0.1,0.82s-0.17,0.42 -0.29,0.57s-0.28,0.26 -0.45,0.33s-0.37,0.1 -0.59,0.1s-0.41,-0.03 -0.59,-0.1s-0.33,-0.18 -0.46,-0.33s-0.23,-0.34 -0.3,-0.57s-0.11,-0.5 -0.11,-0.82V13.5c0,-0.32 0.03,-0.6 0.1,-0.82s0.17,-0.42 0.29,-0.57s0.28,-0.26 0.45,-0.33s0.37,-0.1 0.59,-0.1s0.41,0.03 0.59,0.1c0.18,0.07 0.33,0.18 0.46,0.33s0.23,0.34 0.3,0.57s0.11,0.5 0.11,0.82V14.24zM14.32,13.38c0,-0.19 -0.01,-0.35 -0.04,-0.48s-0.07,-0.23 -0.12,-0.31s-0.11,-0.14 -0.19,-0.17s-0.16,-0.05 -0.25,-0.05s-0.18,0.02 -0.25,0.05s-0.14,0.09 -0.19,0.17s-0.09,0.18 -0.12,0.31s-0.04,0.29 -0.04,0.48v0.97c0,0.19 0.01,0.35 0.04,0.48s0.07,0.24 0.12,0.32s0.11,0.14 0.19,0.17s0.16,0.05 0.25,0.05s0.18,-0.02 0.25,-0.05s0.14,-0.09 0.19,-0.17s0.09,-0.19 0.11,-0.32s0.04,-0.29 0.04,-0.48V13.38z") }
    val Forward10: ImageVector by lazy { playerIcon("Forward10", false, "M18,13c0,3.31 -2.69,6 -6,6s-6,-2.69 -6,-6s2.69,-6 6,-6v4l5,-5l-5,-5v4c-4.42,0 -8,3.58 -8,8c0,4.42 3.58,8 8,8s8,-3.58 8,-8H18z M10.86,15.94l0,-4.27l-0.09,0l-1.77,0.63l0,0.69l1.01,-0.31l0,3.26z M12.25,13.44v0.74c0,1.9 1.31,1.82 1.44,1.82c0.14,0 1.44,0.09 1.44,-1.82v-0.74c0,-1.9 -1.31,-1.82 -1.44,-1.82C13.55,11.62 12.25,11.53 12.25,13.44zM14.29,13.32v0.97c0,0.77 -0.21,1.03 -0.59,1.03c-0.38,0 -0.6,-0.26 -0.6,-1.03v-0.97c0,-0.75 0.22,-1.01 0.59,-1.01C14.07,12.3 14.29,12.57 14.29,13.32z") }
    val VolumeUp: ImageVector by lazy { playerIcon("VolumeUp", true, "M3,9v6h4l5,5L12,4L7,9L3,9zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z") }
    val VolumeOff: ImageVector by lazy { playerIcon("VolumeOff", true, "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9L3,9v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18L12,4z") }
    val Fullscreen: ImageVector by lazy { playerIcon("Fullscreen", false, "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z") }
    val FullscreenExit: ImageVector by lazy { playerIcon("FullscreenExit", false, "M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,8L16,5h-2v5h5L19,8h-3z") }
    val Cast: ImageVector by lazy { playerIcon("Cast", false, "M21,3L3,3c-1.1,0 -2,0.9 -2,2v3h2L3,5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM1,18v3h3c0,-1.66 -1.34,-3 -3,-3zM1,14v2c2.76,0 5,2.24 5,5h2c0,-3.87 -3.13,-7 -7,-7zM1,10v2c4.97,0 9,4.03 9,9h2c0,-6.08 -4.93,-11 -11,-11z") }
    val CastConnected: ImageVector by lazy { playerIcon("CastConnected", false, "M1,18v3h3c0,-1.66 -1.34,-3 -3,-3zM1,14v2c2.76,0 5,2.24 5,5h2c0,-3.87 -3.13,-7 -7,-7zM19,7L5,7v1.63c3.96,1.28 7.09,4.41 8.37,8.37L19,17L19,7zM1,10v2c4.97,0 9,4.03 9,9h2c0,-6.08 -4.93,-11 -11,-11zM21,3L3,3c-1.1,0 -2,0.9 -2,2v3h2L3,5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2L23,5c0,-1.1 -0.9,-2 -2,-2z") }
    val AutoAwesome: ImageVector by lazy { playerIcon("AutoAwesome", false, "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z") }
    val HighQuality: ImageVector by lazy { playerIcon("HighQuality", false, "M19,4L5,4c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,6c0,-1.1 -0.9,-2 -2,-2zM11,15L9.5,15v-2h-2v2L6,15L6,9h1.5v2.5h2L9.5,9L11,9v6zM18,14c0,0.55 -0.45,1 -1,1h-0.75v1.5h-1.5L14.75,15L14,15c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v4zM14.5,13.5h2v-3h-2v3z") }
    val MoreVert: ImageVector by lazy { playerIcon("MoreVert", false, "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z") }
    val Speed: ImageVector by lazy { playerIcon("Speed", false, "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z") }
}

private fun playerIcon(name: String, autoMirror: Boolean, pathData: String): ImageVector =
    ImageVector.Builder(
        name = "Filled.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
