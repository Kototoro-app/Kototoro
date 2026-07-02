package org.skepsun.kototoro.core.db.dao

private const val SQLITE_IN_CLAUSE_CHUNK_SIZE = 900

internal suspend fun <T, R> Collection<T>.flatMapSqliteQueryChunks(
	query: suspend (Collection<T>) -> List<R>,
): List<R> {
	if (isEmpty()) {
		return emptyList()
	}
	if (size <= SQLITE_IN_CLAUSE_CHUNK_SIZE) {
		return query(this)
	}
	return chunked(SQLITE_IN_CLAUSE_CHUNK_SIZE).flatMap { chunk ->
		query(chunk)
	}
}
