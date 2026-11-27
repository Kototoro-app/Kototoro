package org.skepsun.kototoro.tracker.work

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerWebhookCooldownTest {

    @Test
    fun `a webhook that never sent anything can send`() {
        TrackerWebhookCooldown.isActive(lastSentAt = 0L, now = 1_000_000L) shouldBe false
    }

    @Test
    fun `a second request within sixty seconds is held back`() {
        val first = 1_700_000_000_000L

        TrackerWebhookCooldown.isActive(lastSentAt = first, now = first + 1_000L) shouldBe true
        TrackerWebhookCooldown.isActive(lastSentAt = first, now = first + 59_999L) shouldBe true
    }

    @Test
    fun `a request after the interval is allowed again`() {
        val first = 1_700_000_000_000L

        TrackerWebhookCooldown.isActive(lastSentAt = first, now = first + 60_000L) shouldBe false
        TrackerWebhookCooldown.isActive(lastSentAt = first, now = first + 3_600_000L) shouldBe false
    }

    @Test
    fun `the interval is one minute`() {
        TrackerWebhookCooldown.MIN_INTERVAL_MS shouldBe 60_000L
    }
}
