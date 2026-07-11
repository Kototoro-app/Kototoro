package org.skepsun.kototoro.reader.translate.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class PaddleRecognitionCompatibilityTest {

	@Test
	fun `recognition width matches manga translator ceil and maximum width behavior`() {
		assertEquals(97, computePaddleRecognitionWidth(sourceWidth = 201, sourceHeight = 100, maxWidth = 320))
		assertEquals(1, computePaddleRecognitionWidth(sourceWidth = 1, sourceHeight = 100, maxWidth = 320))
		assertEquals(320, computePaddleRecognitionWidth(sourceWidth = 1000, sourceHeight = 100, maxWidth = 320))
	}

	@Test
	fun `vertical rotation matches manga translator threshold`() {
		assertFalse(shouldRotatePaddleRecognitionCrop(width = 100, height = 149))
		assertTrue(shouldRotatePaddleRecognitionCrop(width = 100, height = 150))
		assertFalse(shouldRotateManga48pxRecognitionCrop(width = 100, height = 100))
		assertTrue(shouldRotateManga48pxRecognitionCrop(width = 100, height = 101))
	}

	@Test
	fun `ctc decoding removes blanks and repeats and averages every timestep confidence`() {
		val prediction = arrayOf(
			floatArrayOf(0.9f, 0.1f, 0.0f),
			floatArrayOf(0.1f, 0.8f, 0.1f),
			floatArrayOf(0.1f, 0.7f, 0.2f),
			floatArrayOf(0.6f, 0.2f, 0.2f),
			floatArrayOf(0.1f, 0.2f, 0.7f),
		)

		val result = decodePaddleCtc(
			values = prediction.flatMap(FloatArray::asIterable).toFloatArray(),
			offset = 0,
			timeSteps = prediction.size,
			classes = prediction.first().size,
			dictionary = listOf("A", "B"),
		)

		assertEquals("AB", result.text)
		assertEquals(0.74f, result.confidence, 0.0001f)
	}

	@Test
	fun `manga 48px CTC uses direct dictionary indexes and emitted token log probabilities`() {
		val logits = floatArrayOf(
			4f, 1f, 0f,
			0f, 4f, 1f,
			0f, 3f, 1f,
			4f, 0f, 1f,
			0f, 1f, 4f,
		)

		val result = decodeManga48pxCtc(
			values = logits,
			offset = 0,
			timeSteps = 5,
			classes = 3,
			dictionary = listOf("<PAD>", "A", "B"),
		)

		assertEquals("AB", result.text)
		val expectedConfidence = exp((logSoftmaxOfWinner(4f, 0f, 1f) + logSoftmaxOfWinner(4f, 0f, 1f)) / 2f)
		assertEquals(expectedConfidence, result.confidence, 0.0001f)
	}

	private fun logSoftmaxOfWinner(winner: Float, other1: Float, other2: Float): Float {
		return winner - kotlin.math.ln(exp(winner) + exp(other1) + exp(other2))
	}
}
