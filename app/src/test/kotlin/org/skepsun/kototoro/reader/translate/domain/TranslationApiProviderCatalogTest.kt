package org.skepsun.kototoro.reader.translate.domain

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.translate.data.AdvancedOcrModelPackWorker
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import org.skepsun.kototoro.settings.support.TranslationApiSettingsSupport

class TranslationApiProviderCatalogTest {

	@Test
	fun `provider ids and models dev ids are unique`() {
		val providers = TranslationApiProviderCatalog.providers

		assertEquals(providers.size, providers.map { it.id }.toSet().size)
		assertEquals(providers.size, providers.map { it.modelsDevId }.toSet().size)
		assertTrue(providers.size >= 30)
	}

	@Test
	fun `preset endpoint is resolved without relying on persisted endpoint`() {
		assertEquals(
			"https://api.groq.com/openai/v1/chat/completions",
			TranslationApiProviderCatalog.resolveChatEndpoint("GROQ", ""),
		)
		assertEquals(
			"https://example.com/v1/chat/completions",
			TranslationApiProviderCatalog.resolveChatEndpoint("CUSTOM", " https://example.com/v1/chat/completions "),
		)
	}

	@Test
	fun `preset providers use explicit secure chat and model endpoints`() {
		TranslationApiProviderCatalog.providers.forEach { provider ->
			assertTrue(provider.chatEndpoint.startsWith("https://"), provider.id)
			assertTrue(provider.chatEndpoint.endsWith("/chat/completions"), provider.id)
			assertTrue(provider.modelsEndpoint.startsWith("https://"), provider.id)
			assertEquals(
				provider.modelsEndpoint,
				TranslationApiSettingsSupport.buildModelsUrl(provider.chatEndpoint, provider.id),
			)
			assertTrue(provider.apiKeyUrl.startsWith("https://"), provider.id)
			assertTrue(provider.documentationUrl.startsWith("https://"), provider.id)
		}
	}

	@Test
	fun `preset authentication sends only bearer header`() {
		val request = Request.Builder().url("https://example.com").also { builder ->
			TranslationApiProviderCatalog.applyAuthentication(builder, "OPENAI", "secret")
		}.build()

		assertEquals("Bearer secret", request.header("Authorization"))
		assertEquals(null, request.header("X-API-Key"))
	}

	@Test
	fun `advanced OCR pack contains every automatic recognizer model`() {
		val requiredIds = AdvancedOcrModelPackWorker.REQUIRED_MODEL_IDS

		assertEquals(6, requiredIds.size)
		assertTrue("manga_default_det_20241225_onnx" in requiredIds)
		listOf("ko", "th", "en", "ja").forEach { language ->
			assertTrue(resolveAutomaticReaderRecognizerModelId(language) in requiredIds)
		}
		requiredIds.forEach { modelId ->
			assertNotNull(OnnxOfficialModelCatalog.findById(modelId), modelId)
		}
	}

	@Test
	fun `catalog exposes manga 48px CTC recognizer from verified Hugging Face files`() {
		val model = requireNotNull(OnnxOfficialModelCatalog.findById(MANGA_48PX_CTC_RECOGNIZER_MODEL_ID))

		assertEquals("OCR_RECOGNIZER", model.category.name)
		assertEquals(
			listOf("ocr-48px-ctc.onnx", "alphabet-all-v5.txt"),
			model.files.map { it.fileName },
		)
		assertEquals(
			"da8d4b2c3ea236ad0c741677da29b77360230aee3380675bc95d4c15dc452497",
			model.files.first().sha256,
		)
		assertTrue(model.files.all { it.downloadUrl.startsWith("https://huggingface.co/Skepsun/") })
	}
}
