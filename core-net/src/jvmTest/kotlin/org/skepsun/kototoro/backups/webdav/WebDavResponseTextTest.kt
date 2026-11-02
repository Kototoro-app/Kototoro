package org.skepsun.kototoro.backups.webdav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebDavResponseTextTest {

    @Test
    fun `a multistatus body is read into the resources Android used to see`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/remote.php/dav/files/user/kototoro/</D:href>
                <D:propstat><D:prop><D:displayname>kototoro</D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/remote.php/dav/files/user/kototoro/kototoro-v3-work-v12-20261021-101500.zip</D:href>
                <D:propstat><D:prop>
                  <D:displayname>kototoro-v3-work-v12-20261021-101500.zip</D:displayname>
                  <D:getlastmodified>Wed, 21 Oct 2026 10:15:00 GMT</D:getlastmodified>
                  <D:getcontentlength>524288</D:getcontentlength>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val resources = parseWebDavResources(xml)

        assertEquals(1, resources.size)
        assertEquals("kototoro-v3-work-v12-20261021-101500.zip", resources.single().name)
        assertEquals(524_288L, resources.single().size)
        assertEquals(1_792_577_700_000L, resources.single().lastModifiedMillis)
    }

    @Test
    fun `the namespace prefix is the server's choice and does not decide what is read`() {
        val withLowercase = single("""
            <d:multistatus xmlns:d="DAV:"><d:response>
              <d:href>/dav/kototoro/kototoro-v2-data-v12-20261020-235959.zip</d:href>
              <d:propstat><d:prop><d:getlastmodified>Thu, 20 Oct 2026 23:59:59 GMT</d:getlastmodified>
              <d:getcontentlength>19</d:getcontentlength></d:prop></d:propstat>
            </d:response></d:multistatus>
        """.trimIndent())
        val withGenerated = single("""
            <ns0:multistatus xmlns:ns0="DAV:"><ns0:response>
              <ns0:href>/dav/kototoro/kototoro-v2-data-v12-20261020-235959.zip</ns0:href>
              <ns0:propstat><ns0:prop><ns0:getlastmodified>Thu, 20 Oct 2026 23:59:59 GMT</ns0:getlastmodified>
              <ns0:getcontentlength>19</ns0:getcontentlength></ns0:prop></ns0:propstat>
            </ns0:response></ns0:multistatus>
        """.trimIndent())

        assertEquals(withLowercase, withGenerated)
        assertEquals("kototoro-v2-data-v12-20261020-235959.zip", withLowercase?.name)
    }

    @Test
    fun `directories and empty names never become backup files`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/kototoro/</D:href>
                <D:propstat><D:prop><D:getcontentlength>0</D:getcontentlength></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/kototoro/sub/nested/</D:href>
                <D:propstat><D:prop></D:prop></D:propstat></D:response>
              <D:response><D:href></D:href>
                <D:propstat><D:prop></D:prop></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()

        assertTrue(parseWebDavResources(xml).isEmpty())
    }

    @Test
    fun `a dead property is a zero instead of an unreadable listing`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/kototoro/kototoro-v1-2-20260101-000000.zip</D:href>
                <D:propstat><D:prop><D:displayname>backup</D:displayname></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val resource = single(xml)

        assertEquals("kototoro-v1-2-20260101-000000.zip", resource?.name)
        assertEquals(0L, resource?.size)
        assertEquals(0L, resource?.lastModifiedMillis)
    }

    @Test
    fun `a response without a propstat pair is skipped the way the DOM reader skipped it`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/kototoro/half.zip</D:href></D:response>
            </D:multistatus>
        """.trimIndent()

        assertTrue(parseWebDavResources(xml).isEmpty())
    }

    @Test
    fun `an HTTP date becomes epoch milliseconds in any supported shape`() {
        assertEquals(784_111_777_000L, webDavDateMillis("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(784_111_777_000L, webDavDateMillis("06 Nov 1994 08:49:37 GMT"))
        assertEquals(784_111_777_000L, webDavDateMillis("Sun, 06 Nov 1994 10:49:37 +0200"))
        assertEquals(0L, webDavDateMillis(""))
        assertEquals(0L, webDavDateMillis("not a date"))
    }

    @Test
    fun `an out-of-range day rolls over instead of dropping the backup from the listing`() {
        // Android's lenient SimpleDateFormat read "32 Oct" as 1 November; the listing order depended on it.
        assertEquals(1_446_362_880_000L, webDavDateMillis("Thu, 32 Oct 2015 07:28:00 GMT"))
        assertEquals(786_358_177_000L, webDavDateMillis("Sun, 32 Nov 1994 08:49:37 GMT"))
    }

    private fun single(xml: String): WebDavResource? = parseWebDavResources(xml).singleOrNull()
}
