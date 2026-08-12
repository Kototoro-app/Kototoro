package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserExecutionTest {
	@Test
	fun `request without challenge completes directly`() {
		val execution = BrowserExecution("https://example.com/api", "GET")

		execution.transitionTo(BrowserExecutionState.COMPLETED)

		assertTrue(execution.isTerminal)
	}

	@Test
	fun `challenge execution reaches completed only after original request retry`() {
		val execution = BrowserExecution("https://kagane.to/api/v2/search/series", "POST", "execution")
		val context = BrowserChallengeContext.create(execution.requestUrl, execution.method, "challenge")!!

		execution.challengeDetected(context)
		execution.transitionTo(BrowserExecutionState.RESOLVING_AUTOMATIC)
		execution.transitionTo(BrowserExecutionState.WAITING_FOR_USER)
		execution.transitionTo(BrowserExecutionState.VALIDATING)
		execution.transitionTo(BrowserExecutionState.RETRYING_REQUEST)
		execution.transitionTo(BrowserExecutionState.COMPLETED)

		assertTrue(execution.isTerminal)
		assertEquals(context, execution.challengeContext)
	}

	@Test
	fun `resolution evidence cannot complete execution before retry`() {
		val execution = BrowserExecution("https://example.com/api", "GET")
		val context = BrowserChallengeContext.create(execution.requestUrl, execution.method, "challenge")!!
		execution.challengeDetected(context)
		execution.transitionTo(BrowserExecutionState.RESOLVING_AUTOMATIC)
		execution.transitionTo(BrowserExecutionState.VALIDATING)

		assertThrows(IllegalArgumentException::class.java) {
			execution.transitionTo(BrowserExecutionState.COMPLETED)
		}
	}

	@Test
	fun `terminal execution rejects later transitions`() {
		val execution = BrowserExecution("https://example.com/api", "GET")
		execution.transitionTo(BrowserExecutionState.CANCELLED)

		assertThrows(IllegalArgumentException::class.java) {
			execution.transitionTo(BrowserExecutionState.FETCHING)
		}
	}

	@Test
	fun `retry may report another challenge before execution fails`() {
		val execution = BrowserExecution("https://example.com/api", "POST")
		val context = BrowserChallengeContext.create(execution.requestUrl, execution.method, "challenge")!!
		execution.challengeDetected(context)
		execution.transitionTo(BrowserExecutionState.RESOLVING_AUTOMATIC)
		execution.transitionTo(BrowserExecutionState.VALIDATING)
		execution.transitionTo(BrowserExecutionState.RETRYING_REQUEST)

		execution.challengeDetected(context)
		execution.transitionTo(BrowserExecutionState.FAILED)

		assertTrue(execution.isTerminal)
	}

	@Test
	fun `challenge method must belong to execution`() {
		val execution = BrowserExecution("https://example.com/api", "POST")
		val context = BrowserChallengeContext.create("https://example.com/api", "GET", "challenge")!!

		assertThrows(IllegalArgumentException::class.java) {
			execution.challengeDetected(context)
		}
	}

	@Test
	fun `authorized redirect url may become effective challenge url`() {
		val execution = BrowserExecution("https://example.com/api", "POST")
		val context = BrowserChallengeContext.create("https://www.example.com/api", "POST", "challenge")!!

		execution.challengeDetected(context)

		assertEquals(context, execution.challengeContext)
	}
}
