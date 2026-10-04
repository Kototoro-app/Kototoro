package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceProtocolJson
import java.nio.file.Path

/** Storage probe for Windows/JVM debugging; creates a new v84 database only when the explicit directory is empty. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 2 && args[0] == "storage") { "Usage: storage <data-directory>" }
    DesktopRuntime.open(Path.of(args[1])).use { runtime ->
        val json = SourceProtocolJson.encodeToString(runtime.storageInfo())
        System.out.write((json + "\n").toByteArray(Charsets.UTF_8))
        System.out.flush()
    }
}
