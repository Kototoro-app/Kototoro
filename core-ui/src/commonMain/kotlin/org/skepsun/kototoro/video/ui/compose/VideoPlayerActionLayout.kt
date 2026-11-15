package org.skepsun.kototoro.video.ui.compose

import androidx.compose.ui.semantics.SemanticsPropertyKey

/**
 * How much of the player chrome fits. Effective width is the row's width divided by the font scale, so a large font
 * shrinks the tier the same way a narrow window does — the controls never have to be discovered by dragging sideways.
 */
enum class VideoPlayerControlsTier {
    EXPANDED,
    COMPACT,
    NARROW,
}

/** Every control the bottom bar can draw. */
enum class VideoPlayerActionSlot {
    PREVIOUS_CHAPTER,
    NEXT_CHAPTER,
    CHAPTERS,
    PLAYBACK_SPEED,
    FULLSCREEN,
    QUALITY,
    NOTES,
    INTRO_MARKER,
    OUTRO_MARKER,
    SCREEN_LOCK,
}

/**
 * Which controls are drawn in the row and which have to move behind the overflow button.
 *
 * The invariant this type exists for: [inline] is small enough to fit the screen it was computed for, so the row never
 * needs a horizontal scroll for the things a viewer reaches for while watching — previous/next episode, the episode
 * list, the speed and fullscreen. Everything else is [overflow] and reaches the same action through the overflow.
 */
data class VideoPlayerActionLayout(
    val tier: VideoPlayerControlsTier,
    val inline: List<VideoPlayerActionSlot>,
    val overflow: List<VideoPlayerActionSlot>,
) {
    fun showsInline(slot: VideoPlayerActionSlot): Boolean = slot in inline

    fun showsInOverflow(slot: VideoPlayerActionSlot): Boolean = slot in overflow
}

/**
 * The row's tier, readable from the UI tree so a test can assert the layout decision instead of measuring pixels.
 */
val VideoPlayerActionsTierKey: SemanticsPropertyKey<VideoPlayerControlsTier> =
    SemanticsPropertyKey("VideoPlayerActionsTier")

/**
 * The bottom bar's fit for [availableWidth] at [fontScale].
 *
 * Widths are measured against the *effective* width (availableWidth / fontScale) because a 2.0 font scale doubles the
 * space every label needs: a 900dp row with 2.0 font scale fits what a 450dp row fits at 1.0.
 *
 * Only slots the host [supports] are returned, so a host without intro/outro markers never gets a marker row.
 */
fun videoPlayerActionLayout(
    availableWidth: Float,
    fontScale: Float,
    supports: Set<VideoPlayerActionSlot>,
): VideoPlayerActionLayout {
    if (availableWidth <= 0f) return VideoPlayerActionLayout(VideoPlayerControlsTier.NARROW, emptyList(), emptyList())
    val effective = availableWidth / fontScale.coerceAtLeast(0.5f)
    val all = VideoPlayerActionSlot.entries.filter { it in supports }
    val core = all.filter { it in CoreSlots }
    val extras = all.filter { it !in CoreSlots }
    return when {
        effective >= EXPANDED_MIN_WIDTH -> VideoPlayerActionLayout(
            tier = VideoPlayerControlsTier.EXPANDED,
            inline = core + extras,
            overflow = emptyList(),
        )
        effective >= COMPACT_MIN_WIDTH -> VideoPlayerActionLayout(
            tier = VideoPlayerControlsTier.COMPACT,
            inline = core,
            overflow = extras,
        )
        else -> {
            // Narrow: the speed label is the first thing to give up its place, because the same value is shown in the
            // overflow and the speed is changed far less often than an episode is skipped.
            val narrowInline = core.filter { it != VideoPlayerActionSlot.PLAYBACK_SPEED }
            val narrowOverflow = core.filter { it == VideoPlayerActionSlot.PLAYBACK_SPEED } + extras
            VideoPlayerActionLayout(
                tier = VideoPlayerControlsTier.NARROW,
                inline = narrowInline,
                overflow = narrowOverflow,
            )
        }
    }
}

/**
 * The controls a viewer needs while watching, in the order they are drawn. The screen lock belongs here even though it
 * is not part of the "watching" set in the narrowest sense: it is the only way to stop accidental touches on a phone, so
 * pushing it behind the overflow would add a step to the one thing a viewer reaches for blind.
 */
private val CoreSlots = listOf(
    VideoPlayerActionSlot.PREVIOUS_CHAPTER,
    VideoPlayerActionSlot.NEXT_CHAPTER,
    VideoPlayerActionSlot.CHAPTERS,
    VideoPlayerActionSlot.PLAYBACK_SPEED,
    VideoPlayerActionSlot.FULLSCREEN,
    VideoPlayerActionSlot.SCREEN_LOCK,
)

/** 600dp at font scale 1.0 is where Android's own player shows every button. */
private const val EXPANDED_MIN_WIDTH = 600f

/** Below this the row keeps only the core controls and hides the labels. */
private const val COMPACT_MIN_WIDTH = 380f
