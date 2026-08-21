package org.skepsun.kototoro.core.exceptions

class SyncApiException(
    message: String,
    val code: Int,
) : RuntimeException(message)
