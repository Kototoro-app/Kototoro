package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorProbeResult
import org.skepsun.kototoro.core.prefs.GitHubMirrorProbeState
import org.skepsun.kototoro.core.prefs.GitHubMirrorStrategy
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.desktop.runtime.DesktopGitHubMirrorSettings
import org.skepsun.kototoro.desktop.runtime.DesktopStorageCategory
import org.skepsun.kototoro.desktop.runtime.DesktopStorageUsage
import java.util.Locale

/**
 * Android's "storage and network" hub. The proxy page used to hang off "more" because Windows had no such hub; the
 * figures Android opens its page with were missing entirely, although the data root holds a database, an image
 * directory, the preference store and the source diagnostics that can each grow without the user noticing.
 *
 * The slices are the data root's own children, measured on entry, so the page never guesses what a cache costs.
 */
@Composable
internal fun DesktopStorageSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val usage = state.storageUsage
    val active = !state.busy
    var mirrorDialog by remember { mutableStateOf(false) }
    // The sweep reports through the runtime, like the download queue: the page only reads it.
    val probe by controller.session.githubMirror.probeState.collectAsState()
    val probeResults by controller.session.githubMirror.probeResults.collectAsState()
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active,
            modifier = Modifier.testTag("storage-settings-back")) { Text(desktopText("common.back")) }
        SettingsSectionHeader(AndroidStrings["storage_usage"])
        SettingsGroup {
            if (usage == null) {
                Text(AndroidStrings["computing_"], style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("storage-usage-loading"))
            } else {
                StorageUsageBar(usage,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("storage-usage-chart"))
                Text(
                    text = desktopText("storage.summary", desktopCacheSize(usage.usedBytes),
                        desktopCacheSize(usage.availableBytes), desktopCacheSize(usage.volumeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                        .testTag("storage-usage-summary"),
                )
                val protected = state.imageCache.protectedBytes.takeIf { it > 0L }
                // The app's own slices, largest first, then the volume's free space last as Android lists it.
                val rows = usage.items.filter { it.category != DesktopStorageCategory.AVAILABLE }
                    .sortedByDescending { it.bytes } +
                    usage.items.filter { it.category == DesktopStorageCategory.AVAILABLE }
                rows.forEachIndexed { index, item ->
                    if (index > 0) SettingsRowDivider()
                    val kept = protected.takeIf { item.category == DesktopStorageCategory.IMAGES }
                    StorageUsageRow(item, storageCategoryTitle(item.category, kept))
                }
            }
        }
        SettingsSectionHeader(AndroidStrings["data_removal"])
        SettingsGroup {
            // Android's row for its data-removal page; the page itself lists only the stores this data root has.
            SettingsValueRow(AndroidStrings["data_removal"], null, null, active,
                { controller.showDataCleanup() }, testTag = "cleanup-open",
                leadingIcon = painterResource("icons/ic_delete_all.svg"))
        }
        SettingsSectionHeader(AndroidStrings["network"])
        SettingsGroup {
            // Android's row for the proxy sub-screen, with the current route where Android shows its summary.
            val settings = state.proxySettings
            val route = settings.address.takeIf { settings.isEnabled && it.isNotBlank() }
                ?.let { "${it}:${settings.port}" }
            SettingsValueRow(AndroidStrings["proxy"], route, settings.type.label, active,
                { controller.showProxySettings() }, testTag = "proxy-settings-open",
                leadingIcon = painterResource("icons/ic_web.svg"))
            SettingsRowDivider()
            // Android's GitHub mirror choice, which decides the host every GitHub download goes through.
            SettingsValueRow(AndroidStrings["pref_github_mirror"], AndroidStrings["pref_github_mirror_summary"],
                state.githubMirror.entry.desktopLabel, active, { mirrorDialog = true },
                testTag = "github-mirror-open", leadingIcon = painterResource("icons/ic_code.svg"))
            SettingsRowDivider()
            // Android's speed sweep next to that choice: 28 proxies, one dead host must not hide the rest.
            SettingsActionRow(AndroidStrings["mirror_probe_action"], probe.summary, Modifier.testTag("github-mirror-probe")) {
                if (probe.isRunning) {
                    TextButton({ controller.cancelGitHubMirrorProbe() }, enabled = active,
                        modifier = Modifier.testTag("github-mirror-probe-cancel")) {
                        Text(AndroidStrings["mirror_probe_cancel"])
                    }
                } else {
                    TextButton({ controller.probeGitHubMirrors() }, enabled = active,
                        modifier = Modifier.testTag("github-mirror-probe-start")) {
                        Text(AndroidStrings["mirror_probe_short_action"])
                    }
                }
                probe.fastestId?.takeIf { it != state.githubMirror.entry.id }?.let { fastest ->
                    TextButton({ controller.updateGitHubMirror(DesktopGitHubMirrorSettings(fastest)) },
                        enabled = active, modifier = Modifier.testTag("github-mirror-probe-fastest")) {
                        Text(AndroidStrings["mirror_probe_select_fastest"])
                    }
                }
            }
        }
    }
    if (mirrorDialog) {
        DesktopChoiceSettingsDialog(
            title = AndroidStrings["pref_github_mirror"], summary = AndroidStrings["pref_github_mirror_summary"],
            bodyTag = "github-mirror-dialog", optionTagPrefix = "github-mirror-option",
            options = GitHubMirrorCatalog.builtin.map { entry -> entry.dialogLabel(probeResults, probe.fastestId) to entry.id },
            selected = state.githubMirror.entry.id, enabled = active,
            onPick = { id -> controller.updateGitHubMirror(DesktopGitHubMirrorSettings(id)) },
            onDismiss = { mirrorDialog = false },
        )
    }
}

/**
 * A mirror's label in the interface language: only the direct connection is translated, every other mirror *is* a
 * hostname and must read the same in every locale (the rule Android's `displayName` applies).
 */
internal val GitHubMirrorEntry.desktopLabel: String
    get() = if (strategy == GitHubMirrorStrategy.NATIVE) {
        AndroidStrings["github_mirror_native"]
    } else {
        name
    }

/** Android's `latencyLabel`: the milliseconds a sweep measured, "timeout" for a host that did not answer. */
internal val GitHubMirrorProbeResult?.desktopLatency: String
    get() = when {
        this == null -> ""
        isAvailable -> " · ${latencyMillis ?: "?"} ms"
        else -> " · ${AndroidStrings["mirror_probe_timeout"]}"
    }

/** One dialog row: the mirror's name, what the sweep measured, and the badge Android puts on the fastest mirror. */
private fun GitHubMirrorEntry.dialogLabel(
    results: Map<String, GitHubMirrorProbeResult>,
    fastestId: String?,
): String = buildString {
    append(desktopLabel)
    append(results[id].desktopLatency)
    if (id == fastestId) append(" · ").append(AndroidStrings["mirror_probe_fastest_badge"])
}

/** The sweep's own words, all from Android's catalog so the two hosts read the same sentence. */
private val GitHubMirrorProbeState.summary: String
    get() = when (this) {
        GitHubMirrorProbeState.Idle -> AndroidStrings["mirror_probe_summary"]
        is GitHubMirrorProbeState.Running -> AndroidStrings.format("mirror_probe_running", completed, total)
        is GitHubMirrorProbeState.Finished -> when {
            available == 0 -> AndroidStrings["mirror_probe_none_available"]
            fastestId == null -> AndroidStrings.format("mirror_probe_finished", "—", 0L, available, total)
            else -> AndroidStrings.format("mirror_probe_finished", fastestId, fastestMillis ?: 0L, available, total)
        }
    }

private val GitHubMirrorProbeState.isRunning: Boolean get() = this is GitHubMirrorProbeState.Running
private val GitHubMirrorProbeState.fastestId: String?
    get() = (this as? GitHubMirrorProbeState.Finished)?.fastestId

/** Android's donut shows the application's own data only; the bar keeps that rule and the rows carry the numbers. */
@Composable
private fun StorageUsageBar(usage: DesktopStorageUsage, modifier: Modifier = Modifier) {
    val slices = usage.items.filter { it.category != DesktopStorageCategory.AVAILABLE && it.bytes > 0L }
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    Canvas(modifier.height(12.dp).clip(RoundedCornerShape(6.dp))) {
        if (slices.isEmpty()) {
            drawRect(empty)
            return@Canvas
        }
        val total = slices.sumOf { it.bytes }.toFloat()
        var left = 0f
        for (slice in slices) {
            val width = size.width * (slice.bytes / total)
            drawRect(storageColor(slice.category), Offset(left, 0f), Size(width, size.height))
            left += width
        }
    }
}

@Composable
private fun StorageUsageRow(item: DesktopStorageUsage.Item, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("storage-usage:${item.category}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(storageColor(item.category)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Text(
            text = "${desktopCacheSize(item.bytes)} · ${String.format(Locale.ROOT, "%.1f%%", item.percent)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
        )
    }
}

/** Android's category labels, plus the one Windows-only note: saved chapters share the image directory. */
private fun storageCategoryTitle(category: DesktopStorageCategory, protectedBytes: Long?): String = when (category) {
    DesktopStorageCategory.DATABASE -> desktopText("storage.database")
    DesktopStorageCategory.IMAGES ->
        protectedBytes?.let { desktopText("storage.imagesProtected", desktopCacheSize(it)) }
            ?: desktopText("storage.images")
    DesktopStorageCategory.PREFERENCES -> desktopText("storage.preferences")
    DesktopStorageCategory.LOGS -> desktopText("storage.logs")
    DesktopStorageCategory.OTHER -> desktopText("storage.other")
    DesktopStorageCategory.AVAILABLE -> AndroidStrings["available"]
}

/** Android's palette, so a slice keeps the colour it has on the phone. */
private fun storageColor(category: DesktopStorageCategory): Color = when (category) {
    DesktopStorageCategory.DATABASE -> Color(0xFF3F51B5)
    DesktopStorageCategory.IMAGES -> Color(0xFF4CAF50)
    DesktopStorageCategory.PREFERENCES -> Color(0xFF2196F3)
    DesktopStorageCategory.LOGS -> Color(0xFFFF9800)
    DesktopStorageCategory.OTHER -> Color(0xFF78909C)
    DesktopStorageCategory.AVAILABLE -> Color(0xFFB0BEC5)
}
