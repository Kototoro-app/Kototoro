package org.skepsun.kototoro.reader.render.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.PagedSlotTransitionInput
import org.skepsun.kototoro.reader.ui.compose.calculatePageCurlGeometry
import org.skepsun.kototoro.reader.ui.compose.resolveComposeReaderPageTransform
import org.skepsun.kototoro.reader.ui.compose.resolvePageCurlFromStart
import org.skepsun.kototoro.reader.ui.compose.resolvePageCurlStartFraction

/**
 * Parity between the scene transition styles and the legacy pager animation.
 *
 * The legacy `resolveComposeReaderPageTransform` is the independent oracle here: it shipped
 * with its own test suite and is still used by the legacy hosts, so equal output for equal
 * abstract input proves the scene host does not silently downgrade a user's animation choice.
 */
class ScenePageTransitionRendererTest {

    private fun input(
        pageOffset: Float,
        navigationProgress: Float = 0f,
        isSettledPage: Boolean = false,
        isIncomingPage: Boolean = false,
        isCurlUnfolding: Boolean = false,
    ) = PagedSlotTransitionInput(
        slotIndex = 0,
        pageOffset = pageOffset,
        navigationProgress = navigationProgress,
        isSettledPage = isSettledPage,
        isIncomingPage = isIncomingPage,
        isCurlUnfolding = isCurlUnfolding,
        isTransitionActive = true,
    )

    @Test
    fun `reader animations map onto the three scene transition styles`() {
        assertEquals(ScenePageTransition.SLIDE, resolveScenePageTransition(ReaderAnimation.DEFAULT))
        assertEquals(ScenePageTransition.SLIDE, resolveScenePageTransition(ReaderAnimation.NONE))
        assertEquals(ScenePageTransition.COVER, resolveScenePageTransition(ReaderAnimation.ADVANCED))
        assertEquals(ScenePageTransition.CURL, resolveScenePageTransition(ReaderAnimation.SIMULATION))
    }

    @Test
    fun `slide matches the legacy default transform for every slot of a transition`() {
        val offsets = listOf(-1f, -0.6f, -0.4f, 0f, 0.4f, 0.6f, 1f)

        for (offset in offsets) {
            val scene = ScenePageTransitionRenderer.transformFor(
                transition = ScenePageTransition.SLIDE,
                input = input(pageOffset = offset),
                isVertical = false,
                isReversed = false,
            )
            val legacy = resolveComposeReaderPageTransform(
                animation = ReaderAnimation.DEFAULT,
                pageOffset = offset,
                isVertical = false,
                isReversed = false,
            )

            assertEquals(legacy.translationFactor, scene.translationFactor, TOLERANCE, "translation at $offset")
            assertEquals(legacy.alpha, scene.alpha, TOLERANCE, "alpha at $offset")
            assertEquals(legacy.zIndex, scene.zIndex, TOLERANCE, "zIndex at $offset")
            assertEquals(legacy.foldProgress, scene.foldProgress, TOLERANCE, "fold at $offset")
            assertEquals(legacy.revealedPageShade, scene.revealedPageShade, TOLERANCE, "shade at $offset")
        }
    }

    @Test
    fun `slide adds no per-slot translation because the scroll offset owns the motion`() {
        // The scene host already moves every slot with the paging offset, so a slide style must
        // not shift slots a second time; otherwise a drag would travel twice as far as the finger.
        val moving = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.SLIDE,
            input = input(pageOffset = -0.4f),
            isVertical = false,
            isReversed = false,
        )

        assertEquals(0f, moving.translationFactor, TOLERANCE)
        assertEquals(1f, moving.alpha, TOLERANCE)
        assertEquals(0f, moving.foldProgress, TOLERANCE)
        assertEquals(0f, moving.revealedPageShade, TOLERANCE)
    }

    @Test
    fun `cover matches the legacy advanced transform across settled and incoming slots`() {
        val progresses = listOf(-1f, -0.5f, -0.1f, 0f, 0.1f, 0.5f, 1f)
        val offsets = listOf(-1f, -0.4f, 0f, 0.4f, 1f)

        for (progress in progresses) {
            for (isReversed in listOf(false, true)) {
                for (isSettled in listOf(false, true)) {
                    for (isIncoming in listOf(false, true)) {
                        for (offset in offsets) {
                            val scene = ScenePageTransitionRenderer.transformFor(
                                transition = ScenePageTransition.COVER,
                                input = input(
                                    pageOffset = offset,
                                    navigationProgress = progress,
                                    isSettledPage = isSettled,
                                    isIncomingPage = isIncoming,
                                ),
                                isVertical = false,
                                isReversed = isReversed,
                            )
                            val legacy = resolveComposeReaderPageTransform(
                                animation = ReaderAnimation.ADVANCED,
                                pageOffset = offset,
                                isVertical = false,
                                isReversed = isReversed,
                                navigationProgress = progress,
                                isSettledPage = isSettled,
                                isIncomingPage = isIncoming,
                            )

                            val label = "progress=$progress reversed=$isReversed settled=$isSettled " +
                                "incoming=$isIncoming offset=$offset"
                            assertEquals(legacy.translationFactor, scene.translationFactor, TOLERANCE, label)
                            assertEquals(legacy.zIndex, scene.zIndex, TOLERANCE, label)
                            assertEquals(legacy.alpha, scene.alpha, TOLERANCE, label)
                            assertEquals(legacy.foldProgress, scene.foldProgress, TOLERANCE, label)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `cover pins the settled page above the incoming page while navigating forward`() {
        val settled = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.COVER,
            input = input(pageOffset = -0.4f, navigationProgress = 0.4f, isSettledPage = true),
            isVertical = false,
            isReversed = false,
        )
        val incoming = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.COVER,
            input = input(pageOffset = 0.6f, navigationProgress = 0.4f, isIncomingPage = true),
            isVertical = false,
            isReversed = false,
        )

        // The page being left stays exactly where it is and draws on top; the next page slides in
        // from the reading edge underneath it.
        assertEquals(0f, settled.translationFactor, TOLERANCE)
        assertEquals(1f, settled.zIndex, TOLERANCE)
        assertEquals(0.6f, incoming.translationFactor, TOLERANCE)
        assertEquals(0f, incoming.zIndex, TOLERANCE)
    }

    @Test
    fun `cover lifts the incoming page above the settled page when navigating backward`() {
        val settled = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.COVER,
            input = input(pageOffset = 0.4f, navigationProgress = -0.4f, isSettledPage = true),
            isVertical = false,
            isReversed = false,
        )
        val incoming = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.COVER,
            input = input(pageOffset = -0.6f, navigationProgress = -0.4f, isIncomingPage = true),
            isVertical = false,
            isReversed = false,
        )

        assertEquals(1f, incoming.zIndex, TOLERANCE)
        assertEquals(0f, settled.zIndex, TOLERANCE)
        assertEquals(0.4f, settled.translationFactor, TOLERANCE)
        assertEquals(0f, incoming.translationFactor, TOLERANCE)
    }

    @Test
    fun `cover with no navigation progress leaves every slot untouched`() {
        val transform = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.COVER,
            input = input(pageOffset = 0.8f, navigationProgress = 0f, isSettledPage = true),
            isVertical = false,
            isReversed = false,
        )

        assertEquals(0f, transform.translationFactor, TOLERANCE)
        assertEquals(1f, transform.alpha, TOLERANCE)
        assertEquals(0f, transform.zIndex, TOLERANCE)
    }

    @Test
    fun `curl matches the legacy simulation transform across turning and revealed slots`() {
        val offsets = listOf(-1.2f, -1f, -0.6f, -0.4f, 0f, 0.4f, 0.6f, 1f, 1.2f)

        for (offset in offsets) {
            for (isVertical in listOf(false, true)) {
                for (isReversed in listOf(false, true)) {
                    for (unfolding in listOf(false, true)) {
                        val scene = ScenePageTransitionRenderer.transformFor(
                            transition = ScenePageTransition.CURL,
                            input = input(pageOffset = offset, isCurlUnfolding = unfolding),
                            isVertical = isVertical,
                            isReversed = isReversed,
                        )
                        val legacy = resolveComposeReaderPageTransform(
                            animation = ReaderAnimation.SIMULATION,
                            pageOffset = offset,
                            isVertical = isVertical,
                            isReversed = isReversed,
                            isCurlUnfolding = unfolding,
                        )

                        val label = "offset=$offset vertical=$isVertical reversed=$isReversed unfolding=$unfolding"
                        assertEquals(legacy.translationFactor, scene.translationFactor, TOLERANCE, label)
                        assertEquals(legacy.alpha, scene.alpha, TOLERANCE, label)
                        assertEquals(legacy.zIndex, scene.zIndex, TOLERANCE, label)
                        assertEquals(legacy.foldProgress, scene.foldProgress, TOLERANCE, label)
                        assertEquals(legacy.revealedPageShade, scene.revealedPageShade, TOLERANCE, label)
                        assertEquals(legacy.isCurlUnfolding, scene.isCurlUnfolding, label)
                    }
                }
            }
        }
    }

    @Test
    fun `curl folds the page being turned while the revealed page only takes a shade`() {
        // Without mirroring, the page being turned is the one with a negative offset.
        val turning = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.CURL,
            input = input(pageOffset = -0.4f),
            isVertical = false,
            isReversed = false,
        )
        val revealed = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.CURL,
            input = input(pageOffset = 0.6f),
            isVertical = false,
            isReversed = false,
        )

        assertEquals(0.4f, turning.foldProgress, TOLERANCE)
        assertEquals(1f, turning.zIndex, TOLERANCE)
        assertEquals(1f, turning.alpha, TOLERANCE)
        assertEquals(0f, revealed.foldProgress, TOLERANCE)
        assertEquals(0f, revealed.zIndex, TOLERANCE)
        assertTrue(revealed.revealedPageShade > 0f)
    }

    @Test
    fun `rtl curl folds the page that reads forward rather than the physically leading one`() {
        val turned = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.CURL,
            input = input(pageOffset = 0.4f),
            isVertical = false,
            isReversed = true,
        )
        val revealed = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.CURL,
            input = input(pageOffset = -0.6f),
            isVertical = false,
            isReversed = true,
        )

        assertEquals(0.4f, turned.foldProgress, TOLERANCE)
        assertEquals(0f, revealed.foldProgress, TOLERANCE)
        assertTrue(revealed.revealedPageShade > 0f)
    }

    @Test
    fun `curl fades out a page once it is more than one slot away`() {
        val far = ScenePageTransitionRenderer.transformFor(
            transition = ScenePageTransition.CURL,
            input = input(pageOffset = -1.2f),
            isVertical = false,
            isReversed = false,
        )

        assertEquals(0f, far.alpha, TOLERANCE)
    }

    @Test
    fun `curl geometry stays unavailable until the page actually folds`() {
        assertNull(
            ScenePageTransitionRenderer.resolveCurlGeometry(
                size = Size(1000f, 1600f),
                transform = ScenePageTransform(),
                downFraction = Offset(0.5f, 0.5f),
                horizontalDragFraction = 0.1f,
                isVertical = false,
                isReversed = false,
            ),
        )
    }

    @Test
    fun `cover displacement is zero at rest`() {
        // No travel, no finger on the page: the cover must not nudge any slot.
        for (extent in listOf(0f, 1000f)) {
            assertEquals(0f, ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = 0f, primaryExtentPx = extent), TOLERANCE)
        }
    }

    @Test
    fun `cover pins the settled page during the hold phase`() {
        // Within COVER_HOLD_FRACTION of travel the displacement cancels the scroll motion (the
        // settled page's base is -travel * extent), so the page being left stays glued at the
        // viewport (legacy cover "stays in place" trait) instead of sliding with the finger.
        val extent = 1000f
        for (travel in listOf(0.05f, 0.2f, 0.34f)) {
            val displacement = ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = travel, primaryExtentPx = extent)
            // screen = base(-travel * extent) + displacement == 0  =>  displacement == travel * extent
            assertEquals(travel * extent, displacement, TOLERANCE, "pin at travel=$travel")
        }
    }

    @Test
    fun `cover displacement converges back onto the scroll position at full travel`() {
        // Once the travel is complete the displacement must vanish so the cover joins the plain
        // scroll layout without a jump, in both reading directions.
        val extent = 1000f
        for (travel in listOf(1f, 1.5f, -1f, -2f)) {
            val displacement = ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = travel, primaryExtentPx = extent)
            assertEquals(0f, displacement, TOLERANCE, "converged at travel=$travel")
        }
    }

    @Test
    fun `cover peels the settled page monotonically after the hold phase`() {
        // After the hold the page starts moving in the reading direction of the travel, and the
        // peel grows monotonically until it rejoins the scroll position at full travel.
        val extent = 1000f
        val travels = listOf(0.4f, 0.55f, 0.7f, 0.85f, 0.95f, 1f)
        val screens = travels.map { travel ->
            val displacement = ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = travel, primaryExtentPx = extent)
            // Settled slot base is -travel * extent in the forward direction (LTR).
            displacement - travel * extent
        }
        assertTrue(
            screens.zipWithNext().all { (a, b) -> b < a },
            "peel should move strictly monotonically, got $screens",
        )
        // The first travel past the hold has barely started peeling.
        assertTrue(screens.first() > -0.02f * extent, "peel should begin gently, got ${screens.first()}")
        // The very end lands exactly on the scroll position (page fully off the viewport left).
        assertEquals(-extent, screens.last(), TOLERANCE)
    }

    @Test
    fun `cover displacement is anti-symmetric across reading directions`() {
        val extent = 1000f
        for (travel in listOf(0.3f, 0.5f, 0.7f, 0.9f)) {
            val forward = ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = travel, primaryExtentPx = extent)
            val backward = ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = -travel, primaryExtentPx = extent)
            assertEquals(forward, -backward, TOLERANCE, "travel=$travel")
        }
    }

    @Test
    fun `cover displacement clamps overscroll and ignores empty extents`() {
        val extent = 1000f
        // Overscroll past one full page behaves exactly like the fully peeled case: no shift.
        assertEquals(0f, ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = 3f, primaryExtentPx = extent), TOLERANCE)
        // A degenerate (zero) extent cannot pin anything.
        assertEquals(0f, ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = 0.5f, primaryExtentPx = 0f), TOLERANCE)
        assertEquals(0f, ScenePageTransitionRenderer.resolveCoverDisplacement(travelFraction = -0.5f, primaryExtentPx = -1f), TOLERANCE)
    }

    @Test
    fun `curl geometry feeds the legacy fold derivation the same inputs as the legacy modifier`() {
        val size = Size(1000f, 1600f)
        val transform = ScenePageTransform(foldProgress = 0.35f, isCurlUnfolding = true)
        val downFraction = Offset(0.75f, 0.85f)
        val horizontalDragFraction = -0.2f

        for (isVertical in listOf(false, true)) {
            for (isReversed in listOf(false, true)) {
                val resolved = ScenePageTransitionRenderer.resolveCurlGeometry(
                    size = size,
                    transform = transform,
                    downFraction = downFraction,
                    horizontalDragFraction = horizontalDragFraction,
                    isVertical = isVertical,
                    isReversed = isReversed,
                )
                assertNotNull(resolved)
                val actual = requireNotNull(resolved)
                // The legacy modifier derives its fold origin from these two helpers; the scene must
                // hand the geometry exactly the same pair, or the fold would start from the other edge.
                val curlFromStart = resolvePageCurlFromStart(
                    isVertical = isVertical,
                    isReadingReversed = isReversed,
                    horizontalDragFraction = horizontalDragFraction,
                )
                val expected = calculatePageCurlGeometry(
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

                assertEquals(expected.angle, actual.angle, TOLERANCE, "angle vertical=$isVertical reversed=$isReversed")
                assertEquals(expected.topCurlOffset, actual.topCurlOffset, "top vertical=$isVertical reversed=$isReversed")
                assertEquals(expected.bottomCurlOffset, actual.bottomCurlOffset, "bottom vertical=$isVertical reversed=$isReversed")
                assertEquals(expected.curlLineVector, actual.curlLineVector, "line vertical=$isVertical reversed=$isReversed")
            }
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
