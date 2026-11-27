package org.skepsun.kototoro.reader.translate.domain

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.dictionary.TranslationDictionaryRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.NovelTranslationProcessor
import org.skepsun.kototoro.reader.translate.data.ReaderTranslationTextCache
import java.util.concurrent.atomic.AtomicInteger

class RemoteTranslationConsentPolicyTest {

    @Test
    fun `local offline mode is never blocked even with remote endpoint configured`() {
        val decision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.LOCAL_OFFLINE,
                providerId = "OPENAI",
                providerDisplayName = "OpenAI",
                endpointUrl = "https://api.openai.com/v1/chat/completions",
                payloadKind = RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY,
                consentedEndpointKeys = emptySet(),
            ),
        )

        assertEquals(RemoteTranslationConsentDecision.Allowed, decision)
    }

    @Test
    fun `remote text api first send requires consent with normalized provider host and payload summary`() {
        val decision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_TEXT_API,
                providerId = "openai",
                providerDisplayName = "OpenAI",
                endpointUrl = "https://API.OpenAI.com:443/v1/chat/completions?foo=bar",
                payloadKind = RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY,
                consentedEndpointKeys = emptySet(),
            ),
        )

        val required = decision as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("OPENAI", required.request.providerId)
        assertEquals("OpenAI", required.request.providerDisplayName)
        assertEquals("api.openai.com", required.request.normalizedHost)
        assertEquals("OPENAI|api.openai.com", required.request.consentKey)
        assertEquals(RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY, required.request.payloadKind)
        assertEquals(RemoteTranslationUsageMode.API_ONLY, required.request.usageMode)
    }

    @Test
    fun `remote e2e vision api requires consent with full page image payload kind`() {
        val decision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_E2E_VISION_API,
                providerId = "GEMINI",
                providerDisplayName = "Google Gemini",
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
                payloadKind = RemoteTranslationPayloadKind.FULL_PAGE_IMAGE_AND_GLOSSARY,
                consentedEndpointKeys = emptySet(),
            ),
        )

        val required = decision as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("GEMINI|generativelanguage.googleapis.com", required.request.consentKey)
        assertEquals("generativelanguage.googleapis.com", required.request.normalizedHost)
        assertEquals(RemoteTranslationPayloadKind.FULL_PAGE_IMAGE_AND_GLOSSARY, required.request.payloadKind)
        assertEquals(RemoteTranslationUsageMode.END_TO_END_API, required.request.usageMode)
    }

    @Test
    fun `after consenting to provider and host subsequent requests to same provider and host are allowed`() {
        val firstDecision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_TEXT_API,
                providerId = "DEEPSEEK",
                providerDisplayName = "DeepSeek",
                endpointUrl = "https://api.deepseek.com/chat/completions",
                payloadKind = RemoteTranslationPayloadKind.NOVEL_TEXT_AND_GLOSSARY,
                consentedEndpointKeys = emptySet(),
            ),
        ) as RemoteTranslationConsentDecision.RequiresConsent

        val persistedKeys = RemoteTranslationConsentPolicy.recordConsent(
            existingKeys = emptySet(),
            consentKey = firstDecision.request.consentKey,
        )

        val secondDecision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_TEXT_API,
                providerId = "deepseek",
                providerDisplayName = "DeepSeek",
                endpointUrl = "https://API.DEEPSEEK.COM/v1/chat/completions",
                payloadKind = RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY,
                consentedEndpointKeys = persistedKeys,
            ),
        )

        assertEquals(RemoteTranslationConsentDecision.Allowed, secondDecision)
    }

    @Test
    fun `switching to a different host prompts again`() {
        val persistedKeys = RemoteTranslationConsentPolicy.recordConsent(
            existingKeys = emptySet(),
            consentKey = "CUSTOM|api.first-proxy.example",
        )

        val decision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_TEXT_API,
                providerId = "CUSTOM",
                providerDisplayName = "Custom",
                endpointUrl = "https://api.second-proxy.example/v1/chat/completions",
                payloadKind = RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY,
                consentedEndpointKeys = persistedKeys,
            ),
        )

        val required = decision as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("api.second-proxy.example", required.request.normalizedHost)
        assertEquals("CUSTOM|api.second-proxy.example", required.request.consentKey)
    }

    @Test
    fun `switching to a different provider on the same host prompts again`() {
        val persistedKeys = RemoteTranslationConsentPolicy.recordConsent(
            existingKeys = emptySet(),
            consentKey = "OPENAI|api.openai.com",
        )

        val decision = RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_TEXT_API,
                providerId = "CUSTOM",
                providerDisplayName = "Custom",
                endpointUrl = "https://api.openai.com/v1/chat/completions",
                payloadKind = RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY,
                consentedEndpointKeys = persistedKeys,
            ),
        )

        val required = decision as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("CUSTOM|api.openai.com", required.request.consentKey)
    }

    @Test
    fun `malformed and blank hosts are handled safely and never bypass consent`() {
        val badInputs = listOf(
            "",
            "   ",
            "://missing-scheme.example/v1",
            "https://",
            "https://bad host.example/v1",
            "mailto:user@example.com",
            "http://example.com:notaport/v1",
            "/relative/path/only",
            "http://[::1",
        )
        val poisonedConsentedSet = setOf("", "CUSTOM|", "|", "CUSTOM| ")

        badInputs.forEach { badUrl ->
            assertNull(RemoteTranslationConsentPolicy.extractNormalizedHost(badUrl), "Expected null for '$badUrl'")
            val decision = RemoteTranslationConsentPolicy.evaluate(
                RemoteTranslationConsentInput(
                    backend = RemoteTranslationBackend.REMOTE_TEXT_API,
                    providerId = "CUSTOM",
                    providerDisplayName = "Custom",
                    endpointUrl = badUrl,
                    payloadKind = RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY,
                    consentedEndpointKeys = poisonedConsentedSet,
                ),
            )
            val required = decision as RemoteTranslationConsentDecision.RequiresConsent
            assertEquals("", required.request.normalizedHost)
            assertEquals("", required.request.consentKey)
        }

        assertEquals(
            setOf("OPENAI|api.openai.com"),
            RemoteTranslationConsentPolicy.recordConsent(setOf("OPENAI|api.openai.com"), ""),
        )
        assertEquals(
            setOf("OPENAI|api.openai.com"),
            RemoteTranslationConsentPolicy.recordConsent(setOf("OPENAI|api.openai.com"), "CUSTOM|"),
        )
    }

    @Test
    fun `host extraction normalizes ipv6 userinfo schemeless and trailing dot urls`() {
        assertEquals("[::1]", RemoteTranslationConsentPolicy.extractNormalizedHost("http://[::1]:11434/v1"))
        assertEquals("proxy.example.com", RemoteTranslationConsentPolicy.extractNormalizedHost("https://user:pass@PROXY.example.com:8443/v1"))
        assertEquals("api.openai.com", RemoteTranslationConsentPolicy.extractNormalizedHost("api.openai.com/v1/chat/completions"))
        assertEquals("api.openai.com", RemoteTranslationConsentPolicy.extractNormalizedHost("https://api.openai.com./v1"))
        assertEquals("127.0.0.1", RemoteTranslationConsentPolicy.extractNormalizedHost("http://127.0.0.1:8080/translate"))
    }

    @Test
    fun `android consent adapter respects local only mode preset resolution and novel vs e2e payloads`() {
        val localDecision = evaluateAndroidRemoteTranslationConsent(
            pipelineMode = ReaderTranslationPipelineMode.TWO_STAGE,
            translationMode = ReaderTranslationMode.LOCAL_ONLY,
            apiProviderPreset = "OPENAI",
            apiEndpoint = "https://api.openai.com/v1/chat/completions",
            e2eProviderPreset = "GEMINI",
            e2eEndpoint = "",
            isNovel = false,
            consentedEndpointKeys = emptySet(),
            customProviderLabel = "Custom",
        )
        assertEquals(RemoteTranslationConsentDecision.Allowed, localDecision)

        val mangaPresetDecision = evaluateAndroidRemoteTranslationConsent(
            pipelineMode = ReaderTranslationPipelineMode.TWO_STAGE,
            translationMode = ReaderTranslationMode.API_ONLY,
            apiProviderPreset = "GROQ",
            apiEndpoint = "",
            e2eProviderPreset = "GEMINI",
            e2eEndpoint = "",
            isNovel = false,
            consentedEndpointKeys = emptySet(),
            customProviderLabel = "Custom",
        ) as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("Groq", mangaPresetDecision.request.providerDisplayName)
        assertEquals("api.groq.com", mangaPresetDecision.request.normalizedHost)
        assertEquals(RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY, mangaPresetDecision.request.payloadKind)

        val novelDecision = evaluateAndroidRemoteTranslationConsent(
            pipelineMode = ReaderTranslationPipelineMode.END_TO_END_API,
            translationMode = ReaderTranslationMode.API_ONLY,
            apiProviderPreset = "CUSTOM",
            apiEndpoint = "https://novel-proxy.example/v1/chat/completions",
            e2eProviderPreset = "GEMINI",
            e2eEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            isNovel = true,
            consentedEndpointKeys = emptySet(),
            customProviderLabel = "Custom",
        ) as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("Custom", novelDecision.request.providerDisplayName)
        assertEquals("novel-proxy.example", novelDecision.request.normalizedHost)
        assertEquals(RemoteTranslationPayloadKind.NOVEL_TEXT_AND_GLOSSARY, novelDecision.request.payloadKind)

        val e2eDecision = evaluateAndroidRemoteTranslationConsent(
            pipelineMode = ReaderTranslationPipelineMode.END_TO_END_API,
            translationMode = ReaderTranslationMode.LOCAL_ONLY,
            apiProviderPreset = "CUSTOM",
            apiEndpoint = "",
            e2eProviderPreset = "GEMINI",
            e2eEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            isNovel = false,
            consentedEndpointKeys = emptySet(),
            customProviderLabel = "Custom",
        ) as RemoteTranslationConsentDecision.RequiresConsent
        assertEquals("Google Gemini", e2eDecision.request.providerDisplayName)
        assertEquals("generativelanguage.googleapis.com", e2eDecision.request.normalizedHost)
        assertEquals(RemoteTranslationPayloadKind.FULL_PAGE_IMAGE_AND_GLOSSARY, e2eDecision.request.payloadKind)
        assertTrue(e2eDecision.request.consentKey == "GEMINI|generativelanguage.googleapis.com")
    }

    @Test
    fun `coordinator defense-in-depth skips remote api when unconsented and blocks again on host switch`() = runTest {
        val networkCalls = AtomicInteger(0)
        var endpoint = "https://first-remote.invalid/v1/chat/completions"
        var consented: Set<String> = emptySet()

        val settings = mockk<AppSettings>(relaxed = true) {
            every { readerTranslationMode } returns ReaderTranslationMode.API_ONLY
            every { readerTranslationPipelineMode } returns ReaderTranslationPipelineMode.TWO_STAGE
            every { readerTranslationOnnxModelId } returns ""
            every { readerTranslationApiProviderPreset } returns "CUSTOM"
            every { readerTranslationApiEndpoint } answers { endpoint }
            every { readerTranslationApiModel } returns "test-model"
            every { readerTranslationApiKey } returns "test-key"
            every { readerTranslationApiCustomHeaders } returns ""
            every { remoteTranslationConsentedEndpoints } answers { consented }
        }
        val textCache = mockk<ReaderTranslationTextCache> {
            every { this@mockk[any()] } returns null
            every { this@mockk[any()] = any() } returns Unit
        }
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls.incrementAndGet()
            val body = JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", """[{"id":1,"translation":"你好"}]"""),
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
            openAiTranslationSystemPrompt = "Translate.",
            maxOpenAiBatchSize = 8,
            thinkTagRegex = Regex("(?is)<think>.*?</think>"),
            buildTextCacheKey = { text, src, dst -> "$text|$src|$dst|$endpoint" },
            sanitizeTranslation = { it.trim() },
            isAcceptableTranslation = { _, translated, _, _ -> translated.isNotBlank() },
            log = {},
            oneLine = { value, _ -> value },
        )

        // 1. Unconsented remote endpoint -> 0 network calls.
        val blocked = coordinator.translateBlocksCached(listOf("Hello"), "en", "zh")
        assertEquals("", blocked["Hello"])
        assertEquals(0, networkCalls.get())

        // 2. After consenting -> remote request proceeds.
        consented = setOf("CUSTOM|first-remote.invalid")
        val allowed = coordinator.translateBlocksCached(listOf("Hello"), "en", "zh")
        assertEquals("你好", allowed["Hello"])
        assertEquals(1, networkCalls.get())

        // 3. Switching to a new unconsented host -> blocked again with 0 additional calls.
        endpoint = "https://second-remote.invalid/v1/chat/completions"
        val blockedAfterSwitch = coordinator.translateBlocksCached(listOf("Hello"), "en", "zh")
        assertEquals("", blockedAfterSwitch["Hello"])
        assertEquals(1, networkCalls.get())

        // 4. askBook also refuses to send to an unconsented host.
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                coordinator.askBook(
                    bookTitle = "Book",
                    chapterTitle = "Ch 1",
                    excerpt = "Hello",
                    question = "Explain",
                )
            }
        }
        assertEquals(1, networkCalls.get())
    }

    @Test
    fun `novel translation processor skips background remote send when endpoint is unconsented`() = runTest {
        val networkCalls = AtomicInteger(0)
        val settings = mockk<AppSettings>(relaxed = true) {
            every { readerTranslationMode } returns ReaderTranslationMode.API_ONLY
            every { readerTranslationPipelineMode } returns ReaderTranslationPipelineMode.TWO_STAGE
            every { readerTranslationApiProviderPreset } returns "CUSTOM"
            every { readerTranslationApiEndpoint } returns "https://novel-remote.invalid/v1/chat/completions"
            every { remoteTranslationConsentedEndpoints } returns emptySet()
        }
        val httpClient = OkHttpClient.Builder().addInterceptor { _ ->
            networkCalls.incrementAndGet()
            error("network must not be called when unconsented")
        }.build()
        val processor = NovelTranslationProcessor(
            settings = settings,
            textCache = mockk(relaxed = true),
            okHttpClient = httpClient,
            onnxTranslationEngine = mockk(relaxed = true),
            translationDictionaryRepository = mockk<TranslationDictionaryRepository>(relaxed = true),
        )

        val emissions = processor.translateChapterFlow(
            chapterIndex = 0,
            content = "First paragraph.\n\nSecond paragraph.",
            sourceLang = "en",
            targetLang = "zh",
            displayMode = NovelTranslationDisplayMode.TRANSLATION_ONLY,
        ).toList()

        assertTrue(emissions.isEmpty())
        assertEquals(0, networkCalls.get())
    }
}
