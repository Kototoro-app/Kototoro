package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IntRectTest {

    @Test
    fun `constructor validates bounds and calculates dimensions`() {
        val rect = IntRect(10, 20, 110, 220)
        assertEquals(100, rect.width)
        assertEquals(200, rect.height)
        assertFalse(rect.isEmpty)

        assertThrows<IllegalArgumentException> {
            IntRect(100, 20, 10, 220)
        }
        assertThrows<IllegalArgumentException> {
            IntRect(10, 200, 110, 20)
        }
    }

    @Test
    fun `fromLtwh creates valid rect`() {
        val rect = IntRect.fromLtwh(50, 100, 300, 400)
        assertEquals(50, rect.left)
        assertEquals(100, rect.top)
        assertEquals(350, rect.right)
        assertEquals(500, rect.bottom)
    }

    @Test
    fun `contains tests point inclusion accurately`() {
        val rect = IntRect(10, 10, 50, 50)
        assertTrue(rect.contains(10, 10))
        assertTrue(rect.contains(30, 30))
        assertTrue(rect.contains(50, 50))
        assertFalse(rect.contains(9, 30))
        assertFalse(rect.contains(30, 51))
    }

    @Test
    fun `intersects tests intersection correctly`() {
        val a = IntRect(0, 0, 100, 100)
        val b = IntRect(50, 50, 150, 150)
        val c = IntRect(100, 0, 200, 100) // Adjacent (touching border) -> does not intersect interior
        val d = IntRect(200, 200, 300, 300) // Disjoint

        assertTrue(a.intersects(b))
        assertTrue(b.intersects(a))
        assertFalse(a.intersects(c))
        assertFalse(a.intersects(d))
    }

    @Test
    fun `intersectionOrNull returns overlapping sub-rectangle`() {
        val a = IntRect(0, 0, 100, 100)
        val b = IntRect(50, 20, 150, 80)
        val inter = a.intersectionOrNull(b)

        assertEquals(IntRect(50, 20, 100, 80), inter)

        val disjoint = IntRect(200, 200, 300, 300)
        assertNull(a.intersectionOrNull(disjoint))
    }

    @Test
    fun `translate shifts bounds correctly`() {
        val rect = IntRect(10, 20, 30, 40)
        assertEquals(rect, rect.translate(0, 0))
        assertEquals(IntRect(15, 10, 35, 30), rect.translate(5, -10))
    }

    @Test
    fun `toFloatRect converts coordinates faithfully`() {
        val rect = IntRect(10, 20, 100, 200)
        val floatRect = rect.toFloatRect()
        assertEquals(10f, floatRect.left)
        assertEquals(20f, floatRect.top)
        assertEquals(100f, floatRect.right)
        assertEquals(200f, floatRect.bottom)
    }
}
