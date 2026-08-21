package org.skepsun.kototoro.core.exceptions

import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

class CloudFlareBlockedException(
    override val url: String,
    source: ContentSource?,
) : CloudFlareException("Blocked by CloudFlare", CloudFlareHelper.PROTECTION_BLOCKED) {

    override val source: ContentSource = source ?: UnknownContentSource
}
