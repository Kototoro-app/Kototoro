package org.skepsun.kototoro.reader.novel

import android.net.Uri
import android.util.Log
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.epub.resolveLocalEpubFile
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

internal data class ResolvedNovelContent(
    val manga: Content,
    val originalContent: Content,
    val isLocal: Boolean,
)

internal suspend fun resolveNovelReaderContent(
    contentIntent: ContentIntent,
    fallbackManga: Content?,
    contentDataRepository: ContentDataRepository,
    localContentRepository: LocalMangaRepository,
): ResolvedNovelContent? = resolveNovelReaderContent(
    resolveIntent = { contentDataRepository.resolveIntent(contentIntent, withChapters = true) },
    fallbackManga = fallbackManga,
    getRemoteContent = { localContentRepository.getRemoteContent(it) },
    findSavedContent = { localContentRepository.findSavedContent(it, withDetails = true)?.manga },
    getLocalDetails = { localContentRepository.getDetails(it) },
    parseLocalFallback = { url ->
        val uri = Uri.parse(url)
        val localTarget = resolveLocalEpubFile(
            url,
            uri.path.takeIf { uri.fragment == null && '?' !in url },
        )
        val parser = localTarget?.let(LocalContentParser::getOrNull)
        parser?.getContent(withDetails = true)?.manga
    },
    logDebug = { Log.d(TAG, it) },
    logWarn = { message, error -> Log.w(TAG, message, error) },
)

internal suspend fun resolveNovelReaderContent(
    resolveIntent: suspend () -> Content?,
    fallbackManga: Content?,
    getRemoteContent: suspend (Content) -> Content?,
    findSavedContent: suspend (Content) -> Content?,
    getLocalDetails: suspend (Content) -> Content,
    parseLocalFallback: suspend (String) -> Content?,
    logDebug: (String) -> Unit = {},
    logWarn: (String, Throwable) -> Unit = { _, _ -> },
): ResolvedNovelContent? {
    val mangaSeed = Unit.runCatchingCancellable {
        resolveIntent()
    }.onFailure {
        logWarn("Failed to resolve content intent", it)
    }.getOrNull() ?: fallbackManga ?: return null

    // Save original manga for online fallback（若当前是本地 URI，尝试从 index.json 恢复对应的远端信息以获得原始 URL）
    val isContentSeedLocalUrl = mangaSeed.url.let {
        it.startsWith("file://") || it.startsWith("zip://") || it.startsWith("cbz://") || it.startsWith("local://")
    }
    val maybeRemote = Unit.runCatchingCancellable {
        if (isContentSeedLocalUrl) getRemoteContent(mangaSeed) else null
    }.getOrNull()
    val originalContent = maybeRemote ?: mangaSeed

    val localManga = Unit.runCatchingCancellable {
        findSavedContent(mangaSeed)
    }.getOrNull()
    var resolvedManga = localManga ?: mangaSeed

    // 如果是从历史记录进入（可能 URL 是 local 但 source 已修正）或者来源是 Unknown，
    // 尝试修正为原始来源以支持在线跳转，并确保有远程 URL 可用
    if (resolvedManga.source.name.startsWith("LOCAL") || resolvedManga.source == UnknownContentSource) {
        resolvedManga = resolvedManga.copy(source = originalContent.source, url = originalContent.url)
        logDebug("Fixed manga source to ${resolvedManga.source.name} and URL to ${resolvedManga.url}")
    }

    if (localManga != null && resolvedManga.chapters.isNullOrEmpty()) {
        // 某些情况下索引未带章节，兜底从本地解析一遍
        Unit.runCatchingCancellable {
            resolvedManga = getLocalDetails(resolvedManga)
            logDebug("Refetched local details, chapters=${resolvedManga.chapters?.size ?: 0}")
        }.onFailure {
            logWarn("Failed to refetch local details", it)
        }
        // 再次兜底：直接用 LocalContentParser 解析目录/CBZ
        if (resolvedManga.chapters.isNullOrEmpty()) {
            Unit.runCatchingCancellable {
                val parsed = parseLocalFallback(resolvedManga.url)
                if (parsed != null) {
                    resolvedManga = parsed
                    logDebug("Parsed chapters via LocalContentParser fallback, count=${resolvedManga.chapters?.size ?: 0}")
                }
            }.onFailure {
                logWarn("Fallback parse failed", it)
            }
        }
    }

    return ResolvedNovelContent(
        manga = resolvedManga,
        originalContent = originalContent,
        isLocal = localManga != null,
    )
}

private const val TAG = "NovelReaderActivity"
