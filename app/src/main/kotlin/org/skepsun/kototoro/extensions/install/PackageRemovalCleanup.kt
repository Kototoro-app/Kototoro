package org.skepsun.kototoro.extensions.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.settings.sources.lock.SourceLockStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-package leftovers that must not outlive a removed package. The `sources` row and the lock of a
 * removed package's sources are kept on purpose: they are the user's choice and apply again on reinstall.
 */
@Singleton
class PackageRemovalCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val sourceOriginsDao: SourceOriginsDao,
    private val sourceAvailabilityRepository: SourceAvailabilityRepository,
) {

    /** Drops the package from the JAR priority (JAR only), clears its sources' availability and its origin rows. */
    suspend fun afterPackageRemoved(packageName: String, sourceNames: Collection<String>, isJar: Boolean) {
        if (isJar) {
            val current = settings.jarPriorityOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val kept = current.filterNot { it.equals(packageName.removeSuffix(".jar"), ignoreCase = true) }
            if (kept.size != current.size) {
                settings.jarPriorityOrder = kept.joinToString(",")
            }
        }
        sourceAvailabilityRepository.clearAvailability(sourceNames)
        sourceOriginsDao.findAll()
            .filter { it.packageName == packageName }
            .forEach { sourceOriginsDao.deleteByKey(it.sourceKey) }
    }

    /** A deleted JSON source has no row to keep: its lock, availability and origin go with it. */
    suspend fun afterJsonSourcesDeleted(ids: Collection<String>) {
        if (ids.isEmpty()) return
        runCatching { SourceLockStore.get(context) }.getOrNull()?.let { store ->
            ids.forEach { store.set(it, null) }
        }
        sourceAvailabilityRepository.clearAvailability(ids)
        ids.forEach { sourceOriginsDao.deleteByKey(it) }
    }
}
