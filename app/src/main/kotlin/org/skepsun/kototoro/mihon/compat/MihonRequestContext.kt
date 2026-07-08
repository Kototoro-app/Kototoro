package org.skepsun.kototoro.mihon.compat

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * 为 Mihon 扩展执行链路提供当前源上下文，便于底层网络层为扩展内部请求补齐来源信息。
 */
object MihonRequestContext {

    private val currentSource = ThreadLocal<ContentSource?>()

    fun currentSource(): ContentSource? = currentSource.get()

    fun <T> withSourceBlocking(source: ContentSource, block: () -> T): T {
        val previous = currentSource.get()
        currentSource.set(source)
        return try {
            block()
        } finally {
            if (previous == null) {
                currentSource.remove()
            } else {
                currentSource.set(previous)
            }
        }
    }

    suspend fun <T> withSource(source: ContentSource, block: suspend () -> T): T {
        return withContext(currentSource.asContextElement(source)) {
            block()
        }
    }
}
