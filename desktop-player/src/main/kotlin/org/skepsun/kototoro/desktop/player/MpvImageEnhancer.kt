package org.skepsun.kototoro.desktop.player

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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
 * Runs Anime4K over a still image with libmpv alone: encoding mode decodes the picture, the `gpu` video filter renders
 * it offscreen (Vulkan / D3D11, no window) through the same user shaders the player uses, and a PNG comes out.
 */
class MpvImageEnhancer(private val library: Path, private val shaders: MpvShaderLibrary) {
    fun enhance(input: Path, output: Path, width: Int, height: Int, preset: Anime4KImagePreset) {
        require(width > 0 && height > 0)
        val files = preset.shaders.map { shaders.file(it) }
        Files.deleteIfExists(output)
        var failure: String? = null
        MpvPlayer(library, options = mapOf(
            "o" to output.toAbsolutePath().toString(), "of" to "image2", "ovc" to "png", "frames" to "1",
            "vf" to "gpu=w=${width * preset.scale}:h=${height * preset.scale}",
            "glsl-shaders" to files.joinToString(java.io.File.pathSeparator) { it.toAbsolutePath().toString() },
            "keep-open" to "no", "audio" to "no",
        )).use { player ->
            player.load(MpvStream(input.toAbsolutePath().toString()))
            val deadline = System.currentTimeMillis() + 60_000
            while (player.property("idle-active") != "yes") {
                player.state.value.error?.let { failure = it }
                if (failure != null) break
                if (System.currentTimeMillis() > deadline) { failure = "处理超时"; break }
                Thread.sleep(25)
            }
        }
        if (failure != null || !Files.isRegularFile(output) || Files.size(output) == 0L) {
            throw IOException("Anime4K 图片处理失败：${failure ?: "没有输出"}")
        }
    }
}