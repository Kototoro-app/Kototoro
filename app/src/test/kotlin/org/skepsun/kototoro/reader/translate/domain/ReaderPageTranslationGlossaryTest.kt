package org.skepsun.kototoro.reader.translate.domain

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.dictionary.DictPair
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.reader.translate.data.ReaderTranslationTextCache
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class ReaderPageTranslationGlossaryTest {

    @Test
    fun `empty glossary produces the exact legacy text cache key`() {
        val text = "Alice entered the hall."
        val sourceLang = "en"
        val targetLang = "zh"
        val legacyRaw = listOf(
            READER_TRANSLATION_PIPELINE_VERSION,
            text,
            sourceLang,
            targetLang,
            ReaderTranslationMode.API_ONLY.name,
            "onnx-model",
            "custom",
            "https://translation.invalid/v1/chat/completions",
            "gpt-4o-mini",
            "true",
        ).joinToString("|")
        val expectedLegacyKey = "${READER_TRANSLATION_TEXT_CACHE_PREFIX}${sha256(legacyRaw)}"

        val emptyKey = buildReaderTranslationTextCacheKey(
            text = text,
            sourceLang = sourceLang,
            targetLang = targetLang,
            translationModeName = ReaderTranslationMode.API_ONLY.name,
            onnxModelId = "onnx-model",
            apiProviderPreset = "custom",
            apiEndpoint = "https://translation.invalid/v1/chat/completions",
            apiModel = "gpt-4o-mini",
            qualityFilterEnabled = true,
            glossary = emptyList(),
        )
        val excludedOnlyKey = buildReaderTranslationTextCacheKey(
            text = text,
            sourceLang = sourceLang,
            targetLang = targetLang,
            translationModeName = ReaderTranslationMode.API_ONLY.name,
            onnxModelId = "onnx-model",
            apiProviderPreset = "custom",
            apiEndpoint = "https://translation.invalid/v1/chat/completions",
            apiModel = "gpt-4o-mini",
            qualityFilterEnabled = true,
            glossary = listOf(
                DictPair("Alice", "爱丽丝", excluded = true),
                DictPair("   ", "空"),
                DictPair("Blank", "  "),
            ),
        )

        assertNull(computeGlossaryDigest(emptyList()))
        assertNull(computeGlossaryDigest(listOf(DictPair("Alice", "爱丽丝", excluded = true))))
        assertEquals(expectedLegacyKey, emptyKey)
        assertEquals(expectedLegacyKey, excludedOnlyKey)
    }

    @Test
    fun `changing glossary terms changes the text cache key while reordering keeps it stable`() {
        fun keyFor(glossary: List<DictPair>): String = buildReaderTranslationTextCacheKey(
            text = "Alice met Bob.",
            sourceLang = "en",
            targetLang = "zh",
            translationModeName = ReaderTranslationMode.API_ONLY.name,
            onnxModelId = "",
            apiProviderPreset = "custom",
            apiEndpoint = "https://translation.invalid/v1/chat/completions",
            apiModel = "gpt-4o-mini",
            qualityFilterEnabled = true,
            glossary = glossary,
        )

        val emptyKey = keyFor(emptyList())
        val initialGlossary = listOf(
            DictPair("Alice", "爱丽丝"),
            DictPair("Bob", "鲍勃"),
        )
        val reorderedGlossary = listOf(
            DictPair("Bob", "鲍勃"),
            DictPair("Alice", "爱丽丝"),
        )
        val editedGlossary = listOf(
            DictPair("Alice", "艾莉丝"),
            DictPair("Bob", "鲍勃"),
        )
        val excludedGlossary = listOf(
            DictPair("Alice", "爱丽丝"),
            DictPair("Bob", "鲍勃", excluded = true),
        )

        assertNotEquals(emptyKey, keyFor(initialGlossary))
        assertEquals(keyFor(initialGlossary), keyFor(reorderedGlossary))
        assertNotEquals(keyFor(initialGlossary), keyFor(editedGlossary))
        assertNotEquals(keyFor(initialGlossary), keyFor(excludedGlossary))
    }

    @Test
    fun `translation prompt includes relevant glossary terms and invalidates cache when glossary changes`() = runTest {
        val prompts = CopyOnWriteArrayList<String>()
        val cacheStore = LinkedHashMap<String, String>()
        val settings = mockk<AppSettings> {
            every { readerTranslationMode } returns ReaderTranslationMode.API_ONLY
            every { readerTranslationOnnxModelId } returns ""
            every { readerTranslationApiProviderPreset } returns "custom"
            every { readerTranslationApiEndpoint } returns "https://translation.invalid/v1/chat/completions"
            every { readerTranslationApiModel } returns "test-model"
            every { readerTranslationApiKey } returns "test-key"
            every { readerTranslationApiCustomHeaders } returns ""
            every { isReaderTranslationQualityFilterEnabled } returns true
            every { remoteTranslationConsentedEndpoints } returns setOf("CUSTOM|translation.invalid")
        }
        val textCache = mockk<ReaderTranslationTextCache> {
            every { this@mockk[any()] } answers { cacheStore[firstArg()] }
            every { this@mockk[any()] = any() } answers { cacheStore[firstArg()] = secondArg() }
        }
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val rawRequest = Buffer().also { requireNotNull(chain.request().body).writeTo(it) }.readUtf8()
            val messages = JSONObject(rawRequest).getJSONArray("messages")
            val userPrompt = messages.getJSONObject(messages.length() - 1).getString("content")
            prompts += userPrompt
            val reply = when {
                "艾莉丝" in userPrompt -> """[{"id":1,"translation":"艾莉丝来了"},{"id":2,"translation":"你好"}]"""
                "爱丽丝" in userPrompt -> """[{"id":1,"translation":"爱丽丝来了"},{"id":2,"translation":"你好"}]"""
                else -> """[{"id":1,"translation":"阿丽斯来了"},{"id":2,"translation":"你好"}]"""
            }
            val body = JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", reply),
                    ),
                ),
            ).toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val coordinator = ReaderTranslationCoordinator(
            settings = settings,
            textCache = textCache,
            onnxTranslationEngine = mockk(relaxed = true),
            okHttpClient = httpClient,
            jsonMediaType = "application/json; charset=utf-8".toMediaType(),
            defaultOpenAiModel = "gpt-4o-mini",
            openAiTranslationSystemPrompt = "Translate manga text.",
            maxOpenAiBatchSize = 8,
            thinkTagRegex = Regex("(?is)<think>.*?</think>"),
            buildTextCacheKey = { text, sourceLang, targetLang ->
                buildReaderTranslationTextCacheKey(
                    text = text,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    translationModeName = settings.readerTranslationMode.name,
                    onnxModelId = settings.readerTranslationOnnxModelId,
                    apiProviderPreset = settings.readerTranslationApiProviderPreset,
                    apiEndpoint = settings.readerTranslationApiEndpoint,
                    apiModel = settings.readerTranslationApiModel,
                    qualityFilterEnabled = settings.isReaderTranslationQualityFilterEnabled,
                )
            },
            sanitizeTranslation = { it.trim() },
            isAcceptableTranslation = { _, translated, _, _ -> translated.isNotBlank() },
            log = {},
            oneLine = { value, _ -> value },
            buildTextCacheKeyWithGlossary = { text, sourceLang, targetLang, glossary ->
                buildReaderTranslationTextCacheKey(
                    text = text,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    translationModeName = settings.readerTranslationMode.name,
                    onnxModelId = settings.readerTranslationOnnxModelId,
                    apiProviderPreset = settings.readerTranslationApiProviderPreset,
                    apiEndpoint = settings.readerTranslationApiEndpoint,
                    apiModel = settings.readerTranslationApiModel,
                    qualityFilterEnabled = settings.isReaderTranslationQualityFilterEnabled,
                    glossary = glossary,
                )
            },
        )

        val texts = listOf("Alice arrived", "Hello")
        val initialGlossary = listOf(
            DictPair("Alice", "爱丽丝"),
            DictPair("Carol", "卡罗尔"),
            DictPair("Hello", "哈喽", excluded = true),
        )

        val first = coordinator.translateBlocksCached(
            texts = texts,
            sourceLang = "en",
            targetLang = "zh",
            glossary = initialGlossary,
        )
        assertEquals("爱丽丝来了", first["Alice arrived"])
        assertEquals(1, prompts.size)
        assertTrue(prompts.last().contains("Alice => 爱丽丝"))
        assertFalse(prompts.last().contains("Carol"))
        assertFalse(prompts.last().contains("哈喽"))

        // Same glossary hits the text cache without issuing another API request.
        val cached = coordinator.translateBlocksCached(
            texts = texts,
            sourceLang = "en",
            targetLang = "zh",
            glossary = initialGlossary,
        )
        assertEquals("爱丽丝来了", cached["Alice arrived"])
        assertEquals(1, prompts.size)

        // Updating a glossary term changes the cache key and triggers a new translation request.
        val updatedGlossary = listOf(DictPair("Alice", "艾莉丝"))
        val updated = coordinator.translateBlocksCached(
            texts = texts,
            sourceLang = "en",
            targetLang = "zh",
            glossary = updatedGlossary,
        )
        assertEquals("艾莉丝来了", updated["Alice arrived"])
        assertEquals(2, prompts.size)
        assertTrue(prompts.last().contains("Alice => 艾莉丝"))
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
            }
        }
    }
}
