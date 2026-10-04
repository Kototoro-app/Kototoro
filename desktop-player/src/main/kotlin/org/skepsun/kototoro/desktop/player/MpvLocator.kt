package org.skepsun.kototoro.desktop.player

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where libmpv may come from, first match wins: an explicit `kototoro.libmpv` path, the `KOTOTORO_LIBMPV` environment
 * variable, the packaged app's `mpv` resources, the user data `mpv` folder, then anything on PATH. Nothing is bundled
 * by default; the UI tells the user which folder to drop `libmpv-2.dll` into.
 */
object MpvLocator {
    val libraryNames = listOf("libmpv-2.dll", "mpv-2.dll", "libmpv.so.2", "libmpv.so", "libmpv.2.dylib", "libmpv.dylib")

    fun find(userDataDirectory: Path? = null): Path? = candidates(userDataDirectory).firstOrNull { Files.isRegularFile(it) }

    fun candidates(userDataDirectory: Path?): List<Path> {
        val directories = buildList {
            System.getProperty("kototoro.libmpv.dir")?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)
                ?.let { add(Path.of(it, "mpv")) }
            userDataDirectory?.let { add(it.resolve("mpv")) }
            System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar).filter(String::isNotBlank)
                .forEach { entry -> runCatching { Path.of(entry) }.getOrNull()?.let(::add) }
        }
        val explicit = listOfNotNull(System.getProperty("kototoro.libmpv"), System.getenv("KOTOTORO_LIBMPV"))
            .filter(String::isNotBlank).map { Path.of(it) }
        return explicit + directories.flatMap { directory -> libraryNames.map(directory::resolve) }
    }

    /** A standalone mpv player on PATH, used to hand a stream over when the library is missing. */
    fun findExecutable(): Path? = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
        .filter(String::isNotBlank).flatMap { entry ->
            listOf("mpv.exe", "mpv").mapNotNull { name -> runCatching { Path.of(entry, name) }.getOrNull() }
        }.firstOrNull { Files.isRegularFile(it) }

    /** mpv command-line arguments for [stream]; headers and side tracks travel the same way as in the library. */
    fun commandLine(executable: Path, stream: MpvStream): List<String> = buildList {
        add(executable.toString())
        val headers = stream.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (headers.isNotEmpty()) add("--http-header-fields=" + headers.entries.joinToString(",") {
            "${it.key}: ${it.value}".replace(",", "\\,")
        })
        stream.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { add("--user-agent=${it.value}") }
        if (stream.startSeconds > 0) add("--start=${stream.startSeconds.toInt()}")
        stream.title?.let { add("--force-media-title=$it") }
        stream.subtitles.forEach { add("--sub-file=${it.url}") }
        stream.audio.forEach { add("--audio-file=${it.url}") }
        add("--")
        add(stream.url)
    }
    /** The folder the app suggests to the user when no library was found. */
    fun suggestedDirectory(userDataDirectory: Path): Path = userDataDirectory.resolve("mpv")
}
