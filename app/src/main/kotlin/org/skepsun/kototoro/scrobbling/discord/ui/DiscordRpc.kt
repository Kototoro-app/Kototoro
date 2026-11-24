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

data class VideoRpcState(
    val title: String,
    val episodeTitle: String? = null,
    val episodeNumber: Int = 0,
    val episodesTotal: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = true,
    val playbackSpeed: Float = 1f,
)

internal fun computeVideoRpcTimestamps(
    nowMs: Long,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    fallbackStartMs: Long? = null,
): Pair<Long, Long?> {
    if (!isPlaying || durationMs <= 0L) {
        return (fallbackStartMs ?: nowMs) to null
    }
    val clampedPos = positionMs.coerceIn(0L, durationMs)
    val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
    val elapsedMs = (clampedPos / speed).toLong()
    val remainingMs = ((durationMs - clampedPos) / speed).toLong()
    return (nowMs - elapsedMs) to (nowMs + remainingMs)
}

internal fun resolveVideoRpcStateText(
    title: String,
    episodeTitle: String?,
    episodeNumber: Int,
    episodesTotal: Int,
    formatEpisodeOfTotal: (Int, Int) -> String,
): String? {
    val cleanEpisodeTitle = episodeTitle?.trim()?.takeIf { it.isNotEmpty() && !it.equals(title.trim(), ignoreCase = true) }
    return when {
        episodeNumber > 0 && episodesTotal > 1 -> formatEpisodeOfTotal(episodeNumber, episodesTotal)
        cleanEpisodeTitle != null -> cleanEpisodeTitle
        episodeNumber > 0 && episodesTotal == 1 -> formatEpisodeOfTotal(episodeNumber, episodesTotal)
        else -> null
    }
}

internal fun buildDiscordButtons(
    candidates: List<Pair<String, String>>,
): List<Map<String, String>> = candidates.mapNotNull { (label, url) ->
    val trimmedUrl = url.trim()
    val isHttp = trimmedUrl.startsWith("http://", ignoreCase = true) ||
        trimmedUrl.startsWith("https://", ignoreCase = true)
    if (label.isNotBlank() && label.utf8Size() <= BUTTON_TEXT_LIMIT && isHttp) {
        mapOf("name" to label, "url" to trimmedUrl)
    } else {
        null
    }
}.take(2)

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

    @Volatile
    private var lastIdle: Boolean = false

    @Volatile
    private var lastIsNsfw: Boolean = false

    init {
        lifecycle.addOnClearedListener(this)
    }

    override fun onCleared() {
        clearRpc()
        api.close()
    }

    fun clearRpc() = synchronized(this) {
        rpcUpdateJob?.cancel()
        rpcUpdateJob = null
        rpc?.disconnect()
        rpc = null
        lastPresence = null
        lastIdle = false
        lastIsNsfw = false
        lastUpdate = 0L
    }

    fun setIdle() {
        lastPresence?.let { updateRpcAsync(it, idle = true, isNsfw = lastIsNsfw) }
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

        val buttons = buildDiscordButtons(
            listOf(
                context.getString(R.string.read_on_s, appName) to manga.appUrl.toString(),
                context.getString(R.string.read_on_s, manga.source.getTitle(context)) to manga.publicUrl,
            ),
        )
        if (buttons.isNotEmpty()) {
            presence.setButtons(*buttons.toTypedArray())
        }
        updateRpcAsync(presence, idle = false, isNsfw = manga.isNsfw())
    }

    @AnyThread
    fun updateNovelRpc(
        content: Content,
        chapterNumber: Int,
        chaptersTotal: Int,
        chapterTitle: String? = null,
    ) {
        if (settings.isDiscordRpcSkipNsfw && content.isNsfw()) {
            clearRpc()
            return
        }
        val coverUrl = content.largeCoverUrl?.takeIf { it.isNotBlank() }
            ?: content.coverUrl?.takeIf { it.isNotBlank() }
        val stateText = when {
            chapterNumber > 0 && chaptersTotal > 0 ->
                context.getString(R.string.chapter_d_of_d, chapterNumber, chaptersTotal)
            else -> chapterTitle?.trim()?.takeIf { it.isNotEmpty() && !it.equals(content.title.trim(), ignoreCase = true) }
        }
        val presence = RichPresence()
            .setApplicationId(appId)
            .setName(appName)
            .setDetails(content.title)
            .setType(3)
            .setStartTimestamp(lastPresence?.timestamps?.get("start") ?: System.currentTimeMillis())
            .setAssetsLargeImage(coverUrl)
            .setAssetsLargeText(context.getString(R.string.reading_s, content.title))
            .setAssetsSmallImage(appIcon)
            .setAssetsSmallText(context.getString(R.string.discord_rpc_novel_description))

        if (stateText != null) {
            presence.setState(stateText)
        }
        val buttons = buildDiscordButtons(
            listOf(
                context.getString(R.string.read_on_s, appName) to content.appUrl.toString(),
                context.getString(R.string.read_on_s, content.source.getTitle(context)) to content.publicUrl,
            ),
        )
        if (buttons.isNotEmpty()) {
            presence.setButtons(*buttons.toTypedArray())
        }
        updateRpcAsync(presence, idle = false, isNsfw = content.isNsfw())
    }

    @AnyThread
    fun updateVideoRpc(content: Content?, state: VideoRpcState) {
        val isNsfw = content?.isNsfw() == true
        if (settings.isDiscordRpcSkipNsfw && isNsfw) {
            clearRpc()
            return
        }
        val title = content?.title?.takeIf { it.isNotBlank() }
            ?: state.title.takeIf { it.isNotBlank() }
            ?: return
        val coverUrl = content?.largeCoverUrl?.takeIf { it.isNotBlank() }
            ?: content?.coverUrl?.takeIf { it.isNotBlank() }
        val (startTimestamp, endTimestamp) = computeVideoRpcTimestamps(
            nowMs = System.currentTimeMillis(),
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            isPlaying = state.isPlaying,
            playbackSpeed = state.playbackSpeed,
            fallbackStartMs = lastPresence?.timestamps?.get("start"),
        )
        val stateText = resolveVideoRpcStateText(
            title = title,
            episodeTitle = state.episodeTitle,
            episodeNumber = state.episodeNumber,
            episodesTotal = state.episodesTotal,
            formatEpisodeOfTotal = { ep, total -> context.getString(R.string.episode_d_of_d, ep, total) },
        )

        val presence = RichPresence()
            .setApplicationId(appId)
            .setName(appName)
            .setDetails(title)
            .setType(3)
            .setStartTimestamp(startTimestamp)
            .setAssetsLargeImage(coverUrl)
            .setAssetsLargeText(context.getString(R.string.watching_s, title))
            .setAssetsSmallImage(appIcon)
            .setAssetsSmallText(context.getString(R.string.discord_rpc_video_description))

        if (stateText != null) {
            presence.setState(stateText)
        }
        if (endTimestamp != null) {
            presence.setEndTimestamp(endTimestamp)
        }

        if (content != null) {
            val buttons = buildDiscordButtons(
                listOf(
                    context.getString(R.string.watch_on_s, appName) to content.appUrl.toString(),
                    context.getString(R.string.watch_on_s, content.source.getTitle(context)) to content.publicUrl,
                ),
            )
            if (buttons.isNotEmpty()) {
                presence.setButtons(*buttons.toTypedArray())
            }
        }
        updateRpcAsync(presence, idle = !state.isPlaying, isNsfw = isNsfw)
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
            lastIdle = idle
            lastIsNsfw = isNsfw
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
                    onReady = { lastPresence?.let { updateRpcAsync(it, idle = lastIdle, isNsfw = lastIsNsfw) } }
                    onResumed = { lastPresence?.let { updateRpcAsync(it, idle = lastIdle, isNsfw = lastIsNsfw) } }
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
