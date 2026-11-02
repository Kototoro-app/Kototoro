package com.lagradost.cloudstream3.syncproviders.providers

/**
 * The request headers of Cloudstream's OpenSubtitles client, which some plugins import as plain request headers for
 * their own sites (3isk sends them to every page it loads). The user agent is the official one, so those sites see
 * what they see from Cloudstream; the official API key is left out, as it is Cloudstream's and only means something
 * to OpenSubtitles.
 */
class OpenSubtitlesApi private constructor() {
	companion object {
		const val HOST = "https://api.opensubtitles.com/api/v1"
		const val userAgent = "Cloudstream3 v0.2"
		val headers: Map<String, String> = mapOf("user-agent" to userAgent)
	}
}
