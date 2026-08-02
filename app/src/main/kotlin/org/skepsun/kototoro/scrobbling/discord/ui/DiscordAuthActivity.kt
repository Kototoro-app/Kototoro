package org.skepsun.kototoro.scrobbling.discord.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.scrobbling.discord.data.DiscordRepository
import javax.inject.Inject

@AndroidEntryPoint
class DiscordAuthActivity : ComponentActivity() {

	@Inject
	lateinit var repository: DiscordRepository

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		handleIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent) {
		val data = intent.data
		if (data?.scheme == REDIRECT_SCHEME && data.host == REDIRECT_HOST) {
			val code = data.getQueryParameter("code")
			if (code == null) {
				finish()
				return
			}
			lifecycleScope.launch {
				runCatching { repository.authorize(code) }
					.onSuccess {
						setResult(RESULT_OK)
					}
					.onFailure { it.printStackTraceDebug() }
				finish()
			}
		} else {
			startAuth()
		}
	}

	private fun startAuth() {
		val authIntent = Intent(Intent.ACTION_VIEW, repository.oauthUrl.toUri())
		try {
			startActivity(authIntent)
		} catch (_: Exception) {
			authIntent.data = repository.oauthFallbackUrl.toUri()
			runCatching { startActivity(authIntent) }
				.onFailure {
					it.printStackTraceDebug()
					finish()
				}
		}
	}

	private companion object {
		const val REDIRECT_SCHEME = "kototoro"
		const val REDIRECT_HOST = "discord-auth"
	}
}
