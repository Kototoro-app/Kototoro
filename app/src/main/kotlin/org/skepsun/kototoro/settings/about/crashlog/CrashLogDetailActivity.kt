package org.skepsun.kototoro.settings.about.crashlog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ShareCompat
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.logs.CrashLogManager
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import java.io.File

/**
 * Crash log detail screen migrated from [AppCompatActivity] + XML layout
 * ([activity_crash_log_detail.xml]) to a pure Compose [BaseComposeActivity] + [CrashLogDetailScreen].
 */
@AndroidEntryPoint
class CrashLogDetailActivity : BaseComposeActivity() {

    private var logContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run {
            finish()
            return
        }
        logContent = CrashLogManager.readLog(File(filePath))

        setComposeContent {
            CrashLogDetailScreen(
                logContent = logContent,
                onShare = {
                    ShareCompat.IntentBuilder(this)
                        .setType("text/plain")
                        .setText(logContent)
                        .setSubject("Kototoro Crash Log")
                        .startChooser()
                },
                onCopy = {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash Log", logContent))
                    Toast.makeText(this, android.R.string.copy, Toast.LENGTH_SHORT).show()
                },
                onNavigateUp = ::finishAfterTransition,
            )
        }
    }

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"

        fun newIntent(context: Context, filePath: String): Intent {
            return Intent(context, CrashLogDetailActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }
}
