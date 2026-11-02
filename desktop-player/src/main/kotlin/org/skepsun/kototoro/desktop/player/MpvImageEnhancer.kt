package org.skepsun.kototoro.desktop.player

import com.sun.jna.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * The Android reader's Anime4K image modes (A, B, C, A+A, B+B, C+A): the same shader chains, with the scale they
 * produce (C modes restore without upscaling).
 */
enum class Anime4KImagePreset(val title: String, val scale: Int, val shaders: List<String>) {
    A("Anime4K A", 2, listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_VL.glsl", "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_M.glsl")),
    B("Anime4K B", 2, listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl", "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_S.glsl")),
    C("Anime4K C", 1, listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_S.glsl")),
    AA("Anime4K A+A", 2, listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_VL.glsl", "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl")),
    BB("Anime4K B+B", 2, listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl", "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl")),
    CA("Anime4K C+A", 1, listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl")),
}

/**
 * Runs Anime4K over a still image with libmpv alone, through the same user shaders the player uses, and a PNG comes
 * out. Windows and Linux use encoding mode with the `gpu` video filter (Vulkan / D3D11, no window). The libmpv bundled
 * on macOS is built without FFmpeg encoders or muxers, so there the picture is rendered into an offscreen CGL
 * framebuffer instead ([MpvRenderMode.MACOS_OPENGL]) and written by ImageIO.
 */
class MpvImageEnhancer(private val library: Path, private val shaders: MpvShaderLibrary) {
    fun enhance(input: Path, output: Path, width: Int, height: Int, preset: Anime4KImagePreset) =
        enhance(input, output, width * preset.scale, height * preset.scale, preset.shaders.map { shaders.file(it) })

    /** [width]×[height] is the output size; an empty [files] list is plain scaling (tests compare against it). */
    internal fun enhance(input: Path, output: Path, width: Int, height: Int, files: List<Path>) {
        require(width > 0 && height > 0)
        Files.deleteIfExists(output)
        if (Platform.isMac()) render(input, output, width, height, files) else encode(input, output, width, height, files)
        if (!Files.isRegularFile(output) || Files.size(output) == 0L) throw IOException("Anime4K 图片处理失败：没有输出")
    }

    private fun render(input: Path, output: Path, width: Int, height: Int, files: List<Path>) {
        MpvPlayer(library, options = mapOf(
            "glsl-shaders" to shaderList(files), "audio" to "no", "image-display-duration" to "inf",
        ), renderMode = MpvRenderMode.MACOS_OPENGL).use { player ->
            player.load(MpvStream(input.toAbsolutePath().toString()))
            // The picture is on the video output once mpv reports a configured output and a playback position.
            awaitPlayer(player) { player.property("vo-configured") == "yes" && player.property("time-pos") != null }
            val image = player.renderFrame(width, height)
            try {
                if (!ImageIO.write(image, "png", output.toFile())) throw IOException("Anime4K 图片处理失败：无法写出 PNG")
            } finally {
                image.flush()
            }
        }
    }

    private fun encode(input: Path, output: Path, width: Int, height: Int, files: List<Path>) {
        MpvPlayer(library, options = mapOf(
            "o" to output.toAbsolutePath().toString(), "of" to "image2", "ovc" to "png", "frames" to "1",
            "vf" to "gpu=w=$width:h=$height", "glsl-shaders" to shaderList(files),
            "keep-open" to "no", "audio" to "no",
        )).use { player ->
            player.load(MpvStream(input.toAbsolutePath().toString()))
            awaitPlayer(player) { player.property("idle-active") == "yes" }
        }
    }

    private fun shaderList(files: List<Path>) = files.joinToString(java.io.File.pathSeparator) { it.toAbsolutePath().toString() }

    private fun awaitPlayer(player: MpvPlayer, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!done()) {
            player.state.value.error?.let { throw IOException("Anime4K 图片处理失败：$it") }
            if (System.currentTimeMillis() > deadline) throw IOException("Anime4K 图片处理失败：处理超时")
            Thread.sleep(25)
        }
    }
}
