package org.skepsun.kototoro.reader.image

/**
 * Strategy for physical bitmap allocation.
 *
 * Conforms to ADR 0002 Constraint 2:
 * Decoupled from [PixelUsage]. A renderer backend or platform capability resolver
 * maps [PixelUsage] to an appropriate [DecodeAllocatorPolicy].
 */
enum class DecodeAllocatorPolicy {
    /**
     * Android Hardware Bitmap (stored in graphic memory / DMABuf, zero CPU read access).
     * High UI rendering performance with minimal Java/Native heap usage.
     */
    HARDWARE,

    /**
     * Standard software bitmap in Native heap (ARGB_8888 or RGB_565).
     * Accessible by CPU for image transformations, OCR, or software Canvas fallback.
     */
    SOFTWARE,

    /**
     * Shared memory or texture handle representation for external/native engines (e.g. WebGPU).
     */
    SHARED_MEMORY;

    companion object {
        fun resolve(
            usage: PixelUsage,
            allowHardware: Boolean = true,
        ): DecodeAllocatorPolicy {
            return when (usage) {
                PixelUsage.DISPLAY_ONLY -> if (allowHardware) HARDWARE else SOFTWARE
                PixelUsage.CPU_READ_REQUIRED -> SOFTWARE
                PixelUsage.REGION_TILE -> SOFTWARE // Region tiles typically decode to software bitmaps before texture upload
            }
        }
    }
}
