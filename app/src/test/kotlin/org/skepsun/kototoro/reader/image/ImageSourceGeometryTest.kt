package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize

class ImageSourceGeometryTest {

    @Test
    fun `default uncropped geometry preserves dimensions and identity mapping`() {
        val geo = ImageSourceGeometry(encodedSize = IntSize(1000, 2000))
        assertEquals(IntSize(1000, 2000), geo.logicalSize)

        val mapped = geo.mapLogicalToEncodedRegion(IntRect.fromLtwh(100, 200, 300, 400))
        assertEquals(IntRect.fromLtwh(100, 200, 300, 400), mapped)
    }

    @Test
    fun `cropped contentRect translates logical region correctly`() {
        // Source is 1200x2400, cropped margin 100px on left, 200px on top -> 1000x2000 logical content
        val content = IntRect.fromLtwh(100, 200, 1000, 2000)
        val geo = ImageSourceGeometry(
            encodedSize = IntSize(1200, 2400),
            contentRect = content,
        )
        assertEquals(IntSize(1000, 2000), geo.logicalSize)

        // Logical tile [0, 0, 500, 500] should map to encoded [100, 200, 600, 700]
        val mapped = geo.mapLogicalToEncodedRegion(IntRect.fromLtwh(0, 0, 500, 500))
        assertEquals(IntRect.fromLtwh(100, 200, 500, 500), mapped)
    }

    @Test
    fun `split double page mapping maps right and left halves accurately`() {
        // Double page spread 2000x1500
        val encodedSize = IntSize(2000, 1500)
        val leftHalf = IntRect.fromLtwh(0, 0, 1000, 1500)
        val rightHalf = IntRect.fromLtwh(1000, 0, 1000, 1500)

        val geoLeft = ImageSourceGeometry(encodedSize, leftHalf)
        val geoRight = ImageSourceGeometry(encodedSize, rightHalf)

        assertEquals(IntSize(1000, 1500), geoLeft.logicalSize)
        assertEquals(IntSize(1000, 1500), geoRight.logicalSize)

        val tileInRight = geoRight.mapLogicalToEncodedRegion(IntRect.fromLtwh(100, 100, 400, 400))
        assertEquals(IntRect.fromLtwh(1100, 100, 400, 400), tileInRight)
    }

    @Test
    fun `rotated geometry swaps logical dimensions and transforms coordinates`() {
        // Encoded 800x1200, rotated 90 deg clockwise -> logical 1200x800
        val geo = ImageSourceGeometry(
            encodedSize = IntSize(800, 1200),
            orientationDegrees = 90,
        )
        assertEquals(IntSize(1200, 800), geo.logicalSize)

        val logicalRegion = IntRect.fromLtwh(0, 0, 300, 200)
        val encodedRegion = geo.mapLogicalToEncodedRegion(logicalRegion)
        // unrotated: left=top=0, right=200, top=1200-300=900, bottom=1200
        assertEquals(0, encodedRegion.left)
        assertEquals(200, encodedRegion.right)
        assertEquals(900, encodedRegion.top)
        assertEquals(1200, encodedRegion.bottom)
    }
}
