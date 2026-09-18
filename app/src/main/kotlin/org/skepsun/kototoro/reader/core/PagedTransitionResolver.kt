package org.skepsun.kototoro.reader.core

import kotlin.math.abs

/**
 * How a single slot participates in the page transition that is currently in flight.
 *
 * The field names intentionally follow the legacy pager animation contract (see
 * `ComposeReaderPageAnimation`): a scene renderer can therefore resolve the very same visual
 * styles — slide, cover and curl — from scene state, without inheriting the legacy pager's
 * own state organisation or its PagerState coupling.
 *
 * Pure semantics: no Android or Compose types, per ADR 0002 invariant I1.
 */
data class PagedSlotTransitionInput(
    val slotIndex: Int,
    /**
     * Signed offset in the legacy convention: `0` when the slot sits at the viewport origin,
     * negative while it leaves the viewport forward, positive while the next slot is revealed.
     */
    val pageOffset: Float,
    /** Cover-mode navigation progress in `[-1, 1]`, relative to the settled slot. */
    val navigationProgress: Float,
    /** Whether this slot is the transition anchor that stays in place (cover mode). */
    val isSettledPage: Boolean,
    /** Whether this slot is the page entering the viewport (cover mode). */
    val isIncomingPage: Boolean,
    /** Whether the curl is unfolding from the far edge for the active transition. */
    val isCurlUnfolding: Boolean,
    /** Whether any transition is in flight; false means every slot is at rest. */
    val isTransitionActive: Boolean,
)

/**
 * Derives per-slot transition inputs from a [PagedMotionSnapshot].
 *
 * Slots are indexed in reading order, so a positive [PagedMotionSnapshot.offsetFraction] always
 * means "progressing forward" regardless of the physical direction. Horizontal right-to-left
 * reading mirrors the signed [PagedSlotTransitionInput.pageOffset] (reading forward is
 * physically leftward there) while keeping the settled/incoming roles and the progress
 * direction identical to left-to-right.
 */
object PagedTransitionResolver {

    /** Below this magnitude a progress value counts as settled rather than a transition. */
    const val PROGRESS_EPSILON = 0.001f

    fun resolve(
        snapshot: PagedMotionSnapshot,
        slotIndex: Int,
        direction: SceneReadingDirection,
        isCurlUnfolding: Boolean = false,
    ): PagedSlotTransitionInput {
        val distance = (slotIndex - snapshot.currentSlot).toFloat()
        val progress = snapshot.offsetFraction
        val pageOffset = if (direction == SceneReadingDirection.RIGHT_TO_LEFT) {
            progress - distance
        } else {
            distance - progress
        }
        val navigationProgress = ((snapshot.currentSlot - snapshot.settledSlot) + progress)
            .coerceIn(-1f, 1f)
        val incomingDelta = when {
            navigationProgress > PROGRESS_EPSILON -> 1
            navigationProgress < -PROGRESS_EPSILON -> -1
            else -> 0
        }
        return PagedSlotTransitionInput(
            slotIndex = slotIndex,
            pageOffset = pageOffset,
            navigationProgress = navigationProgress,
            isSettledPage = slotIndex == snapshot.settledSlot,
            isIncomingPage = incomingDelta != 0 && slotIndex == snapshot.settledSlot + incomingDelta,
            isCurlUnfolding = isCurlUnfolding,
            isTransitionActive = snapshot.isScrollInProgress || abs(progress) > PROGRESS_EPSILON,
        )
    }
}
