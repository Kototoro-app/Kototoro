package org.skepsun.kototoro.reader.render.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.PagedSlotTransitionInput
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderPageCurlGeometry
import org.skepsun.kototoro.reader.ui.compose.calculatePageCurlGeometry
import org.skepsun.kototoro.reader.ui.compose.resolvePageCurlFromStart
import org.skepsun.kototoro.reader.ui.compose.resolvePageCurlStartFraction
import kotlin.math.abs

/** Shade the revealed page takes so a fold reads as depth rather than a flat swap. */
private const val REVEALED_SHADE_FACTOR = 0.16f

/** A turning page only starts fading this far into its fold. */
private const val FADE_START_PROGRESS = 0.92f

/** Fold progress span over which the turning page fades from opaque to invisible. */
private const val FADE_SPAN = 0.08f

/**
 * Visual page-transition style of the paged scene host.
 *
 * Scene geometry and transition visuals are deliberately separate concerns (ADR 0002 I3):
 * [org.skepsun.kototoro.reader.core.PagedReaderScene] decides where slots are, this layer
 * decides how a slot looks while a transition is in flight.
 */
internal enum class ScenePageTransition {
    /** Pager-like motion: the scroll offset alone moves the slots. */
    SLIDE,

    /** The settled page stays in place while the incoming page slides over it. */
    COVER,

    /** The turning page folds like paper and reveals the next page underneath. */
    CURL,
}

/**
 * Maps the user's persisted animation preference onto a scene transition style.
 *
 * `NONE` shares the [ScenePageTransition.SLIDE] visuals with `DEFAULT`; the two differ only in
 * whether the release is animated at all, which the host decides from `isAnimationEnabled` and
 * the preference itself.
 */
internal fun resolveScenePageTransition(animation: ReaderAnimation): ScenePageTransition = when (animation) {
    ReaderAnimation.NONE, ReaderAnimation.DEFAULT -> ScenePageTransition.SLIDE
    ReaderAnimation.ADVANCED -> ScenePageTransition.COVER
    ReaderAnimation.SIMULATION -> ScenePageTransition.CURL
}

/**
 * Draw-phase transform for one slot, expressed in the same abstract units as the legacy pager
 * transform: [translationFactor] is a fraction of the slot's own size and [foldProgress] drives
 * the curl geometry.
 */
internal data class ScenePageTransform(
    val translationFactor: Float = 0f,
    val alpha: Float = 1f,
    val zIndex: Float = 0f,
    val foldProgress: Float = 0f,
    val isCurlUnfolding: Boolean = false,
    val revealedPageShade: Float = 0f,
)

/** Resolves the per-slot transform of each [ScenePageTransition] style. */
internal object ScenePageTransitionRenderer {

    /** Below this magnitude a cover navigation counts as settled rather than in flight. */
    const val COVER_PROGRESS_EPSILON = 0.001f

    fun transformFor(
        transition: ScenePageTransition,
        input: PagedSlotTransitionInput,
        isVertical: Boolean,
        isReversed: Boolean,
    ): ScenePageTransform = when (transition) {
        // The scene host already moves every slot with the paging offset, so a slide needs no
        // additional per-slot translation: adding one would move the page twice per finger pixel.
        ScenePageTransition.SLIDE -> ScenePageTransform()
        ScenePageTransition.COVER -> resolveCoverTransform(input, isReversed)
        ScenePageTransition.CURL -> resolveCurlTransform(input, isVertical, isReversed)
    }

    /**
     * Fold geometry for a slot whose transform is actually folding, or `null` while the slot is
     * flat. The fold origin derivation is delegated to the legacy helpers so the scene folds from
     * the same reading edge as the legacy hosts.
     *
     * The pure curl math still lives in `reader/ui/compose/ComposeReaderPageAnimation.kt`; moving
     * it down into this render layer is tracked as cleanup in the scene cut-over plan.
     */
    fun resolveCurlGeometry(
        size: Size,
        transform: ScenePageTransform,
        downFraction: Offset,
        horizontalDragFraction: Float,
        isVertical: Boolean,
        isReversed: Boolean,
    ): ComposeReaderPageCurlGeometry? {
        if (transform.foldProgress <= 0f) return null
        val curlFromStart = resolvePageCurlFromStart(
            isVertical = isVertical,
            isReadingReversed = isReversed,
            horizontalDragFraction = horizontalDragFraction,
        )
        return calculatePageCurlGeometry(
            size = size,
            progress = transform.foldProgress,
            touchFraction = resolvePageCurlStartFraction(
                downFraction = downFraction,
                isVertical = isVertical,
                curlFromStart = curlFromStart,
            ),
            isVertical = isVertical,
            isReversed = curlFromStart,
            isCurlUnfolding = transform.isCurlUnfolding,
        )
    }

    /**
     * Paper-fold animation: the page being turned folds and fades out at the very end of its
     * travel, while the page revealed underneath only takes a shade so the reader sees depth
     * instead of a flat swap. Mirrored reading flips which physical side folds.
     */
    private fun resolveCurlTransform(
        input: PagedSlotTransitionInput,
        isVertical: Boolean,
        isReversed: Boolean,
    ): ScenePageTransform {
        val reversed = isReversed && !isVertical
        val isTurningPage = if (reversed) input.pageOffset >= 0f else input.pageOffset <= 0f
        val foldProgress = if (isTurningPage) abs(input.pageOffset).coerceIn(0f, 1f) else 0f
        val revealedPageShade = if (!isTurningPage && abs(input.pageOffset) <= 1f) {
            abs(input.pageOffset) * REVEALED_SHADE_FACTOR
        } else {
            0f
        }
        return ScenePageTransform(
            translationFactor = -input.pageOffset,
            alpha = if (input.pageOffset !in -1f..1f) {
                0f
            } else {
                1f - ((foldProgress - FADE_START_PROGRESS) / FADE_SPAN).coerceIn(0f, 1f)
            },
            zIndex = if (isTurningPage) 1f else 0f,
            foldProgress = foldProgress,
            isCurlUnfolding = isTurningPage && input.isCurlUnfolding,
            revealedPageShade = revealedPageShade,
        )
    }

    /**
     * The page being left stays in place while the incoming page slides in over/under it,
     * mirroring the legacy cover animation. Reading direction only flips the physical sign of the
     * travel; which slot is on top follows from whether navigation moves forward or backward.
     */
    private fun resolveCoverTransform(
        input: PagedSlotTransitionInput,
        isReversed: Boolean,
    ): ScenePageTransform {
        if (abs(input.navigationProgress) < COVER_PROGRESS_EPSILON) {
            return ScenePageTransform()
        }
        val isForwardNavigation = input.navigationProgress > 0f
        val physicalDirection = if (isReversed) -1f else 1f
        return when {
            input.isSettledPage && isForwardNavigation -> ScenePageTransform(zIndex = 1f)
            input.isSettledPage -> ScenePageTransform(
                translationFactor = input.pageOffset * physicalDirection,
            )
            input.isIncomingPage -> ScenePageTransform(
                translationFactor = if (isForwardNavigation) input.pageOffset * physicalDirection else 0f,
                zIndex = if (isForwardNavigation) 0f else 1f,
            )
            // Slots beyond the two participants stay behind the moving pair.
            else -> ScenePageTransform(zIndex = -1f)
        }
    }
}
