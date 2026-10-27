package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsRanges

/**
 * Android's `VideoDecoderMode`, which libmpv expresses as its `hwdec` pair: Windows has no media3 renderers to switch
 * between, but the user's question is the same one — may a file be decoded on the GPU, and if that fails, what then.
 */
enum class DesktopVideoDecoderMode {
    HARDWARE_ONLY,
    HARDWARE_PREFERRED,
    SOFTWARE_PREFERRED;

    /**
     * mpv options for this mode. `auto-safe` only picks hardware decoders mpv considers safe and falls back to
     * software on its own; `hwdec-fallback=no` turns that fallback off, which is what "hardware only" means (an
     * unsupported file then fails instead of quietly decoding on the CPU).
     */
    val mpvOptions: Map<String, String>
        get() = when (this) {
            HARDWARE_ONLY -> mapOf("hwdec" to "auto-safe", "hwdec-fallback" to "no")
            HARDWARE_PREFERRED -> mapOf("hwdec" to "auto-safe")
            SOFTWARE_PREFERRED -> mapOf("hwdec" to "no")
        }

    /** Android's option label from `video_decoder_modes`. */
    val title: String
        get() = when (this) {
            HARDWARE_ONLY -> AndroidStrings["video_decoder_hardware_only"]
            HARDWARE_PREFERRED -> AndroidStrings["video_decoder_hardware_preferred"]
            SOFTWARE_PREFERRED -> AndroidStrings["video_decoder_software_preferred"]
        }

    /** What the choice does to libmpv; Android's own summary line names the platform decoder it would pick instead. */
    val summary: String
        get() = when (this) {
            HARDWARE_ONLY -> desktopText("video.decoder.hardwareOnly")
            HARDWARE_PREFERRED -> desktopText("video.decoder.hardwarePreferred")
            SOFTWARE_PREFERRED -> desktopText("video.decoder.softwarePreferred")
        }
}

/**
 * Android's playback settings as the Windows player consumes them: the backdrop behind the video, the decoder
 * preference and the two opacities of the control overlay.
 *
 * Both alphas are kept as the percentages Android stores (`video_controls_alpha` / `video_gradient_alpha` are ints
 * 0..100 there), so the same preferences file shape survives and the row shows a whole number.
 */
data class DesktopVideoSettings(
    /** Android's `video_background`, defaulting to the theme background; the five values are the video choices. */
    val background: DesktopReaderBackground = DesktopReaderBackground.DEFAULT,
    val decoderMode: DesktopVideoDecoderMode = DesktopVideoDecoderMode.HARDWARE_PREFERRED,
    /** Android's default is 90%, and its slider stops at 30% so the controls never disappear. */
    val controlsAlpha: Int = 90,
    /** Android's default is 70%; zero means no gradient at all. */
    val gradientAlpha: Int = 70,
) {
    /** The control bar's opacity; clamped the way Android's preference range clamps it. */
    val controlsOpacity: Float get() = controlsAlpha.coerceIn(MIN_CONTROLS_ALPHA, 100) / 100f

    /** The fade above the control bar, 0..1. */
    val gradientOpacity: Float get() = gradientAlpha.coerceIn(0, 100) / 100f

    companion object {
        /** Both ranges live beside the shared page rows, so a host cannot drift from what it renders. */
        val MIN_CONTROLS_ALPHA = PlaybackSettingsRanges.controlsAlpha.first
        val CONTROLS_ALPHA_RANGE = PlaybackSettingsRanges.controlsAlpha
        val GRADIENT_ALPHA_RANGE = PlaybackSettingsRanges.gradientAlpha
    }
}
