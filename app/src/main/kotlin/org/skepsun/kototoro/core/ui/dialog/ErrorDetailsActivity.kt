package org.skepsun.kototoro.core.ui.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.AppUpdateRepository
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.copyToClipboard
import org.skepsun.kototoro.core.util.ext.getCauseUrl
import org.skepsun.kototoro.core.util.ext.isHttpUrl
import org.skepsun.kototoro.core.util.ext.isReportable
import org.skepsun.kototoro.core.util.ext.report
import java.io.Serializable
import javax.inject.Inject

@AndroidEntryPoint
class ErrorDetailsActivity : BaseComposeActivity() {
    @Inject lateinit var appUpdateRepository: AppUpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exception = intent.getSerializableExtra(AppRouter.KEY_ERROR) as? Throwable ?: return finish()
        val causeUrl = exception.getCauseUrl()?.takeIf(String::isHttpUrl)
        setComposeContent {
            AlertDialog(
                onDismissRequest = ::finishAfterTransition,
                title = { Text(getString(R.string.error_details)) },
                text = {
                    Column {
                        exception.message?.let { Text(it) }
                        if (causeUrl != null) {
                            TextButton(onClick = { router.openBrowser(causeUrl, null, null) }) {
                                Text(getString(R.string.open_in_browser))
                            }
                        }
                        val disclaimer = when {
                            appUpdateRepository.isUpdateAvailable -> R.string.error_disclaimer_app_outdated
                            exception.isReportable() -> R.string.error_disclaimer_report
                            else -> 0
                        }
                        if (disclaimer != 0) Text(getString(disclaimer), Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    when {
                        appUpdateRepository.isUpdateAvailable -> TextButton(onClick = {
                            router.openAppUpdate()
                            finishAfterTransition()
                        }) { Text(getString(R.string.update)) }
                        exception.isReportable() -> TextButton(onClick = {
                            exception.report(silent = true)
                            finishAfterTransition()
                        }) { Text(getString(R.string.report)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        copyToClipboard(getString(R.string.error), exception.stackTraceToString())
                    }) { Text(getString(androidx.preference.R.string.copy)) }
                },
            )
        }
    }
}
