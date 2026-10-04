package org.skepsun.kototoro.desktop.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopSQLiteDriverTest {
    private class Connection(private val failure: Throwable? = null) : SQLiteConnection {
        var closes = 0
        override fun prepare(sql: String): SQLiteStatement = error("Fixture does not execute SQL")
        override fun close() { closes++; failure?.let { throw it } }
    }

    @Test
    fun `normal pool close and shutdown do not double close while unpooled handles are released`() {
        val normal = Connection()
        val unpooled = Connection()
        var opened = 0
        val delegate = object : SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection = if (opened++ == 0) normal else unpooled
        }
        val driver = DesktopSQLiteDriver(delegate)
        driver.open("fixture").close()
        driver.open("fixture") // Simulates a connection abandoned by Room's failing configuration callback.
        driver.close()
        driver.close()
        assertEquals(1, normal.closes)
        assertEquals(1, unpooled.closes)
        assertThrows(IllegalStateException::class.java) { driver.open("fixture") }
        assertEquals(2, opened)
    }

    @Test
    fun `shutdown attempts every close even when cleanup fails and preserves the primary error`() {
        val first = IllegalStateException("first cleanup")
        val second = IllegalStateException("second cleanup")
        val connections = listOf(Connection(first), Connection(second), Connection())
        var opened = 0
        val delegate = object : SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection = connections[opened++]
        }
        val driver = DesktopSQLiteDriver(delegate)
        repeat(3) { driver.open("fixture") }
        assertSame(first, assertThrows(IllegalStateException::class.java) { driver.close() })
        assertSame(second, first.suppressed.single())
        assertEquals(listOf(1, 1, 1), connections.map { it.closes })
        driver.close()
        assertEquals(listOf(1, 1, 1), connections.map { it.closes })
    }
}
