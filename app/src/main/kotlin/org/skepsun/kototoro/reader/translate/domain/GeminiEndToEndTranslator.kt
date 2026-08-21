package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.prefs.AppSettings
import eu.kanade.tachiyomi.network.await
import java.io.ByteArrayOutputStream

internal class GeminiEndToEndTranslator(
    private val settings: AppSettings,
    private val okHttpClient: OkHttpClient,
    private val jsonMediaType: MediaType,
    private val log: (() -> String) -> Unit,
) {

    suspend fun processImage(
        bitmap: Bitmap,
        sourceLang: String,
        targetLang: String,
    ): List<Pair<BubbleInput, String>> = withContext(Dispatchers.IO) {
        val endpoint = settings.readerE2eApiEndpoint.trim()
        val apiKey = settings.readerE2eApiKey.trim()
        val model = settings.readerE2eApiModel.trim().ifBlank { "gemini-2.0-flash" }

        if (endpoint.isBlank() || apiKey.isBlank()) {
            log { "GeminiEndToEndTranslator: endpoint or api key is empty" }
            return@withContext emptyList()
        }

        val base64Image = encodeBitmapToBase64(bitmap)
        if (base64Image.isEmpty()) {
            log { "GeminiEndToEndTranslator: Failed to encode bitmap" }
            return@withContext emptyList()
        }

        val systemPrompt = "You are a manga translation assistant with precise vision capabilities."
        val userPrompt = buildString {
            appendLine("Please identify all the text in the image.")
            appendLine("This is a manga page. The text is in $sourceLang. Please translate it into $targetLang.")
            appendLine("Please output the information of each text block in a JSON array format. Do not use markdown blocks, output raw JSON only.")
            appendLine("The JSON format MUST be an array of objects, where each object contains:")
            appendLine("- `coordinates`: an array of exactly 4 numbers [ymin, xmin, ymax, xmax], representing normalized coordinates from 0 to 1000. If you are unsure about the coordinates, strictly output [0, 0, 0, 0] instead of leaving it empty.")
            appendLine("- `original_text`: the original text.")
            appendLine("- `translated_text`: the $targetLang translation.")
            appendLine("- IMPORTANT: If the detected text is explicitly a pirate manga website URL, watermark (like 'colamanga'), or completely meaningless background texture rather than human dialogue/story structure, set `translated_text` exactly to 'KOTOTORO_IGNORE_BLOCK'.")
        }

        // Choose payload format based on endpoint: Assume Native Google API if it ends with generateContent
        val isNativeGoogleFormat = endpoint.contains("generateContent") || endpoint.contains("googleapis.com/v1beta/models/")
        val isOpenAiFormat = !isNativeGoogleFormat

        val payload = JSONObject()
        if (isOpenAiFormat) {
            payload.put("model", model)
            payload.put("temperature", 0.1) // Avoid boundary 0 which some proxies reject
            payload.put("max_tokens", 4096)
            // Avoid response_format setting as some proxy APIs strictly reject it for Gemini models.

            // Combine system and user prompts to avoid routing crashes on proxies that don't support system instructions with vision payloads
            val combinedPrompt = "$systemPrompt\n\n$userPrompt"

            payload.put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", combinedPrompt)
                        ).put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$base64Image")
                                )
                        )
                    )
                )
            )
        } else {
            // Native Gemini Format
            payload.put("system_instruction", JSONObject().put(
                "parts", JSONObject().put("text", systemPrompt)
            ))
            payload.put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(
                    JSONObject().put("text", userPrompt)
                ).put(
                    JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    })
                ))
            }))
            payload.put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("responseMimeType", "application/json")
            })
        }

        // Support Google's URL parameter key if no headers were expected
        var finalUrl = endpoint
        val trimmedEndpoint = endpoint.trimEnd('/')
        if (isOpenAiFormat && !trimmedEndpoint.endsWith("/chat/completions")) {
            finalUrl = "$trimmedEndpoint/chat/completions"
        } else if (isOpenAiFormat) {
            finalUrl = trimmedEndpoint // ensures no trailing slash breaks proxy routers
        }
        if (!isOpenAiFormat && !endpoint.contains("key=")) {
            val separator = if (endpoint.contains("?")) "&" else "?"
            finalUrl = "$endpoint${separator}key=$apiKey"
        }

        // org.json.JSONObject escapes '/' to '\/' which breaks many domestic API proxies in image base64
        val payloadStr = payload.toString().replace("\\/", "/")

        val request = Request.Builder()
            .url(finalUrl)
            .post(payloadStr.toRequestBody(jsonMediaType))
            .header("Content-Encoding", "identity") // Bypasses Kototoro's broken GZipInterceptor which adds the gzip header without compressing
            .apply {
                if (isOpenAiFormat) {
                    header("Authorization", "Bearer $apiKey")
                }
                val customHeaders = settings.readerE2eApiCustomHeaders.trim()
                if (customHeaders.isNotBlank() && customHeaders.startsWith("{")) {
                    runCatching {
                        val json = JSONObject(customHeaders)
                        for (key in json.keys()) {
                            val value = json.optString(key)
                            if (value.isNotBlank()) {
                                header(key, value)
                            }
                        }
                    }
                }
            }
            .build()

        log { "GeminiEndToEndTranslator: Sending payload (size: ${payload.toString().length})" }

        val result: Response? = runCatching {
            okHttpClient.newCall(request).await()
        }.onFailure {
            log { "GeminiEndToEndTranslator network error: ${it.message}" }
        }.getOrNull()

        if (result == null) return@withContext emptyList()

        try {
            val body = result.body?.string() ?: ""
            if (!result.isSuccessful) {
                log { "GeminiEndToEndTranslator failed: code=${result.code} body=$body" }
                return@withContext emptyList()
            }

            return@withContext parseResponse(body, bitmap.width, bitmap.height, sourceLang, targetLang)
        } finally {
            result.close()
        }
    }

    private fun parseResponse(rawBody: String, imageWidth: Int, imageHeight: Int, sourceLang: String, targetLang: String): List<Pair<BubbleInput, String>> {
        if (rawBody.isBlank()) return emptyList()

        val content = extractMessageContent(rawBody)
        if (content.isBlank()) return emptyList()

        val jsonArray = parseJsonArray(content) ?: return emptyList()
        val bubbles = mutableListOf<Pair<BubbleInput, String>>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val coords = obj.optJSONArray("coordinates") ?: continue
            if (coords.length() < 4) continue

            // [ymin, xmin, ymax, xmax] normalized 0-1000
            val yminNorm = coords.optDouble(0, 0.0)
            val xminNorm = coords.optDouble(1, 0.0)
            val ymaxNorm = coords.optDouble(2, 0.0)
            val xmaxNorm = coords.optDouble(3, 0.0)

            val left = ((xminNorm / 1000.0) * imageWidth).toInt().coerceIn(0, imageWidth)
            val top = ((yminNorm / 1000.0) * imageHeight).toInt().coerceIn(0, imageHeight)
            val right = ((xmaxNorm / 1000.0) * imageWidth).toInt().coerceIn(0, imageWidth)
            val bottom = ((ymaxNorm / 1000.0) * imageHeight).toInt().coerceIn(0, imageHeight)

            val originalText = obj.optString("original_text", "")
            val optTranslatedText = obj.optString("translated_text", "")
            // Some models might output translation directly under translatedText
            val translatedText = if (optTranslatedText.isBlank()) obj.optString("translation", "") else optTranslatedText

            if (translatedText.isBlank() || translatedText.contains("KOTOTORO_IGNORE_BLOCK")) continue

            // Discard completely invalid or degenerate boxes
            if (left >= right || top >= bottom) continue

            val rect = Rect(left, top, right, bottom)
            val bubbleInput = BubbleInput(
                rect = rect,
                sourceText = originalText,
                verticalPreferred = isVerticalTargetLanguage(targetLang) && sourceLang.startsWith("ja") && rect.height() > rect.width() * 1.3,
                classId = 0, // 0 usually maps to default/text
                detectorAnchored = true,
                sourceContentRect = rect
            )
            bubbles.add(bubbleInput to translatedText)
        }

        log { "GeminiEndToEndTranslator: Parsed ${bubbles.size} bubbles out of ${jsonArray.length()} array items" }
        return bubbles
    }

    private fun isVerticalTargetLanguage(lang: String): Boolean {
        return lang.startsWith("ja") || lang.startsWith("zh") || lang.startsWith("ko")
    }

    private fun extractMessageContent(rawBody: String): String {
        return runCatching {
            val json = JSONObject(rawBody)

            // Try OpenAI Format first
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.optJSONObject(0)?.optJSONObject("message")
                if (message != null) return@runCatching message.optString("content", "")
            }

            // Try Native Gemini Format
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return@runCatching parts.optJSONObject(0)?.optString("text", "") ?: ""
                }
            }

            ""
        }.getOrDefault("")
    }

    private fun parseJsonArray(content: String): JSONArray? {
        var clean = content.replace("```json", "").replace("```", "").trim()
        // Fallback patch for Gemini occasionally generating malformed JSON like `"coordinates":,`
        clean = clean.replace(Regex("\"\\s*:\\s*,"), "\": null,")
        return runCatching {
            JSONArray(clean)
        }.onFailure {
            log { "GeminiEndToEndTranslator: parseJsonArray failed, content: $clean" }
        }.getOrNull()
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        var scaledBitmap = bitmap
        val maxDim = 1024 // Extremely aggressive downscale for strict domestic proxy body size limits (e.g. 256KB)
        if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = kotlin.math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            val newWidth = (bitmap.width * ratio).toInt()
            val newHeight = (bitmap.height * ratio).toInt()
            scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }
        try {
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            val byteArray = outputStream.toByteArray()
            return Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } finally {
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
        }
    }
}
