package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Native bundle operations stay behind the existing desktop installer; no second update state machine. */
internal object MacosAppUpdater {
    const val BUNDLE_ID = "org.skepsun.kototoro.desktop"

    suspend fun stage(download: DesktopDownloadedUpdate, staging: Path, installed: Path): Path {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(download.file).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(download.sha256, ignoreCase = true)) {
            "The downloaded DMG changed before staging"
        }
        Files.createDirectories(staging)
        val work = Files.createTempDirectory(staging, "macos-")
        val mount = Files.createDirectory(work.resolve("mount"))
        val image = work.resolve("image/Kototoro.app")
        try {
            command("/usr/bin/hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount.toString(),
                download.file.toAbsolutePath().toString())
            try {
                currentCoroutineContext().ensureActive()
                val source = mount.resolve("Kototoro.app")
                validate(source, installed, download.release.version)
                command("/usr/bin/ditto", source.toString(), image.toString())
                validate(image, installed, download.release.version)
            } finally { command("/usr/bin/hdiutil", "detach", mount.toString()) }
            currentCoroutineContext().ensureActive()
            return image
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw DesktopUpdateException("macOS update staging failed: ${error.message}", error)
        }
    }

    fun validate(bundle: Path, installed: Path, version: DesktopPackageVersion) {
        require(Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)) { "Missing Kototoro.app" }
        val executable = bundle.resolve("Contents/MacOS/Kototoro")
        require(Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(executable)) {
            "The update has no executable Kototoro launcher"
        }
        val identifier = command("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier",
            bundle.resolve("Contents/Info.plist").toString()).trim()
        require(identifier == BUNDLE_ID) { "The update belongs to another application" }
        command("/usr/bin/codesign", "--verify", "--deep", "--strict", bundle.toString())
        val team = team(installed)
        if (team != null) command("/usr/bin/codesign", "--verify", "--strict", "-R",
            "anchor apple generic and certificate leaf[subject.OU] = \"$team\"", bundle.toString())
        val cpu = when (DesktopUpdateTarget.current()) {
            DesktopUpdateTarget.MACOS_ARM64 -> 0x0100000c
            DesktopUpdateTarget.MACOS_X64 -> 0x01000007
            else -> error("macOS update architecture is unsupported")
        }
        require(macosExecutableArchitectures(executable).contains(cpu)) { "The update targets another CPU architecture" }
        val app = bundle.resolve("Contents/app")
        val stamp = Files.newDirectoryStream(app, "*.jar").use { jars ->
            jars.firstNotNullOfOrNull { jar ->
                ZipFile(jar.toFile()).use { zip ->
                    zip.getEntry("desktop-version.properties")?.let { entry ->
                        Properties().apply { zip.getInputStream(entry).use { load(it) } }
                    }
                }
            }
        } ?: error("The update carries no desktop version stamp")
        require(DesktopPackageVersion.parse(stamp.getProperty("versionName")).compareTo(version) == 0) {
            "The update bundle version differs from the release"
        }
    }

    fun team(bundle: Path): String? = command("/usr/bin/codesign", "--display", "--verbose=4", bundle.toString())
        .lineSequence().firstOrNull { it.startsWith("TeamIdentifier=") }?.substringAfter('=')
        ?.takeIf { it.matches(Regex("[A-Z0-9]{10}")) }

    private fun command(vararg arguments: String): String {
        val log = Files.createTempFile("kototoro-macos-update-", ".log")
        try {
            val process = ProcessBuilder(*arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                check(process.waitFor(90, TimeUnit.SECONDS)) { "${arguments.first()} timed out" }
                val output = Files.readString(log)
                check(process.exitValue() == 0) { "${arguments.first()}: ${output.takeLast(4000)}" }
                return output
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { Files.deleteIfExists(log) }
    }
}

/** Mach-O headers are enough to select a CPU; installed applications must not depend on Xcode's lipo shim. */
internal fun macosExecutableArchitectures(executable: Path): Set<Int> = Files.newByteChannel(executable).use { channel ->
    fun read(size: Int): ByteBuffer {
        val bytes = ByteBuffer.allocate(size)
        while (bytes.hasRemaining()) require(channel.read(bytes) > 0) { "Truncated Mach-O header" }
        return bytes.apply { flip() }
    }
    val header = read(8)
    val magic = header.int
    val little = magic in setOf(0xcefaedfe.toInt(), 0xcffaedfe.toInt(), 0xbebafeca.toInt(), 0xbfbafeca.toInt())
    val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    header.order(order)
    when (magic) {
        0xfeedface.toInt(), 0xfeedfacf.toInt(), 0xcefaedfe.toInt(), 0xcffaedfe.toInt() -> setOf(header.int)
        0xcafebabe.toInt(), 0xbebafeca.toInt(), 0xcafebabf.toInt(), 0xbfbafeca.toInt() -> {
            val count = header.int
            require(count in 1..64) { "Invalid Mach-O architecture count" }
            val entrySize = if (magic in setOf(0xcafebabf.toInt(), 0xbfbafeca.toInt())) 32 else 20
            val architectures = read(count * entrySize).order(order)
            (0 until count).map { architectures.getInt(it * entrySize) }.toSet()
        }
        else -> error("The update launcher is not a Mach-O executable")
    }
}

/** Identifies a real, writable bundle; a DMG or protected system installation cannot replace itself. */
internal fun macosInstallLayout(executable: Path): DesktopInstallLayout? {
    val contents = executable.parent?.parent ?: return null
    val root = contents.parent ?: return null
    if (executable.fileName?.toString() != "Kototoro" || executable.parent.fileName?.toString() != "MacOS" ||
        contents.fileName?.toString() != "Contents" || root.fileName?.toString()?.endsWith(".app") != true) return null
    if (!Files.isRegularFile(contents.resolve("Info.plist"), LinkOption.NOFOLLOW_LINKS) ||
        !Files.isDirectory(contents.resolve("runtime"), LinkOption.NOFOLLOW_LINKS) ||
        !Files.isExecutable(executable) || Files.isSymbolicLink(root) ||
        Files.getFileStore(root).isReadOnly || !Files.isWritable(root.parent)) return null
    return DesktopInstallLayout(root, executable, DesktopInstallKind.MACOS_APP)
}

internal fun macosUpdateScript(staged: DesktopStagedUpdate, log: Path, restart: Boolean,
    ownerPid: Long = ProcessHandle.current().pid(), restartArguments: List<String> = emptyList(),
    restartCommand: List<String> = listOf("/usr/bin/open", "-n", "-a", staged.root.toString(), "--args") + restartArguments,
): String {
    val requirement = MacosAppUpdater.team(staged.root)?.let {
        "/usr/bin/codesign --verify --strict -R ${("anchor apple generic and certificate leaf[subject.OU] = \"$it\"").shellQuote()} \"\$next\""
    }.orEmpty()
    val relaunch = if (restart) restartCommand.joinToString(" ") { it.shellQuote() } else ":"
    return """
        |#!/bin/sh
        |set -eu
        |exec >>${log.toString().shellQuote()} 2>&1
        |source=${staged.packagePath.toString().shellQuote()}
        |target=${staged.root.toString().shellQuote()}
        |count=0
        |while kill -0 $ownerPid 2>/dev/null; do
        |  count=${'$'}((count + 1))
        |  [ "${'$'}count" -lt 180 ] || { printf '%s\n' 'Owner did not exit; update cancelled'; exit 1; }
        |  /bin/sleep 1
        |done
        |[ -d "${'$'}target" ] && [ ! -L "${'$'}target" ]
        |parent=${'$'}(/usr/bin/dirname "${'$'}target")
        |transaction=${'$'}(/usr/bin/mktemp -d "${'$'}parent/.kototoro-update.XXXXXX")
        |next="${'$'}transaction/next.app"
        |backup="${'$'}transaction/previous.app"
        |rollback() {
        |  status=${'$'}?
        |  trap - EXIT HUP INT TERM
        |  if [ "${'$'}status" -ne 0 ] && [ -d "${'$'}backup" ]; then
        |    if [ -e "${'$'}target" ]; then /bin/mv "${'$'}target" "${'$'}transaction/failed.app"; fi
        |    /bin/mv "${'$'}backup" "${'$'}target"
        |    printf '%s\n' 'Update failed; previous application restored'
        |  fi
        |  exit "${'$'}status"
        |}
        |trap rollback EXIT
        |trap 'exit 1' HUP INT TERM
        |/usr/bin/ditto "${'$'}source" "${'$'}next"
        |/usr/bin/codesign --verify --deep --strict "${'$'}next"
        |$requirement
        |[ "${'$'}(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${'$'}next/Contents/Info.plist")" = '${MacosAppUpdater.BUNDLE_ID}' ]
        |[ -x "${'$'}next/Contents/MacOS/Kototoro" ]
        |/bin/mv "${'$'}target" "${'$'}backup"
        |/bin/mv "${'$'}next" "${'$'}target"
        |$relaunch
        |printf '%s\n' 'Update installed; previous bundle retained in transaction directory'
    """.trimMargin()
}

private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
