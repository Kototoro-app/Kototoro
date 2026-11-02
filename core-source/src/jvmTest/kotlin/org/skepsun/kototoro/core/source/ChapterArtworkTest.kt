package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChapterArtworkTest {
    @Test
    fun `existing Cloudstream metadata keeps its poster and request headers`() {
        val metadata = """{"version":1,"season":2,"episode":3,"posterUrl":"https://cdn.test/3.jpg",
            "posterHeaders":{"Referer":"https://source.test/"},"description":"Episode three"}"""

        assertEquals(
            ChapterArtwork("https://cdn.test/3.jpg", mapOf("Referer" to "https://source.test/")),
            ChapterArtworkCodec.decode(metadata),
        )
    }

    @Test
    fun `absent artwork and unrelated or malformed metadata leave the title card usable`() {
        listOf(
            null,
            "",
            "not json",
            "{}",
            """{"season":1}""",
            """{"posterUrl":null}""",
            """{"posterUrl":" "}""",
        ).forEach { assertNull(ChapterArtworkCodec.decode(it)) }
    }
}
