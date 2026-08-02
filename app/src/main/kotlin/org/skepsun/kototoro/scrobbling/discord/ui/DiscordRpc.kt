package org.skepsun.kototoro.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.discord.oauth2rpc.API
import com.discord.oauth2rpc.DiscordAssetRegistrar
import com.discord.oauth2rpc.GatewayClient
import com.discord.oauth2rpc.GatewayConnectOptions
import com.discord.oauth2rpc.structures.RichPresence
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import okio.utf8Size
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.model.appUrl
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.lifecycleScope
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.ui.pager.ReaderUiState
import org.skepsun.kototoro.scrobbling.discord.data.DiscordRepository
import java.io.File
import java.util.Collections
import javax.inject.Inject

private const val STATUS_ONLINE = "online"
private const val STATUS_IDLE = "idle"
private const val BUTTON_TEXT_LIMIT = 32
private const val DEBOUNCE_TIMEOUT = 16_000L // 16 sec

@ViewModelScoped
class DiscordRpc @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val repository: DiscordRepository,
	private val imageLoader: ImageLoader,
	lifecycle: ViewModelLifecycle,
) : RetainedLifecycle.OnClearedListener {

	private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
	private val appId = context.getString(R.string.discord_app_id)
	private val appName = context.getString(R.string.app_name)
	private val appIcon = context.getString(R.string.app_icon_url)
	private val mpCache = Collections.synchronizedMap(ArrayMap<String, String>())
	private val api = API()
	private var lastUpdate = 0L
	private var rpc: GatewayClient? = null
	private var rpcUpdateJob: Job? = null
	private var assetRegistrar: DiscordAssetRegistrar? = null
	private var registrarToken: String? = null

	@Volatile
	private var lastPresence: RichPresence? = null

	init {
		lifecycle.addOnClearedListener(this)
	}

	override fun onCleared() {
		clearRpc()
		api.close()
	}

	fun clearRpc() = synchronized(this) {
		rpc?.disconnect()
		rpc = null
		lastUpdate = 0L
	}

	fun setIdle() {
		lastPresence?.let { updateRpcAsync(it, idle = true, isNsfw = false) }
	}

	@AnyThread
	fun updateRpc(manga: Content, state: ReaderUiState) {
		if (settings.isDiscordRpcSkipNsfw && manga.isNsfw()) {
			clearRpc()
			return
		}
		val coverUrl = manga.largeCoverUrl?.takeIf { it.isNotBlank() }
			?: manga.coverUrl?.takeIf { it.isNotBlank() }
		val presence = RichPresence()
			.setApplicationId(appId)
			.setName(appName)
			.setDetails(manga.title)
			.setState(context.getString(R.string.chapter_d_of_d, state.chapterNumber, state.chaptersTotal))
			.setType(3)
			.setStartTimestamp(lastPresence?.timestamps?.get("start") ?: System.currentTimeMillis())
			.setAssetsLargeImage(coverUrl)
			.setAssetsLargeText(context.getString(R.string.reading_s, manga.title))
			.setAssetsSmallImage(appIcon)
			.setAssetsSmallText(context.getString(R.string.discord_rpc_description))

		val appButton = context.getString(R.string.read_on_s, appName)
		val sourceButton = context.getString(R.string.read_on_s, manga.source.getTitle(context))
		if (appButton.utf8Size() <= BUTTON_TEXT_LIMIT && sourceButton.utf8Size() <= BUTTON_TEXT_LIMIT) {
			presence.setButtons(
				mapOf("name" to appButton, "url" to manga.appUrl.toString()),
				mapOf("name" to sourceButton, "url" to manga.publicUrl),
			)
		}
		updateRpcAsync(presence, idle = false, isNsfw = manga.isNsfw())
	}

	private fun updateRpcAsync(
		presence: RichPresence,
		idle: Boolean,
		isNsfw: Boolean,
	) {
		val previousJob = rpcUpdateJob
		rpcUpdateJob = coroutineScope.launch {
			previousJob?.cancelAndJoin()
			val debounceTime = lastUpdate + DEBOUNCE_TIMEOUT - SystemClock.elapsedRealtime()
			if (debounceTime > 0) delay(debounceTime)
			launch { getRpc() }
			presence.setAssetsLargeImage(presence.assets["largeImage"]?.toMediaProxyUrl(isNsfw))
			presence.setAssetsSmallImage(presence.assets["smallImage"]?.toMediaProxyUrl(false))
			lastPresence = presence
			getRpc()?.let { client ->
				client.send(
					3,
					mutableMapOf<String, Any?>(
						"activities" to listOf(presence.toJSON()),
						"status" to if (idle) STATUS_IDLE else STATUS_ONLINE,
						"since" to (presence.timestamps?.get("start") ?: System.currentTimeMillis()),
						"afk" to idle,
					),
				)
				lastUpdate = SystemClock.elapsedRealtime()
			}
		}
	}

	private suspend fun String.toMediaProxyUrl(isNsfw: Boolean): String? {
		if (repository.isMediaProxyUrl(this)) return this
		return mpCache[this] ?: runCatchingCancellable {
			val upload = getCacheFile(this)?.let { repository.getMediaProxyUrl(it) }
			getRegistrar()?.resolve(upload ?: this, if (isNsfw) 1 else 0)
		}.onSuccess { url ->
			url?.let { mpCache[this] = it }
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private suspend fun getCacheFile(url: String): File? {
		var snapshot = imageLoader.diskCache?.openSnapshot(url)
		if (snapshot == null) {
			val result = imageLoader.execute(ImageRequest.Builder(context).data(url).build())
			if (result is SuccessResult) snapshot = imageLoader.diskCache?.openSnapshot(url)
		}
		return snapshot?.use { File(it.data.toString()) }
	}

	private fun getRpc(): GatewayClient? = rpc ?: synchronized(this) {
		rpc ?: settings.discordToken
			?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
			?.takeIf { settings.isDiscordRpcEnabled }
			?.let { token ->
				GatewayClient().apply {
					onReady = { lastPresence?.let { updateRpcAsync(it, idle = false, isNsfw = false) } }
					onResumed = { lastPresence?.let { updateRpcAsync(it, idle = false, isNsfw = false) } }
					coroutineScope.launch {
						try {
							var currentToken = token
							runCatching { repository.checkToken(currentToken) }.onFailure {
								repository.refreshToken()
								currentToken = settings.discordToken ?: token
							}
							connect(GatewayConnectOptions(token = currentToken))
						} catch (e: Exception) {
							e.printStackTraceDebug()
							clearRpc()
						}
					}
				}
			}
			.also { rpc = it }
	}

	private fun getRegistrar(): DiscordAssetRegistrar? {
		val token = settings.discordToken ?: return null
		if (assetRegistrar == null || registrarToken != token) {
			registrarToken = token
			assetRegistrar = DiscordAssetRegistrar(api, appId, token)
		}
		return assetRegistrar
	}
}
