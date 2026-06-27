package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
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
    ) {
        val webDavEnabled = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_WEBDAV_ENABLED, false)
        val keepLocal = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY, true)

        SettingsPreferenceSection(title = "") {
            SettingsActionPreference(
                title = stringResource(R.string.backups_output_directory),
                summary = outputSummary,
                enabled = !webDavEnabled || keepLocal,
                onClick = onOutputClick,
            )
            SettingsChoicePreference(
                title = stringResource(R.string.backup_frequency),
                value = settings.prefs.getString(AppSettings.KEY_BACKUP_PERIODICAL_FREQUENCY, "7") ?: "7",
                options = freqOptions,
                onValueChange = { value ->
                    settings.prefs.edit().putString(AppSettings.KEY_BACKUP_PERIODICAL_FREQUENCY, value).apply()
                },
            )
            val trimEnabled = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_PERIODICAL_TRIM, true)
            SettingsSwitchPreference(
                title = stringResource(R.string.delete_old_backups),
                summary = stringResource(R.string.delete_old_backups_summary),
                checked = trimEnabled,
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_PERIODICAL_TRIM, checked).apply()
                },
            )
            if (trimEnabled) {
                SettingsSliderPreference(
                    title = stringResource(R.string.max_backups_count),
                    value = settings.prefs.getInt(AppSettings.KEY_BACKUP_PERIODICAL_COUNT, 10),
                    valueRange = 1..32,
                    step = 1,
                    valueText = { it.toString() },
                    onValueChange = { value ->
                        settings.prefs.edit().putInt(AppSettings.KEY_BACKUP_PERIODICAL_COUNT, value).apply()
                    },
                )
            }
            if (isLastBackupVisible) {
                SettingsInfoPreference(
                    title = lastBackupSummary ?: "",
                    summary = "",
                )
            }
        }

        if (isTelegramAvailable) {
            SettingsPreferenceSection(title = stringResource(R.string.telegram_integration)) {
                val tgEnabled = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_TG_ENABLED, false)
                SettingsSwitchPreference(
                    title = stringResource(R.string.send_backups_telegram),
                    checked = tgEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_TG_ENABLED, checked).apply()
                    },
                )
                if (tgEnabled) {
                    SettingsTextInputPreference(
                        title = stringResource(R.string.telegram_chat_id),
                        summary = settings.prefs.getString(AppSettings.KEY_BACKUP_TG_CHAT, "")?.ifEmpty { stringResource(R.string.telegram_chat_id_summary) } ?: stringResource(R.string.telegram_chat_id_summary),
                        value = settings.prefs.getString(AppSettings.KEY_BACKUP_TG_CHAT, "") ?: "",
                        onValueChange = { value ->
                            settings.prefs.edit().putString(AppSettings.KEY_BACKUP_TG_CHAT, value).apply()
                        },
                    )
                    SettingsActionPreference(
                        title = stringResource(R.string.open_telegram_bot),
                        summary = stringResource(R.string.open_telegram_bot_summary),
                        onClick = onTelegramOpenClick,
                    )
                    SettingsActionPreference(
                        title = stringResource(R.string.test_connection),
                        enabled = !isTelegramCheckLoading,
                        onClick = onTelegramTestClick,
                    )
                }
            }
        }

        SettingsPreferenceSection(title = stringResource(R.string.webdav_integration)) {
            SettingsSwitchPreference(
                title = stringResource(R.string.send_backups_webdav),
                checked = webDavEnabled,
                onCheckedChange = { checked ->
                    settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_WEBDAV_ENABLED, checked).apply()
                },
            )
            if (webDavEnabled) {
                SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_server_url),
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_URL, "") ?: "",
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_URL, value).apply()
                    },
                )
                SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_username),
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_USERNAME, "") ?: "",
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_USERNAME, value).apply()
                    },
                )
                SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_password),
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_PASSWORD, "") ?: "",
                    isPassword = true,
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_PASSWORD, value).apply()
                    },
                )
                SettingsTextInputPreference(
                    title = stringResource(R.string.webdav_remote_path),
                    value = settings.prefs.getString(AppSettings.KEY_BACKUP_WEBDAV_PATH, "") ?: "",
                    onValueChange = { value ->
                        settings.prefs.edit().putString(AppSettings.KEY_BACKUP_WEBDAV_PATH, value).apply()
                    },
                )
                SettingsActionPreference(
                    title = stringResource(R.string.test_connection),
                    enabled = !isWebDavCheckLoading,
                    onClick = onWebDavTestClick,
                )
                SettingsActionPreference(
                    title = stringResource(R.string.webdav_upload_now),
                    enabled = !isWebDavCheckLoading,
                    onClick = onWebDavUploadClick,
                )
                SettingsActionPreference(
                    title = stringResource(R.string.webdav_restore_now),
                    enabled = !isWebDavCheckLoading,
                    onClick = onWebDavRestoreClick,
                )
                SettingsSwitchPreference(
                    title = stringResource(R.string.webdav_keep_local_copy),
                    summary = stringResource(R.string.webdav_keep_local_copy_summary),
                    checked = keepLocal,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY, checked).apply()
                    },
                )
                SettingsSwitchPreference(
                    title = stringResource(R.string.webdav_auto_restore),
                    summary = stringResource(R.string.webdav_auto_restore_summary),
                    checked = settings.prefs.getBoolean(AppSettings.KEY_BACKUP_WEBDAV_AUTO_RESTORE, false),
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_BACKUP_WEBDAV_AUTO_RESTORE, checked).apply()
                    },
                )
                if (webDavLastActionText != null) {
                    SettingsInfoPreference(
                        title = "${stringResource(R.string.recent_webdav_action)}\n$webDavLastActionText",
                        summary = "",
                    )
                }
                if (!keepLocal) {
                    SettingsInfoPreference(
                        title = stringResource(R.string.backup_periodic_explain_keep_local_copy_off),
                        summary = "",
                    )
                }
            }
        }
    }
}
