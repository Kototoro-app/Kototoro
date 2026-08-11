package org.skepsun.kototoro.core.exceptions.resolve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CloudFlareResolverStateTest {

    private var now = 1_000L
    private val state = CloudFlareResolverState { now }

    @Test
    fun `fresh foreground challenge runs automatic then manual`() {
        assertEquals(
            CloudFlareResolvePlan.AUTO_THEN_MANUAL,
            state.plan("example.org", tryAutomatic = true, allowManual = true),
        )
    }

    @Test
    fun `fresh background challenge only runs automatic resolver`() {
        assertEquals(
            CloudFlareResolvePlan.AUTO_ONLY,
            state.plan("example.org", tryAutomatic = true, allowManual = false),
        )
    }

    @Test
    fun `challenge repeated after automatic success escalates to manual`() {
        state.recordSuccess("example.org", CloudFlareResolveStage.AUTOMATIC)

        assertEquals(
            CloudFlareResolvePlan.MANUAL_ONLY,
            state.plan("example.org", tryAutomatic = true, allowManual = true),
        )
    }

    @Test
    fun `challenge repeated after manual success enters cooldown`() {
        state.recordSuccess("example.org", CloudFlareResolveStage.MANUAL)

        assertEquals(
            CloudFlareResolvePlan.FAIL_FAST,
            state.plan("example.org", tryAutomatic = true, allowManual = true),
        )
        now += 60_000L
        assertEquals(
            CloudFlareResolvePlan.FAIL_FAST,
            state.plan("example.org", tryAutomatic = true, allowManual = true),
        )
        now += 61_000L
        assertEquals(
            CloudFlareResolvePlan.AUTO_THEN_MANUAL,
            state.plan("example.org", tryAutomatic = true, allowManual = true),
        )
    }

    @Test
    fun `resolver state is isolated by host`() {
        state.recordSuccess("blocked.example", CloudFlareResolveStage.MANUAL)
        state.plan("blocked.example", tryAutomatic = true, allowManual = true)

        assertEquals(
            CloudFlareResolvePlan.AUTO_THEN_MANUAL,
            state.plan("other.example", tryAutomatic = true, allowManual = true),
        )
    }
}
