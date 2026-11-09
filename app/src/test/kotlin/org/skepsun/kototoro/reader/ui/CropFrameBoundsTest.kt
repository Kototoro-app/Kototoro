package org.skepsun.kototoro.reader.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CropFrameBoundsTest {

    private val image = FrameBox(left = 400f, top = 100f, right = 700f, bottom = 2000f)

    @Test
    fun `a frame on the image is left alone`() {
        val frame = FrameBox(450f, 300f, 650f, 900f)

        keepFrameOnImage(frame, previous = frame, image = image) shouldBe frame
    }

    @Test
    fun `an edge dragged past the image stops at the image edge`() {
        val previous = FrameBox(400f, 100f, 700f, 2000f)
        val dragged = FrameBox(150f, 100f, 700f, 2000f)

        keepFrameOnImage(dragged, previous, image) shouldBe previous
    }

    @Test
    fun `a corner dragged off the image keeps the part still on it`() {
        val previous = FrameBox(450f, 300f, 650f, 900f)
        val dragged = FrameBox(450f, 300f, 900f, 1200f)

        keepFrameOnImage(dragged, previous, image) shouldBe FrameBox(450f, 300f, 700f, 1200f)
    }

    @Test
    fun `a frame moved off the image slides back without shrinking`() {
        val previous = FrameBox(450f, 300f, 650f, 900f)
        val moved = FrameBox(600f, 1700f, 800f, 2300f)

        keepFrameOnImage(moved, previous, image) shouldBe FrameBox(500f, 1400f, 700f, 2000f)
    }

    @Test
    fun `a moved frame wider than the image lines up with it`() {
        val previous = FrameBox(300f, 300f, 800f, 900f)
        val moved = FrameBox(320f, 300f, 820f, 900f)

        keepFrameOnImage(moved, previous, image) shouldBe FrameBox(400f, 300f, 700f, 900f)
    }
}
