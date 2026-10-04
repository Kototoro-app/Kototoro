package org.skepsun.kototoro.desktop.player

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Real-time video enhancement as on Android: Anime4K presets (the same GLSL files the Android player runs) and AMD
 * FidelityFX Super Resolution 1.0. mpv runs them as user shaders on its GPU output.
 */
enum class MpvEnhancementMode(val title: String, internal val shaders: List<String>) {
    OFF("关闭", emptyList()),
    /** Android "fast": Mode B, balanced restore and upscale. */
    ANIME4K_FAST("Anime4K 快速", listOf(
        "Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl", "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_S.glsl",
    )),
    /** Android "quality": Mode A, strong restore and upscale. */
    ANIME4K_QUALITY("Anime4K 质量", listOf(
        "Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_VL.glsl", "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_M.glsl",
    )),
    /** Mode C: restore only, for sources already at display size. */
    ANIME4K_RESTORE("Anime4K 仅修复", listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_S.glsl")),
    FSR("FSR 1.0", listOf("FSR.glsl")),
}

/** A mode plus FSR's sharpness (0 = soft … 1 = sharpest, the Android setting's scale). */
data class MpvEnhancement(val mode: MpvEnhancementMode = MpvEnhancementMode.OFF, val fsrSharpness: Float = 0.9f)

/**
 * Unpacks the bundled shaders into [directory] (once per content) and turns a choice into mpv's `glsl-shaders` list.
 * FSR's sharpness is a compile-time define in the shader, so each sharpness gets its own small file.
 */
class MpvShaderLibrary(private val directory: Path) {
    fun files(enhancement: MpvEnhancement): List<Path> {
        if (enhancement.mode == MpvEnhancementMode.OFF) return emptyList()
        Files.createDirectories(directory)
        return enhancement.mode.shaders.map { name ->
            if (name == "FSR.glsl") fsr(enhancement.fsrSharpness) else extract(name)
        }
    }

    /** One bundled shader, unpacked. */
    fun file(name: String): Path { Files.createDirectories(directory); return extract(name) }

    private fun extract(name: String): Path {
        val bytes = resource(name)
        val target = directory.resolve(name)
        if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) write(target, bytes)
        return target
    }

    /** RCAS takes "stops" of sharpness reduction (0 = maximum); Android maps its 0..1 slider as 2 × (1 − value). */
    private fun fsr(sharpness: Float): Path {
        val stops = 2f * (1f - sharpness.coerceIn(0f, 1f))
        val source = resource("FSR.glsl").decodeToString()
        val tuned = Regex("""#define SHARPNESS [0-9.]+""").replace(source, "#define SHARPNESS ${"%.2f".format(Locale.ROOT, stops)}")
        val target = directory.resolve("FSR-${"%.2f".format(Locale.ROOT, stops)}.glsl")
        val bytes = tuned.encodeToByteArray()
        if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) write(target, bytes)
        return target
    }

    private fun write(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(directory, ".shader-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/shaders/$name")) {
        "Missing bundled shader $name"
    }.use { it.readBytes() }
}
