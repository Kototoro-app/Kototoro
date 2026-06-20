package org.skepsun.kototoro.sync.google.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncAuthorizationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class GoogleDriveSyncAuth @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	suspend fun requireAccessToken(): String {
		val result = Identity.getAuthorizationClient(context)
			.authorize(request())
			.await()
		return result.accessTokenOrThrow()
	}

	fun accessTokenFromIntent(data: Intent?): String {
		val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
		return result.accessTokenOrThrow()
	}

	private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
		.setRequestedScopes(listOf(Scope(SCOPE_DRIVE_APPDATA)))
		.build()

	private fun AuthorizationResult.accessTokenOrThrow(): String {
		val token = accessToken
		if (!token.isNullOrBlank()) {
			return token
		}
		throw GoogleDriveSyncAuthorizationException(pendingIntent)
	}

	private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
		addOnSuccessListener { result -> cont.resume(result) }
		addOnFailureListener { error -> cont.resumeWithException(error) }
		addOnCanceledListener { cont.cancel() }
	}

	companion object {

		const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
	}
}
