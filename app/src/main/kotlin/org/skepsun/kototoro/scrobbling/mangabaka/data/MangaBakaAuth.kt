package org.skepsun.kototoro.scrobbling.mangabaka.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

const val MANGABAKA_REDIRECT_URI = "kototoro://mangabaka-auth"
private const val AUTH_BASE = "https://mangabaka.org/auth/oauth2"

class MangaBakaAuth(
    private val client: OkHttpClient,
    private val storage: ScrobblerStorage,
    val clientId: String,
) {
    private val refreshMutex = Mutex()

    fun beginAuthorization(): String {
        check(clientId.isNotBlank()) { "MangaBaka OAuth is not configured" }
        val verifier = randomToken()
        val state = randomToken()
        storage["oauth_verifier"] = verifier
        storage["oauth_state"] = state
        storage["oauth_started_at"] = System.currentTimeMillis().toString()
        return "$AUTH_BASE/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", MANGABAKA_REDIRECT_URI)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", "openid profile library.read library.write offline_access")
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", mangaBakaCodeChallenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build().toString()
    }

    internal data class Tokens(val accessToken: String, val refreshToken: String?)

    internal suspend fun exchange(code: String, state: String): Tokens {
        val expected = storage["oauth_state"]
        val verifier = storage["oauth_verifier"]
        val startedAt = storage["oauth_started_at"]?.toLongOrNull() ?: 0L
        require(!expected.isNullOrBlank() && state == expected && !verifier.isNullOrBlank()
            && System.currentTimeMillis() - startedAt in 0..600_000) { "Invalid or expired MangaBaka authorization" }
        storage["oauth_state"] = null
        storage["oauth_verifier"] = null
        storage["oauth_started_at"] = null
        return exchangeToken(FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", clientId)
            .add("redirect_uri", MANGABAKA_REDIRECT_URI).add("code", code)
            .add("code_verifier", verifier).build())
    }

    internal suspend fun refresh(failedToken: String): String = refreshMutex.withLock {
        storage.accessToken?.takeIf { it != failedToken }?.let { return@withLock it }
        val refreshToken = storage.refreshToken ?: throw MangaBakaHttpException(401)
        val tokens = exchangeToken(FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", clientId).add("refresh_token", refreshToken)
            .add("redirect_uri", MANGABAKA_REDIRECT_URI).build())
        if (storage.accessToken != failedToken) throw MangaBakaHttpException(401)
        storage.accessToken = tokens.accessToken
        storage.refreshToken = tokens.refreshToken ?: refreshToken
        tokens.accessToken
    }

    private suspend fun exchangeToken(form: FormBody): Tokens {
        val request = Request.Builder().url("$AUTH_BASE/token").post(form).build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw MangaBakaHttpException(response.code)
            val result = Json.parseToJsonElement(response.body.string()).jsonObject
            val token = result.text("access_token")?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing MangaBaka access token")
            require(result.text("token_type").equals("Bearer", ignoreCase = true))
            Tokens(token, result.text("refresh_token"))
        }
    }

    private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

internal fun mangaBakaCodeChallenge(verifier: String): String =
    verifier.encodeUtf8().sha256().base64Url().trimEnd('=')
