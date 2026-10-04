package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourceRef
import java.io.InputStream

/** Runs on the host's I/O dispatcher; the response owner closes input and provides a cancellation checkpoint. */
fun interface SourceImageStore {
    fun materialize(
        source: SourceRef,
        pageId: Long,
        input: InputStream,
        contentLength: Long,
        checkpoint: () -> Unit,
    ): SourceImageArtifact

    /** Reuse the same content-addressed publication; the wire result retains content rather than page identity. */
    fun materializeCover(source: SourceRef, contentId: Long, input: InputStream, contentLength: Long,
        checkpoint: () -> Unit): SourceCoverArtifact {
        val file = materialize(source, contentId, input, contentLength, checkpoint)
        return SourceCoverArtifact(source, contentId, file.relativePath, file.sha256, file.byteSize, file.contentType)
    }
}
