package org.skepsun.kototoro.video.player

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object Anime4KShaderAssets {

    private const val ASSET_DIR = "shaders"
    private const val TARGET_DIR = "anime4k_shaders"

    // Mode A: strong restore + upscale
    val modeAPreset = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
    )

    // Mode B: balanced restore + upscale
    val modeBPreset = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_S.glsl",
    )

    // Mode C: denoise only, no upscale
    val modeCPreset = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_S.glsl",
    )

    // Mode A+A: enhanced Mode A, heavier restore
    val modeAPlusPreset = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
    )

    // Mode B+B: enhanced Mode B, moderate extra restore
    val modeBPlusPreset = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
    )

    // Mode C+A: enhanced Mode C, stronger denoise without upscale
    val modeCAPlusPreset = listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl",
    )

    // Backward-compatible aliases
    val qualityPreset = modeAPreset
    val efficiencyPreset = modeBPreset
    fun ensureShadersCopied(context: Context): File {
        val targetDir = File(context.filesDir, TARGET_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val assetManager = context.assets
        val shaderFiles = assetManager.list(ASSET_DIR).orEmpty()
            .filter { it.endsWith(".glsl", ignoreCase = true) || it.equals("LICENSE", true) }
        for (file in shaderFiles) {
            val outFile = File(targetDir, file)
            if (outFile.exists()) continue
            assetManager.open("$ASSET_DIR/$file").use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return targetDir
    }

    fun buildShaderPathList(directory: File, shaders: List<String>): String {
        val separator = if (File.separatorChar == '\\') ';' else ':'
        return shaders.joinToString(separator.toString()) { File(directory, it).absolutePath }
    }
}
