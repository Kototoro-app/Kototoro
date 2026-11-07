package org.skepsun.kototoro.tracker.work

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions
import org.skepsun.kototoro.tracker.domain.SourceAnswer
import java.io.IOException

class TrackSourceHealthTest {

    @Test
    fun `a challenge anywhere in the causes is a challenge`() {
        val error = IOException("wrapped", CloudFlareBlockedException("https://example.org", null))

        error.toSourceAnswer() shouldBe SourceAnswer.Challenged
    }

    @Test
    fun `a Mihon 429 is a rate limit without a delay`() {
        HttpException(429).toSourceAnswer() shouldBe SourceAnswer.RateLimited(null)
    }

    @Test
    fun `a parser 429 carries its retry delay`() {
        val answer = TooManyRequestExceptions("https://example.org", 30_000L).toSourceAnswer()

        // The exception turns Retry-After into a deadline when it is built, so a few millis may have passed.
        (answer as SourceAnswer.RateLimited).retryAfterMs!! shouldBeInRange 29_000L..30_000L
    }

    @Test
    fun `other failures and answers are plain answers`() {
        HttpException(500).toSourceAnswer() shouldBe SourceAnswer.Answered
        (null as Throwable?).toSourceAnswer() shouldBe SourceAnswer.Answered
    }
}
