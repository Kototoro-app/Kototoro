package org.skepsun.kototoro.core.network.webview

import java.util.UUID

internal enum class BrowserExecutionState {
	FETCHING,
	CHALLENGE_DETECTED,
	RESOLVING_AUTOMATIC,
	WAITING_FOR_USER,
	VALIDATING,
	RETRYING_REQUEST,
	COMPLETED,
	CANCELLED,
	FAILED,
}

internal class BrowserExecution(
	val requestUrl: String,
	val method: String,
	val executionId: String = UUID.randomUUID().toString(),
) {
	var state: BrowserExecutionState = BrowserExecutionState.FETCHING
		private set

	var challengeContext: BrowserChallengeContext? = null
		private set

	val isTerminal: Boolean
		get() = state == BrowserExecutionState.COMPLETED ||
			state == BrowserExecutionState.CANCELLED ||
			state == BrowserExecutionState.FAILED

	fun challengeDetected(context: BrowserChallengeContext) {
		require(context.method == method) {
			"Challenge method does not belong to this execution"
		}
		transitionTo(BrowserExecutionState.CHALLENGE_DETECTED)
		challengeContext = context
	}

	fun transitionTo(next: BrowserExecutionState) {
		require(next in state.allowedTransitions()) {
			"Illegal BrowserExecution transition: $state -> $next"
		}
		state = next
	}

	private fun BrowserExecutionState.allowedTransitions(): Set<BrowserExecutionState> = when (this) {
		BrowserExecutionState.FETCHING -> setOf(
			BrowserExecutionState.CHALLENGE_DETECTED,
			BrowserExecutionState.COMPLETED,
			BrowserExecutionState.CANCELLED,
			BrowserExecutionState.FAILED,
		)

		BrowserExecutionState.CHALLENGE_DETECTED -> setOf(
			BrowserExecutionState.RESOLVING_AUTOMATIC,
			BrowserExecutionState.FAILED,
			BrowserExecutionState.CANCELLED,
		)

		BrowserExecutionState.RESOLVING_AUTOMATIC -> setOf(
			BrowserExecutionState.WAITING_FOR_USER,
			BrowserExecutionState.VALIDATING,
			BrowserExecutionState.FAILED,
			BrowserExecutionState.CANCELLED,
		)

		BrowserExecutionState.WAITING_FOR_USER -> setOf(
			BrowserExecutionState.VALIDATING,
			BrowserExecutionState.FAILED,
			BrowserExecutionState.CANCELLED,
		)

		BrowserExecutionState.VALIDATING -> setOf(
			BrowserExecutionState.RETRYING_REQUEST,
			BrowserExecutionState.FAILED,
			BrowserExecutionState.CANCELLED,
		)

		BrowserExecutionState.RETRYING_REQUEST -> setOf(
			BrowserExecutionState.CHALLENGE_DETECTED,
			BrowserExecutionState.COMPLETED,
			BrowserExecutionState.FAILED,
			BrowserExecutionState.CANCELLED,
		)

		BrowserExecutionState.COMPLETED,
		BrowserExecutionState.CANCELLED,
		BrowserExecutionState.FAILED,
		-> emptySet()
	}
}
