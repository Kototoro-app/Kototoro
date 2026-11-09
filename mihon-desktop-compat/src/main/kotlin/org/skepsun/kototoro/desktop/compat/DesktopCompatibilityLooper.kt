package org.skepsun.kototoro.desktop.compat

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/** Owns a stoppable JVM compatibility main loop, not an Android device's unquittable application main loop. */
internal class DesktopCompatibilityLooper : Closeable {
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Throwable>()
    private val loop = AtomicReference<Looper>()
    private val thread = Thread({
        try {
            Looper.prepare()
            val current = requireNotNull(Looper.myLooper())
            loop.set(current)
            // These are public APIs of this pinned desktop compatibility implementation.
            Looper.setMainLooperForTest(current)
            ready.countDown()
            runLoop()
        } catch (error: Throwable) { failure.set(error); ready.countDown() }
    }, "Kototoro compatibility main").apply { isDaemon = true }
    private val handler: Handler

    init {
        check(Looper.getMainLooper() == null) { "Compatibility main Looper already has an owner" }
        thread.start()
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "Compatibility main Looper startup timed out" }
            failure.get()?.let { throw IllegalStateException("Compatibility main Looper startup failed", it) }
            handler = Handler(requireNotNull(loop.get()))
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /**
     * Runs the loop until it is quit. A callback that throws would end `Looper.loop()` and with it the process's only
     * main loop, so every later main-thread call of every extension would fail; as on Android, where Kototoro keeps
     * its main loop going past a plugin's crash, the error is logged and the loop resumes.
     */
    private fun runLoop() {
        while (true) {
            try {
                Looper.loop()
                return
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                logger.log(Level.WARNING, "A callback on the compatibility main loop failed; the loop continues", error)
            }
        }
    }

    fun dispatch(action: () -> Unit) {
        val current = requireNotNull(loop.get())
        check(thread.isAlive) { "Compatibility main Looper has stopped" }
        if (Looper.myLooper() === current) action() else check(handler.post(action)) {
            "Compatibility main Looper rejected a callback"
        }
    }

    override fun close() {
        val current = loop.get()
        current?.quitSafely()
        if (Thread.currentThread() !== thread) thread.join(10000)
        check(!thread.isAlive) { "Compatibility main Looper did not stop" }
        if (current != null && Looper.getMainLooper() === current) Looper.clearMainLooperForTest()
    }

    private companion object {
        val logger: Logger = Logger.getLogger("DesktopCompatibility")
    }
}
