package org.skepsun.kototoro.backups.external

class UnsupportedExternalBackupException(message: String) : IllegalArgumentException(message)

class ExternalBackupTooLargeException(message: String) : IllegalArgumentException(message)
