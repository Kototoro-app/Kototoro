package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionBackup
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionExport
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionGroups
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionPackage
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionRepo
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings

/** Id and name of the group this host writes; Android lists it with its own source groups after a restore. */
private const val GROUP_ID = "desktop"

/** What a restore did, for the message the user sees. */
internal data class ExtensionRestoreSummary(val installed: Int, val failed: List<String>)

/**
 * Android's extension groups (`EXTENSION_GROUPS`) on this host: the installed packages, the repositories they come from
 * and the enabled sources go out as one group, `DesktopExtensionGroups` owning the wire format; a restore reads every
 * group in the archive — Android's or this host's — and installs the packages that are missing from the repositories it
 * names or this host already knows, so a reinstall or a new computer gets its extensions, their switches and their
 * order back from the backup alone.
 */
internal class DesktopExtensionBackupBridge(
    private val session: DesktopSession,
    private val onRestored: (ExtensionRestoreSummary) -> Unit = {},
    private val onRestoreOrder: (List<String>) -> Unit = {},
) : DesktopExtensionBackup {

    override suspend fun export(): String? {
        val entries = session.installedEntries()
        if (entries.isEmpty()) return null
        val disabled = session.disabledSources()
        return DesktopExtensionGroups.encode(
            DesktopExtensionExport(
                id = GROUP_ID,
                name = "Windows",
                createdAt = System.currentTimeMillis(),
                repos = session.repositories.saved().map { DesktopExtensionRepo("MIHON", it.indexUrl, it.name) },
                packages = entries.map { entry ->
                    DesktopExtensionPackage(
                        pkg = entry.id,
                        kind = entry.ecosystem.groupKind(),
                        versionName = entry.version,
                        versionCode = entry.versionCode ?: 0L,
                    )
                },
                enabledSources = railOrder().filterNot { it in disabled },
                disabledSources = disabled.toList(),
                jarPriority = session.jarPriorityNames(),
            ),
        )
    }

    override suspend fun restore(groups: List<JsonObject>) {
        val plan = DesktopExtensionGroups.decode(groups)
        val packages = plan.packages
        val urls = (plan.repositoryUrls + packages.mapNotNull { it.repoUrl } +
            session.repositories.saved().map { it.indexUrl }).distinct()
        val missing = packages.filter { wanted -> session.installedEntries().none { it.id == wanted.pkg } }
        var installed = 0
        val failed = mutableListOf<String>()
        if (missing.isNotEmpty()) {
            val catalogs = urls.mapNotNull { url -> attempt { session.repositories.fetch(url) } }
            for (wanted in missing) {
                currentCoroutineContext().ensureActive()
                val (catalog, extension) = catalogs.sortedByDescending { it.repository.indexUrl == wanted.repoUrl }
                    .firstNotNullOfOrNull { catalog ->
                        catalog.extensions.firstOrNull { it.packageName == wanted.pkg }?.let { catalog to it }
                    } ?: run { failed += wanted.pkg; continue }
                if (attempt { session.installExtension(catalog, extension, enableSources = true) } != null) installed++
                else failed += wanted.pkg
            }
        }
        // Whatever the backup switched off stays off, on the packages that were already here as well.
        val disabled = plan.disabledSources
        if (disabled.isNotEmpty()) session.setSourcesEnabled(disabled, false)
        // The organization the backup carried: the sequence the rail shows and Android's JAR priority. Both are written
        // after the installs so the sources that just came back take their place in the order.
        if (plan.enabledOrder.isNotEmpty()) onRestoreOrder(plan.enabledOrder)
        if (plan.jarPriority.isNotEmpty()) session.setJarPriorityOrder(plan.jarPriority)
        onRestored(ExtensionRestoreSummary(installed, failed))
    }

    /**
     * The order the rail shows: the hand-picked sequence when it is the chosen sort, the host's own order otherwise.
     * Read from the settings store rather than the screen state so this bridge stays independent of the controller.
     */
    private fun railOrder(): List<String> =
        DesktopSourceSettings.from(session.storage.preferences.open("desktop_background").snapshot())
            .sorted(session.sourceListings())
            .map { it.source.name }

    private suspend fun <T : Any> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}

private fun SourceEcosystem.groupKind(): String = when (this) {
    SourceEcosystem.MIHON -> "mihon"
    SourceEcosystem.ANIYOMI -> "aniyomi"
    SourceEcosystem.TSUNDOKU -> "tsundoku"
    SourceEcosystem.CLOUDSTREAM -> "cloudstream"
    else -> "jar"
}
