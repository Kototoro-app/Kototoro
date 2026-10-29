package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.desktop.runtime.DesktopDataPaths
import org.skepsun.kototoro.desktop.runtime.DesktopPlatform
import java.awt.Dimension
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Keep Compose's default popup layers. compose.layers.type=WINDOW (once set here so player menus stay above the
    // native video surface) crashed the JVM on the first dropdown or dialog: on Windows a popup window's bounds change
    // re-renders it inside its own frame and skiko dies in PictureRecorder.finishRecordingAsPicture.
    try {
        runDesktop(args)
    } catch (error: Throwable) {
        // The jpackage launcher never exits when main ends with an exception ("Failed to launch JVM" and a hung
        // process), so report the failure and leave through the JVM instead.
        error.printStackTrace()
        exitProcess(1)
    }
}

internal fun runDesktop(args: Array<String>) {
    val options = args.toList()
    require(options.size % 2 == 0 &&
        options.chunked(2).all { it[0] in setOf("--data-dir", "--import", "--check-runtime", "--check-update", "--channel", "--lang") }) {
        "Usage: [--data-dir <directory>] [--import <extension.jar>] [--check-runtime <report>] " +
            "[--check-update <report> [--channel <release|nightly>]] [--lang <tag>]"
    }
    // `--lang` (or -Dkototoro.desktop.locale) fixes the language for this run instead of the saved choice.
    options.chunked(2).lastOrNull { it[0] == "--lang" }?.let { DesktopStrings.pin(it[1]) }
    val root = options.chunked(2).lastOrNull { it[0] == "--data-dir" }?.get(1)?.let(Path::of)
        ?: DesktopDataPaths.defaultRoot()
    val imports = options.chunked(2).filter { it[0] == "--import" }.map { Path.of(it[1]) }
    options.chunked(2).lastOrNull { it[0] == "--check-runtime" }?.let {
        require(options.chunked(2).any { pair -> pair[0] == "--data-dir" }) {
            "Runtime check requires an explicit --data-dir"
        }
        DesktopRuntimeCheck.run(root, Path.of(it[1]), imports)
        return
    }
    // Answers the update question from a packaged image, where the window is not reachable: see DesktopUpdateCheck.
    options.chunked(2).lastOrNull { it[0] == "--check-update" }?.let {
        val channel = options.chunked(2).lastOrNull { pair -> pair[0] == "--channel" }?.get(1)
        exitProcess(DesktopAppUpdateCheck.run(Path.of(it[1]), channel))
    }
    application(exitProcessOnExit = false) {
        var controller by remember { mutableStateOf<DesktopController?>(null) }
        var startupError by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(width = 1260.dp, height = 850.dp)
        val scope = rememberCoroutineScope()
        var startup by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        // One quit path for the window button, the titlebar close and an update that needs the process to end.
        val requestExit: () -> Unit = {
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        startup?.cancel()
                        startup?.join()
                        controller?.shutdown()
                    } finally { exitApplication() }
                }
            }
        }
        LaunchedEffect(root) {
            startup = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            var acquired: DesktopSession? = null
            try {
                val session = DesktopSession.open(root)
                acquired = session
                try { for (jar in imports) session.importJar(jar) } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    session.startupErrors += error.message ?: desktopText("app.extensionImportFailed")
                }
                // Android's periodic tracker and suggestion jobs run while the window is open.
                controller = DesktopController(session).also { it.startBackgroundWork() }
                acquired = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                startupError = desktopText("app.runtimeIncompatible", error.message ?: error.javaClass.simpleName)
            } catch (error: Exception) { startupError = error.message ?: error.javaClass.simpleName }
            finally { withContext(NonCancellable + Dispatchers.IO) { acquired?.close() } }
        }
        Window(
            title = "Kototoro · ${DesktopPlatform.current.displayName}",
            icon = painterResource("icons/app.png"),
            state = windowState,
            onCloseRequest = requestExit,
        ) {
            window.minimumSize = Dimension(920, 620)
            val owner = controller
            val fullscreen = rememberDesktopFullscreen(window, windowState)
            if (owner == null) DesktopStartup(startupError) else DesktopApp(owner, closing,
                fullscreen = fullscreen.active, onToggleFullscreen = fullscreen::toggle, onExitRequest = requestExit)
        }
    }
}
