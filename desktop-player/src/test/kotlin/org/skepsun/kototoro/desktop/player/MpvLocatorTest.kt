package org.skepsun.kototoro.desktop.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * How libmpv is found on each desktop system. A Linux distribution installs `libmpv.so.2` into
 * `/usr/lib/<multiarch triplet>`, which is on neither PATH nor a folder the user picks, so these tests pin that the
 * locator looks there too — and that Windows keeps its DLL order.
 */
class MpvLocatorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `library names follow the platform`() {
        assertEquals(listOf("libmpv-2.dll", "mpv-2.dll"), MpvLocator.libraryNamesFor("Windows 11"))
        assertEquals(listOf("libmpv.2.dylib", "libmpv.dylib"), MpvLocator.libraryNamesFor("Mac OS X"))
        assertEquals(listOf("libmpv.so.2", "libmpv.so", "libmpv.so.1"), MpvLocator.libraryNamesFor("Linux"))
    }

    @Test
    fun `linux searches the multiarch directory of the machine`() {
        assertEquals(Path.of("/usr/lib/x86_64-linux-gnu"), MpvLocator.systemDirectories("Linux", "amd64").first())
        assertTrue(MpvLocator.systemDirectories("Linux", "aarch64").contains(Path.of("/usr/lib/aarch64-linux-gnu")))
        assertTrue(MpvLocator.systemDirectories("Linux", "armv7l").contains(Path.of("/usr/lib/arm-linux-gnueabihf")))
        assertTrue(MpvLocator.systemDirectories("Linux", "riscv64").contains(Path.of("/usr/lib/riscv64-linux-gnu")))
        // Distributions that keep libraries flat still work, and an architecture nobody mapped still gets those.
        val unknown = MpvLocator.systemDirectories("Linux", "mips64")
        assertTrue(unknown.contains(Path.of("/usr/lib")))
        assertTrue(unknown.none { it.toString().contains("-linux-gnu") })
        assertNull(MpvLocator.multiarchTriplet("mips64"))
    }

    @Test
    fun `windows keeps no system directories and macOS looks at homebrew`() {
        assertTrue(MpvLocator.systemDirectories("Windows 11", "amd64").isEmpty())
        assertEquals(listOf(Path.of("/opt/homebrew/lib"), Path.of("/usr/local/lib")),
            MpvLocator.systemDirectories("Mac OS X", "aarch64"))
    }

    @Test
    fun `library path entries are split like the loader does`() {
        val environment = mapOf(
            "LD_LIBRARY_PATH" to "/opt/lib${File.pathSeparator}/usr/lib/custom${File.pathSeparator}::",
        )
        assertEquals(listOf(Path.of("/opt/lib"), Path.of("/usr/lib/custom")),
            MpvLocator.libraryPathEntries(environment, "LD_LIBRARY_PATH"))
        assertTrue(MpvLocator.libraryPathEntries(emptyMap(), "LD_LIBRARY_PATH").isEmpty())
    }

    @Test
    fun `ldconfig output yields the libmpv entries`() {
        val output = """
            1234 libs found in cache `/etc/ld.so.cache'
            	libmpv.so.2 (libc6,x86-64) => /usr/lib/x86_64-linux-gnu/libmpv.so.2
            	libmpv.so (libc6,x86-64) => /usr/lib/x86_64-linux-gnu/libmpv.so
            	libssl.so.3 (libc6,x86-64) => /usr/lib/x86_64-linux-gnu/libssl.so.3
        """.trimIndent()
        assertEquals(listOf(Path.of("/usr/lib/x86_64-linux-gnu/libmpv.so.2"),
            Path.of("/usr/lib/x86_64-linux-gnu/libmpv.so")),
            MpvLocator.parseLdconfig(output, MpvLocator.libraryNamesFor("Linux")))
        assertTrue(MpvLocator.parseLdconfig(output, MpvLocator.libraryNamesFor("Windows 11")).isEmpty())
        assertTrue(MpvLocator.parseLdconfig("", MpvLocator.libraryNamesFor("Linux")).isEmpty())
    }

    @Test
    fun `an explicit path wins and the user data folder comes next`() {
        val userData = directory.resolve("data")
        Files.createDirectories(userData.resolve("mpv"))
        val library = userData.resolve("mpv").resolve(MpvLocator.libraryNames.first())
        Files.writeString(library, "not a real library")
        // `-PlibmpvDirectory` (the real playback tests) sets a directory that rightly outranks the user data folder.
        val configured = System.clearProperty("kototoro.libmpv.dir")
        try {
            assertEquals(library, MpvLocator.find(userData))
        } finally {
            configured?.let { System.setProperty("kototoro.libmpv.dir", it) }
        }

        val candidates = MpvLocator.candidates(userData, emptyMap(), "Linux", "amd64")
        val suggested = candidates.indexOf(userData.resolve("mpv").resolve("libmpv.so.2"))
        val system = candidates.indexOf(Path.of("/usr/lib/x86_64-linux-gnu/libmpv.so.2"))
        assertTrue(suggested >= 0 && system > suggested, "user data folder must be searched before the system ones")

        System.setProperty("kototoro.libmpv", directory.resolve("chosen.so").toString())
        try {
            assertEquals(Path.of(directory.resolve("chosen.so").toString()),
                MpvLocator.candidates(userData, emptyMap(), "Linux", "amd64").first())
        } finally {
            System.clearProperty("kototoro.libmpv")
        }
    }

    @Test
    fun `candidates come from LD_LIBRARY_PATH before PATH`() {
        val environment = mapOf("LD_LIBRARY_PATH" to "/opt/mpv", "PATH" to "/usr/bin")
        val candidates = MpvLocator.candidates(null, environment, "Linux", "amd64")
        val loader = candidates.indexOf(Path.of("/opt/mpv/libmpv.so.2"))
        val path = candidates.indexOf(Path.of("/usr/bin/libmpv.so.2"))
        assertTrue(loader >= 0 && path > loader, "LD_LIBRARY_PATH is where the loader looks, before PATH")
    }
}
