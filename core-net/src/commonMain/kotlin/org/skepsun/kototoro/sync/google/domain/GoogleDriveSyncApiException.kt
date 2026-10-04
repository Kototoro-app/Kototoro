package org.skepsun.kototoro.sync.google.domain

class GoogleDriveSyncApiException(
    val code: Int,
    override val message: String,
) : Exception(message)
