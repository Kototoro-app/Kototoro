package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.markdown.MarkdownText
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel

/**
 * Android's "about and update" screen for the Windows package: what this copy is, a check against the release
 * channel, and the download / restart-and-install flow. The package swaps itself after the window closes, so the
 * last button is labelled as a restart, not as an install that finishes here.
 */
@Composable
internal fun DesktopAppUpdateSection(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
    onExitRequest: () -> Unit,
) {
    val update = state.update
    val settings = update.settings
    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("settings-app-update")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(desktopText("more.about"), style = MaterialTheme.typography.titleMedium)
            Text(desktopText("update.version", update.version.versionName.ifEmpty { "—" }),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-update-version"))
            if (org.skepsun.kototoro.desktop.runtime.DesktopUpdateTarget.current() == null) {
                Text(desktopText("update.platformUnavailable"), color = Muted, fontSize = 12.sp,
                    modifier = Modifier.testTag("app-update-unavailable"))
                return@Column
            }
            if (!update.supported) {
                Text(desktopText("update.unsupported"), color = Muted, fontSize = 12.sp)
            }
            // Android's allow_unstable_updates: a stable copy may follow the nightly repository; a nightly one cannot
            // go back, so the switch is only shown as a locked "on".
            UpdateSettingSwitch(AndroidStrings["allow_unstable_updates"],
                AndroidStrings["allow_unstable_updates_summary"],
                update.updateChannel == DesktopUpdateChannel.NIGHTLY,
                enabled && update.unstableEditable && !update.installing && update.progress == null,
                "app-update-unstable") {
                controller.appUpdateSettings(settings.copy(unstable = it))
            }
            val available = update.available
            val downloaded = update.downloaded
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ controller.checkAppUpdate() }, enabled = enabled && !update.checking && !update.installing,
                    modifier = Modifier.testTag("app-update-check")) {
                    Text(if (update.checking) desktopText("update.checking") else desktopText("update.checkNow"))
                }
                if (available != null) {
                    Text(desktopText("update.available", available.version.text),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-update-available"))
                }
                Spacer(Modifier.weight(1f))
                // Android's about screen also opens the changelog from here; the page lists every published version.
                OutlinedButton({ controller.showChangelog() }, enabled = enabled && !update.installing,
                    modifier = Modifier.testTag("app-update-changelog")) { Text(AndroidStrings["changelog"]) }
            }
            // Android's UpdateDescription: the package size, then the release's own Markdown notes.
            if (available != null) {
                val release = available.available
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(desktopText("update.size", desktopCacheSize(available.sizeBytes)),
                        fontSize = 12.sp, color = Muted, modifier = Modifier.testTag("app-update-size"))
                    desktopReleaseDate(release.publishedAt)?.let { date ->
                        Text(desktopText("update.published", date), fontSize = 12.sp, color = Muted,
                            modifier = Modifier.testTag("app-update-published"))
                    }
                }
                if (release.notes.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().testTag("app-update-notes")) {
                        MarkdownText(release.notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                                .padding(12.dp))
                    }
                }
            }
            if (available != null && downloaded == null) {
                val progress = update.progress
                if (progress == null) {
                    OutlinedButton({ controller.downloadAppUpdate() }, enabled = enabled && !update.installing,
                        modifier = Modifier.testTag("app-update-download")) { Text(desktopText("update.download")) }
                } else {
                    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth()
                        .testTag("app-update-progress"))
                    Text(desktopText("update.downloadingPercent", (progress * 100).toInt()), fontSize = 12.sp, color = Muted)
                }
            }
            if (downloaded != null) {
                Text(desktopText("update.downloaded", downloaded.release.version.text),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-update-downloaded"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ controller.installAppUpdate(onExitRequest) }, enabled = enabled && update.supported,
                        modifier = Modifier.testTag("app-update-install")) { Text(desktopText("update.install")) }
                    OutlinedButton({ controller.discardAppUpdate() }, enabled = enabled && !update.installing,
                        modifier = Modifier.testTag("app-update-discard")) { Text(desktopText("update.discard")) }
                }
            }
            update.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.testTag("app-update-error")) }
            UpdateSettingSwitch(desktopText("update.autoCheck"),
                desktopText("update.autoCheckDetail", UPDATE_CHECK_INTERVAL_HOURS.toInt()),
                settings.autoCheck, enabled, "app-update-auto-check") {
                controller.appUpdateSettings(settings.copy(autoCheck = it))
            }
            UpdateSettingSwitch(desktopText("update.autoDownload"), desktopText("update.autoDownloadDetail"),
                settings.autoDownload, enabled && settings.autoCheck, "app-update-auto-download") {
                controller.appUpdateSettings(settings.copy(autoDownload = it))
            }
            UpdateSettingSwitch(desktopText("update.installOnExit"), desktopText("update.installOnExitDetail"),
                settings.installOnExit, enabled && settings.autoDownload, "app-update-install-on-exit") {
                controller.appUpdateSettings(settings.copy(installOnExit = it))
            }
        }
    }
}

@Composable
private fun UpdateSettingSwitch(title: String, summary: String?, checked: Boolean, enabled: Boolean, tag: String,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (enabled) Color.Unspecified else Muted)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * GitHub's `published_at` as a plain date, in the reader's own zone. A missing or unreadable stamp yields null rather
 * than a made-up day, and a payload that only looks like a date keeps its date part.
 */
internal fun desktopReleaseDate(publishedAt: String?): String? {
    val text = publishedAt?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { java.time.OffsetDateTime.parse(text).toLocalDate().toString() }
        .recoverCatching {
            java.time.Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }
        .getOrElse { text.substringBefore('T').takeIf { it.isNotEmpty() } }
}
