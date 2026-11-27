package org.skepsun.kototoro.tracker.work

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class TrackerWebhookUrlTest {

    @Test
    fun `a plain http endpoint is rejected`() {
        TrackerWebhookUrl.normalize("http://discord.com/api/webhooks/1/abc").shouldBeNull()
        TrackerWebhookUrl.isValid("http://example.org/hook") shouldBe false
    }

    @Test
    fun `an empty value is rejected`() {
        TrackerWebhookUrl.normalize(null).shouldBeNull()
        TrackerWebhookUrl.normalize("").shouldBeNull()
        TrackerWebhookUrl.normalize("   ").shouldBeNull()
    }

    @Test
    fun `a malformed value is rejected`() {
        TrackerWebhookUrl.normalize("not a url").shouldBeNull()
        TrackerWebhookUrl.normalize("https://").shouldBeNull()
        TrackerWebhookUrl.normalize("discord.com/api/webhooks/1").shouldBeNull()
        TrackerWebhookUrl.normalize("ftp://example.org/hook").shouldBeNull()
    }

    @Test
    fun `a valid https endpoint is accepted and normalized`() {
        TrackerWebhookUrl.normalize("https://discord.com/api/webhooks/123/abc-token") shouldBe
            "https://discord.com/api/webhooks/123/abc-token"
        TrackerWebhookUrl.isValid("https://example.org/hook") shouldBe true
    }

    @Test
    fun `surrounding whitespace and an upper case scheme are tolerated`() {
        val url = TrackerWebhookUrl.normalize("  HTTPS://Example.org/hook  ")

        url shouldBe "https://example.org/hook"
        url!!.shouldStartWith("https://")
    }

    @Test
    fun `a self hosted endpoint with a port and query is accepted`() {
        TrackerWebhookUrl.normalize("https://hooks.example.org:8443/tracker?key=1") shouldBe
            "https://hooks.example.org:8443/tracker?key=1"
    }
}
