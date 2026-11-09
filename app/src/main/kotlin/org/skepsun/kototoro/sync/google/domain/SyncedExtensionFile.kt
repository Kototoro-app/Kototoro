package org.skepsun.kototoro.sync.google.domain

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.skepsun.kototoro.core.extensions.jarPackageKey
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage

internal fun writeSyncedExtension(target: File, bytes: ByteArray, expectedSize: Long, readOnly: Boolean = false) {
    require(bytes.isNotEmpty() && (expectedSize <= 0 || bytes.size.toLong() == expectedSize)) {
        "Incomplete extension payload"
    }
    val staging = File.createTempFile(".sync-extension-", ".tmp", target.parentFile)
    try {
        staging.outputStream().use { output ->
            if (readOnly) check(staging.setReadOnly()) { "Cannot make restored APK read-only" }
            output.write(bytes)
            output.fd.sync()
        }
        // Open readers keep the old inode until the complete replacement is ready.
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staging.delete()
    }
}

/**
 * Deletes JARs of the same package as [packageId] except [keep].
 *
 * A restored extension is written to the file name recorded in the snapshot. When that name differs
 * from the one already on disk (renamed repository, re-versioned build) the old file survives next to
 * the new one, and every JAR in the plugin directory is loaded as its own plugin, so the same sources
 * end up installed twice.
 */
internal fun deleteStaleExtensionFiles(directory: File, keep: File, packageId: String): List<File> {
    val packageKey = packageId.jarPackageKey()
    val staleFiles = directory
        .listFiles { file -> file.extension.equals("jar", ignoreCase = true) }
        ?.filter { it != keep && it.name.jarPackageKey() == packageKey }
        .orEmpty()
    return staleFiles.filter { stale ->
        // Restored JARs are made read-only, so deleting them requires lifting the flag first.
        val writable = !stale.exists() || stale.canWrite() || stale.setWritable(true)
        writable && stale.delete()
    }
}

/**
 * Whether [installed] holds a copy of the removed package installed after the removal. JARs are matched by
 * package key: a reinstall from a repository may use a versioned file name for the same package.
 */
internal fun isReinstalledAfterRemoval(
    kind: String,
    packageId: String,
    removedAt: Long,
    installed: List<SyncExtensionPackage>,
): Boolean {
    val isJar = kind.equals("jar", ignoreCase = true)
    return installed.any { item ->
        !item.isDeleted &&
            item.kind.equals(kind, ignoreCase = true) &&
            item.installedAt > removedAt &&
            if (isJar) item.packageId.jarPackageKey() == packageId.jarPackageKey() else item.packageId == packageId
    }
}
