package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.desktop.runtime.DesktopExtensionBackup

/** Id and name of the group this host writes; Android lists it with its own source groups after a restore. */
private const val GROUP_ID = "desktop"
private const val GROUP_SCHEMA = 2

/** A package an extension group names: [pkg] is the identity, [repoUrl] where Android got it from. */
internal data class BackupExtensionPackage(val pkg: String, val repoUrl: String?)

/** What a restore did, for the message the user sees. */
internal data class ExtensionRestoreSummary(val installed: Int, val failed: List<String>)

/**
 * Android's extension groups (`EXTENSION_GROUPS`) on this host: the installed packages, the repositories they come from
 * and the enabled sources go out as one group; a restore reads every group in the archive — Android's or this host's —
 * and installs the packages that are missing from the repositories it names or this host already knows, so a reinstall or
 * a new computer gets its extensions and their switches back from the backup alone.
 */
internal class DesktopExtensionBackupBridge(
    private val session: DesktopSession,
    private val onRestored: (ExtensionRestoreSummary) -> Unit = {},
) : DesktopExtensionBackup {

    override suspend fun export(): String? {
        val entries = session.installedEntries()
        if (entries.isEmpty()) return null
        val repositories = session.repositories.saved()
        val disabled = session.disabledSources()
        val enabled = entries.flatMap { it.sourceNames }.filterNot { it in disabled }
        val group = buildJsonObject {
            put("schema", GROUP_SCHEMA)
            put("id", GROUP_ID)
            put("name", "Windows")
            put("createdAt", System.currentTimeMillis())
            putJsonArray("repos") {
                repositories.forEach { repository ->
                    add(buildJsonObject {
                        put("type", "MIHON")
                        put("url", repository.indexUrl)
                        put("name", repository.name)
                    })
                }
            }
            putJsonArray("packages") {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("pkg", entry.id)
                        put("kind", entry.ecosystem.groupKind())
                        put("versionName", entry.version)
                        put("versionCode", entry.versionCode ?: 0L)
                        put("installMode", "managed")
                    })
                }
            }
            put("sourceStates", JsonArray(emptyList()))
            putJsonArray("enabledSources") { enabled.forEach { add(JsonPrimitive(it)) } }
            put("enabledSortStart", 1)
            put("enabledSortGaps", JsonArray(emptyList()))
            put("enabledUnsorted", JsonArray(emptyList()))
            put("jarPriority", JsonArray(emptyList()))
        }
        return JsonArray(listOf(group)).toString()
    }

    override suspend fun restore(groups: List<JsonObject>) {
        val packages = groups.flatMap { it.packages() }.distinctBy { it.pkg }
        val urls = (groups.flatMap { it.repositoryUrls() } + packages.mapNotNull { it.repoUrl } +
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
        val disabled = groups.flatMap { it.disabledSourceNames() }
        if (disabled.isNotEmpty()) session.setSourcesEnabled(disabled, false)
        onRestored(ExtensionRestoreSummary(installed, failed))
    }

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

private fun JsonObject.packages(): List<BackupExtensionPackage> =
    (this["packages"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val pkg = item.string("pkg")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BackupExtensionPackage(pkg, item.string("repoUrl")?.takeIf { it.isNotBlank() })
    }

private fun JsonObject.repositoryUrls(): List<String> =
    (this["repos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("url")?.takeIf(String::isNotBlank) }

/** Sources the group records as switched off; the compact `enabledSources` form only ever lists enabled ones. */
private fun JsonObject.disabledSourceNames(): List<String> =
    (this["sourceStates"] as? JsonArray).orEmpty().mapNotNull { element ->
        val state = element as? JsonObject ?: return@mapNotNull null
        val enabled = (state["enabled"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return@mapNotNull null
        state.string("source")?.takeIf { !enabled }
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
