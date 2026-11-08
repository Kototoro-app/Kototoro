package org.skepsun.kototoro.desktop.compat

import android.os.Looper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopCompatibilityLooperTest {

    @Test
    fun `a callback that throws leaves the main loop running`() {
        // The main loop is process-wide; another test in this JVM may hold it.
        assumeTrue(Looper.getMainLooper() == null, "The compatibility main Looper belongs to another test")
        DesktopCompatibilityLooper().use { looper ->
            looper.dispatch { throw IllegalStateException("an extension's callback failed") }
            val ran = CountDownLatch(1)

            looper.dispatch { ran.countDown() }

            assertTrue(ran.await(5, TimeUnit.SECONDS), "callbacks after the failing one must still run")
        }
    }
}
