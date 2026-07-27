package org.skepsun.kototoro.settings.about.crashlog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.logs.CrashLogManager
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import java.io.File

/**
 * Crash log list screen migrated from [AppCompatActivity] + XML layouts
 * ([activity_crash_log.xml], [item_crash_log.xml]) to a pure Compose
 * [BaseComposeActivity] + [CrashLogScreen].
 */
@AndroidEntryPoint
class CrashLogActivity : BaseComposeActivity() {

    private var logFiles by mutableStateOf<List<File>>(emptyList())
    private var showClearConfirm by mutableStateOf(false)

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
                    showClearConfirm = true
                },
                onNavigateUp = ::finishAfterTransition,
            )

            if (showClearConfirm) {
                SettingsAlertDialog(
                    title = stringResource(R.string.clear_crash_logs),
                    onDismissRequest = { showClearConfirm = false },
                    text = { Text(stringResource(R.string.clear_crash_logs_confirm)) },
                    confirmButton = {
                        SettingsDialogActionButton(
                            text = stringResource(R.string.clear_crash_logs),
                            onClick = {
                                showClearConfirm = false
                                CrashLogManager.clearAll(this)
                                refreshList()
                                Toast.makeText(
                                    this,
                                    R.string.crash_logs_cleared,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    },
                    dismissButton = {
                        SettingsDialogActionButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = { showClearConfirm = false },
                        )
                    },
                )
            }
        }
    }

    private fun refreshList() {
        logFiles = CrashLogManager.getLogFiles(this)
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, CrashLogActivity::class.java)
    }
}
