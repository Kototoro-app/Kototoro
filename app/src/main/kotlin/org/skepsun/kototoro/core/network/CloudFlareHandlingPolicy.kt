package org.skepsun.kototoro.core.network

import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException

data class CloudFlareHandlingPolicy(
    val allowBrowserTransport: Boolean = true,
    val allowBlockedResponse: Boolean = false,
    val allowCaptchaResponse: Boolean = false,
    val onCaptchaDetected: ((CloudFlareProtectedException) -> Unit)? = null,
)
