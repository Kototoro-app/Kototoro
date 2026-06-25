package org.skepsun.kototoro.sync.google.domain

import android.app.PendingIntent

class GoogleDriveSyncApiException(
	val code: Int,
	override val message: String,
) : Exception(message)

class GoogleDriveSyncSchemaException(
	val remoteSchemaVersion: Int,
) : Exception("Google Drive sync schema $remoteSchemaVersion is newer than this app supports")

class GoogleDriveSyncProtocolException(
	message: String = "Google Drive sync snapshot is not a current Work sync snapshot. Clear current remote sync data or use explicit old sync import.",
) : Exception(message)

class GoogleDriveSyncWriteBlockedException(
	message: String = "Work migration normalization is not complete. Google Drive upload is blocked until legacy state is converted to Work state.",
) : Exception(message)

class GoogleDriveSyncAuthorizationException(
	val authorizationIntent: PendingIntent? = null,
	message: String = "Google Drive authorization required",
) : Exception(message)
