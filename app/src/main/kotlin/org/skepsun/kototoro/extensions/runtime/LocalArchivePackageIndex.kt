package org.skepsun.kototoro.extensions.runtime

import android.content.pm.PackageInfo
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Parsed metadata of the managed APK archives of one directory, cached until the files change.
 *
 * `PackageManager.getPackageArchiveInfo` opens and parses the archive, which costs milliseconds per
 * file. Callers ask about one package at a time (extension list rows, the signature fallback that
 * probes four ecosystems), so walking the directory inside every lookup made the cost
 * O(packages x APKs). Here the directory is parsed once per [fingerprintOf] change instead.
 */
internal class LocalArchivePackageIndex {

    private class Entry(
        val fingerprint: Long,
        val entries: List<Pair<File, PackageInfo>>,
    )

    private val lock = Any()
    private val entriesByKey = HashMap<String, Entry>()

    fun entries(
        key: String,
        apkFiles: List<File>,
        parse: (File) -> PackageInfo?,
    ): List<Pair<File, PackageInfo>> {
        val fingerprint = fingerprintOf(apkFiles)
        synchronized(lock) {
            entriesByKey[key]?.takeIf { it.fingerprint == fingerprint }?.let { return it.entries }
            val startedAt = SystemClock.elapsedRealtime()
            val entries = apkFiles.mapNotNull { apkFile ->
                parse(apkFile)?.let { packageInfo -> apkFile to packageInfo }
            }
            entriesByKey[key] = Entry(fingerprint, entries)
            Log.d(
                TAG,
                "Indexed $key managed APK archives apks=${apkFiles.size} parsed=${entries.size} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            return entries
        }
    }

    companion object {
        private const val TAG = "KototoroInit"

        /**
         * Identity of an archive set: paths, sizes and timestamps catch added, removed, replaced and
         * overwritten APKs while staying far cheaper than parsing the archives.
         */
        fun fingerprintOf(apkFiles: List<File>): Long {
            var hash = 1125899906842597L
            apkFiles.sortedBy { it.absolutePath }.forEach { apkFile ->
                hash = 31 * hash + apkFile.absolutePath.hashCode()
                hash = 31 * hash + apkFile.length()
                hash = 31 * hash + apkFile.lastModified()
            }
            return hash
        }
    }
}
