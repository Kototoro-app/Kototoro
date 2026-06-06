package org.skepsun.kototoro.favourites.domain

data class MigrationItem(
    val mangaId: Long,
    val title: String,
    val status: MigrationStatus = MigrationStatus.PENDING,
    val errorMessage: String? = null,
)

enum class MigrationStatus {
    PENDING,
    SEARCHING,
    MIGRATING,
    SUCCESS,
    NOT_FOUND,
    ERROR,
}

data class MigrationProgress(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val notFound: Int,
    val reused: Int = 0,
    val attached: Int = 0,
    val currentItem: MigrationItem?,
    val items: List<MigrationItem>,
    val isFinished: Boolean = false,
)

data class MigrationResult(
    val succeeded: Int,
    val failed: Int,
    val notFound: Int,
    val errors: List<Pair<Long, String>>,
)
