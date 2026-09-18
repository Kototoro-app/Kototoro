package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Semantic contract of the paged scene transition seam.
 *
 * The legacy pager feeds its animation transform three abstract quantities per page:
 * a signed [pageOffset], an ADVANCED-only `navigationProgress`, and the settled/incoming
 * roles. These tests pin the equivalent derivation from scene state, and they assert the
 * mirrored LTR/RTL behaviour instead of restating the arithmetic.
 */
class PagedTransitionResolverTest {

    private fun snapshot(
        currentSlot: Int,
        settledSlot: Int = currentSlot,
        targetSlot: Int = currentSlot,
        offsetFraction: Float = 0f,
    ) = PagedMotionSnapshot(
        currentSlot = currentSlot,
        settledSlot = settledSlot,
        targetSlot = targetSlot,
        offsetFraction = offsetFraction,
        isScrollInProgress = offsetFraction != 0f,
    )

    @Test
    fun `ltr forward drag turns the settled slot and reveals the next one`() {
        val state = snapshot(currentSlot = 2, offsetFraction = 0.4f)

        val settled = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 2,
            direction = SceneReadingDirection.LEFT_TO_RIGHT,
        )
        val incoming = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 3,
            direction = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        // The settled page is the one leaving the viewport, so its offset turns negative.
        assertEquals(-0.4f, settled.pageOffset, TOLERANCE)
        assertEquals(0.6f, incoming.pageOffset, TOLERANCE)
        assertTrue(settled.isSettledPage)
        assertFalse(settled.isIncomingPage)
        assertFalse(incoming.isSettledPage)
        assertTrue(incoming.isIncomingPage)
        assertEquals(0.4f, settled.navigationProgress, TOLERANCE)
        assertTrue(settled.isTransitionActive)
    }

    @Test
    fun `rtl forward drag mirrors the offsets while keeping the same reading roles`() {
        val state = snapshot(currentSlot = 2, offsetFraction = 0.4f)

        val settled = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 2,
            direction = SceneReadingDirection.RIGHT_TO_LEFT,
        )
        val incoming = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 3,
            direction = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        // Reading forward is physically leftward, so the signed offsets flip sign while the
        // settled/incoming roles and the progress direction stay exactly as in LTR.
        assertEquals(0.4f, settled.pageOffset, TOLERANCE)
        assertEquals(-0.6f, incoming.pageOffset, TOLERANCE)
        assertTrue(settled.isSettledPage)
        assertTrue(incoming.isIncomingPage)
        assertEquals(0.4f, settled.navigationProgress, TOLERANCE)
    }

    @Test
    fun `vertical paged drag uses slot order without mirroring`() {
        val state = snapshot(currentSlot = 1, offsetFraction = 0.25f)

        val settled = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 1,
            direction = SceneReadingDirection.TOP_TO_BOTTOM,
        )
        val incoming = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 2,
            direction = SceneReadingDirection.TOP_TO_BOTTOM,
        )

        assertEquals(-0.25f, settled.pageOffset, TOLERANCE)
        assertEquals(0.75f, incoming.pageOffset, TOLERANCE)
    }

    @Test
    fun `settled scene offsets every slot by its distance from the gesture origin`() {
        val state = snapshot(currentSlot = 2)

        val previous = PagedTransitionResolver.resolve(state, slotIndex = 1, direction = SceneReadingDirection.LEFT_TO_RIGHT)
        val current = PagedTransitionResolver.resolve(state, slotIndex = 2, direction = SceneReadingDirection.LEFT_TO_RIGHT)
        val next = PagedTransitionResolver.resolve(state, slotIndex = 3, direction = SceneReadingDirection.LEFT_TO_RIGHT)

        assertEquals(-1f, previous.pageOffset, TOLERANCE)
        assertEquals(0f, current.pageOffset, TOLERANCE)
        assertEquals(1f, next.pageOffset, TOLERANCE)
        assertEquals(0f, current.navigationProgress, TOLERANCE)
        assertFalse(current.isIncomingPage)
        assertFalse(current.isTransitionActive)
    }

    @Test
    fun `backward drag marks the previous slot as the incoming page`() {
        val state = snapshot(currentSlot = 2, offsetFraction = -0.3f)

        val previous = PagedTransitionResolver.resolve(state, slotIndex = 1, direction = SceneReadingDirection.LEFT_TO_RIGHT)
        val settled = PagedTransitionResolver.resolve(state, slotIndex = 2, direction = SceneReadingDirection.LEFT_TO_RIGHT)

        assertEquals(-0.7f, previous.pageOffset, TOLERANCE)
        assertEquals(0.3f, settled.pageOffset, TOLERANCE)
        assertTrue(previous.isIncomingPage)
        assertTrue(settled.isSettledPage)
    }

    @Test
    fun `curl unfolding flows through to every slot of the active transition`() {
        val state = snapshot(currentSlot = 2, offsetFraction = 0.4f)

        val settled = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 2,
            direction = SceneReadingDirection.LEFT_TO_RIGHT,
            isCurlUnfolding = true,
        )
        val idle = PagedTransitionResolver.resolve(
            snapshot = state,
            slotIndex = 2,
            direction = SceneReadingDirection.LEFT_TO_RIGHT,
            isCurlUnfolding = false,
        )

        assertTrue(settled.isCurlUnfolding)
        assertFalse(idle.isCurlUnfolding)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
