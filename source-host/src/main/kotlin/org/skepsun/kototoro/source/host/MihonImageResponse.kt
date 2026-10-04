package org.skepsun.kototoro.source.host

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Response ownership includes its extension loader, including responses delivered after caller cancellation. */
internal class MihonImageResponse(val native: Any, private val lease: MihonJarRegistry.SourceLease) : Closeable {
    private val hold = lease.retain()
    private val closed = AtomicBoolean()

    suspend fun materialize(store: SourceImageStore, pageId: Long): SourceImageArtifact = materializeOwned {
        stream, length, checkpoint -> store.materialize(lease.descriptor.source, pageId, stream, length, checkpoint)
    }

    suspend fun materializeCover(store: SourceImageStore, contentId: Long): SourceCoverArtifact = materializeOwned {
        stream, length, checkpoint -> store.materializeCover(lease.descriptor.source, contentId, stream, length, checkpoint)
    }

    private suspend fun <T> materializeOwned(write: (InputStream, Long, () -> Unit) -> T): T {
        val context = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cause ->
                try { close() } catch (cleanup: Throwable) { cause?.addSuppressed(cleanup) }
            }
            try {
                context.ensureActive()
                val code = MihonReflection.call(native, "code") as Int
                if (code !in 200..299) throw IOException("Image response failed")
                val body = MihonReflection.call(native, "body") ?: throw IOException("Missing image body")
                val length = MihonReflection.call(body, "contentLength") as Long
                val stream = MihonReflection.call(body, "byteStream") as InputStream
                val artifact = write(stream, length) {
                    context.ensureActive()
                }
                context.ensureActive()
                close()
                continuation.resumeWith(Result.success(artifact))
            } catch (error: Throwable) {
                try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        var failure: Throwable? = null
        thread.contextClassLoader = lease.loader
        try {
            if (native is AutoCloseable) native.close() else MihonReflection.call(native, "close")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            thread.contextClassLoader = previous
            try { hold.close() } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }
}
