package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings

@Composable
fun PeriodicalBackupSettingsScreen(
    settings: AppSettings,
    outputSummary: String?,
    isOutputError: Boolean,
    lastBackupSummary: String?,
    isLastBackupVisible: Boolean,
    isLastBackupError: Boolean,
    isTelegramAvailable: Boolean,
    isTelegramCheckLoading: Boolean,
    isWebDavCheckLoading: Boolean,
    webDavLastActionText: String?,
    onOutputClick: () -> Unit,
    onTelegramOpenClick: () -> Unit,
    onTelegramTestClick: () -> Unit,
    onWebDavTestClick: () -> Unit,
    onWebDavUploadClick: () -> Unit,
    onWebDavRestoreClick: () -> Unit,
) {
    val freqOptions = listOf(
        SettingsChoiceOption("6", stringResource(R.string.frequency_every_6_hours)),
        SettingsChoiceOption("24", stringResource(R.string.frequency_every_day)),
        SettingsChoiceOption("48", stringResource(R.string.frequency_every_2_days)),
        SettingsChoiceOption("168", stringResource(R.string.frequency_once_per_week)),
        SettingsChoiceOption("720", stringResource(R.string.frequency_twice_per_month)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val webDavEnabled = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_WEBDAV_ENABLED, false)
        val keepLocal = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY, true)

        SettingsPreferenceGroup(title = "") {
            item { SettingsActionPreference(
                title = stringResource(R.string.backups_output_directory),
                iconRes = R.drawable.ic_folder_file,
                summary = outputSummary,
                enabled = !webDavEnabled || keepLocal,
                onClick = onOutputClick,
            ) }
            item { SettingsChoicePreference(
                title = stringResource(R.string.backup_frequency),
                iconRes = R.drawable.ic_schedule,
                value = settings.prefs.getString(AppSettings.KEY_BACKUP_PERIODICAL_FREQUENCY, "7") ?: "7",
                options = freqOptions,
                onValueChange = { value ->
                    settings.prefs.edit().putString(AppSettings.KEY_BACKUP_PERIODICAL_FREQUENCY, value).apply()
                },
            ) }
            val trimEnabled = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_PERIODICAL_TRIM, true)
            item { SettingsSwitchPreference(
                title = stringResource(R.string.delete_old_backups),
                iconRes = R.drawable.ic_delete,
                summary = stringResource(R.string.delete_old_backups_summary),
                checked = trimEnabled,
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_PERIODICAL_TRIM, checked).apply()
                },
            ) }
            if (trimEnabled) {
                item { SettingsSliderPreference(
                    title = stringResource(R.string.max_backups_count),
                    iconRes = R.drawable.ic_timeline,
                    value = settings.prefs.getInt(AppSettings.KEY_BACKUP_PERIODICAL_COUNT, 10),
                    valueRange = 1..32,
                    step = 1,
                    valueText = { it.toString() },
                    onValueChange = { value ->
                        settings.prefs.edit().putInt(AppSettings.KEY_BACKUP_PERIODICAL_COUNT, value).apply()
                    },
                ) }
            }
            if (isLastBackupVisible) {
                item { SettingsInfoPreference(
                    title = lastBackupSummary ?: "",
                    iconRes = R.drawable.ic_info_outline,
                    summary = "",
                ) }
            }
        }

        if (isTelegramAvailable) {
            SettingsPreferenceGroup(title = stringResource(R.string.telegram_integration)) {
                val tgEnabled = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_TG_ENABLED, false)
                item { SettingsSwitchPreference(
                    title = stringResource(R.string.send_backups_telegram),
                    iconRes = R.drawable.ic_send,
                    checked = tgEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_TG_ENABLED, checked).apply()
                    },
                ) }
                if (tgEnabled) {
                    item { SettingsTextInputPreference(
                        title = stringResource(R.string.telegram_chat_id),
                        iconRes = R.drawable.ic_user,
                        summary = settings.prefs.getString(AppSettings.KEY_BACKUP_TG_CHAT, "")?.ifEmpty { stringResource(R.string.telegram_chat_id_summary) } ?: stringResource(R.string.telegram_chat_id_summary),
                        value = settings.prefs.getString(AppSettings.KEY_BACKUP_TG_CHAT, "") ?: "",
                        onValueChange = { value ->
                            settings.prefs.edit().putString(AppSettings.KEY_BACKUP_TG_CHAT, value).apply()
                        },
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.open_telegram_bot),
                        iconRes = R.drawable.ic_open_external,
                        summary = stringResource(R.string.open_telegram_bot_summary),
                        onClick = onTelegramOpenClick,
                    ) }
                    item { SettingsActionPreference(
                        title = stringResource(R.string.test_connection),
                        iconRes = R.drawable.ic_plug,
                        enabled = !isTelegramCheckLoading,
                        onClick = onTelegramTestClick,
                    ) }
                }
            }
        }

        SettingsPreferenceGroup(title = stringResource(R.string.webdav_integration)) {
            item { SettingsSwitchPreference(
                title = stringResource(R.string.send_backups_webdav),
                iconRes = R.drawable.ic_cloud_upload,
                checked = webDavEnabled,
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_WEBDAV_ENABLED, checked).apply()
                },
            ) }
            if (webDavEnabled) {
                item { SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_server_url),
                    iconRes = R.drawable.ic_web,
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_URL, "") ?: "",
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_URL, value).apply()
                    },
                ) }
                item { SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_username),
                    iconRes = R.drawable.ic_user,
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_USERNAME, "") ?: "",
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_USERNAME, value).apply()
                    },
                ) }
                item { SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_password),
                    iconRes = R.drawable.ic_key,
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_PASSWORD, "") ?: "",
                    isPassword = true,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_PASSWORD, value).apply()
                    },
                ) }
                item { SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_remote_path),
                    iconRes = R.drawable.ic_folder_file,
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_PATH, "") ?: "",
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_PATH, value).apply()
                    },
                ) }
                item { SettingsActionPreference(
                    title = stringResource(R.string.test_connection),
                    iconRes = R.drawable.ic_plug,
                    enabled = !isWebDavCheckLoading,
                    onClick = onWebDavTestClick,
                ) }
                item { SettingsActionPreference(
                    title = stringResource(R.string.webdav_upload_now),
                    iconRes = R.drawable.ic_cloud_upload,
                    enabled = !isWebDavCheckLoading,
                    onClick = onWebDavUploadClick,
                ) }
                item { SettingsActionPreference(
                    title = stringResource(R.string.webdav_restore_now),
                    iconRes = R.drawable.ic_cloud_download,
                    enabled = !isWebDavCheckLoading,
                    onClick = onWebDavRestoreClick,
                ) }
                item { SettingsSwitchPreference(
                    title = stringResource(R.string.webdav_keep_local_copy),
                    iconRes = R.drawable.ic_save,
                    summary = stringResource(R.string.webdav_keep_local_copy_summary),
                    checked = keepLocal,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY, checked).apply()
                    },
                ) }
                item { SettingsSwitchPreference(
                    title = stringResource(R.string.webdav_auto_restore),
                    iconRes = R.drawable.ic_sync,
                    summary = stringResource(R.string.webdav_auto_restore_summary),
                    checked = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_WEBDAV_AUTO_RESTORE, false),
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_WEBDAV_AUTO_RESTORE, checked).apply()
                    },
                ) }
                if (webDavLastActionText != null) {
                    item { SettingsInfoPreference(
                        title = "${stringResource(R.string.recent_webdav_action)}\n$webDavLastActionText",
                        iconRes = R.drawable.ic_info_outline,
                        summary = "",
                    ) }
                }
                if (!keepLocal) {
                    item { SettingsInfoPreference(
                        title = stringResource(R.string.backup_periodic_explain_keep_local_copy_off),
                        iconRes = R.drawable.ic_info_outline,
                        summary = "",
                    ) }
                }
            }
        }
    }
}
