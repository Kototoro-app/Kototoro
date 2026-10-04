package org.skepsun.kototoro.desktop.runtime

import kotlinx.serialization.Serializable
import org.skepsun.kototoro.dex.ApkExtensionConverter
import org.skepsun.kototoro.dex.ApkManifestReader
import org.skepsun.kototoro.dex.DexConversionReport
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * An app-owned copy of a parser plugin jar; the logical [id] drives source priority, the file is named by hash.
 * [conversion] is set when the input was an Android DEX plugin that had to be converted to class files.
 */
data class DesktopManagedParserPlugin(
    val path: Path,
    val id: String,
    val sha256: String,
    val conversion: DexConversionReport? = null,
)

/** Persisted install record, restored with the same id and hash on the next start. */
@Serializable
data class DesktopParserRecord(val id: String, val path: String, val sha256: String)

enum class DesktopExtensionKind {
    /**
     * Tachiyomi ABI (Mihon manga, Tsundoku novels): a jar with a text AndroidManifest.xml, or an APK that is converted
     * to one. The manifest keys say which of the two it is.
     */
    MIHON,
    /** kototoro / kotatsu / Tsuki (UMA): identified by its generated parser factory instead of a manifest. */
    PARSER,
    /** Aniyomi: an APK declaring the `tachiyomi.animeextension` feature. */
    ANIYOMI,
}

object DesktopExtensionFiles {
    fun kind(path: Path): DesktopExtensionKind {
        if (ApkExtensionConverter.isApk(path)) {
            val anime = runCatching { "tachiyomi.animeextension" in ApkManifestReader.read(path).features }.getOrDefault(false)
            return if (anime) DesktopExtensionKind.ANIYOMI else DesktopExtensionKind.MIHON
        }
        return JarFile(path.toFile()).use { jar ->
            if (jar.getJarEntry("AndroidManifest.xml") != null) DesktopExtensionKind.MIHON else DesktopExtensionKind.PARSER
        }
    }

    /**
     * Plugin ids come from the file name, without version or packaging suffixes, so that
     * `kototoro-parsers-1.0.jar` and `kototoro-parsers.jar` both rank as the `kototoro-parsers` priority entry.
     */
    fun parserPluginId(fileName: String): String {
        val base = fileName.substringBeforeLast('.').lowercase()
            .replace(Regex("[-_.]v?\\d+(\\.\\d+)*([-.][a-z0-9]+)?$"), "")
            .replace(Regex("[-_.](plugin|jvm|all)$"), "")
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('-', '.', '_')
        return base.ifEmpty { "parser-plugin" }.take(128)
    }
}
