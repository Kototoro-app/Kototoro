package org.skepsun.kototoro.reader.novel.tts.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.reader.novel.tts.model.AudioData
import org.skepsun.kototoro.reader.novel.tts.model.Token
import org.skepsun.kototoro.reader.novel.tts.model.TokenType
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class SystemTTSEngine(
    private val context: Context
) : TTSEngine {

    private val mutex = Mutex() // 防止并发冲突的绝对屏障
    private val initDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

    // 延迟初始化的TTS实例
    private val tts: TextToSpeech by lazy {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initDeferred.complete(true)
            } else {
                initDeferred.complete(false)
            }
        }.apply {
            setOnUtteranceProgressListener(null) // Reset first
        }
    }

    override suspend fun synthesize(token: Token): Result<AudioData> {
        // Ensure TTS is requested to initialize
        val myTts = tts

        // Wait for initialization to complete
        val isInitialized = initDeferred.await()
        if (!isInitialized) {
            return Result.failure(Exception("System TTS Engine failed to initialize."))
        }

        return mutex.withLock {
            if (token.type == TokenType.PAUSE) {
                // 生成静音文件并直接返回
                return@withLock Result.success(createSilenceWav(token.durationHintMs ?: 300))
            }

            suspendCancellableCoroutine { cont ->
                val file = File.createTempFile(
                    "tts_${token.id}_",
                    ".wav",
                    context.cacheDir
                )

                val utteranceId = UUID.randomUUID().toString()

                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Do nothing
                    }

                    override fun onDone(utteranceId: String?) {
                        if (!cont.isCompleted) {
                            cont.resume(
                                Result.success(
                                    AudioData(
                                        uri = file.toUri(),
                                        durationMs = null
                                    )
                                ),
                                onCancellation = null
                            )
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        if (!cont.isCompleted) {
                            cont.resume(Result.failure(Exception("TTS synthesizeToFile failed for token ${token.id}")), null)
                        }
                    }
                }

                tts.setOnUtteranceProgressListener(listener)

                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val voiceId = prefs.getString("tts_system_voice", "default")

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    if (voiceId != null && voiceId != "default") {
                        putString("voiceName", voiceId)
                    }
                }

                if (voiceId != null && voiceId != "default") {
                    try {
                        val voices = tts.voices
                        if (voices != null) {
                            val targetVoice = voices.firstOrNull { it.name == voiceId }
                            if (targetVoice != null) {
                                tts.voice = targetVoice
                            }
                        } else {
                            // Fallback for OEM TTS (like OnePlus) where getVoices is empty but language is supported
                            val languageTag = voiceId
                            val locale = java.util.Locale.forLanguageTag(languageTag)
                            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                                tts.language = locale
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("SystemTTSEngine", "Failed to set voice or language", e)
                    }
                }

                val result = tts.synthesizeToFile(
                    token.text,
                    params,
                    file,
                    utteranceId
                )

                if (result != TextToSpeech.SUCCESS) {
                    if (!cont.isCompleted) {
                        cont.resume(Result.failure(Exception("TTS start failed with code $result")), null)
                    }
                }

                cont.invokeOnCancellation {
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun createSilenceWav(
        durationMs: Long,
        sampleRate: Int = 22050
    ): AudioData {
        val numSamples = (durationMs * sampleRate / 1000).toInt()
        val data = ShortArray(numSamples) { 0 }

        val file = File.createTempFile("silence_", ".wav", context.cacheDir)

        FileOutputStream(file).use { fos ->
            // Write basic WAV header
            val dataSize = data.size * 2
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put("RIFF".toByteArray())
                putInt(36 + dataSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16) // Subchunk1Size
                putShort(1) // AudioFormat (PCM)
                putShort(1) // NumChannels
                putInt(sampleRate) // SampleRate
                putInt(sampleRate * 2) // ByteRate
                putShort(2) // BlockAlign
                putShort(16) // BitsPerSample
                put("data".toByteArray())
                putInt(dataSize)
            }
            fos.write(header.array())

            // Write short array
            val buffer = ByteBuffer.allocate(dataSize)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (sample in data) {
                buffer.putShort(sample)
            }
            fos.write(buffer.array())
        }

        return AudioData(file.toUri(), durationMs)
    }

    override fun release() {
        if (initDeferred.isCompleted && initDeferred.getCompleted()) {
            tts.shutdown()
        }
    }
}
