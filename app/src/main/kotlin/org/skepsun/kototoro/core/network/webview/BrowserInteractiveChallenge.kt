package org.skepsun.kototoro.core.network.webview

import java.util.UUID

internal enum class BrowserInteractiveChallengeState {
	PENDING,
	ATTACHED,
	RESOLVED,
	CANCELLED,
	FAILED,
}

internal data class BrowserInteractiveChallenge(
	val sessionId: String,
	val challengeId: String = UUID.randomUUID().toString(),
	val origin: String,
	val requestUrl: String,
	val method: String,
	val displayUrl: String,
	val state: BrowserInteractiveChallengeState = BrowserInteractiveChallengeState.PENDING,
) {
	fun transitionTo(next: BrowserInteractiveChallengeState): BrowserInteractiveChallenge {
		if (state == next) return this
		check(!isTerminal) { "Interactive challenge is already terminal: $state" }
		check(next != BrowserInteractiveChallengeState.PENDING) {
			"Interactive challenge cannot return to PENDING"
		}
		return copy(state = next)
	}

	val isTerminal: Boolean
		get() = state == BrowserInteractiveChallengeState.RESOLVED ||
			state == BrowserInteractiveChallengeState.CANCELLED ||
			state == BrowserInteractiveChallengeState.FAILED
}
