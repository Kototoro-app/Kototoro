package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.desktop.runtime.DesktopDataPaths
import java.awt.Dimension
import java.nio.file.Path

fun main(args: Array<String>) {
    val options = args.toList()
    require(options.size % 2 == 0 && options.chunked(2).all { it[0] in setOf("--data-dir", "--import", "--check-runtime") }) {
        "Usage: [--data-dir <directory>] [--import <extension.jar>] [--check-runtime <report>]"
    }
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
    application(exitProcessOnExit = false) {
        var controller by remember { mutableStateOf<DesktopController?>(null) }
        var startupError by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(width = 1260.dp, height = 850.dp)
        var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
        val scope = rememberCoroutineScope()
        var startup by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        LaunchedEffect(root) {
            startup = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            var acquired: DesktopSession? = null
            try {
                val session = DesktopSession.open(root)
                acquired = session
                try { for (jar in imports) session.importJar(jar) } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    session.startupErrors += error.message ?: "扩展导入失败"
                }
                controller = DesktopController(session)
                acquired = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                startupError = "运行时 API 不兼容：${error.message ?: error.javaClass.simpleName}"
            } catch (error: Exception) { startupError = error.message ?: error.javaClass.simpleName }
            finally { withContext(NonCancellable + Dispatchers.IO) { acquired?.close() } }
        }
        Window(
            title = "Kototoro · Windows",
            state = windowState,
            onCloseRequest = {
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
            },
        ) {
            window.minimumSize = Dimension(920, 620)
            val owner = controller
            if (owner == null) DesktopStartup(startupError) else DesktopApp(owner, closing,
                fullscreen = windowState.placement == WindowPlacement.Fullscreen, onToggleFullscreen = {
                    if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
                    else {
                        previousPlacement = windowState.placement
                        windowState.placement = WindowPlacement.Fullscreen
                    }
                })
        }
    }
}
