package org.skepsun.kototoro.tracker.work

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.tracker.domain.SourceHealth
import org.skepsun.kototoro.tracker.domain.SourceTurn
import java.io.IOException

class TrackSourceHealthTest {

    @Test
    fun `a challenge anywhere in the causes skips the source`() {
        val error = IOException("wrapped", CloudFlareBlockedException("https://example.org", null))

        SourceHealth().afterCheck(error, now = 0L).turn(now = 0L) shouldBe SourceTurn.Skip
    }

    @Test
    fun `a Mihon 429 pauses the source`() {
        SourceHealth().afterCheck(HttpException(429), now = 0L).turn(now = 0L) shouldBe SourceTurn.WaitUntil(5_000L)
    }

    @Test
    fun `a parser 429 honours its retry delay`() {
        val turn = SourceHealth().afterCheck(TooManyRequestExceptions("https://example.org", 30_000L), now = 0L)
            .turn(now = 0L)

        // The exception turns Retry-After into a deadline when it is built, so a few millis may have passed.
        (turn as SourceTurn.WaitUntil).time shouldBeInRange 29_000L..30_000L
    }

    @Test
    fun `other failures and answers leave the source going`() {
        SourceHealth().afterCheck(HttpException(500), now = 0L).turn(now = 0L) shouldBe SourceTurn.Go
        SourceHealth().afterCheck(null, now = 0L).turn(now = 0L) shouldBe SourceTurn.Go
    }
}
