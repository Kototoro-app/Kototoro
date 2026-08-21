package org.skepsun.kototoro.local.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.parsers.model.Content

abstract class LocalObserveMapper<E : Any, R : Any>(
    private val localContentIndex: LocalContentIndex,
) {

    protected fun Flow<Collection<E>>.mapToLocal() = onStart {
        localContentIndex.updateIfRequired()
    }.mapLatest {
        it.mapToLocal()
    }

    private suspend fun Collection<E>.mapToLocal(): List<R> = coroutineScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(6)
        map { item ->
            val m = toContent(item)
            async(dispatcher) {
                val mapped = if (m.isLocal) {
                    m
                } else {
                    localContentIndex.get(m.id, withDetails = false)?.manga
                }
                mapped?.let { mm -> toResult(item, mm) }
            }
        }.awaitAll().filterNotNull()
    }

    protected abstract fun toContent(e: E): Content

    protected abstract fun toResult(e: E, manga: Content): R
}
