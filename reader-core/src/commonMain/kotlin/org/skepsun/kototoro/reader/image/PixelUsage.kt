package org.skepsun.kototoro.reader.image

/**
 * Declares the intended consumer usage of decoded pixel data.
 *
 * Conforms to ADR 0002 Constraint 2:
 * Separates "why pixels are needed" from backend-specific bitmap memory allocation.
 */
enum class PixelUsage {
    /**
     * Display-only rendering in GPU/hardware pipeline (default for reader canvases).
     * Eligible for HARDWARE bitmaps on Android API 26+ when backed by native Canvas.
     */
    DISPLAY_ONLY,

    /**
     * CPU pixel reading required (e.g., OCR text extraction, color analysis, smart crop).
     * Must be decoded into software-readable memory (ARGB_8888 or RGB_565).
     */
    CPU_READ_REQUIRED,

    /**
     * Region / Tiled decoding for ultra-long or zoomed strips.
     * Slices image into tiles to prevent OOM and avoid GPU texture limit overflows.
     */
    REGION_TILE,
}
