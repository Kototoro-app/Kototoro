package org.skepsun.kototoro.desktop.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal const val DESKTOP_GOOGLE_CLIENT_ID =
    "937269114137-905oul32pv7n3j2ek9caf42eq5gn09td.apps.googleusercontent.com"
internal const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

internal enum class DesktopDriveFailure(val stringKey: String) {
    AUTHORIZATION("google_drive_authorization_required"),
    CANCELED("google_drive_authorization_canceled"),
    BROWSER("google_drive_browser_failed"),
    CREDENTIAL_STORE("google_drive_credential_store_failed"),
    SETTINGS("google_drive_settings_failed"),
    PROTOCOL("google_drive_protocol_failed"),
    CONFLICT("google_drive_conflict_failed"),
    NETWORK("google_drive_network_failed"),
    ACCESS("google_drive_access_failed"),
    CONFIGURATION("google_drive_client_configuration_failed"),
    CLIENT_SECRET("google_drive_client_secret_required"),
}

/** Never attach a token response or an authorization callback to an exception or log. */
internal class DesktopDriveException(val failure: DesktopDriveFailure) : Exception(failure.name)

@Serializable
internal class DesktopDriveCredential(
    val clientId: String,
    val accountId: String,
    val email: String,
    val refreshToken: String,
    val accessToken: String,
    val expiresAt: Long,
)

/** System-browser OAuth with a single-use loopback listener. No embedded source browser or copied cookies. */
internal class DesktopGoogleDriveAuth(
    private val http: HttpClient,
    private val clientId: String = DESKTOP_GOOGLE_CLIENT_ID,
    private val openBrowser: (String) -> Boolean = ::openDesktopUrl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientSecret: String = DesktopGoogleOAuthConfig.clientSecret,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun authorize(): DesktopDriveCredential = withContext(Dispatchers.IO) {
        requireClientConfiguration()
        val verifier = randomValue()
        val state = randomValue()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val redirect = "http://127.0.0.1:${server.address.port}/oauth2callback"
        val result = CompletableDeferred<String>()
        server.createContext("/") { exchange ->
            val code = callbackCode(exchange, redirect, state, result)
            if (code != null) result.complete(code)
        }
        try {
            server.start()
            if (!openBrowser(authorizationUrl(clientId, redirect, state, verifier))) {
                throw DesktopDriveException(DesktopDriveFailure.BROWSER)
            }
            val code = try { withTimeout(180_000) { result.await() } }
            catch (_: TimeoutCancellationException) { throw DesktopDriveException(DesktopDriveFailure.CANCELED) }
            val token = exchange(mapOf(
                "client_id" to clientId, "code" to code, "code_verifier" to verifier,
                "redirect_uri" to redirect, "grant_type" to "authorization_code",
            ))
            val refresh = token.refresh_token?.takeIf { it.isNotBlank() }
                ?: throw DesktopDriveException(DesktopDriveFailure.AUTHORIZATION)
            if (token.scope?.split(' ')?.contains(DRIVE_APPDATA_SCOPE) != true) {
                throw DesktopDriveException(DesktopDriveFailure.ACCESS)
            }
            val response = http.get("https://openidconnect.googleapis.com/v1/userinfo") {
                header("Authorization", "Bearer ${token.access_token}")
            }
            if (!response.status.isSuccess()) throw DesktopDriveException(DesktopDriveFailure.AUTHORIZATION)
            val account = json.decodeFromString<Account>(response.bodyAsText())
            if (account.sub.isBlank()) throw DesktopDriveException(DesktopDriveFailure.AUTHORIZATION)
            DesktopDriveCredential(clientId, account.sub, account.email ?: "Google Drive", refresh,
                token.access_token, clock() + token.expires_in * 1000L)
        } finally {
            server.stop(0)
            result.cancel()
        }
    }

    suspend fun refresh(credential: DesktopDriveCredential): DesktopDriveCredential {
        requireClientConfiguration()
        if (credential.clientId != clientId) throw DesktopDriveException(DesktopDriveFailure.AUTHORIZATION)
        val token = exchange(mapOf("client_id" to clientId, "refresh_token" to credential.refreshToken,
            "grant_type" to "refresh_token"))
        if (token.scope != null && DRIVE_APPDATA_SCOPE !in token.scope.split(' ')) {
            throw DesktopDriveException(DesktopDriveFailure.ACCESS)
        }
        return DesktopDriveCredential(clientId, credential.accountId, credential.email,
            token.refresh_token?.takeIf(String::isNotBlank) ?: credential.refreshToken,
            token.access_token, clock() + token.expires_in * 1000L)
    }

    private suspend fun exchange(values: Map<String, String>): Token {
        val response = http.submitForm("https://oauth2.googleapis.com/token", Parameters.build {
            values.forEach { (key, value) -> append(key, value) }
            append("client_secret", clientSecret)
        })
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<TokenError>(response.bodyAsText()) }.getOrNull()
            val failure = when {
                response.status.value >= 500 || response.status.value == 429 -> DesktopDriveFailure.NETWORK
                error?.error == "invalid_request" && error.error_description?.contains("client_secret") == true ->
                    DesktopDriveFailure.CLIENT_SECRET
                error?.error in setOf("invalid_client", "unauthorized_client", "invalid_request") ->
                    DesktopDriveFailure.CONFIGURATION
                error?.error == "invalid_scope" -> DesktopDriveFailure.ACCESS
                else -> DesktopDriveFailure.AUTHORIZATION
            }
            throw DesktopDriveException(failure)
        }
        val token = json.decodeFromString<Token>(response.bodyAsText())
        if (token.access_token.isBlank() || token.expires_in !in 1..86400 ||
            !token.token_type.equals("Bearer", ignoreCase = true)) {
            throw DesktopDriveException(DesktopDriveFailure.AUTHORIZATION)
        }
        return token
    }

    private fun requireClientConfiguration() {
        if (clientSecret.isBlank()) throw DesktopDriveException(DesktopDriveFailure.CLIENT_SECRET)
    }

    @Serializable
    private class Token(
        val access_token: String,
        val expires_in: Long,
        val refresh_token: String? = null,
        val scope: String? = null,
        val token_type: String,
    )

    @Serializable
    private class TokenError(val error: String, val error_description: String? = null)

    @Serializable
    private class Account(val sub: String, val email: String? = null)

    companion object {
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))

        fun authorizationUrl(clientId: String, redirect: String, state: String, verifier: String): String =
            "https://accounts.google.com/o/oauth2/v2/auth?" + form(mapOf(
                "client_id" to clientId, "redirect_uri" to redirect, "response_type" to "code",
                "scope" to "openid email $DRIVE_APPDATA_SCOPE", "state" to state,
                "code_challenge" to challenge(verifier), "code_challenge_method" to "S256",
                "access_type" to "offline", "prompt" to "consent select_account",
            ))

        private fun randomValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun form(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, UTF_8)}=${URLEncoder.encode(value, UTF_8)}"
        }

        private fun callbackCode(
            exchange: HttpExchange,
            redirect: String,
            state: String,
            result: CompletableDeferred<String>,
        ): String? = exchange.use {
            val expected = URI(redirect)
            val validRequest = exchange.requestMethod == "GET" && exchange.requestURI.rawPath == expected.rawPath &&
                exchange.requestHeaders.getFirst("Host") == expected.rawAuthority &&
                exchange.remoteAddress.address.isLoopbackAddress && !result.isCompleted
            val code = if (validRequest) parseCallback(exchange.requestURI.rawQuery, state) else null
            val accepted = code != null
            val message = AndroidStrings[if (accepted) "google_drive_browser_complete" else "google_drive_browser_rejected"]
            val bytes = message.toByteArray(UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
            exchange.sendResponseHeaders(if (accepted) 200 else 400, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            if (code == "") result.completeExceptionally(DesktopDriveException(DesktopDriveFailure.CANCELED))
            code?.takeIf(String::isNotEmpty)
        }

        /** Null rejects an unrelated callback; an empty code is a user denial with a valid state. */
        internal fun parseCallback(query: String?, state: String): String? {
            if (query == null || query.length > 8192) return null
            val values = runCatching {
                query.split('&').map { part ->
                    URLDecoder.decode(part.substringBefore('='), UTF_8) to
                        URLDecoder.decode(part.substringAfter('=', ""), UTF_8)
                }
            }.getOrNull() ?: return null
            if (values.map { it.first }.distinct().size != values.size) return null
            val params = values.toMap()
            if (!MessageDigest.isEqual(state.toByteArray(UTF_8), params["state"]?.toByteArray(UTF_8))) return null
            if (params.containsKey("error")) return ""
            return params["code"]?.takeIf(String::isNotBlank)
        }
    }
}
