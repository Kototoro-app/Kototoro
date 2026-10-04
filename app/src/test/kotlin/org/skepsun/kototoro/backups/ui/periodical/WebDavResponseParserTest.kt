package org.skepsun.kototoro.backups.ui.periodical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.webdav.WebDavResource
import org.xml.sax.SAXParseException

class WebDavResponseParserTest {
    private fun document(responses: String) = """<multistatus xmlns="DAV:">$responses</multistatus>"""

    @Test
    fun `namespace aware parsing preserves encoded href instead of display name`() {
        val resources = parseWebDavResponse(document("""
            <response><href>/backup/a%20b.zip</href><propstat><prop>
              <displayname>Different name.zip</displayname>
              <getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</getlastmodified>
              <getcontentlength>456</getcontentlength>
            </prop></propstat></response>
        """))
        assertEquals(listOf(WebDavResource("a%20b.zip", 1445412480000, 456)), resources)
    }

    @Test
    fun `directories and incomplete properties are skipped`() {
        val resources = parseWebDavResponse(document("""
            <response><href>/backup/</href><propstat><prop/></propstat></response>
            <response><propstat><prop/></propstat></response>
            <response><href>/no-propstat.zip</href></response>
            <response><href>/no-prop.zip</href><propstat/></response>
            <response><href>/empty.zip</href><propstat><prop/></propstat></response>
        """))
        assertEquals(listOf(WebDavResource("empty.zip", 0, 0)), resources)
    }

    @Test
    fun `invalid date and length fall back to zero and first propstat remains authoritative`() {
        val resources = parseWebDavResponse(document("""
            <response><href>/first.zip</href>
              <propstat><status>HTTP/1.1 404 Not Found</status><prop>
                <getlastmodified>bad date</getlastmodified><getcontentlength>-7</getcontentlength>
              </prop></propstat>
              <propstat><prop><getcontentlength>123</getcontentlength></prop></propstat>
            </response>
            <response><href>/invalid.zip</href><propstat><prop>
              <getcontentlength>999999999999999999999999</getcontentlength>
            </prop></propstat></response>
        """))
        assertEquals(listOf(WebDavResource("first.zip", 0, -7), WebDavResource("invalid.zip", 0, 0)), resources)
    }

    @Test
    fun `legacy lenient date parsing is preserved`() {
        val resources = parseWebDavResponse(document("""
            <response><href>/a.zip</href><propstat><prop>
              <getlastmodified>Thu, 32 Oct 2015 07:28:00 GMT</getlastmodified>
            </prop></propstat></response>
        """))
        assertEquals(1446362880000L, resources.single().lastModifiedMillis)
    }

    @Test
    fun `malformed XML remains a parser error`() {
        assertThrows(SAXParseException::class.java) { parseWebDavResponse("<malformed>") }
    }
}
