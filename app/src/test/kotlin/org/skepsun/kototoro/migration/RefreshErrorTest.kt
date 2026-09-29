package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.RefreshError

class RefreshErrorTest {
    @Test
    fun `cloudflare and site challenges are recognised`() {
        assertEquals(
            RefreshError.Challenge,
            RefreshError.parse("org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException: Protected by CloudFlare"),
        )
        assertEquals(
            RefreshError.Challenge,
            RefreshError.parse(
                "org.jsoup.HttpStatusException: : {\"challenge_url\":\"/__gatekeeper_challenge/start\"," +
                    "\"error\":\"challenge_required\"}. Status=403, URL=[https://www.baozimh.com/comic/x]",
            ),
        )
    }

    @Test
    fun `http status is extracted`() {
        assertEquals(RefreshError.Http(404), RefreshError.parse("org.jsoup.HttpStatusException: HTTP error fetching URL. Status=404, URL=[x]"))
        assertEquals(RefreshError.Http(502), RefreshError.parse("java.io.IOException: HTTP 502 Bad Gateway"))
    }

    @Test
    fun `parse and network failures are grouped`() {
        assertEquals(
            RefreshError.Parse,
            RefreshError.parse("org.skepsun.kototoro.parsers.exception.ParseException: Cannot find \"#mainer\" at https://x"),
        )
        assertEquals(RefreshError.Network, RefreshError.parse("java.net.SocketTimeoutException: timeout"))
        assertEquals(RefreshError.Network, RefreshError.parse("java.net.UnknownHostException: Unable to resolve host"))
    }

    @Test
    fun `other errors keep a short message without the class name`() {
        assertEquals(RefreshError.Other("Something odd"), RefreshError.parse("com.example.WeirdException: Something odd\n\tat foo"))
        assertEquals(RefreshError.Other("x".repeat(60) + "…"), RefreshError.parse("x".repeat(100)))
    }

    @Test
    fun `blank input is null`() {
        assertNull(RefreshError.parse("  "))
        assertNull(RefreshError.parse(null))
    }
}
