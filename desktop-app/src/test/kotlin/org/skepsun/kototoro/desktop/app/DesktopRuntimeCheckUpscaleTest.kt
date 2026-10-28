package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.desktop.runtime.DesktopSuperResolution
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleTool
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--check-runtime`'s super-resolution probe on a data root where the ncnn programs are installed (opt-in:
 * `-PncnnDirectory` with the two release zips; needs a Vulkan GPU). Windows images bundle the programs, a Linux user
 * installs them from the reader's upscale row — this is that state, so the probe has to say `upscale=ok` rather than
 * `unavailable`.
 */
class DesktopRuntimeCheckUpscaleTest {
    @Test
    fun `the runtime check reports installed super resolution programs`(@TempDir directory: Path) {
        val archives = System.getProperty("kototoro.ncnn.dir")?.let(Path::of)
        assumeTrue(archives != null, "no -PncnnDirectory")
        val root = Files.createDirectories(directory.resolve("中文 data root"))
        val upscaling = DesktopSuperResolution(root)
        runBlocking {
            for (tool in DesktopUpscaleTool.entries) {
                val archive = requireNotNull(tool.asset()).archive
                upscaling.installArchive(tool, requireNotNull(archives).resolve(archive))
            }
        }
        val report = runBlocking { DesktopRuntimeCheck.probeUpscaling(root, upscaling, required = false) }
        for (tool in DesktopUpscaleTool.entries) {
            val program = report["upscale.${tool.name.lowercase()}"]
            assertTrue(program != null && program.startsWith(root.toString())) { "${tool.name}: $program" }
        }
        // `ok` is only written after every program upscaled the 32x48 sample and the 64 pixel width checked out.
        assertEquals("ok", report["upscale"])
        // A required program that is missing is a packaging bug, not a "not installed yet".
        val empty = DesktopSuperResolution(directory.resolve("empty root").also { Files.createDirectories(it) })
        val holder = Files.createDirectories(directory.resolve("page holder"))
        val missing = runBlocking { DesktopRuntimeCheck.probeUpscaling(holder, empty, required = false) }
        assertEquals("unavailable", missing["upscale"])
        val failure = runCatching {
            runBlocking { DesktopRuntimeCheck.probeUpscaling(holder, empty, required = true) }
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("unavailable") == true) { "required should fail: $failure" }
    }
}
