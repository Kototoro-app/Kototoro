package org.skepsun.kototoro.source.host

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.skepsun.kototoro.core.source.SourceEndpoint
import java.io.Reader
import java.io.Writer
import java.nio.file.Path

/** One JVM/registry for a local JSONL session. Input/output remain owned by the caller; EOF closes the runtime. */
object SourceHostJsonSession {
    suspend fun run(
        config: SourceHostConfig,
        configurationDirectory: Path,
        input: Reader,
        output: Writer,
        platform: SourceHostPlatform,
    ) {
        // Preferences outlive the platform's shutdown callbacks, and are available before JAR construction.
        var preferences: FileSourcePreferenceStore? = null
        var failure: Throwable? = null
        try {
            platform.use {
                preferences = config.preferenceDirectory?.let {
                    require(platform is SourceHostPreferencePlatform) { "Platform does not support persistent preferences" }
                    FileSourcePreferenceStore(configurationDirectory.resolve(it).normalize())
                }
                val loader = if (preferences != null) {
                    (platform as SourceHostPreferencePlatform).initialize(requireNotNull(preferences))
                } else platform.initialize()
                MihonJarRegistry(loader).use { registry ->
                    for (jar in config.jars) {
                        registry.load(configurationDirectory.resolve(jar.path).normalize(), jar.identity)
                    }
                    val images = config.imageDirectory?.let {
                        FileSourceImageStore(configurationDirectory.resolve(it).normalize())
                    }
                    val context = (platform as? SourceHostPreferenceUiPlatform)?.preferenceContext()
                    val endpoint = SourceEndpoint(MihonSourceRuntime(registry, images, context))
                    val lines = input.buffered()
                    val responses = output.buffered()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = lines.readLine() ?: break
                        if (line.isBlank()) continue
                        responses.write(endpoint.exchange(line))
                        responses.newLine()
                        responses.flush()
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { preferences?.close() } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
