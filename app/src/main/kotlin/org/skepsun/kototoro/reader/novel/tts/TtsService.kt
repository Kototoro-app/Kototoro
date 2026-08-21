package org.skepsun.kototoro.reader.novel.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.novel.tts.model.Token

@AndroidEntryPoint
class TtsService : LifecycleService() {

    // Note: Assuming TtsManager is provided by Hilt or manually instantiated for now
    // For MVVM, this could be passed via binder or injected. We use late init placeholder.
    lateinit var ttsManager: TtsManager

    private val binder = TtsBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _serviceState = kotlinx.coroutines.flow.MutableStateFlow(org.skepsun.kototoro.reader.novel.tts.TtsState.IDLE)
    private var stateCollectionJob: kotlinx.coroutines.Job? = null
    private var isForegroundStarted = false

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        ttsManager = createTtsManager()
        observeManagerState()
    }

    private fun observeManagerState() {
        stateCollectionJob?.cancel()
        stateCollectionJob = serviceScope.launch {
            ttsManager.state.collect { _serviceState.value = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundStarted()
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        if (::ttsManager.isInitialized) {
            ttsManager.release()
        }
        super.onDestroy()
    }

    // Public API for binding components
    fun startTts(tokens: List<Token>, startIndex: Int) {
        ensureForegroundStarted()
        ttsManager.start(tokens, startIndex)
    }

    fun pause() {
        ttsManager.pause()
    }

    fun resume() {
        ensureForegroundStarted()
        ttsManager.resume()
    }

    fun seekNext() {
        ttsManager.seekNext()
    }

    fun seekPrev() {
        ttsManager.seekPrev()
    }

    fun stopTts() {
        ttsManager.stop()
        if (isForegroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        stopSelf()
    }

    fun getState(): StateFlow<TtsState> = _serviceState

    fun getPlayingTokenIndex() = ttsManager.currentPlayingTokenIndex

    fun getToken(index: Int) = ttsManager.getToken(index)

    fun getTokens(): List<Token> = ttsManager.getTokens()

    fun reloadEngine() {
        val wasPlaying = ttsManager.state.value == TtsState.PLAYING
        val currentIndex = ttsManager.currentPlayingTokenIndex.value ?: 0
        val tokens = ttsManager.getTokens()

        ttsManager.release()
        ttsManager = createTtsManager()
        observeManagerState()

        // Ensure ExoPlayer is fully re-initialized before attempting to start playback again
        if (wasPlaying && tokens.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (::ttsManager.isInitialized) {
                    startTts(tokens, currentIndex)
                }
            }, 300)
        }
    }

    private fun createTtsManager(): TtsManager {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val engineType = prefs.getString("tts_engine_type", "SYSTEM")

        val engine: org.skepsun.kototoro.reader.novel.tts.engine.TTSEngine = if (engineType == "LEGADO") {
            val currentJson = prefs.getString("legado_tts_configs", "[]") ?: "[]"
            val voiceUrl = prefs.getString("tts_legado_voice", "")

            val type = object : com.google.gson.reflect.TypeToken<List<org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig>>() {}.type
            val configs: List<org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig> = try {
                com.google.gson.Gson().fromJson(currentJson, type) ?: emptyList()
            } catch (e: Exception) { emptyList() }

            val matchingConfig = configs.find { it.url == voiceUrl } ?: configs.firstOrNull()

            if (matchingConfig != null) {
                val okHttpClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                org.skepsun.kototoro.reader.novel.tts.engine.HttpTTSEngine(okHttpClient, matchingConfig, this)
            } else {
                // Fallback to System TTS if no Legado config found
                org.skepsun.kototoro.reader.novel.tts.engine.SystemTTSEngine(this)
            }
        } else {
            val systemVoice = prefs.getString("tts_system_voice", "default") ?: "default"
            org.skepsun.kototoro.reader.novel.tts.engine.SystemTTSEngine(this)
        }

        val cache = org.skepsun.kototoro.reader.novel.tts.TtsCache(this)
        val player = org.skepsun.kototoro.reader.novel.tts.player.ExoPlayerController(this)
        return TtsManager(this, engine, cache, player)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Kototoro ")
        .setContentText("朗读中...")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun ensureForegroundStarted() {
        if (isForegroundStarted) {
            return
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
        isForegroundStarted = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "kototoro_tts_channel"
        private const val NOTIFICATION_ID = 20000
    }
}
