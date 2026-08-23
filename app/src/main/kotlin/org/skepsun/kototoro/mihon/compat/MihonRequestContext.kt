package org.skepsun.kototoro.mihon.compat

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiXSourceAdapter
import org.skepsun.kototoro.parsers.model.ContentSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 为 Tachiyomi-ABI 扩展执行链路提供当前源上下文，便于底层网络层为扩展内部请求补齐来源信息。
 * 来源 URL 一律通过 [TachiyomiXSourceAdapter] 读取（Mihon 今天、Tsundoku 后续），不再硬转
 * `MihonMangaSource`（计划 §6.2 / T1.4）。
 */
object MihonRequestContext {

    private val currentSource = ThreadLocal<ContentSource?>()
    private val registeredSources = ConcurrentHashMap<String, ContentSource>()

    fun currentSource(): ContentSource? = currentSource.get()

    /** Legacy hint only; callers must still validate the resulting SourceRequestContext origin. */
    fun sourceForHost(host: String): ContentSource? = registeredSources[host.lowercase()]

    fun registerSource(source: ContentSource) {
        val adapter = source as? TachiyomiXSourceAdapter ?: return
        val host = adapter.baseUrlOrNull?.toHttpUrlOrNull()?.host?.lowercase() ?: return
        registeredSources[host] = source
    }

    fun <T> withSourceBlocking(source: ContentSource, block: () -> T): T {
        registerSource(source)
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
        registerSource(source)
        return withContext(currentSource.asContextElement(source)) {
            block()
        }
    }
}
