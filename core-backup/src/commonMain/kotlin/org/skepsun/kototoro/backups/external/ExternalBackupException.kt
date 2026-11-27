package org.skepsun.kototoro.backups.external

class UnsupportedExternalBackupException(message: String) : IllegalArgumentException(message)

class ExternalBackupTooLargeException(message: String) : IllegalArgumentException(message)

/** The archive is the right app's but a section could not be parsed (truncated/edited/wrong encoding). */
class ExternalBackupCorruptException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
