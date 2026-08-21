package org.skepsun.kototoro.reader.novel.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.reader.novel.tts.engine.TTSEngine
import org.skepsun.kototoro.reader.novel.tts.model.AudioData
import org.skepsun.kototoro.reader.novel.tts.model.Token
import org.skepsun.kototoro.reader.novel.tts.model.TtsSession
import org.skepsun.kototoro.reader.novel.tts.player.ExoPlayerController
import android.util.Log

enum class TtsState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    SEEKING,
    COMPLETED,
    ERROR
}

class TtsManager(
    private val context: android.content.Context,
    private val engine: TTSEngine,
    private val cache: TtsCache,
    private val playerController: ExoPlayerController
) {
    private val prefetcher = Prefetcher(engine, cache, context)

    private val _state = MutableStateFlow(TtsState.IDLE)
    val state = _state.asStateFlow()

    val currentPlayingTokenIndex = playerController.currentItemIndex

    private var currentSession: TtsSession? = null
    private var audioQueue = Channel<Pair<Int, AudioData>>(capacity = 10)

    private var tokens: List<Token> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var prefetchJob: Job? = null
    private var playbackJob: Job? = null

    // For caching hash purposes (read from preferences)
    private val engineId: String
    private val voiceId: String
    private val speed: Float
    private val pitch: Float

    init {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        engineId = prefs.getString("tts_engine_type", "SYSTEM") ?: "SYSTEM"
        voiceId = if (engineId == "LEGADO") {
            prefs.getString("tts_legado_voice", "default") ?: "default"
        } else {
            prefs.getString("tts_system_voice", "default") ?: "default"
        }
        speed = 1.0f // TODO: read from settings when implemented
        pitch = 1.0f // TODO: read from settings when implemented
    }

    companion object {
        private const val TAG = "TtsManager"
    }

    fun start(tokens: List<Token>, startIndex: Int) {
        Log.d(TAG, "start called with startIndex: $startIndex, tokens count: ${tokens.size}")
        // Cancel old operations
        shutdownCurrentSession()

        // Start new session
        val session = TtsSession(
            id = System.currentTimeMillis(),
            startTokenIndex = startIndex
        )
        currentSession = session
        this.tokens = tokens

        _state.value = TtsState.BUFFERING

        // Create a new channel for the new session
        audioQueue = Channel(capacity = 10)

        // Reset playback completion flag to prevent stale completion signals
        // from the previous session triggering handleTtsPageCompleted() re-entrantly
        playerController.resetCompletion()

        Log.d(TAG, "Created new TtsSession with id: ${session.id}")

        startPrefetch(session, startIndex)
        startPlayback(session)
        watchPlaybackCompletion()
    }

    private fun startPrefetch(session: TtsSession, startIndex: Int) {
        Log.d(TAG, "startPrefetch initiated for session ${session.id}")
        prefetchJob = scope.launch(Dispatchers.IO) {
            prefetcher.prefetch(
                session = session,
                tokens = tokens,
                startIndex = startIndex,
                engineId = engineId,
                voiceId = voiceId,
                speed = speed,
                pitch = pitch
            ).collect { (index, audio) ->
                // Crucial Race Condition Defense: Return if session has changed
                if (session != currentSession) {
                    Log.w(TAG, "Session mismatch detected in prefetch, discarding audio chunk for index: $index")
                    return@collect
                }

                Log.d(TAG, "Prefetch sending chunk to audioQueue for index $index")
                audioQueue.send(index to audio)
            }
        }
    }

    private fun startPlayback(session: TtsSession) {
        Log.d(TAG, "startPlayback initiated for session ${session.id}")
        playbackJob = scope.launch(Dispatchers.Main) {
            playerController.play(audioQueue.consumeAsFlow())
            _state.value = TtsState.PLAYING
            Log.d(TAG, "Playback flow consumption started.")
        }
    }

    private var completionWatchJob: Job? = null

    private fun watchPlaybackCompletion() {
        completionWatchJob?.cancel()
        completionWatchJob = scope.launch {
            playerController.playbackCompleted.collect { completed ->
                // Only transition to COMPLETED if we are actually PLAYING.
                // Ignore stale completion signals during BUFFERING/IDLE/COMPLETED
                // to prevent re-entrant handleTtsPageCompleted() loops.
                if (completed && _state.value == TtsState.PLAYING && currentSession != null) {
                    Log.d(TAG, "Playback completed for current page")
                    _state.value = TtsState.COMPLETED
                }
            }
        }
    }

    fun seekTo(index: Int) {
        Log.d(TAG, "seekTo index: $index")
        _state.value = TtsState.SEEKING
        start(tokens, index)
    }

    fun seekNext() {
        Log.d(TAG, "seekNext requested")
        playerController.seekNext()
    }

    fun seekPrev() {
        Log.d(TAG, "seekPrev requested")
        playerController.seekPrev()
    }

    fun pause() {
        Log.d(TAG, "pause requested")
        playerController.pause()
        _state.value = TtsState.PAUSED
    }

    fun resume() {
        Log.d(TAG, "resume requested")
        playerController.resume()
        _state.value = TtsState.PLAYING
    }

    fun getToken(index: Int): Token? {
        return tokens.getOrNull(index)
    }

    fun getTokens(): List<Token> = tokens

    fun stop() {
        Log.d(TAG, "stop requested")
        shutdownCurrentSession()
        _state.value = TtsState.IDLE
    }

    private fun shutdownCurrentSession() {
        if (currentSession != null) {
            Log.d(TAG, "Shutting down current session: ${currentSession?.id}")
        }
        currentSession = null
        prefetchJob?.cancel()
        playbackJob?.cancel()
        completionWatchJob?.cancel()
        // Only close the channel — calling cancel() after close() throws
        // CancellationException that propagates to the consumer coroutine
        runCatching { audioQueue.close() }
        playerController.stop()
    }

    fun release() {
        Log.d(TAG, "release requested")
        shutdownCurrentSession()
        playerController.release()
        engine.release()
    }
}
