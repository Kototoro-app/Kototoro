package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceChapterDownloadTest {
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val chapter = SourceChapter(Long.MAX_VALUE, "一", 1f, 0, "/1", null, Long.MIN_VALUE, null, source)
    private val page = SourcePage(Long.MIN_VALUE, "/page", null, source,
        requestContext = SourcePageRequestContext(17, "/origin", "/image#opaque"))
    private val revision = "a".repeat(64)
    private val artifact = SourceImageArtifact(source, page.id, "$revision.img", revision, 100, "image/png")

    @Test
    fun `partial and complete chapter manifests round trip full requests and decimal 64 bit identities`() {
        val partial = SourceChapterDownload(Long.MIN_VALUE, chapter, revision, listOf(SourceDownloadPage(page)))
        assertFalse(partial.isComplete)
        assertEquals(0, partial.completedPages)
        val complete = partial.copy(pages = listOf(SourceDownloadPage(page, artifact, 400, 600)))
        val encoded = SourceProtocolJson.encodeToString(complete)
        assertTrue(encoded.contains("\"contentId\":\"" + Long.MIN_VALUE + "\""))
        assertEquals(complete, SourceProtocolJson.decodeFromString<SourceChapterDownload>(encoded))
        assertTrue(complete.isComplete)
        assertEquals(1, complete.completedPages)
        assertFalse(partial.copy(pages = emptyList()).isComplete)
    }

    @Test
    fun `chunk manifests retain header identities and enforce publication shape and format version`() {
        val manifest = SourceDownloadManifest(Long.MIN_VALUE, chapter, revision, "作品", 65,
            listOf(revision, "b".repeat(64)))
        assertEquals(manifest, SourceProtocolJson.decodeFromString<SourceDownloadManifest>(
            SourceProtocolJson.encodeToString(manifest)))
        assertEquals(0, manifest.copy(pageCount = 0, chunks = emptyList()).pageCount)
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(version = 2) }
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(pageCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(chunks = listOf(revision)) }
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(chunks = listOf(revision, "../foreign")) }
    }

    @Test
    fun `page chunks round trip completed requests and reject empty or overfull payloads`() {
        val pages = (0..63).map { SourceDownloadPage(page.copy(id = it.toLong()), artifact.copy(pageId = it.toLong()), 1, 1) }
        val chunk = SourceDownloadChunk(pages)
        assertEquals(chunk, SourceProtocolJson.decodeFromString<SourceDownloadChunk>(SourceProtocolJson.encodeToString(chunk)))
        assertThrows(IllegalArgumentException::class.java) { SourceDownloadChunk(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { SourceDownloadChunk(pages + pages[0]) }
    }

    @Test
    fun `manifests reject duplicate pages mismatched sources artifacts and invalid geometry`() {
        assertThrows(IllegalArgumentException::class.java) { SourceChapterDownload(1, chapter, "bad") }
        assertThrows(IllegalArgumentException::class.java) {
            SourceChapterDownload(1, chapter, revision, listOf(SourceDownloadPage(page), SourceDownloadPage(page)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceChapterDownload(1, chapter, revision,
                listOf(SourceDownloadPage(page.copy(source = source.copy(name = "other")))))
        }
        assertThrows(IllegalArgumentException::class.java) { SourceDownloadPage(page, artifact.copy(pageId = 4), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { SourceDownloadPage(page, artifact, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { SourceDownloadPage(page, width = 1, height = 1) }
    }
}
