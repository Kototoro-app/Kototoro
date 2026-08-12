package org.skepsun.kototoro.core.exceptions

import okhttp3.Headers
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

class CloudFlareProtectedException(
	override val url: String,
	source: ContentSource?,
	@Transient val headers: Headers,
	@Transient val method: String = "GET",
	@Transient val body: String? = null,
	@Transient val contentType: String? = headers["Content-Type"],
) : CloudFlareException("Protected by CloudFlare", CloudFlareHelper.PROTECTION_CAPTCHA) {

	override val source: ContentSource = source ?: UnknownContentSource
}
