package org.skepsun.kototoro.parserhost

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParserCookieJarTest {
    @Test
    fun `a token a parser saves without an expiry outlives the session, site cookies keep theirs`() {
        val saved = ArrayList<Cookie>()
        val store = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { saved += cookies }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = saved
        }
        val url = "https://n.novelia.cc/".toHttpUrl()
        val session = Cookie.parse(url, "authorization=jwt")!!
        val expiring = Cookie.parse(url, "theme=dark; Max-Age=60")!!
        assertFalse(session.persistent)
        ParserCookieJar(store).saveFromResponse(url, listOf(session, expiring))
        val token = saved.single { it.name == "authorization" }
        assertTrue(token.persistent, "a store that only writes persistent cookies must keep the login")
        assertEquals("jwt", token.value)
        assertEquals("n.novelia.cc", token.domain)
        assertTrue(token.expiresAt > System.currentTimeMillis() + 300L * 24 * 60 * 60 * 1000)
        assertEquals(expiring, saved.single { it.name == "theme" })
    }
}
