package org.skepsun.kototoro.settings.about.crashlog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.logs.CrashLogManager
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import java.io.File

/**
 * Crash log list screen migrated from [AppCompatActivity] + XML layouts
 * ([activity_crash_log.xml], [item_crash_log.xml]) to a pure Compose
 * [BaseComposeActivity] + [CrashLogScreen].
 */
@AndroidEntryPoint
class CrashLogActivity : BaseComposeActivity() {

    private var logFiles by mutableStateOf<List<File>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshList()
        setComposeContent {
            CrashLogScreen(
                logFiles = logFiles,
                onLogClick = { file ->
                    startActivity(CrashLogDetailActivity.newIntent(this, file.absolutePath))
                },
                onClearAll = {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.clear_crash_logs)
                        .setMessage(R.string.clear_crash_logs_confirm)
                        .setPositiveButton(R.string.clear_crash_logs) { _, _ ->
                            CrashLogManager.clearAll(this)
                            refreshList()
                            Toast.makeText(this, R.string.crash_logs_cleared, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                },
                onNavigateUp = ::finishAfterTransition,
            )
        }
    }

    private fun refreshList() {
        logFiles = CrashLogManager.getLogFiles(this)
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, CrashLogActivity::class.java)
    }
}
