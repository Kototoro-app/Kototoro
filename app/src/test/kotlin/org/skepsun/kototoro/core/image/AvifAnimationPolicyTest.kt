package org.skepsun.kototoro.core.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvifAnimationPolicyTest {

    @Test
    fun `default preserves resolution and recommendations fit two common playback buffers`() {
        assertFalse(AvifAnimationPolicy(16 * MIB).allowDownsampling)
        assertEquals(1080 to 1920, resolveAvifAnimatedDecodeSize(
            1080, 1920, 2, 4, profile(2048, 192, true).resolvePolicy(0, false).memoryBudgetBytes, false,
        ))
        assertEquals(4096 to 4096, resolveAvifAnimatedDecodeSize(
            4096, 4096, 2, 4, profile(8192, 512).resolvePolicy(0, false).memoryBudgetBytes, false,
        ))
    }

    @Test
    fun `defaults adapt to physical memory heap limit and low RAM flag`() {
        assertEquals(16, profile(2048, 192, true).recommendedMemoryLimitMb)
        assertEquals(32, profile(4096, 256).recommendedMemoryLimitMb)
        assertEquals(128, profile(8192, 512).recommendedMemoryLimitMb)
        assertEquals(256, profile(12288, 1024).recommendedMemoryLimitMb)
        assertEquals(32, profile(8192, 512, true).recommendedMemoryLimitMb)
        assertEquals(32, profile(8192, 128).recommendedMemoryLimitMb)
    }

    @Test
    fun `high RAM devices use larger defaults even with a 512 MiB app heap`() {
        assertEquals(256, profile(16384, 512).recommendedMemoryLimitMb)
        // System-reported RAM excludes reserved memory on a nominal 12/16 GB device.
        assertEquals(256, profile(11264, 512).recommendedMemoryLimitMb)
        assertEquals(128, profile(8192, 512).recommendedMemoryLimitMb)
        assertEquals(64, profile(16384, 128).recommendedMemoryLimitMb)
        assertEquals(32, profile(16384, 512, true).recommendedMemoryLimitMb)
    }

    @Test
    fun `automatic follows the device while manual selection and reduction remain independent`() {
        val device = profile(8192, 512)
        assertEquals(128 * MIB, device.resolvePolicy(0, true).memoryBudgetBytes)
        val manual = device.resolvePolicy(192, false)
        assertEquals(192 * MIB, manual.memoryBudgetBytes)
        assertFalse(manual.allowDownsampling)
        assertTrue(device.resolvePolicy(0, true).allowDownsampling)
    }

    @Test
    fun `a restored manual budget is bounded by the current device`() {
        val device = profile(4096, 256)
        assertEquals(128 * MIB, device.resolvePolicy(512, false).memoryBudgetBytes)
        assertEquals(16 * MIB, device.resolvePolicy(1, true).memoryBudgetBytes)
        assertEquals(32 * MIB, device.resolvePolicy(-1, true).memoryBudgetBytes)
        assertEquals(512, profile(32768, 4096).maxMemoryLimitMb)
    }

    private fun profile(ramMb: Int, heapMb: Int, lowRam: Boolean = false) =
        AvifAnimationDeviceProfile(ramMb * MIB, heapMb * MIB, lowRam)

    private companion object {
        const val MIB = 1024L * 1024
    }
}
