package org.skepsun.kototoro.scrobbling.discord.data

import android.content.Context
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.internal.closeQuietly
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.ensureSuccess
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.parseRaw
import java.io.File
import java.util.UUID
import javax.inject.Inject

private const val SCHEME_MP = "mp:"
private const val REDIRECT_URI = "kototoro://discord-auth"

@Reusable
class DiscordRepository @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
	@BaseHttpClient private val httpClient: OkHttpClient,
) {

	private val appId = context.getString(R.string.discord_app_id)

	suspend fun getMediaProxyUrl(file: File): String? {
		val requestBody = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("reqtype", "fileupload")
			.addFormDataPart("time", "24h")
			.addFormDataPart(
				"fileToUpload",
				file.name,
				file.asRequestBody("image/*".toMediaTypeOrNull()),
			)
			.build()
		val request = Request.Builder()
			.url("https://litterbox.catbox.moe/resources/internals/api.php")
			.post(requestBody)
			.build()
		var response: okhttp3.Response? = null
		return try {
			response = httpClient.newCall(request).await()
			if (response.isSuccessful) response.parseRaw().trim() else null
		} catch (_: Exception) {
			null
		} finally {
			response?.closeQuietly()
		}
	}

	fun isMediaProxyUrl(url: String) = url.startsWith(SCHEME_MP)

	suspend fun checkToken(token: String): String {
		val request = Request.Builder()
			.url("https://discord.com/api/v10/users/@me")
			.header(CommonHeaders.AUTHORIZATION, token)
			.get()
			.build()
		val response = httpClient.newCall(request).await().ensureSuccess()
		val raw = try {
			response.parseRaw()
		} finally {
			response.closeQuietly()
		}
		val json = Json.parseToJsonElement(raw).jsonObject
		return json["global_name"]?.jsonPrimitive?.contentOrNull
			?: json["username"]?.jsonPrimitive?.contentOrNull
			.orEmpty()
	}

	val oauthUrl: String
		get() {
			val verifier = UUID.randomUUID().toString() + UUID.randomUUID().toString()
			settings.discordCodeVerifier = verifier
			return "discord://action/oauth2/authorize?client_id=$appId" +
				"&scope=openid%20sdk.social_layer_presence" +
				"&response_type=code" +
				"&code_challenge=${generateDiscordCodeChallenge(verifier)}" +
				"&code_challenge_method=S256" +
				"&redirect_uri=$REDIRECT_URI"
		}

	val oauthFallbackUrl: String
		get() = "https://discord.com/oauth2/authorize?client_id=$appId" +
			"&scope=openid%20sdk.social_layer_presence" +
			"&response_type=code" +
			"&redirect_uri=$REDIRECT_URI" +
			"&code_challenge=${generateDiscordCodeChallenge(settings.discordCodeVerifier.orEmpty())}" +
			"&code_challenge_method=S256"

	suspend fun authorize(code: String) {
		val verifier = checkNotNull(settings.discordCodeVerifier) { "Discord code verifier is missing" }
		val request = Request.Builder()
			.url("https://discord.com/api/v10/oauth2/token")
			.post(
				FormBody.Builder()
					.add("client_id", appId)
					.add("grant_type", "authorization_code")
					.add("code", code)
					.add("redirect_uri", REDIRECT_URI)
					.add("code_verifier", verifier)
					.build(),
			)
			.build()
		storeTokenResponse(executeTokenRequest(request))
		settings.discordCodeVerifier = null
	}

	suspend fun refreshToken() {
		val refreshToken = checkNotNull(settings.discordRefreshToken) { "Discord refresh token is missing" }
		val request = Request.Builder()
			.url("https://discord.com/api/v10/oauth2/token")
			.post(
				FormBody.Builder()
					.add("client_id", appId)
					.add("grant_type", "refresh_token")
					.add("refresh_token", refreshToken)
					.build(),
			)
			.build()
		storeTokenResponse(executeTokenRequest(request), refreshToken)
	}

	private suspend fun executeTokenRequest(request: Request) =
		httpClient.newCall(request).await().ensureSuccess().let { response ->
			try {
				Json.parseToJsonElement(response.parseRaw()).jsonObject
			} finally {
				response.closeQuietly()
			}
		}

	private fun storeTokenResponse(
		json: kotlinx.serialization.json.JsonObject,
		fallbackRefreshToken: String? = null,
	) {
		val accessToken = checkNotNull(json["access_token"]?.jsonPrimitive?.contentOrNull) {
			"Discord access token is missing"
		}
		val tokenType = json["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer"
		settings.discordToken = "$tokenType $accessToken"
		settings.discordRefreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: fallbackRefreshToken
	}
}
