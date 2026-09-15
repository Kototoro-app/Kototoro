package org.skepsun.kototoro.settings.about

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.GitHubMirrorProbeResult
import org.skepsun.kototoro.core.github.GitHubMirrorProbeState
import org.skepsun.kototoro.core.github.latencyLabel
import org.skepsun.kototoro.core.github.mirrorProbeSummary
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorStrategy

class AppUpdateMirrorOptionsTest {

    @Test
    fun `AppUpdateMirrorOption preserves label compatibility and fields`() {
        val probe = GitHubMirrorProbeResult(latencyMillis = 85L, isAvailable = true)
        val option = AppUpdateMirrorOption(
            id = "gh_proxy_com",
            name = "gh-proxy.com",
            probeResult = probe,
            isFastest = true,
        )

        assertEquals("gh_proxy_com", option.id)
        assertEquals("gh-proxy.com", option.name)
        assertEquals("gh-proxy.com", option.label)
        assertEquals(probe, option.probeResult)
        assertTrue(option.isFastest)
    }

    @Test
    fun `latencyLabel formats available and timeout states correctly`() {
        val context = mockk<Context>()
        every { context.getString(R.string.mirror_probe_timeout) } returns "timeout"

        val available = GitHubMirrorProbeResult(latencyMillis = 150L, isAvailable = true)
        val timeout = GitHubMirrorProbeResult(latencyMillis = null, isAvailable = false)
        val nullResult: GitHubMirrorProbeResult? = null

        assertEquals(" · 150 ms", available.latencyLabel(context))
        assertEquals(" · timeout", timeout.latencyLabel(context))
        assertEquals("", nullResult.latencyLabel(context))
    }

    @Test
    fun `mirrorProbeSummary produces correct strings across states`() {
        val context = mockk<Context>()
        every { context.getString(R.string.mirror_probe_running, 2, 5) } returns "Testing mirrors (2/5)…"
        every { context.getString(R.string.mirror_probe_finished, "gh-proxy.com", 60L, 4, 5) } returns
            "Fastest: gh-proxy.com (60 ms) · 4/5 reachable"
        every { context.getString(R.string.mirror_probe_none_available) } returns "No mirror reachable"
        every { context.getString(R.string.mirror_probe_summary) } returns "Probe summary"

        val entries = listOf(
            GitHubMirrorEntry(id = "native", name = "Direct Native", strategy = GitHubMirrorStrategy.NATIVE),
            GitHubMirrorEntry(id = "gh_proxy_com", name = "gh-proxy.com", strategy = GitHubMirrorStrategy.PREFIX),
        )

        val runningState = GitHubMirrorProbeState.Running(completed = 2, total = 5)
        assertEquals("Testing mirrors (2/5)…", mirrorProbeSummary(context, runningState, entries))

        val finishedState = GitHubMirrorProbeState.Finished(
            available = 4,
            total = 5,
            fastestId = "gh_proxy_com",
            fastestMillis = 60L,
        )
        assertEquals(
            "Fastest: gh-proxy.com (60 ms) · 4/5 reachable",
            mirrorProbeSummary(context, finishedState, entries),
        )

        val noneAvailableState = GitHubMirrorProbeState.Finished(
            available = 0,
            total = 5,
            fastestId = null,
            fastestMillis = null,
        )
        assertEquals("No mirror reachable", mirrorProbeSummary(context, noneAvailableState, entries))

        val idleState = GitHubMirrorProbeState.Idle
        assertEquals("Probe summary", mirrorProbeSummary(context, idleState, entries))
    }
}
