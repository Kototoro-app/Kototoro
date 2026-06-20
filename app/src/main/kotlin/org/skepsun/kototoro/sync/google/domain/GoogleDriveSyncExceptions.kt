package org.skepsun.kototoro.sync.google.domain

import android.app.PendingIntent

class GoogleDriveSyncApiException(
	val code: Int,
	override val message: String,
) : Exception(message)

class GoogleDriveSyncSchemaException(
	val remoteSchemaVersion: Int,
) : Exception("Google Drive sync schema $remoteSchemaVersion is newer than this app supports")

class GoogleDriveSyncAuthorizationException(
	val authorizationIntent: PendingIntent? = null,
	message: String = "Google Drive authorization required",
) : Exception(message)
