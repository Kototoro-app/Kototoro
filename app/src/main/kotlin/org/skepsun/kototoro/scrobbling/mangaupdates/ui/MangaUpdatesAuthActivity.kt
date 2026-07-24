package org.skepsun.kototoro.scrobbling.mangaupdates.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.parsers.util.urlEncoded

class MangaUpdatesAuthActivity : BaseComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            MangaUpdatesAuthScreen(
                onCancel = ::finish,
                onContinue = ::continueAuth,
            )
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun continueAuth(username: String, password: String) {
        val url = "kototoro://mangaupdates-auth?code=" + "$username;$password".urlEncoded()
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        finishAfterTransition()
    }
}
