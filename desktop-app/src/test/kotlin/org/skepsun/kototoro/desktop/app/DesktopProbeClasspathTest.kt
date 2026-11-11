package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

internal fun writeProbeClasspathJar(output: Path, entries: List<Path>): Path {
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ") { it.toAbsolutePath().toUri().toASCIIString() }
    }
    JarOutputStream(Files.newOutputStream(output), manifest).use { }
    return output
}

class DesktopProbeClasspathTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `probe classpath loads jars and directories in order from unicode paths`() {
        val folder = Files.createDirectory(directory.resolve("中文 ไทย folder"))
        Files.writeString(folder.resolve("probe.txt"), "directory")
        Files.writeString(folder.resolve("only-directory.txt"), "directory")
        val fixture = directory.resolve("中文 ไทย fixture.jar")
        JarOutputStream(Files.newOutputStream(fixture)).use { jar ->
            jar.putNextEntry(JarEntry("probe.txt"))
            jar.write("jar".toByteArray())
            jar.closeEntry()
        }
        val classpath = writeProbeClasspathJar(directory.resolve("probe.jar"), List(500) { fixture } + listOf(folder))
        URLClassLoader(arrayOf(classpath.toUri().toURL()), null).use { loader ->
            assertEquals("jar", loader.getResourceAsStream("probe.txt")!!.bufferedReader().use { it.readText() })
            assertEquals("directory", loader.getResourceAsStream("only-directory.txt")!!.bufferedReader().use { it.readText() })
        }
    }
}
