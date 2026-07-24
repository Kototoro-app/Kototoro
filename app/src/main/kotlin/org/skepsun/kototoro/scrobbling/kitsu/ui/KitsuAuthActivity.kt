package org.skepsun.kototoro.scrobbling.kitsu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.parsers.util.urlEncoded

class KitsuAuthActivity : BaseComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            KitsuAuthScreen(
                onCancel = ::finish,
                onContinue = ::continueAuth,
            )
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun continueAuth(email: String, password: String) {
        val url = "kototoro://kitsu-auth?code=" + "$email;$password".urlEncoded()
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        finishAfterTransition()
    }
}
