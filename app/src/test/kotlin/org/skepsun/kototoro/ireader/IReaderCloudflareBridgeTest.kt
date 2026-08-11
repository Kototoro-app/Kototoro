package org.skepsun.kototoro.ireader

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IReaderCloudflareBridgeTest {

    @Test
    fun `normalizes domain to challenge url`() {
        "example.org".toIReaderChallengeUrl() shouldBe "https://example.org/"
        ".example.org".toIReaderChallengeUrl() shouldBe "https://example.org/"
        "https://example.org/challenge".toIReaderChallengeUrl() shouldBe
            "https://example.org/challenge"
    }

    @Test
    fun `parses webview cookie header without truncating encoded values`() {
        "cf_clearance=abc==; __cf_bm=def; malformed".toIReaderCookieMap() shouldContainExactly mapOf(
            "cf_clearance" to "abc==",
            "__cf_bm" to "def",
        )
    }

    @Test
    fun `uses the same cache key for url and domain`() {
        "https://Example.org/challenge".toIReaderCacheKey() shouldBe "example.org"
        "example.org".toIReaderCacheKey() shouldBe "example.org"
    }
}
