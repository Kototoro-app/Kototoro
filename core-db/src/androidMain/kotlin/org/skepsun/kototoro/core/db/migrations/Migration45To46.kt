package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration45To46 : Migration(45, 46) {

    override fun migrate(db: SupportSQLiteDatabase) {
        updateCloudstreamRepo(
            db = db,
            oldBaseUrl = "https://raw.githubusercontent.com/phisher98/CXXX/builds",
            newBaseUrl = "https://raw.githubusercontent.com/phisher98/CXXX/builds/CXXX.json",
        )
        updateCloudstreamRepo(
            db = db,
            oldBaseUrl = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds",
            newBaseUrl = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
        )
        updateCloudstreamRepo(
            db = db,
            oldBaseUrl = "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/builds",
            newBaseUrl = "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/builds/Netflix.json",
        )
    }

    private fun updateCloudstreamRepo(
        db: SupportSQLiteDatabase,
        oldBaseUrl: String,
        newBaseUrl: String,
    ) {
        val fingerprint = newBaseUrl.hashCode().toString(16)
        db.execSQL(
            "DELETE FROM extension_repos WHERE type = ? AND base_url = ? AND EXISTS (SELECT 1 FROM extension_repos WHERE type = ? AND base_url = ?)",
            arrayOf("CLOUDSTREAM", oldBaseUrl, "CLOUDSTREAM", newBaseUrl),
        )
        db.execSQL(
            """
			UPDATE extension_repos
			SET base_url = ?,
			    website = CASE WHEN website = ? THEN ? ELSE website END,
			    signing_key_fingerprint = ?
			WHERE type = ? AND base_url = ?
            """.trimIndent(),
            arrayOf(newBaseUrl, oldBaseUrl, newBaseUrl, fingerprint, "CLOUDSTREAM", oldBaseUrl),
        )
    }
}
