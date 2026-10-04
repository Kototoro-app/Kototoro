package org.skepsun.kototoro.desktop.app

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Only this probe class is added to the packaged JVM; production dependencies all come from the image. */
object DesktopDistributionWindowProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args[0] == "source-browse") {
                runBlocking {
                    val session = DesktopSession.open(Path.of(args[1]))
                    val controller = DesktopController(session)
                    try {
                        val extension = session.importJar(Path.of(args[2]))
                        controller.selectSource(extension.sources.single()).join()
                        check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                        val items = controller.state.value.items
                        check(items.single().title == "offline 1")
                    } finally { controller.shutdown() }
                }
                println("DESKTOP_SOURCE_BROWSE_OK=controller-streaming-json")
            } else DesktopWindowProbe.run(args)
            exitProcess(0)
        }
        catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
    }
}
