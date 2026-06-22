package org.skepsun.kototoro.sync.google.data

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest.Prompt
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncAuthorizationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GoogleDriveAuthorization(
	val accessToken: String,
	val email: String?,
	val displayName: String?,
	val account: Account?,
)

@Singleton
class GoogleDriveSyncAuth @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	suspend fun authorize(promptSelectAccount: Boolean = false): GoogleDriveAuthorization {
		val result = Identity.getAuthorizationClient(context)
			.authorize(request(promptSelectAccount))
			.await()
		return result.toAuthorization()
	}

	suspend fun requireAccessToken(): String = authorize().accessToken

	fun authorizationFromIntent(data: Intent?): GoogleDriveAuthorization {
		val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
		return result.toAuthorization()
	}

	suspend fun revokeAccess(account: Account?) {
		val requestBuilder = RevokeAccessRequest.builder()
			.setScopes(scopes())
		if (account != null) {
			requestBuilder.setAccount(account)
		}
		Identity.getAuthorizationClient(context).revokeAccess(requestBuilder.build()).await()
	}

	private fun request(promptSelectAccount: Boolean): AuthorizationRequest {
		val builder = AuthorizationRequest.builder()
			.setRequestedScopes(scopes())
		if (promptSelectAccount) {
			builder.setPrompt(Prompt.SELECT_ACCOUNT)
		}
		return builder.build()
	}

	private fun AuthorizationResult.accessTokenOrThrow(): String {
		val token = accessToken
		if (!token.isNullOrBlank()) {
			return token
		}
		throw GoogleDriveSyncAuthorizationException(pendingIntent)
	}

	private fun AuthorizationResult.toAuthorization(): GoogleDriveAuthorization {
		val account = toGoogleSignInAccount()
		return GoogleDriveAuthorization(
			accessToken = accessTokenOrThrow(),
			email = account?.email,
			displayName = account?.displayName,
			account = account?.account,
		)
	}

	private fun scopes() = listOf(Scope(SCOPE_DRIVE_APPDATA))

	private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
		addOnSuccessListener { result -> cont.resume(result) }
		addOnFailureListener { error -> cont.resumeWithException(error) }
		addOnCanceledListener { cont.cancel() }
	}

	companion object {

		const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
	}
}
