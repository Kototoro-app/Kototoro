package org.skepsun.kototoro.parserhost

import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

/** The three parser ABIs a plugin jar can be compiled against; each one owns a factory entry point. */
enum class ParserPluginArchitecture(
    val factoryClass: String,
    val sourceEnumClass: String,
    val contextClass: String,
) {
    // Probe order mirrors the Android JarExtensionLoader: Kotatsu, Kototoro, then Tsuki (UMA).
    KOTATSU(
        "org.koitharu.kotatsu.parsers.MangaParserFactoryKt",
        "org.koitharu.kotatsu.parsers.model.MangaParserSource",
        "org.koitharu.kotatsu.parsers.MangaLoaderContext",
    ),
    KOTOTORO(
        "org.skepsun.kototoro.parsers.ContentParserFactoryKt",
        "org.skepsun.kototoro.parsers.model.ContentParserSource",
        "org.skepsun.kototoro.parsers.ContentLoaderContext",
    ),
    TSUKI(
        "tsuki.MangaParserFactoryKt",
        "tsuki.model.MangaParserSource",
        "tsuki.MangaLoaderContext",
    ),
    ;

    val factoryEntry: String get() = factoryClass.replace('.', '/') + ".class"
}

enum class ParserPluginFailure {
    INVALID_ARCHIVE,
    /** A DEX-only jar is what the Android repositories publish; the JVM host needs class files. */
    DEX_ONLY,
    UNSUPPORTED_ARCHITECTURE,
    UNSUPPORTED_BYTECODE,
    HASH_MISMATCH,
    ALREADY_LOADED,
    NO_SOURCES,
    CONSTRUCTION_FAILED,
    CLOSED,
}

class ParserPluginException(val failure: ParserPluginFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

data class ParserPluginMetadata(
    val architecture: ParserPluginArchitecture,
    val sha256: String,
    val classCount: Int,
    val maximumClassVersion: Int,
)

/** Inspects data only; no plugin class is loaded or initialised. */
class ParserPluginInspector(
    private val classVersionCeiling: Int = System.getProperty("java.class.version").substringBefore('.').toInt(),
) {
    fun inspect(path: Path): ParserPluginMetadata {
        val hash = try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (error: Exception) {
            throw ParserPluginException(ParserPluginFailure.INVALID_ARCHIVE, error)
        }
        try {
            return JarFile(path.toFile()).use { jar ->
                var classes = 0
                var maximum = 0
                var hasDex = false
                for (entry in jar.entries()) {
                    if (entry.isDirectory) continue
                    if (entry.name.endsWith(".dex")) hasDex = true
                    if (!entry.name.endsWith(".class")) continue
                    if (entry.name.startsWith("META-INF/versions/")) {
                        val release = entry.name.removePrefix("META-INF/versions/").substringBefore('/').toIntOrNull()
                        if (!jar.isMultiRelease || release == null || release > classVersionCeiling - 44) continue
                    }
                    classes++
                    val version = jar.getInputStream(entry).use { stream ->
                        DataInputStream(stream).use { input ->
                            require(input.readInt() == 0xCAFEBABE.toInt())
                            val minor = input.readUnsignedShort()
                            require(minor != 65535) // The host does not launch with preview bytecode enabled.
                            input.readUnsignedShort()
                        }
                    }
                    if (version > classVersionCeiling) throw ParserPluginException(ParserPluginFailure.UNSUPPORTED_BYTECODE)
                    maximum = maxOf(maximum, version)
                }
                if (classes == 0) {
                    throw ParserPluginException(
                        if (hasDex) ParserPluginFailure.DEX_ONLY else ParserPluginFailure.INVALID_ARCHIVE,
                    )
                }
                val architecture = ParserPluginArchitecture.entries.firstOrNull { jar.getJarEntry(it.factoryEntry) != null }
                    ?: throw ParserPluginException(ParserPluginFailure.UNSUPPORTED_ARCHITECTURE)
                ParserPluginMetadata(architecture, hash, classes, maximum)
            }
        } catch (error: ParserPluginException) {
            throw error
        } catch (error: Exception) {
            throw ParserPluginException(ParserPluginFailure.INVALID_ARCHIVE, error)
        }
    }

    fun verify(metadata: ParserPluginMetadata, expectedSha256: String) {
        if (!expectedSha256.matches(Regex("[0-9a-fA-F]{64}")) || !metadata.sha256.equals(expectedSha256, ignoreCase = true)) {
            throw ParserPluginException(ParserPluginFailure.HASH_MISMATCH)
        }
    }
}
