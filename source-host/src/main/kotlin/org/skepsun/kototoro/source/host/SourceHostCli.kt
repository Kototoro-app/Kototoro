package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.MihonJarIdentity
import org.skepsun.kototoro.core.source.SourceProtocolJson
import java.nio.file.Path
import java.nio.file.Files
import java.io.PrintStream

/** Data inspection works without AndroidCompat; loading requires a platform-initialized compatibility classpath. */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "Usage: inspect <jar> | sources <jar> <package> <versionCode> <sha256> | serve <config.json>"
    }
    when (args[0]) {
        "inspect" -> {
            require(args.size == 2)
            println(SourceProtocolJson.encodeToString(MihonJarInspector().inspect(Path.of(args[1]))))
        }
        "sources" -> {
            require(args.size == 5)
            val expected = MihonJarIdentity(args[2], args[3].toLong(), args[4])
            MihonJarRegistry(Thread.currentThread().contextClassLoader).use { registry ->
                println(SourceProtocolJson.encodeToString(registry.load(Path.of(args[1]), expected)))
            }
        }
        "serve" -> {
            require(args.size == 2)
            val path = Path.of(args[1]).toAbsolutePath().normalize()
            val config = SourceProtocolJson.decodeFromString<SourceHostConfig>(Files.readString(path, Charsets.UTF_8))
            val protocolOutput = System.out
            // Third-party bootstrap/extension println must not corrupt protocol responses on stdout.
            System.setOut(PrintStream(System.err, true, Charsets.UTF_8))
            try {
                val type = Class.forName(config.platformClass).asSubclass(SourceHostPlatform::class.java)
                val platform = type.getDeclaredConstructor().newInstance()
                runBlocking {
                    SourceHostJsonSession.run(config, path.parent, System.`in`.reader(Charsets.UTF_8),
                        protocolOutput.writer(Charsets.UTF_8), platform)
                }
            } finally {
                System.setOut(protocolOutput)
            }
        }
        else -> throw IllegalArgumentException("Unknown host command")
    }
}
