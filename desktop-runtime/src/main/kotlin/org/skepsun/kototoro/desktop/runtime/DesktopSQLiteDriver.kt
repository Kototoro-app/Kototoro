package org.skepsun.kototoro.desktop.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Owns even connections Room opened but never pooled because schema/open configuration failed. */
internal class DesktopSQLiteDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver()) :
    SQLiteDriver by delegate, Closeable {
    private val connections = linkedSetOf<SQLiteConnection>()
    private var closed = false

    @Synchronized
    override fun open(fileName: String): SQLiteConnection {
        check(!closed) { "Desktop SQLite driver is closed" }
        val native = delegate.open(fileName)
        val connection = OwnedConnection(native)
        connections += connection
        return connection
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (connection in connections.toList()) {
            try { connection.close() } catch (error: Throwable) {
                failure?.let { if (it !== error) it.addSuppressed(error) } ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    private inner class OwnedConnection(private val native: SQLiteConnection) : SQLiteConnection by native {
        private val closed = AtomicBoolean()
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { native.close() } finally {
                synchronized(this@DesktopSQLiteDriver) { connections.remove(this) }
            }
        }
    }
}
