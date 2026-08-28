package org.skepsun.kototoro.backups.ui.restore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.external.ExternalBackupImportSummary

/**
 * Translucent host activity that summarizes the result of an external backup import
 * (favorites/history counts, skipped titles and — most importantly — the sources that
 * are not installed, so the user knows why some favorites carry no source name).
 */
@AndroidEntryPoint
class ExternalImportResultActivity : AppCompatActivity() {

    private var shown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.hasExtra(EXTRA_SUMMARY)) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (shown || isFinishing) return
        shown = true
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            showResultDialog()
        }
    }

    private fun showResultDialog() {
        val summary = intent.getStringExtra(EXTRA_SUMMARY)?.let { raw ->
            runCatching { json.decodeFromString<ExternalBackupImportSummary>(raw) }.getOrNull()
        } ?: run {
            finish()
            return
        }
        val text = buildString {
            append(getString(R.string.external_import_result_summary, summary.favoritesImported, summary.historyImported))
            if (summary.uninstalledSources.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.external_import_result_uninstalled_header))
                summary.uninstalledSources.sortedByDescending { it.recordCount }.forEach { source ->
                    append('\n')
                    append(
                        getString(
                            R.string.external_import_result_uninstalled_item,
                            source.displayName ?: source.sourceKey,
                            source.recordCount,
                        ),
                    )
                }
            }
            if (summary.failedCount > 0) {
                append("\n\n")
                append(getString(R.string.external_import_result_failed, summary.failedCount))
                val preview = summary.failedTitles.take(5).joinToString("、")
                if (preview.isNotBlank()) {
                    append('\n')
                    append(preview)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.external_import_result_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        private const val EXTRA_SUMMARY = "external_import_summary"
        private val json = Json { ignoreUnknownKeys = true }

        fun createIntent(context: Context, summary: ExternalBackupImportSummary): Intent {
            return Intent(context, ExternalImportResultActivity::class.java)
                .putExtra(EXTRA_SUMMARY, json.encodeToString(summary))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /**
         * Launches the summary dialog. Returns `false` when the app is in the background
         * (Android blocks background activity launches) — the completion notification is
         * the fallback path then.
         */
        fun startIfAppInForeground(context: Context, summary: ExternalBackupImportSummary): Boolean {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return false
            }
            return try {
                context.startActivity(createIntent(context, summary))
                true
            } catch (e: Throwable) {
                false
            }
        }
    }
}
