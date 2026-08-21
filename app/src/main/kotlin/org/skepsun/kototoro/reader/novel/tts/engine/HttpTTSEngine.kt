package org.skepsun.kototoro.reader.novel.tts.engine

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.skepsun.kototoro.reader.novel.tts.model.AudioData
import org.skepsun.kototoro.reader.novel.tts.model.Token
import org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow
import android.util.Log
import java.net.URLEncoder

class HttpTTSEngine(
    private val client: OkHttpClient,
    private val config: TtsHttpConfig,
    private val context: Context
) : TTSEngine {

    companion object {
        private const val TAG = "HttpTTSEngine"
    }

    override suspend fun synthesize(token: Token): Result<AudioData> = withContext(Dispatchers.IO) {
        // Safe synthesize with Exponential Backoff
        var lastException: Exception? = null

        repeat(3) { attempt ->
            try {
                Log.d(TAG, "Starting HTTP TTS synthesis, attempt ${attempt + 1}/3 for token: ${token.id}")
                val request = buildRequest(token)
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val code = response.code
                    val msg = response.message
                    Log.e(TAG, "HTTP Failed with code $code: $msg")
                    throw Exception("HTTP Failed with code $code: $msg")
                }

                val tmpFile = saveToFile(response)
                Log.d(TAG, "Successfully downloaded audio stream for token ${token.id}")
                return@withContext Result.success(
                    AudioData(uri = tmpFile.toUri(), durationMs = null)
                )
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for token ${token.id}", e)
                // Exponential backoff: 200ms, 400ms, 800ms
                val backoffMs = (2.0.pow(attempt).toLong() * 200)
                Log.d(TAG, "Retrying in $backoffMs ms...")
                delay(backoffMs)
            }
        }

        Log.e(TAG, "All 3 attempts failed for token ${token.id}")
        Result.failure(lastException ?: Exception("HTTP TTS synthesize failed after 3 attempts"))
    }

    private fun applyTemplate(template: String, token: Token): String {
        val voice = token.speaker?.voiceId ?: config.voice
        val text = token.text
        val encodedOnce = URLEncoder.encode(text, "UTF-8")
        val encodedTwice = URLEncoder.encode(encodedOnce, "UTF-8")

        var parsed = template
            .replace("{{text}}", text.replace("\"", "\\\""))
            .replace("{{speakText}}", text)
            .replace("{{java.encodeURI(speakText)}}", encodedOnce)
            .replace("{{java.encodeURI(java.encodeURI(speakText))}}", encodedTwice)
            .replace("{{voice}}", voice)
            .replace("{{speed}}", config.speed.toString())

        // Provide blanket regex fallback for other URI encodes
        if (parsed.contains("encodeURI")) {
            val urlEncodeRegex = Regex("\\{\\{java\\.encodeURI\\((.*?)\\)\\}\\}")
            parsed = parsed.replace(urlEncodeRegex) { matchResult ->
                URLEncoder.encode(matchResult.groupValues[1].replace("speakText", text), "UTF-8")
            }
        }
        return parsed
    }

    private fun buildRequest(token: Token): Request {
        val parsedUrl = applyTemplate(config.url, token)
        val builder = Request.Builder().url(parsedUrl)

        config.headers.forEach { (k, v) ->
            builder.addHeader(k, applyTemplate(v, token))
        }

        if (config.method.equals("POST", ignoreCase = true)) {
            val bodyStr = applyTemplate(config.bodyTemplate, token)
            val body = bodyStr.toRequestBody("application/json".toMediaType())
            builder.post(body)
        } else {
            builder.get()
        }

        return builder.build()
    }

    private fun saveToFile(response: Response): File {
        val tmp = File.createTempFile("http_tts_", ".mp3", context.cacheDir)

        response.body?.byteStream()?.use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Null response body")

        return tmp
    }
}
