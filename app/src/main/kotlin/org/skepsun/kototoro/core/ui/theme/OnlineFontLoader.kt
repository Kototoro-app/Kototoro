package org.skepsun.kototoro.core.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

sealed interface OnlineFontDownloadStatus {
	data object Idle : OnlineFontDownloadStatus
	data class Downloading(val preset: OnlineFontPreset) : OnlineFontDownloadStatus
	data class Ready(val preset: OnlineFontPreset) : OnlineFontDownloadStatus
	data class Failed(val preset: OnlineFontPreset) : OnlineFontDownloadStatus
}

enum class OnlineFontPreset(
	val displayName: String,
	val cacheName: String,
	val url: String,
	val sha256: String,
	val extension: String,
) {
	SARASA_GOTHIC(
		displayName = "Sarasa Gothic",
		cacheName = "sarasa-gothic-sc",
		url = "https://unpkg.com/@fontpkg/sarasa-gothic-sc@0.36.0/sarasa-gothic-sc-regular.ttf",
		sha256 = "ce122dd0cc3bf33f32ccf48e30c0779ddf8bf3c5a3b30b335b5f5d7916caafab",
		extension = "ttf",
	),
	LXGW_WENKAI(
		displayName = "LXGW WenKai",
		cacheName = "lxgw-wen-kai",
		url = "https://unpkg.com/@fontpkg/lxgw-wen-kai@1.520.0/LXGWWenKai-Regular.ttf",
		sha256 = "8d6ba638ac9553413354cfaab97637c1cd778444e259441ea1e5f8fb2c697fba",
		extension = "ttf",
	),
	NOTO_SANS_CJK_SC(
		displayName = "Noto Sans CJK SC",
		cacheName = "noto-sans-cjk-sc",
		url = "https://unpkg.com/@fontpkg/noto-sans-cjk-sc@2.4.0/NotoSansCJKsc-Regular.otf",
		sha256 = "2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b",
		extension = "otf",
	),
	SOURCE_HAN_SERIF_SC(
		displayName = "Source Han Serif SC",
		cacheName = "source-han-serif-sc",
		url = "https://unpkg.com/@fontpkg/source-han-serif-sc@2.3.2/SourceHanSerifSC-Regular.otf",
		sha256 = "0bf165efa014db063f2d62a12cec1c237038dfad3d67fb2c77818a540f016180",
		extension = "otf",
	),
}

@Singleton
class OnlineFontLoader @Inject constructor(
	@ApplicationContext context: Context,
	@ContentHttpClient private val httpClientProvider: Provider<OkHttpClient>,
) {
	private val cacheDirectory = File(context.filesDir, "fonts")
	private val downloadLocks = OnlineFontPreset.entries.associateWith { Mutex() }
	private val applicationContext = context.applicationContext
	private val mainHandler = Handler(Looper.getMainLooper())
	private val notificationManager = NotificationManagerCompat.from(applicationContext)
	private val _downloadStatus = kotlinx.coroutines.flow.MutableStateFlow<OnlineFontDownloadStatus>(
		OnlineFontDownloadStatus.Idle,
	)
	val downloadStatus: kotlinx.coroutines.flow.StateFlow<OnlineFontDownloadStatus> = _downloadStatus

	suspend fun load(preset: OnlineFontPreset): FontFamily? = downloadLocks.getValue(preset).withLock {
		withContext(Dispatchers.IO) {
			val fontFile = getOrDownload(preset) ?: return@withContext null
			FontFamily(Font(fontFile))
		}
	}

	private fun getOrDownload(preset: OnlineFontPreset): File? {
		cacheDirectory.mkdirs()
		val target = File(cacheDirectory, "${preset.cacheName}.${preset.extension}")
		if (target.isValidFont(preset)) {
			android.util.Log.i(TAG, "Font cache hit: ${preset.name} (${target.length()} bytes)")
			return target
		}
		android.util.Log.i(TAG, "Font download started: ${preset.name} from ${preset.url}")
		target.delete()
		announceDownloadStatus(OnlineFontDownloadStatus.Downloading(preset))

		val temporary = File(cacheDirectory, "${target.name}.download")
		temporary.delete()
		return try {
			val request = Request.Builder().url(preset.url).get().build()
			httpClientProvider.get().newCall(request).execute().use { response ->
				check(response.isSuccessful) { "Font request failed: HTTP ${response.code}" }
				val body = checkNotNull(response.body) { "Font response has no body" }
				check(body.contentLength() <= MAX_FONT_SIZE) { "Font response is too large" }
				body.byteStream().use { input ->
					temporary.outputStream().use { output -> input.copyTo(output) }
				}
			}
			check(temporary.length() in MIN_FONT_SIZE..MAX_FONT_SIZE) { "Invalid font size" }
			check(temporary.sha256() == preset.sha256) { "Font checksum mismatch" }
			check(temporary.renameTo(target)) { "Cannot move downloaded font into cache" }
			announceDownloadStatus(OnlineFontDownloadStatus.Ready(preset))
			target
		} catch (error: Throwable) {
			temporary.delete()
			if (error is CancellationException) throw error
			android.util.Log.e(TAG, "Font download failed: ${preset.name}", error)
			announceDownloadStatus(OnlineFontDownloadStatus.Failed(preset))
			null
		}
	}

	@SuppressLint("MissingPermission")
	private fun announceDownloadStatus(status: OnlineFontDownloadStatus) {
		_downloadStatus.value = status
		val message = when (status) {
			is OnlineFontDownloadStatus.Downloading ->
				applicationContext.getString(R.string.font_downloading, status.preset.displayName)
			is OnlineFontDownloadStatus.Ready ->
				applicationContext.getString(R.string.font_download_ready, status.preset.displayName)
			is OnlineFontDownloadStatus.Failed ->
				applicationContext.getString(R.string.font_download_failed, status.preset.displayName)
			OnlineFontDownloadStatus.Idle -> return
		}
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(applicationContext.getString(R.string.font_downloads))
			.setContentText(message)
			.setSmallIcon(R.drawable.ic_notification)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.setOnlyAlertOnce(true)
			.setAutoCancel(status !is OnlineFontDownloadStatus.Downloading)
			.setOngoing(status is OnlineFontDownloadStatus.Downloading)
			.apply {
				if (status is OnlineFontDownloadStatus.Downloading) {
					setProgress(0, 0, true)
				}
			}
			.build()
		try {
			notificationManager.createNotificationChannel(
				NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
					.setName(applicationContext.getString(R.string.font_downloads))
					.setShowBadge(false)
					.setVibrationEnabled(false)
					.setLightsEnabled(false)
					.setSound(null, null)
					.build(),
			)
			if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
				notificationManager.notify(TAG, NOTIFICATION_ID, notification)
				return
			}
			android.util.Log.w(TAG, "Font notification permission is disabled")
		} catch (error: SecurityException) {
			android.util.Log.w(TAG, "Cannot show font notification", error)
		}
		mainHandler.post {
			Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
		}
	}

	private fun File.isValidFont(preset: OnlineFontPreset): Boolean {
		return isFile && length() in MIN_FONT_SIZE..MAX_FONT_SIZE && sha256() == preset.sha256
	}

	private fun File.sha256(): String {
		val digest = MessageDigest.getInstance("SHA-256")
		inputStream().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	fun clearDownloadStatus(status: OnlineFontDownloadStatus) {
		if (_downloadStatus.value == status) {
			_downloadStatus.value = OnlineFontDownloadStatus.Idle
		}
	}

	private companion object {
		const val TAG = "OnlineFontLoader"
		const val CHANNEL_ID = "font_downloads"
		const val NOTIFICATION_ID = 18041
		const val MIN_FONT_SIZE = 64L * 1024L
		const val MAX_FONT_SIZE = 32L * 1024L * 1024L
	}
}
