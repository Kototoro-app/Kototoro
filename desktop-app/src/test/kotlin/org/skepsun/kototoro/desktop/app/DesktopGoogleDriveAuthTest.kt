package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DesktopGoogleDriveAuthTest {
    @Test
    fun `missing desktop client configuration fails before opening a browser`() = runBlocking {
        var opened = false
        val http = HttpClient(MockEngine { error("Missing configuration must not contact Google") })
        try {
            val auth = DesktopGoogleDriveAuth(http, openBrowser = { opened = true; true }, clientSecret = "")
            val error = runCatching { auth.authorize() }.exceptionOrNull()
            assertTrue(error is DesktopDriveException)
            assertEquals(DesktopDriveFailure.CLIENT_SECRET, (error as DesktopDriveException).failure)
            assertFalse(opened)
        } finally { http.close() }
    }

    @Test
    fun `refresh includes desktop client configuration without placing it in authorization URLs`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("fixture-client-secret", form["client_secret"])
            assertEquals("fixture-refresh", form["refresh_token"])
            assertEquals("refresh_token", form["grant_type"])
            respond("""{"access_token":"new-access","expires_in":3600,"token_type":"Bearer"}""")
        })
        try {
            val auth = DesktopGoogleDriveAuth(http, clientSecret = "fixture-client-secret")
            val credential = auth.refresh(DesktopDriveCredential(DESKTOP_GOOGLE_CLIENT_ID, "account",
                "fixture@example.test", "fixture-refresh", "old-access", 1))
            assertEquals("new-access", credential.accessToken)
            assertEquals("fixture-refresh", credential.refreshToken)
            assertFalse(DesktopGoogleDriveAuth.authorizationUrl(DESKTOP_GOOGLE_CLIENT_ID,
                "http://127.0.0.1:12345/oauth2callback", "state", "verifier").contains("client_secret"))
        } finally { http.close() }
    }

    @Test
    fun `Google requiring client secret is not misreported as expired user authorization`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"error":"invalid_request","error_description":"client_secret is missing."}""",
                HttpStatusCode.BadRequest)
        })
        try {
            val error = runCatching {
                DesktopGoogleDriveAuth(http, clientSecret = "fixture-client-secret").refresh(DesktopDriveCredential(
                    DESKTOP_GOOGLE_CLIENT_ID, "account", "fixture@example.test", "fixture-refresh", "fixture-access", 1))
            }.exceptionOrNull()
            assertTrue(error is DesktopDriveException)
            assertEquals("google_drive_client_secret_required", (error as DesktopDriveException).failure.stringKey)
        } finally { http.close() }
    }

    @Test
    fun `PKCE matches RFC 7636 and malformed callbacks cannot complete authorization`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DesktopGoogleDriveAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        for (query in listOf(null, "state=wrong&code=value", "state=expected&state=expected&code=value",
            "state=expected&code=a&code=b", "state=%ZZ&code=value", "state=expected")) {
            assertNull(DesktopGoogleDriveAuth.parseCallback(query, "expected"))
        }
        assertEquals("a+b/c", DesktopGoogleDriveAuth.parseCallback("state=expected&code=a%2Bb%2Fc", "expected"))
        assertEquals("", DesktopGoogleDriveAuth.parseCallback("state=expected&error=access_denied", "expected"))
    }

    @Test
    fun `system browser loopback flow exchanges code with PKCE and desktop client configuration`() = runBlocking {
        var redirect: String? = null
        var challenge: String? = null
        var exchanges = 0
        val http = HttpClient(MockEngine { request ->
            when (request.url.host) {
                "oauth2.googleapis.com" -> {
                    val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals(DESKTOP_GOOGLE_CLIENT_ID, form["client_id"])
                    assertEquals("fixture-code", form["code"])
                    assertEquals(redirect, form["redirect_uri"])
                    assertEquals(challenge, DesktopGoogleDriveAuth.challenge(form["code_verifier"]!!))
                    assertEquals("fixture-client-secret", form["client_secret"])
                    exchanges++
                    respond("""{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"Bearer","scope":"openid email $DRIVE_APPDATA_SCOPE"}""")
                }
                "openidconnect.googleapis.com" -> {
                    assertEquals("Bearer fixture-access", request.headers["Authorization"])
                    respond("""{"sub":"account-id","email":"fixture@example.test"}""")
                }
                else -> error("Unexpected endpoint")
            }
        })
        try {
            val auth = DesktopGoogleDriveAuth(http, openBrowser = { url ->
                val parsed = URI(url)
                assertEquals("https", parsed.scheme)
                assertEquals("accounts.google.com", parsed.host)
                val params = parseQueryString(parsed.rawQuery)
                redirect = params["redirect_uri"]!!
                challenge = params["code_challenge"]!!
                assertEquals("S256", params["code_challenge_method"])
                val browser = java.net.http.HttpClient.newHttpClient()
                val invalid = browser.send(HttpRequest.newBuilder(URI("$redirect?state=wrong&code=bad")).build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals(400, invalid.statusCode())
                val callback = browser.send(HttpRequest.newBuilder(URI("$redirect?state=${params["state"]}&code=fixture-code")).build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals(200, callback.statusCode())
                true
            }, clock = { 1000 }, clientSecret = "fixture-client-secret")
            val credential = auth.authorize()
            assertEquals("fixture@example.test", credential.email)
            assertEquals("fixture-refresh", credential.refreshToken)
            assertEquals(3_601_000L, credential.expiresAt)
            assertEquals(1, exchanges)
        } finally { http.close() }
    }
}
