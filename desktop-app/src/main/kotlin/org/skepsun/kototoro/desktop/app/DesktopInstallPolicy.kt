package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.desktop.runtime.DesktopExtensionFiles
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionKind
import org.skepsun.kototoro.desktop.runtime.DesktopRepositoryCatalog
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.extensions.repo.ExtensionStoreIndex
import java.nio.file.Path

/** Android's `extension_install_policies`: one entry per kind, `"<type>=<POLICY>"`, the default left out. */
internal const val EXTENSION_INSTALL_POLICIES_KEY = "extension_install_policies"

/**
 * Android's `extension_install_behavior`, one row per source kind this host can install.
 *
 * [typeName] is Android's own `UnifiedSourceKind` name, so a policy means the same thing on both platforms. Windows
 * installs four of Android's nine pipelines — LEGADO/LNREADER/TVBOX/JS JSON sources are imported rather than packaged,
 * and IReader has no Windows runtime — while Tsundoku, Aniyomi and Mihon jars all arrive through the one extension
 * pipeline. Which of the three a repository entry is comes from its package name, exactly as the catalogue row's
 * ecosystem label decides it (see [installKind]).
 */
enum class DesktopInstallKind(val typeName: String, val titleKey: String) {
    MIHON("MIHON", "source_type_mihon"),
    TSUNDOKU("TSUNDOKU", "source_type_tsundoku"),
    ANIYOMI("ANIYOMI", "source_type_aniyomi"),
    JAR("JAR", "source_type_jar"),
    CLOUDSTREAM("CLOUDSTREAM", "source_type_cloudstream"),
}

/** The string Android's dialog uses for [this] policy (its `ask_every_time`, `extension_install_only`, …). */
internal fun ExtensionInstallPolicy.labelKey(): String = when (this) {
    ExtensionInstallPolicy.ASK_EVERY_TIME -> "ask_every_time"
    ExtensionInstallPolicy.INSTALL_ONLY -> "extension_install_only"
    ExtensionInstallPolicy.INSTALL_AND_ENABLE -> "extension_install_and_enable"
}

/**
 * Android's per-kind answer, or [ExtensionInstallPolicy.ASK_EVERY_TIME] for a kind nobody configured — the default
 * `AppSettings.getExtensionInstallPolicy` returns.
 */
internal fun Map<String, ExtensionInstallPolicy>.policyFor(kind: DesktopInstallKind): ExtensionInstallPolicy =
    this[kind.typeName] ?: ExtensionInstallPolicy.ASK_EVERY_TIME

/**
 * What the settings row and the sheet's batch row show: the answers the kinds share, in Android's kind order, so a
 * uniform setting reads as that one policy and a mixed one names each ("每次询问 · 仅安装").
 */
internal fun Map<String, ExtensionInstallPolicy>.policySummary(): String =
    DesktopInstallKind.entries.map { policyFor(it) }.distinct().joinToString(" · ") { AndroidStrings[it.labelKey()] }

/**
 * Which kind a catalogue entry installs, from the same markers the row's ecosystem label uses: a parser plugin is
 * `JAR`, a `.cs3` is `CLOUDSTREAM`, and a managed jar is whichever runtime its package name names.
 */
internal fun installKind(catalog: DesktopRepositoryCatalog, extension: ExtensionStoreIndex.Extension): DesktopInstallKind =
    when {
        catalog.isParserPlugin(extension) -> DesktopInstallKind.JAR
        catalog.isCloudstreamPlugin(extension) -> DesktopInstallKind.CLOUDSTREAM
        extension.packageName.contains(".animeextension.") -> DesktopInstallKind.ANIYOMI
        extension.packageName.contains(".novelextension.") -> DesktopInstallKind.TSUNDOKU
        else -> DesktopInstallKind.MIHON
    }

/**
 * Which kind a picked file installs. The file only says "extension jar", "anime APK", "parser plugin" or "Cloudstream
 * plugin", so a novel jar imported this way keys [DesktopInstallKind.MIHON]: the repository path is the one that can
 * tell Tsundoku apart, because its index carries the package name.
 */
internal fun Path.installKind(): DesktopInstallKind = when (DesktopExtensionFiles.kind(this)) {
    DesktopExtensionKind.MIHON -> DesktopInstallKind.MIHON
    DesktopExtensionKind.ANIYOMI -> DesktopInstallKind.ANIYOMI
    DesktopExtensionKind.PARSER -> DesktopInstallKind.JAR
    DesktopExtensionKind.CLOUDSTREAM -> DesktopInstallKind.CLOUDSTREAM
}

/**
 * Android's `ConfirmPackageInstall`: a catalogue row that is about to install and is waiting for the answer to
 * "install and enable" / "install only". Android asks before downloading; here the row's question comes first too,
 * because the download is the expensive half and would be wasted on a cancel.
 */
sealed interface DesktopInstallRequest {
    val kind: DesktopInstallKind
    val name: String
    val sourceCount: Int

    /** A repository package, installed through the same call the catalogue row's button uses. */
    data class FromRepository(
        val catalog: DesktopRepositoryCatalog,
        val extension: ExtensionStoreIndex.Extension,
        override val kind: DesktopInstallKind,
        override val sourceCount: Int,
    ) : DesktopInstallRequest {
        override val name: String get() = extension.name
    }

    /** A package the user picked from disk (drag and drop or the file picker). */
    data class FromFile(val path: Path, override val kind: DesktopInstallKind) : DesktopInstallRequest {
        override val name: String get() = path.fileName?.toString().orEmpty()

        /** The file's own sources are only known after it is installed, which is why Android's message has a variant. */
        override val sourceCount: Int get() = 0
    }
}
