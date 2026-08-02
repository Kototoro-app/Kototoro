package org.skepsun.kototoro.settings.discord

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.isNetworkError
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.discord.data.DiscordRepository
import javax.inject.Inject

@HiltViewModel
class DiscordSettingsViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: DiscordRepository,
) : BaseViewModel() {

	val tokenState: StateFlow<Pair<TokenState, String?>> = settings.observe(
		AppSettings.KEY_DISCORD_RPC,
		AppSettings.KEY_DISCORD_TOKEN,
	).flatMapLatest {
		checkToken()
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		TokenState.CHECKING to settings.discordToken,
	)

	private fun checkToken(): Flow<Pair<TokenState, String?>> = flow {
		val token = settings.discordToken
		if (!settings.isDiscordRpcEnabled) {
			emit(
				if (token == null) {
					TokenState.EMPTY to null
				} else {
					TokenState.VALID to null
				},
			)
			return@flow
		}
		if (token == null) {
			emit(TokenState.REQUIRED to null)
			return@flow
		}
		if (!token.startsWith("Bearer ", ignoreCase = true)) {
			emit(TokenState.INVALID to null)
			return@flow
		}
		emit(TokenState.CHECKING to null)
		runCatchingCancellable { repository.checkToken(token) }.fold(
			onSuccess = { username -> emit(TokenState.VALID to username) },
			onFailure = {
				if (it.isNetworkError()) {
					emit(TokenState.VALID to null)
				} else {
					emit(TokenState.INVALID to null)
				}
			},
		)
	}
}
