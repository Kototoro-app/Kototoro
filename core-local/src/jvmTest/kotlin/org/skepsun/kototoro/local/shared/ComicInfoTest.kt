package org.skepsun.kototoro.local.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComicInfoTest {

    /** Shaped like the file Mihon's local source writes. */
    private val mihon = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:ty="http://www.w3.org/2001/XMLSchema-instance">
          <Title>Chapter 12: The Return</Title>
          <Series>Witch Hat Atelier</Series>
          <Number>12</Number>
          <Volume>3</Volume>
          <Summary>Coco wants to be a witch &amp; learns why it&apos;s forbidden.</Summary>
          <Writer>Kamome Shirahama</Writer>
          <Penciller>Kamome Shirahama, Assistant</Penciller>
          <Genre>Fantasy, Adventure</Genre>
          <Tags>Magic; fantasy</Tags>
          <Web>https://example.org/series/1 https://example.org/mirror</Web>
          <LanguageISO>en</LanguageISO>
          <AgeRating>Teen</AgeRating>
          <ty:PublishingStatusTachiyomi>Ongoing</ty:PublishingStatusTachiyomi>
        </ComicInfo>
    """.trimIndent()

    @Test
    fun `reads the metadata a Mihon archive carries`() {
        val info = checkNotNull(ComicInfo.parse(mihon))
        assertEquals("Witch Hat Atelier", info.series)
        assertEquals("Chapter 12: The Return", info.title)
        assertEquals(12f, info.chapterNumber)
        assertEquals(3, info.volume)
        assertEquals("Coco wants to be a witch & learns why it's forbidden.", info.summary)
        assertEquals(listOf("Kamome Shirahama", "Assistant"), info.authors)
        assertEquals(listOf("Fantasy", "Adventure", "Magic"), info.allTags)
        assertEquals("https://example.org/series/1", info.web)
        assertEquals("en", info.languageIso)
        assertEquals(ComicInfo.Status.ONGOING, info.status)
        assertFalse(info.isAdult)
    }

    @Test
    fun `adult ratings and finished status are recognised`() {
        val info = checkNotNull(
            ComicInfo.parse(
                "<ComicInfo><Series>S</Series><AgeRating>Adults Only 18+</AgeRating>" +
                    "<PublishingStatusTachiyomi>Publishing finished</PublishingStatusTachiyomi></ComicInfo>",
            ),
        )
        assertTrue(info.isAdult)
        assertEquals(ComicInfo.Status.FINISHED, info.status)
    }

    @Test
    fun `other documents and empty files give nothing`() {
        assertNull(ComicInfo.parse("<package><title>Not a comic</title></package>"))
        assertNull(ComicInfo.parse("<ComicInfo></ComicInfo>"))
        assertNull(ComicInfo.parse(""))
    }

    @Test
    fun `non-numeric chapter numbers stay text`() {
        val info = checkNotNull(ComicInfo.parse("<ComicInfo><Number>Extra</Number></ComicInfo>"))
        assertEquals("Extra", info.number)
        assertNull(info.chapterNumber)
    }
}
