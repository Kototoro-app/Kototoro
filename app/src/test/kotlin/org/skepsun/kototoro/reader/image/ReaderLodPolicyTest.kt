package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderLodPolicyTest {

    private val policy = ReaderLodPolicy(qualityOverscan = 1.0f, hysteresisFraction = 0.15f)

    @Test
    fun `resolves correct sample size at 1x fit width`() {
        // High-res webtoon strip: source 4000px wide, display 1000px wide -> 4x downsample
        val lod4000 = policy.resolveLod(
            sourceContentWidthPx = 4000,
            baseDisplayWidthPx = 1000,
            cameraScale = 1.0f,
        )
        assertEquals(4, lod4000.sampleSize)
        assertEquals(0.25f, lod4000.targetPixelScale)

        // Native 1000px on 1000px display -> 1x
        val lod1000 = policy.resolveLod(
            sourceContentWidthPx = 1000,
            baseDisplayWidthPx = 1000,
            cameraScale = 1.0f,
        )
        assertEquals(1, lod1000.sampleSize)
        assertEquals(1.0f, lod1000.targetPixelScale)

        // Smaller source 800px on 1000px display -> 1x (never upscale sampleSize)
        val lod800 = policy.resolveLod(
            sourceContentWidthPx = 800,
            baseDisplayWidthPx = 1000,
            cameraScale = 1.0f,
        )
        assertEquals(1, lod800.sampleSize)
    }

    @Test
    fun `zoom scale progressively increases LOD resolution`() {
        val sourceWidth = 4000
        val displayWidth = 1000

        // 1x zoom -> target 1000 -> sampleSize 4
        val lod1x = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 1.0f)
        assertEquals(4, lod1x.sampleSize)

        // 2x zoom -> target 2000 -> sampleSize 2
        val lod2x = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 2.0f)
        assertEquals(2, lod2x.sampleSize)

        // 4x zoom -> target 4000 -> sampleSize 1
        val lod4x = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 4.0f)
        assertEquals(1, lod4x.sampleSize)

        // 8x zoom -> clamped to max source resolution -> sampleSize 1
        val lod8x = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 8.0f)
        assertEquals(1, lod8x.sampleSize)
    }

    @Test
    fun `hysteresis prevents thrashing near threshold boundaries`() {
        val sourceWidth = 4000
        val displayWidth = 1000

        // Baseline at 2.0x is sampleSize 2 (ratio = 4000 / 2000 = 2.0)
        val currentLod = LodSpec(level = 3, sampleSize = 2, targetPixelScale = 0.5f)

        // Slight pinch-in (1.95x): without hysteresis, ratio is 4000/1950 = 2.05 -> might downgrade to 4
        // With hysteresis (15%), ratio 2.05 is below downgrade threshold (2 * 2 * 1.15 = 4.6), so stays at 2!
        val nudgedDown = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 1.95f, currentLod = currentLod)
        assertEquals(2, nudgedDown.sampleSize)

        // Slight pinch-out (2.05x): ratio is 4000/2050 = 1.95
        // Upgrade to 1 requires ratio <= 2 * (1 - 0.15) = 1.70. Since 1.95 > 1.70, stays at 2!
        val nudgedUp = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 2.05f, currentLod = currentLod)
        assertEquals(2, nudgedUp.sampleSize)

        // Substantial pinch-out (2.5x): ratio is 4000/2500 = 1.60 <= 1.70 -> cleanly upgrades to 1!
        val zoomedIn = policy.resolveLod(sourceWidth, displayWidth, cameraScale = 2.5f, currentLod = currentLod)
        assertEquals(1, zoomedIn.sampleSize)
    }
}
