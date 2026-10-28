package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** How the running copy of the app was installed; it decides which package can replace it. */
enum class DesktopInstallKind {
    /** The jpackage image plus its portable ZIP: the update overwrites the image in place. */
    PORTABLE,

    /** A per-user MSI: the update is handed to `msiexec`, which owns upgrade and file replacement. */
    MSI,
    MACOS_APP,
}

/** The installed application image: `<root>/Kototoro.exe` beside `app/` and `runtime/`. */
data class DesktopInstallLayout(val root: Path, val executable: Path, val kind: DesktopInstallKind)

/**
 * An update that has been downloaded, verified and unpacked as far as it can be while the app runs. Everything the
 * swap needs — the source image, the target root — is resolved here; only the file replacement waits for exit.
 */
data class DesktopStagedUpdate(
    val release: DesktopUpdateRelease,
    val kind: DesktopInstallKind,
    /** The MSI, or the directory that holds the new image to copy over [root]. */
    val packagePath: Path,
    val root: Path,
    val executable: Path,
    val sha256: String,
)

/**
 * Finds the installed image and replaces it with a downloaded update.
 *
 * The application image and the user data live apart (`DesktopDataPaths` keeps the database, images and preferences
 * under `%LOCALAPPDATA%/Kototoro`, the program under `%LOCALAPPDATA%/Kototoro-App`), so replacing the image touches no
 * user data. The image cannot replace itself while it is running, so [apply] hands the swap to a small PowerShell
 * script that waits for this process to exit, copies the new image, and starts the app again.
 */
class DesktopUpdateInstaller(
    private val layout: DesktopInstallLayout?,
    private val registryQuery: () -> String? = ::queryUninstallRegistry,
) {
    /** The image this process runs from, or null when it was not started from an installed package (Gradle, IDE). */
    fun layout(): DesktopInstallLayout? = layout

    /** Whether the update machinery can replace what is running; the window hides the feature when it cannot. */
    val canSelfUpdate: Boolean get() = layout != null

    /**
     * Unpacks a verified download into [staging]. A ZIP is expanded and its image located, so a package with the
     * wrong layout fails here, while the app is still running, instead of after it has quit.
     */
    suspend fun stage(
        download: DesktopDownloadedUpdate,
        staging: Path,
    ): DesktopStagedUpdate = withContext(Dispatchers.IO) {
        val install = layout ?: throw DesktopUpdateException(
            DesktopRuntimeStrings.text("runtime.update.notInstalled", "this copy is not an installed package, so it cannot update itself"))
        require((install.kind == DesktopInstallKind.MACOS_APP) == (download.packageKind == DesktopUpdatePackage.DMG)) {
            "The update package does not match the installation platform"
        }
        val packagePath = when (download.packageKind) {
            DesktopUpdatePackage.MSI -> download.file
            DesktopUpdatePackage.PORTABLE_ZIP -> expand(download.file, staging)
            DesktopUpdatePackage.DMG -> {
                require(install.kind == DesktopInstallKind.MACOS_APP) { "A DMG requires a macOS installation" }
                MacosAppUpdater.stage(download, staging, install.root)
            }
        }
        DesktopStagedUpdate(
            release = download.release,
            kind = install.kind,
            packagePath = packagePath,
            root = install.root,
            executable = install.executable,
            sha256 = download.sha256,
        )
    }

    /**
     * Starts the script that performs the swap once this process exits. The window must close itself afterwards —
     * the script waits for this process and then continues alone.
     */
    fun apply(staged: DesktopStagedUpdate, logDirectory: Path, restart: Boolean = true,
        restartArguments: List<String> = emptyList()): Path {
        require(layout?.root == staged.root && layout.kind == staged.kind) { "The staged update belongs to another installation" }
        Files.createDirectories(logDirectory)
        if (staged.kind == DesktopInstallKind.MACOS_APP) {
            MacosAppUpdater.validate(staged.packagePath, staged.root, staged.release.version)
            val script = logDirectory.resolve("apply-update-${java.util.UUID.randomUUID()}.sh")
            val log = logDirectory.resolve("update.log")
            Files.writeString(script, macosUpdateScript(staged, log, restart, restartArguments = restartArguments))
            ProcessBuilder("/bin/sh", script.toAbsolutePath().toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start()
            return script
        }
        val script = logDirectory.resolve("apply-update-${System.currentTimeMillis()}.ps1")
        val log = logDirectory.resolve("update.log")
        Files.writeString(script, updateScript(staged, log, restart), Charsets.UTF_8)
        val powershell = System.getenv("SystemRoot")?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" }
            ?: "powershell.exe"
        ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start()
        return script
    }

    /** Expands the portable ZIP and returns the directory that holds `Kototoro.exe`. */
    private fun expand(zip: Path, staging: Path): Path {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) staging.toFile().deleteRecursively()
        Files.createDirectories(staging)
        val normalizedStaging = staging.toAbsolutePath().normalize()
        Files.newInputStream(zip).use { input ->
            ZipInputStream(input).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val target = normalizedStaging.resolve(entry.name).normalize()
                    if (!target.startsWith(normalizedStaging)) throw DesktopUpdateException(
                        DesktopRuntimeStrings.text("runtime.update.unsafeEntry", "the package contains an unsafe path: %s", entry.name))
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        target.parent?.let(Files::createDirectories)
                        Files.copy(archive, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
        return findImageRoot(normalizedStaging) ?: throw DesktopUpdateException(
            DesktopRuntimeStrings.text("runtime.update.notAnImage", "the package is not a Kototoro image"))
    }
}

/** The directory inside an expanded package that holds `Kototoro.exe`, at most one level down (`Kototoro/`). */
internal fun findImageRoot(expanded: Path): Path? {
    fun isImage(directory: Path) = Files.isRegularFile(directory.resolve("Kototoro.exe"), LinkOption.NOFOLLOW_LINKS) &&
        Files.isDirectory(directory.resolve("app"), LinkOption.NOFOLLOW_LINKS) &&
        Files.isDirectory(directory.resolve("runtime"), LinkOption.NOFOLLOW_LINKS)
    if (isImage(expanded)) return expanded
    Files.newDirectoryStream(expanded).use { children ->
        return children.firstOrNull { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && isImage(it) }
    }
}

/**
 * Reads the `reg query HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall /s /f Kototoro` output for the
 * install location of a per-user package. The MSI writes `InstallLocation`, which is how an MSI installation is
 * told apart from an unpacked portable image: the two look identical on disk, both under `%LOCALAPPDATA%/Kototoro-App`.
 */
internal fun parseMsiInstallLocation(output: String): Path? = output.lineSequence()
    .mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("InstallLocation", ignoreCase = true)) return@mapNotNull null
        val value = trimmed.substringAfter("REG_SZ", "").trim().trim('"')
        value.takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }
    }
    .firstOrNull()

/** The image this JVM was started from, or null outside a jpackage image. */
fun locateInstallLayout(
    appPath: String? = System.getProperty("jpackage.app-path"),
    registryQuery: () -> String? = ::queryUninstallRegistry,
): DesktopInstallLayout? {
    val executable = executableFromAppPath(appPath) ?: executableFromClassPath() ?: return null
    if (executable.fileName.toString() == "Kototoro") return macosInstallLayout(executable)
    val root = executable.parent ?: return null
    val kind = if (registryQuery()?.let(::parseMsiInstallLocation)?.let { it == root.toAbsolutePath().normalize() } == true) {
        DesktopInstallKind.MSI
    } else {
        DesktopInstallKind.PORTABLE
    }
    return DesktopInstallLayout(root, executable, kind)
}

private fun executableFromAppPath(appPath: String?): Path? {
    val candidate = appPath?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: return null
    return candidate.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
}

/**
 * `<root>/app/<jar>` on the classpath identifies the image without the launcher's property, which keeps the updater
 * testable and covers a JVM started straight from the image's runtime by the distribution probes.
 */
private fun executableFromClassPath(): Path? {
    val location = DesktopInstallLayout::class.java.protectionDomain?.codeSource?.location ?: return null
    val appDirectory = runCatching { Path.of(location.toURI()) }.getOrNull()?.parent ?: return null
    if (appDirectory.fileName?.toString() != "app" || !Files.isDirectory(appDirectory)) return null
    val root = appDirectory.parent ?: return null
    if (root.fileName?.toString() == "Contents") {
        return root.resolve("MacOS/Kototoro").takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }
    return root.resolve("Kototoro.exe").takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
}

private fun queryUninstallRegistry(): String? = runCatching {
    val systemRoot = System.getenv("SystemRoot") ?: return@runCatching null
    val process = ProcessBuilder("$systemRoot\\System32\\reg.exe", "query",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "/s", "/f", "Kototoro")
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
    output.ifBlank { null }
}.getOrNull()

/**
 * The script that replaces the image after this process exits.
 *
 * It waits for [DesktopStagedUpdate]'s own process id, refuses an incomplete image, copies with `robocopy` (which
 * overwrites files in place and tolerates the launcher still holding a handle for a moment), and starts the app
 * again. Every step is logged beside the package, so a failed update is diagnosable instead of silent.
 */
internal fun updateScript(staged: DesktopStagedUpdate, log: Path, restart: Boolean): String {
    val root = staged.root.toString()
    val executable = staged.executable.toString()
    val body = when (staged.kind) {
        DesktopInstallKind.MACOS_APP -> error("macOS uses macosUpdateScript")
        DesktopInstallKind.PORTABLE -> """
            |${'$'}source = '${staged.packagePath.toString().escapeForPowerShell()}'
            |if (!(Test-Path -LiteralPath (Join-Path ${'$'}source 'Kototoro.exe'))) { throw 'the staged image has no Kototoro.exe' }
            |if (!(Test-Path -LiteralPath (Join-Path ${'$'}source 'app'))) { throw 'the staged image has no app directory' }
            |${'$'}copy = Start-Process -FilePath 'robocopy.exe' -ArgumentList @(
            |    ${'$'}source, '${root.escapeForPowerShell()}', '/E', '/R:2', '/W:1', '/NP', '/NFL', '/NDL', '/NJH', '/NJS'
            |) -Wait -PassThru -WindowStyle Hidden
            |if (${'$'}copy.ExitCode -ge 8) { throw "robocopy failed with exit code ${'$'}(${'$'}copy.ExitCode)" }
        """.trimMargin()
        DesktopInstallKind.MSI -> """
            |${'$'}msi = '${staged.packagePath.toString().escapeForPowerShell()}'
            |${'$'}client = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + ${'$'}msi + '"'), '/qb', '/norestart') -Wait -PassThru -WindowStyle Hidden
            |if (${'$'}client.ExitCode -ne 0) { throw "msiexec failed with exit code ${'$'}(${'$'}client.ExitCode)" }
        """.trimMargin()
    }
    val relaunch = if (restart) "Start-Process -FilePath '${executable.escapeForPowerShell()}'" else "# no restart requested"
    return """
        |${'$'}ErrorActionPreference = 'Stop'
        |${'$'}log = '${log.toString().escapeForPowerShell()}'
        |function Write-UpdateLog([string] ${'$'}message) {
        |    "${'$'}([DateTime]::Now.ToString('s')) ${'$'}message" | Out-File -LiteralPath ${'$'}log -Append -Encoding utf8
        |}
        |Write-UpdateLog 'update ${staged.release.version.text} (sha256 ${staged.sha256}) starting'
        |try {
        |    ${'$'}owner = Get-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
        |    if (${'$'}null -ne ${'$'}owner) { ${'$'}owner.WaitForExit(180000) | Out-Null }
        |    $body
        |    Write-UpdateLog 'update installed'
        |    $relaunch
        |} catch {
        |    Write-UpdateLog ("update failed: " + ${'$'}_.Exception.Message)
        |    exit 1
        |}
    """.trimMargin()
}

private fun String.escapeForPowerShell() = replace("'", "''")
