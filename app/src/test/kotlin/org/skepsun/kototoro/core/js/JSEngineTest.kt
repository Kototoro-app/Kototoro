package org.skepsun.kototoro.core.js

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The QuickJS native library is not available on the JVM, so these run the Rhino fallback, which is the path
 * that must stay sandboxed.
 */
class JSEngineTest {

    private val engine = JSEngine()

    @Test
    fun `evaluates plain scripts`() {
        assertEquals("ab3", engine.evaluate("'a' + 'b' + (1 + 2)", clazz = String::class.java))
        assertEquals(42, engine.evaluate("6 * 7", clazz = Int::class.java))
    }

    @Test
    fun `exposes no java interop`() {
        val probe = """
            [typeof Packages, typeof java, typeof javax, typeof JavaAdapter, typeof getClass].join(',')
        """.trimIndent()
        assertEquals(
            "undefined,undefined,undefined,undefined,undefined",
            engine.evaluate(probe, clazz = String::class.java),
        )
        assertNull(engine.evaluate("java.lang.System.getProperty('user.home')", clazz = String::class.java))
        assertNull(engine.evaluate("({}).getClass()", clazz = String::class.java))
    }

    @Test
    fun `stops runaway scripts`() {
        val started = System.nanoTime()
        assertNull(engine.evaluate("while (true) {}", clazz = String::class.java))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < JSEngine.EVALUATION_TIMEOUT_MS + 5_000, "took $elapsedMs ms")
    }
}
