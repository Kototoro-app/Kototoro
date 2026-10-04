package org.skepsun.kototoro.parserhost

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Plugin jars built from `src/fixture*`; real bytecode compiled against the host ABI. */
internal object Fixtures {
    private val directory: Path = Path.of(requireNotNull(System.getProperty("kototoro.parserhost.fixtures")) {
        "The fixture directory is supplied by the Gradle test task"
    })
    val kototoro: Path = directory.resolve("fixture-kototoro.jar")
    val kotatsu: Path = directory.resolve("fixture-kotatsu.jar")
    val tsuki: Path = directory.resolve("fixture-tsuki.jar")

    /** A valid 1x1 PNG, enough for the image store's signature check. */
    val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==",
    )

    fun jar(path: Path, entries: Map<String, ByteArray>): Path {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }
}

internal class TestPlatform(
    root: Path,
    override val httpClient: OkHttpClient = OkHttpClient(),
) : ParserPlatform, Closeable {
    val store = FileSourcePreferenceStore(root.resolve("preferences"))
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val preferences: SourcePreferenceStore get() = store

    override fun close() = store.close()
}
