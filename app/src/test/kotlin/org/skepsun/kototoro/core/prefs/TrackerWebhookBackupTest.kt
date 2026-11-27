package org.skepsun.kototoro.core.prefs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * A webhook URL is a secret: whoever holds it can post into the user's channel. It must never end up
 * in an exported settings section, and a restore must not be able to bring one back.
 */
class TrackerWebhookBackupTest {

    private val webhookUrl = "https://discord.com/api/webhooks/12345/token-secret"

    private val devicePrefs = mapOf(
        "reader_mode" to 0,
        AppSettings.KEY_TRACKER_ENABLED to true,
        AppSettings.KEY_TRACKER_WEBHOOK_ENABLED to true,
        AppSettings.KEY_TRACKER_WEBHOOK_URL to webhookUrl,
        AppSettings.KEY_TRACKER_WEBHOOK_LAST_SENT to 1_700_000_000_000L,
    )

    @Test
    fun `the webhook url is not a backupable key`() {
        BackupSettingsFilter.isBackupableKey(AppSettings.KEY_TRACKER_WEBHOOK_URL) shouldBe false
    }

    @Test
    fun `the last webhook send time is not a backupable key`() {
        BackupSettingsFilter.isBackupableKey(AppSettings.KEY_TRACKER_WEBHOOK_LAST_SENT) shouldBe false
    }

    @Test
    fun `the webhook switch itself is still backed up`() {
        BackupSettingsFilter.isBackupableKey(AppSettings.KEY_TRACKER_WEBHOOK_ENABLED) shouldBe true
    }

    @Test
    fun `the exported settings section has no plaintext webhook url`() {
        // What BackupRepository.dumpSettings() writes: every preference that passes the filter, as JSON.
        val exported = JSONObject(devicePrefs.filterKeys(BackupSettingsFilter::isBackupableKey)).toString()

        exported shouldNotContain webhookUrl
        exported shouldNotContain "token-secret"
        exported shouldNotContain AppSettings.KEY_TRACKER_WEBHOOK_URL
        exported shouldContain AppSettings.KEY_TRACKER_ENABLED
    }

    @Test
    fun `a restored section cannot bring a webhook url back`() {
        // What AppSettings.upsertAll() keeps from the backup: the same filter applies on the way in,
        // so a hand-edited or foreign backup cannot install a working endpoint.
        val fromBackup = devicePrefs.filterKeys(BackupSettingsFilter::isBackupableKey)

        fromBackup.containsKey(AppSettings.KEY_TRACKER_WEBHOOK_URL) shouldBe false
        fromBackup.containsKey(AppSettings.KEY_TRACKER_WEBHOOK_LAST_SENT) shouldBe false
    }
}
