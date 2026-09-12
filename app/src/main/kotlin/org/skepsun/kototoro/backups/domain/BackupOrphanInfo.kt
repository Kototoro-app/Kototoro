package org.skepsun.kototoro.backups.domain

import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

/**
 * A user-readable description of a work-state row whose owning WORK entity is
 * missing from the local database.
 *
 * This is intentionally a small transport object: it is passed from the
 * background backup service to the review activity after an export guard
 * failure. It does not contain mutable database state and it never authorizes
 * a repair by itself.
 */
@Serializable
data class BackupOrphanInfo(
    val entityId: Long,
    val anchorMangaId: Long?,
    val title: String?,
    val source: String?,
    val stateKinds: List<StateKind>,
) : JavaSerializable {

    @Serializable
    enum class StateKind : JavaSerializable {
        HISTORY,
        FAVOURITE,
        STATISTICS,
    }
}

@Serializable
data class BackupOrphanReport(
    val totalCount: Int,
    val items: List<BackupOrphanInfo>,
) : JavaSerializable
