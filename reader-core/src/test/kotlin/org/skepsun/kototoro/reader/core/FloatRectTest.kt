package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FloatRectTest {

    @Test
    fun `calculates dimensions and empty state correctly`() {
        val rect = FloatRect(left = 10f, top = 20f, right = 60f, bottom = 120f)
        assertEquals(50f, rect.width)
        assertEquals(100f, rect.height)
        assertFalse(rect.isEmpty)

        val emptyRect = FloatRect(left = 10f, top = 20f, right = 10f, bottom = 20f)
        assertEquals(0f, emptyRect.width)
        assertEquals(0f, emptyRect.height)
        assertTrue(emptyRect.isEmpty)
    }

    @Test
    fun `rejects inverted bounds`() {
        assertThrows<IllegalArgumentException> {
            FloatRect(left = 100f, top = 0f, right = 50f, bottom = 100f)
        }
        assertThrows<IllegalArgumentException> {
            FloatRect(left = 0f, top = 100f, right = 50f, bottom = 50f)
        }
    }

    @Test
    fun `fromLtwh creates valid rect`() {
        val rect = FloatRect.fromLtwh(left = 10f, top = 20f, width = 30f, height = 40f)
        assertEquals(10f, rect.left)
        assertEquals(20f, rect.top)
        assertEquals(40f, rect.right)
        assertEquals(60f, rect.bottom)
    }

    @Test
    fun `contains checks point inclusion`() {
        val rect = FloatRect(0f, 0f, 100f, 100f)
        assertTrue(rect.contains(50f, 50f))
        assertTrue(rect.contains(0f, 0f))
        assertTrue(rect.contains(100f, 100f))
        assertFalse(rect.contains(-1f, 50f))
        assertFalse(rect.contains(50f, 101f))
    }

    @Test
    fun `intersection returns overlapping region`() {
        val r1 = FloatRect(0f, 0f, 100f, 100f)
        val r2 = FloatRect(50f, 50f, 150f, 150f)
        val intersection = r1.intersectionOrNull(r2)

        assertEquals(FloatRect(50f, 50f, 100f, 100f), intersection)
    }

    @Test
    fun `intersection returns null for disjoint rects`() {
        val r1 = FloatRect(0f, 0f, 50f, 50f)
        val r2 = FloatRect(60f, 60f, 100f, 100f)
        assertNull(r1.intersectionOrNull(r2))
    }

    @Test
    fun `edge-touching rects do not intersect`() {
        val r1 = FloatRect(0f, 0f, 50f, 50f)
        val r2 = FloatRect(50f, 0f, 100f, 50f)
        assertNull(r1.intersectionOrNull(r2))
        assertFalse(r1.intersects(r2))
    }

    @Test
    fun `translate moves coordinates by delta`() {
        val rect = FloatRect(10f, 20f, 30f, 40f)
        val translated = rect.translate(5f, -10f)
        assertEquals(FloatRect(15f, 10f, 35f, 30f), translated)
    }
}
