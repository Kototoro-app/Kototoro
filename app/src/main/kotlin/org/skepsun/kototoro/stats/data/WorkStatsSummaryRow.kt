package org.skepsun.kototoro.stats.data

data class WorkStatsSummaryRow(
	val entityId: Long,
	val totalPages: Int,
	val averageTimePerPage: Long,
	val entryCount: Int,
)
