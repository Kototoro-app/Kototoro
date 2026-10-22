package org.skepsun.kototoro.ios

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContent

/** Persist source-owned fields instead of reconstructing requests from Swift's presentation values. */
class IosDownloadArchive {
    @Throws(Exception::class)
    fun encode(content: IosSourceContent, chapter: IosSourceChapter): String {
        require(content.value.source.name == chapter.value.source.name) { localized("下载章节与作品来源不一致") }
        require(content.mediaKind in DOWNLOADABLE) { localized("当前仅支持漫画、小说与视频下载") }
        // A thousand-chapter catalog must not be duplicated once per queued chapter.
        return Json.encodeToString(Request(content.value.copy(chapters = null), chapter.value))
    }

    @Throws(Exception::class)
    fun decode(json: String): IosDownloadRequest {
        val request = Json.decodeFromString<Request>(json)
        require(request.version == 1) { localized("下载任务版本尚不支持") }
        val content = IosSourceContent(request.content)
        val chapter = IosSourceChapter(request.chapter)
        require(request.content.source.name == request.chapter.source.name) { localized("下载章节与作品来源不一致") }
        require(content.mediaKind in DOWNLOADABLE) { localized("当前仅支持漫画、小说与视频下载") }
        return IosDownloadRequest(content, chapter)
    }

    /**
     * The work as its downloads describe it, listing only the downloaded chapters (by volume and number), for reading
     * offline when the source cannot list them and no list was saved. Null without a readable download of one work.
     */
    fun offlineContent(payloads: List<String>): IosSourceContent? {
        val requests = payloads.mapNotNull { payload ->
            runCatching { Json.decodeFromString<Request>(payload) }.getOrNull()?.takeIf { it.version == 1 }
        }
        val work = requests.firstOrNull()?.content ?: return null
        val chapters = requests
            .filter { it.content.id == work.id && it.content.source.name == work.source.name &&
                it.chapter.source.name == work.source.name }
            .map { it.chapter }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.volume }, { it.number }))
        return IosSourceContent(work.copy(chapters = chapters))
    }

    @Serializable
    private data class Request(val content: SourceContent, val chapter: SourceChapter, val version: Int = 1)

    private companion object {
        val DOWNLOADABLE = setOf("MANGA", "NOVEL", "VIDEO")
    }
}

/** A downloaded novel chapter: its HTML and pictures with the headers the source requires, for offline reading. */
class IosChapterTextArchive {
    fun encode(text: IosChapterText): String = Json.encodeToString(Stored(text.value))

    @Throws(Exception::class)
    fun decode(json: String): IosChapterText {
        val stored = Json.decodeFromString<Stored>(json)
        require(stored.version == 1) { localized("离线章节版本尚不支持") }
        return IosChapterText(stored.content)
    }

    @Serializable
    private data class Stored(val content: SourceChapterContent, val version: Int = 1)
}

class IosDownloadRequest internal constructor(val content: IosSourceContent, val chapter: IosSourceChapter)
