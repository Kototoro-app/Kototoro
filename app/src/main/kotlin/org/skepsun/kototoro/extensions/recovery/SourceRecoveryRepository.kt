package org.skepsun.kototoro.extensions.recovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import javax.inject.Inject
import javax.inject.Singleton

/** A single origin with its strictly derived recovery status and reference flag. */
data class SourceRecoveryState(
    val origin: SourceOriginEntity,
    val status: SourceRecoveryStatus,
    /** Whether this source key is still referenced by any piece of content (work). */
    val referenced: Boolean,
)

/**
 * Supplies the set of source keys currently referenced by content (works). This is a
 * plain synchronous set – the domain layer treats it as an already-materialized snapshot.
 */
interface SourceReferenceProvider {

    fun referencedSourceKeys(): Set<String>
}

/**
 * Default no-op reference provider. The main session wires a real provider backed by the
 * content catalog (e.g. a cached `SELECT DISTINCT source FROM manga` / unified sources
 * snapshot); until then nothing is reported as referenced.
 */
class DefaultSourceReferenceProvider : SourceReferenceProvider {

    override fun referencedSourceKeys(): Set<String> = emptySet()
}

/**
 * Domain entry point for source recovery: reads the persisted [SourceOriginsDao],
 * merges it with the injected [SourceRuntimeSnapshot] and [SourceReferenceProvider],
 * and exposes strictly derived [SourceRecoveryState]s.
 *
 * The status/referenced flags are never persisted — they are derived on every read.
 */
@Singleton
class SourceRecoveryRepository @Inject constructor(
    private val originsDao: SourceOriginsDao,
    // Runtime snapshot provider. The Tsundoku/Mihon manager-backed implementation is wired
    // by the main session in Phase 2A+; a no-op default is left on the constructor so the
    // repository stays assemble-able standalone.
    private val snapshot: SourceRuntimeSnapshot = DefaultSourceRuntimeSnapshot(),
    // Reference provider (which source keys are referenced by works). Default is a no-op.
    private val refsProvider: SourceReferenceProvider = DefaultSourceReferenceProvider(),
) {

    /** All origins in storage order, each with its derived status and reference flag. */
    suspend fun deriveAll(): List<SourceRecoveryState> {
        val origins = originsDao.findAll()
        val referenced = refsProvider.referencedSourceKeys()
        return origins.toStates(referenced)
    }

    /** Emits the derived states whenever the underlying origins change. */
    fun observeAll(): Flow<List<SourceRecoveryState>> {
        return originsDao.observeAll().map { origins ->
            val referenced = refsProvider.referencedSourceKeys()
            origins.toStates(referenced)
        }
    }

    /** Derived status for a single source key, or `null` when the origin is unknown. */
    suspend fun statusOf(sourceKey: String): SourceRecoveryStatus? {
        val origin = originsDao.getByKey(sourceKey) ?: return null
        return SourceRecoveryDerivation.deriveStatus(origin, snapshot)
    }

    suspend fun upsert(origin: SourceOriginEntity) {
        originsDao.upsert(origin)
    }

    suspend fun remove(sourceKey: String) {
        originsDao.deleteByKey(sourceKey)
    }

    suspend fun countByKey(sourceKey: String): Int {
        return originsDao.countByKey(sourceKey)
    }

    /** Source keys referenced by content, as reported by the injected provider. */
    suspend fun referencedSourceKeys(): Set<String> {
        return refsProvider.referencedSourceKeys()
    }

    private fun List<SourceOriginEntity>.toStates(referenced: Set<String>): List<SourceRecoveryState> {
        return map { origin ->
            SourceRecoveryState(
                origin = origin,
                status = SourceRecoveryDerivation.deriveStatus(origin, snapshot),
                referenced = origin.sourceKey in referenced,
            )
        }
    }
}
