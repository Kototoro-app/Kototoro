package org.skepsun.kototoro.reader.novel.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.skepsun.kototoro.reader.novel.tts.engine.TTSEngine
import org.skepsun.kototoro.reader.novel.tts.model.AudioData
import org.skepsun.kototoro.reader.novel.tts.model.Token
import org.skepsun.kototoro.reader.novel.tts.model.TokenType
import org.skepsun.kototoro.reader.novel.tts.model.TtsSession
import kotlin.coroutines.cancellation.CancellationException

class Prefetcher(
    private val engine: TTSEngine,
    private val cache: TtsCache,
    private val context: Context
) {
    private val semaphore = Semaphore(3) // 保护 HTTP 免于 429 Too Many Requests

    companion object {
        private const val TAG = "TtsPrefetcher"
    }

    fun prefetch(
        session: TtsSession,
        tokens: List<Token>,
        startIndex: Int,
        engineId: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): Flow<Pair<Int, AudioData>> = channelFlow {
        val jobs = kotlinx.coroutines.channels.Channel<kotlinx.coroutines.Deferred<Pair<Int, AudioData>?>>(capacity = tokens.size - startIndex + 1)

        launch {
            for (i in startIndex until tokens.size) {
                val token = tokens[i]
                val deferred = async {
                    semaphore.withPermit {
                        Log.d(TAG, "Starting synthesis for token index: $i, text: ${token.text.take(10)}")
                        synthesizeToken(i, token, engineId, voiceId, speed, pitch)
                    }
                }
                // Session may have been cancelled while we were synthesizing;
                // channel would be closed, so bail out gracefully.
                try {
                    jobs.send(deferred)
                } catch (e: ClosedSendChannelException) {
                    Log.d(TAG, "Jobs channel closed, stopping prefetch at index $i")
                    return@launch
                }
            }
            jobs.close()
        }

        for (deferred in jobs) {
            val result = try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TTS prefetch task failed", e)
                null
            }
            if (result != null) {
                try {
                    send(result)
                } catch (e: ClosedSendChannelException) {
                    Log.d(TAG, "Output channel closed, stopping emission")
                    break
                }
            }
        }
    }

    private suspend fun synthesizeToken(
        index: Int,
        token: Token,
        engineId: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): Pair<Int, AudioData>? {
        try {
            if (token.type == TokenType.PAUSE) {
                val audio = engine.synthesize(token).getOrNull()
                return if (audio != null) index to audio else null
            }

            val currentVoiceId = token.speaker?.voiceId ?: voiceId
            val key = cache.buildCacheKey(token, engineId, currentVoiceId, speed, pitch)
            var audio = cache.get(key)
            if (audio == null) {
                audio = engine.synthesize(token).getOrNull()
                if (audio != null && !cache.put(key, audio)) {
                    Log.w(TAG, "Synthesized empty TTS audio for token index: $index")
                    return null
                }
            }
            return if (audio != null) index to audio else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prefetch TTS for token index: $index", e)
            return null
        }
    }
}
