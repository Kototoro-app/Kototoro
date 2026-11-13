package org.skepsun.kototoro.video.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class VideoZoomControllerTest : StringSpec({

    val immediateAnimatorFactory: (Float, Float, (Float) -> Unit, () -> Unit) -> Unit = { _, _, onUpdate, onEnd ->
        onUpdate(1f)
        onEnd()
    }

    "initial state is not zoomed with scale 1.0 and zero offset" {
        var lastTransform: Triple<Float, Float, Float>? = null
        val controller = VideoZoomController(
            onTransformChanged = { s, x, y -> lastTransform = Triple(s, x, y) },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.scale shouldBe 1.0f
        controller.translationX shouldBe 0.0f
        controller.translationY shouldBe 0.0f
        controller.isZoomed.shouldBeFalse()
        controller.isPinching.shouldBeFalse()
    }

    "onScale multiplies scale within limits" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.onScaleBegin(focusX = 500f, focusY = 500f)
        controller.isPinching.shouldBeTrue()

        controller.onScale(
            scaleFactor = 2.0f,
            focusX = 500f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
        )

        controller.scale shouldBe 2.0f
        controller.isZoomed.shouldBeTrue()

        controller.onScaleEnd(viewportWidth = 1000, viewportHeight = 1000)
        controller.isPinching.shouldBeFalse()
        controller.scale shouldBe 2.0f
    }

    "onScale clamps to MAX_SCALE" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.onScaleBegin(500f, 500f)
        controller.onScale(
            scaleFactor = 10.0f,
            focusX = 500f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
        )

        controller.scale shouldBe VideoZoomController.MAX_SCALE
    }

    "displayedScale applies damping below 1.0" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        // Raw scale 0.8 -> 1.0 - (1.0 - 0.8) * 0.5 = 0.9
        val displayed = controller.displayedScale(0.8f)
        abs(displayed - 0.9f) shouldBeLessThan 0.001f
    }

    "clampTranslation restricts translation when zoomed" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.onScaleBegin(500f, 500f)
        controller.onScale(
            scaleFactor = 2.0f,
            focusX = 500f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
            contentWidth = 1000,
            contentHeight = 1000,
        )

        // overflowX = 1000 * 2.0 - 1000 = 1000. maxX = 500
        controller.panBy(1000f, 1000f, viewportWidth = 1000, viewportHeight = 1000)
        controller.translationX shouldBe 500f
        controller.translationY shouldBe 500f

        controller.panBy(-2000f, -2000f, viewportWidth = 1000, viewportHeight = 1000)
        controller.translationX shouldBe -500f
        controller.translationY shouldBe -500f
    }

    "clampTranslation allows zero horizontal translation when content is smaller than viewport" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.onScaleBegin(500f, 500f)
        // Viewport is 1000 wide, content is 600 wide (e.g. pillarbox).
        // At scale 1.2: effective content width is 600 * 1.2 = 720 <= 1000 -> overflowX is 0.
        controller.onScale(
            scaleFactor = 1.2f,
            focusX = 500f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
            contentWidth = 600,
            contentHeight = 1000,
        )

        controller.panBy(
            dx = 100f,
            dy = 0f,
            viewportWidth = 1000,
            viewportHeight = 1000,
            contentWidth = 600,
            contentHeight = 1000,
        )
        controller.translationX shouldBe 0f
    }

    "panBy is no-op when not zoomed" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.panBy(50f, 50f, viewportWidth = 1000, viewportHeight = 1000)
        controller.translationX shouldBe 0f
        controller.translationY shouldBe 0f
    }

    "focal point tracking shifts translation towards pinch focus" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        // Pinch centered on the right half (focusX = 800 in a 1000-wide view, center is 500)
        controller.onScaleBegin(focusX = 800f, focusY = 500f)
        controller.onScale(
            scaleFactor = 2.0f,
            focusX = 800f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
        )

        // Zooming in on the right should shift content left (negative translationX)
        controller.translationX shouldBeLessThan 0f
    }

    "onScaleEnd under magnet scale triggers reset" {
        var endTriggered = false
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.onScaleBegin(500f, 500f)
        controller.onScale(
            scaleFactor = 1.02f,
            focusX = 500f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
        )
        // 1.02 < MAGNET_SCALE (1.05)
        controller.onScaleEnd(viewportWidth = 1000, viewportHeight = 1000) {
            endTriggered = true
        }

        controller.scale shouldBe 1.0f
        controller.translationX shouldBe 0f
        controller.translationY shouldBe 0f
        endTriggered.shouldBeTrue()
    }

    "reset restores initial scale and translation" {
        val controller = VideoZoomController(
            onTransformChanged = { _, _, _ -> },
            animatorFactory = immediateAnimatorFactory,
        )

        controller.onScaleBegin(500f, 500f)
        controller.onScale(
            scaleFactor = 3.0f,
            focusX = 500f,
            focusY = 500f,
            viewportWidth = 1000,
            viewportHeight = 1000,
        )

        controller.scale shouldBe 3.0f
        controller.reset()

        controller.scale shouldBe 1.0f
        controller.translationX shouldBe 0f
        controller.translationY shouldBe 0f
        controller.isZoomed.shouldBeFalse()
    }
})
