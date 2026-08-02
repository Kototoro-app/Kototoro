package org.skepsun.kototoro.scrobbling.discord.data

import okio.ByteString.Companion.encodeUtf8

internal fun generateDiscordCodeChallenge(verifier: String): String =
	verifier.encodeUtf8().sha256().base64Url().trimEnd('=')
