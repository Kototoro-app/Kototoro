package org.skepsun.kototoro.reader.translate.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog

class DefaultDbNetTextDetectorTest {

	@Test
	fun `catalog points to verified model artifact`() {
		val model = requireNotNull(OnnxOfficialModelCatalog.findById(DefaultDbNetTextDetector.MODEL_ID))
		assertEquals(OnnxModelCategory.OCR_DETECTOR, model.category)
		assertEquals(1, model.files.size)
		assertEquals(DefaultDbNetTextDetector.MODEL_FILE_NAME, model.files.single().fileName)
		assertEquals(DefaultDbNetTextDetector.MODEL_SHA256, model.files.single().sha256)
		assertEquals(
			"https://huggingface.co/Skepsun/manga-translator-ui-onnx/resolve/main/detect-20241225.onnx",
			model.files.single().downloadUrl,
		)
	}

	@Test
	fun `db logits use sigmoid before thresholding`() {
		assertEquals(0.5f, DefaultDbNetTextDetector.sigmoid(0f), 0.00001f)
		assertTrue(DefaultDbNetTextDetector.sigmoid(1f) > 0.5f)
		assertTrue(DefaultDbNetTextDetector.sigmoid(-1f) < 0.5f)
	}

	@Test
	fun `input padding follows 256 pixel multiple`() {
		assertEquals(0, DefaultDbNetTextDetector.paddingFor(1024, 256))
		assertEquals(255, DefaultDbNetTextDetector.paddingFor(1025, 256))
		assertEquals(1, DefaultDbNetTextDetector.paddingFor(1279, 256))
	}

	@Test
	fun `configured detection size keeps the 1536 default and caps oversized input`() {
		assertEquals(512, DefaultDbNetTextDetector.resolveDetectionSize(256))
		assertEquals(1536, DefaultDbNetTextDetector.resolveDetectionSize(1536))
		assertEquals(2048, DefaultDbNetTextDetector.resolveDetectionSize(4096))
	}

	@Test
	fun `adaptive detection keeps normal pages fast and raises resolution for webtoon strips`() {
		assertEquals(1024, DefaultDbNetTextDetector.resolveAdaptiveDetectionSize(1200, 1800, 1536))
		assertEquals(1536, DefaultDbNetTextDetector.resolveAdaptiveDetectionSize(720, 3000, 1536))
		assertEquals(2048, DefaultDbNetTextDetector.resolveAdaptiveDetectionSize(720, 4000, 4096))
		assertEquals(768, DefaultDbNetTextDetector.resolveAdaptiveDetectionSize(1200, 1800, 768))
	}

	@Test
	fun `long image rearrange uses reference thresholds`() {
		assertTrue(DefaultDbNetTextDetector.requiresLongImageRearrange(1200, 6000, 1536))
		assertFalse(DefaultDbNetTextDetector.requiresLongImageRearrange(1200, 3800, 1536))
		assertFalse(DefaultDbNetTextDetector.requiresLongImageRearrange(2000, 6000, 1536))
	}
}
