package org.skepsun.kototoro.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseComposeActivity

class OpenUrlConfirmActivity : BaseComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URI).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        val mimeType = intent?.getStringExtra(EXTRA_MIME_TYPE)
        val sourceName = intent?.getStringExtra(EXTRA_SOURCE_NAME).orEmpty()

        val message = listOfNotNull(
            url,
            sourceName.takeIf { it.isNotBlank() }?.let { getString(R.string.source) + ": " + it },
        ).joinToString("\n")

        setComposeContent {
            OpenUrlConfirmDialog(
                message = message,
                onDismissRequest = ::finish,
                onConfirm = {
                    openTarget(url, mimeType)
                    finish()
                },
            )
        }
    }

    @Composable
    private fun OpenUrlConfirmDialog(
        message: String,
        onDismissRequest: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(R.string.open_in_browser)) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(text = stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    private fun openTarget(url: String, mimeType: String?) {
        val parsedUri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!mimeType.isNullOrBlank()) {
                setDataAndType(parsedUri, mimeType)
            }
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_MIME_TYPE = "mimeType"
        const val EXTRA_SOURCE_ORIGIN = "sourceOrigin"
        const val EXTRA_SOURCE_NAME = "sourceName"
        const val EXTRA_SOURCE_TYPE = "sourceType"

        fun newIntent(
            activity: AppCompatActivity,
            url: String,
            mimeType: String?,
            sourceOrigin: String,
            sourceName: String,
            sourceType: Int,
        ): Intent {
            return Intent(activity, OpenUrlConfirmActivity::class.java).apply {
                putExtra(EXTRA_URI, url)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_SOURCE_ORIGIN, sourceOrigin)
                putExtra(EXTRA_SOURCE_NAME, sourceName)
                putExtra(EXTRA_SOURCE_TYPE, sourceType)
                putExtra(AppRouter.KEY_TITLE, sourceName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
