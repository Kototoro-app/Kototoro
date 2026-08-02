package org.skepsun.kototoro.scrobbling.discord.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscordOAuthPkceTest {

	@Test
	fun `generates RFC 7636 S256 code challenge`() {
		val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

		assertEquals(
			"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
			generateDiscordCodeChallenge(verifier),
		)
	}
}
