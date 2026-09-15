package org.skepsun.kototoro.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StorageAndNetworkCacheLimitsTest {

    @Test
    fun `formatCacheLimitMb formats values below 1024 as MB`() {
        formatCacheLimitMb(32) shouldBe "32 MB"
        formatCacheLimitMb(256) shouldBe "256 MB"
        formatCacheLimitMb(512) shouldBe "512 MB"
    }

    @Test
    fun `formatCacheLimitMb formats exact multiples of 1024 as whole GB`() {
        formatCacheLimitMb(1024) shouldBe "1 GB"
        formatCacheLimitMb(2048) shouldBe "2 GB"
        formatCacheLimitMb(4096) shouldBe "4 GB"
        formatCacheLimitMb(16384) shouldBe "16 GB"
    }

    @Test
    fun `formatCacheLimitMb formats non-exact gigabytes with decimal place`() {
        formatCacheLimitMb(1536) shouldBe "1.5 GB"
        formatCacheLimitMb(7634) shouldBe "7.5 GB"
    }
}
