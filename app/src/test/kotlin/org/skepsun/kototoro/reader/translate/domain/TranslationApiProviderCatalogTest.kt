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
		assertFalse(providers.any { it.id == "MINIMAX" || it.id == "BAIDU" })
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

		assertEquals(5, requiredIds.size)
		assertTrue("manga_default_det_20241225_onnx" in requiredIds)
		listOf("ko", "th", "en", "ja").forEach { language ->
			assertTrue(resolveAutomaticPaddleRecognizerModelId(language) in requiredIds)
		}
		requiredIds.forEach { modelId ->
			assertNotNull(OnnxOfficialModelCatalog.findById(modelId), modelId)
		}
	}
}
