package org.skepsun.kototoro.desktop.player

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Where libmpv may come from, first match wins: an explicit `kototoro.libmpv` path, the `KOTOTORO_LIBMPV` environment
 * variable, the packaged app's `mpv` resources, the user data `mpv` folder, the platform's library directories, then
 * `LD_LIBRARY_PATH`/`DYLD_LIBRARY_PATH`, PATH and finally whatever `ldconfig` reports. Nothing is bundled by default;
 * the UI tells the user where to put the library.
 *
 * Windows only ever ships `libmpv-2.dll` next to the app, but a Linux distribution installs `libmpv.so.2` through its
 * package manager, so `/usr/lib/<multiarch triplet>` has to be searched: that directory is not on PATH and a player
 * that only looked at PATH would report "libmpv missing" on a machine that has it.
 */
object MpvLocator {
    // macOS needs the libmpv render API; an AWT component pointer is not a supported mpv --wid target there.
    // On Linux the AWT canvas becomes an X11 window (XWayland under Wayland sessions) and JNA's component id is that
    // X11 drawable, which is what mpv's wid option takes.
    val supportsEmbeddedPlayback: Boolean get() = !com.sun.jna.Platform.isMac()

    val libraryNames: List<String> get() = libraryNamesFor(System.getProperty("os.name").orEmpty())

    internal fun libraryNamesFor(osName: String): List<String> = when {
        osName.startsWith("Windows", ignoreCase = true) -> listOf("libmpv-2.dll", "mpv-2.dll")
        osName.startsWith("Mac", ignoreCase = true) || osName.equals("Darwin", ignoreCase = true) ->
            listOf("libmpv.2.dylib", "libmpv.dylib")
        else -> listOf("libmpv.so.2", "libmpv.so", "libmpv.so.1")
    }

    fun find(userDataDirectory: Path? = null): Path? =
        candidates(userDataDirectory).firstOrNull { Files.isRegularFile(it) }

    fun candidates(
        userDataDirectory: Path?,
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name").orEmpty(),
        architecture: String = System.getProperty("os.arch").orEmpty(),
    ): List<Path> {
        val names = libraryNamesFor(osName)
        val linux = isLinux(osName)
        val directories = buildList {
            System.getProperty("kototoro.libmpv.dir")?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)
                ?.let { add(Path.of(it, "mpv")) }
            userDataDirectory?.let { add(it.resolve("mpv")) }
            addAll(systemDirectories(osName, architecture))
            addAll(libraryPathEntries(environment, if (linux) "LD_LIBRARY_PATH" else "DYLD_LIBRARY_PATH"))
            addAll(pathEntries(environment))
        }
        val explicit = listOfNotNull(System.getProperty("kototoro.libmpv"), environment["KOTOTORO_LIBMPV"])
            .filter(String::isNotBlank).map { Path.of(it) }
        val cached = if (linux) ldconfigCache else emptyList()
        return explicit + directories.flatMap { directory -> names.map(directory::resolve) } + cached
    }

    /** Directories a distribution keeps its libraries in, per platform and machine architecture. */
    internal fun systemDirectories(osName: String, architecture: String): List<Path> = when {
        isLinux(osName) -> buildList {
            multiarchTriplet(architecture)?.let { triplet ->
                add(Path.of("/usr/lib", triplet))
                add(Path.of("/lib", triplet))
            }
            val flat = listOf("/usr/lib64", "/usr/lib", "/usr/local/lib64", "/usr/local/lib", "/lib64", "/lib")
            for (directory in flat) {
                add(Path.of(directory))
            }
        }.distinct()
        osName.startsWith("Mac", ignoreCase = true) || osName.equals("Darwin", ignoreCase = true) ->
            listOf(Path.of("/opt/homebrew/lib"), Path.of("/usr/local/lib"))
        else -> emptyList()
    }

    /** Debian-style per-architecture library directory, which is what `apt install libmpv2` writes into. */
    internal fun multiarchTriplet(architecture: String): String? = when (architecture.lowercase()) {
        "amd64", "x86_64", "x86-64" -> "x86_64-linux-gnu"
        "aarch64", "arm64" -> "aarch64-linux-gnu"
        "arm", "armv7l", "armv7" -> "arm-linux-gnueabihf"
        "i386", "i486", "i586", "i686", "x86" -> "i386-linux-gnu"
        "riscv64" -> "riscv64-linux-gnu"
        "ppc64le" -> "powerpc64le-linux-gnu"
        "s390x" -> "s390x-linux-gnu"
        "loongarch64" -> "loongarch64-linux-gnu"
        else -> null
    }

    internal fun libraryPathEntries(environment: Map<String, String>, variable: String): List<Path> =
        environment[variable].orEmpty().split(java.io.File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .mapNotNull { runCatching { Path.of(it) }.getOrNull() }

    internal fun pathEntries(environment: Map<String, String>): List<Path> =
        environment["PATH"].orEmpty().split(java.io.File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .mapNotNull { runCatching { Path.of(it) }.getOrNull() }

    /** Reads the loader cache (`libmpv.so.2 (libc6,x86-64) => /usr/lib/x86_64-linux-gnu/libmpv.so.2`). */
    internal fun parseLdconfig(output: String, names: List<String>): List<Path> = output.lineSequence()
        .mapNotNull { line -> line.substringAfter("=>", missingDelimiterValue = "").trim().takeIf(String::isNotBlank) }
        .filter { path -> names.any { name -> path.substringAfterLast('/') == name } }
        .map { Path.of(it) }
        .toList()

    /** The loader cache, when the machine has one; a missing or failing `ldconfig` just means no extra candidates. */
    internal fun ldconfigLibraryPaths(names: List<String>): List<Path> = runCatching {
        val process = ProcessBuilder("ldconfig", "-p").redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            if (process.exitValue() != 0) emptyList() else parseLdconfig(output, names)
        } finally {
            process.destroyForcibly()
        }
    }.getOrDefault(emptyList())

    /** A standalone mpv player on PATH, used to hand a stream over when the library is missing. */
    fun findExecutable(
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path? {
        val name = if (osName.startsWith("Windows", ignoreCase = true)) "mpv.exe" else "mpv"
        val extras = when {
            osName.startsWith("Mac", ignoreCase = true) || osName.equals("Darwin", ignoreCase = true) ->
                listOf("/opt/homebrew/bin", "/usr/local/bin")
            isLinux(osName) -> listOf("/usr/bin", "/usr/local/bin", "/snap/bin", "/var/lib/flatpak/exports/bin")
            else -> emptyList()
        }
        return (pathEntries(environment).map(Path::toString) + extras)
            .filter(String::isNotBlank)
            .mapNotNull { entry -> runCatching { Path.of(entry, name) }.getOrNull() }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    /** mpv command-line arguments for [stream]; headers and side tracks travel the same way as in the library. */
    fun commandLine(executable: Path, stream: MpvStream): List<String> = buildList {
        add(executable.toString())
        val headers = stream.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (headers.isNotEmpty()) add("--http-header-fields=" + headers.entries.joinToString(",") {
            "${it.key}: ${it.value}".replace(",", "\\,")
        })
        stream.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { add("--user-agent=${it.value}") }
        if (stream.startSeconds > 0) add("--start=${stream.startSeconds.toInt()}")
        stream.title?.let { add("--force-media-title=$it") }
        stream.subtitles.forEach { add("--sub-file=${it.url}") }
        stream.audio.forEach { add("--audio-file=${it.url}") }
        add("--")
        add(stream.url)
    }

    /** The folder the app suggests to the user when no library was found. */
    fun suggestedDirectory(userDataDirectory: Path): Path = userDataDirectory.resolve("mpv")

    /**
     * Linux distributions package libmpv, so the hint tells the user to install it with the package manager instead of
     * dropping a Windows DLL into a folder. Windows and macOS keep the folder/Homebrew wording.
     */
    val packagedByDistribution: Boolean get() = com.sun.jna.Platform.isLinux()

    /** The loader cache is read once per process: it cannot change while the app runs. */
    private val ldconfigCache: List<Path> by lazy { ldconfigLibraryPaths(libraryNames) }

    private fun isLinux(osName: String): Boolean = osName.startsWith("Linux", ignoreCase = true)
}
