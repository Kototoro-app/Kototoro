package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable

/** Original page requests and verified artifact identities, without platform paths or pixel objects. */
@Serializable
data class SourceDownloadPage(
    val page: SourcePage,
    val artifact: SourceImageArtifact? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    init {
        require(if (artifact == null) width == 0 && height == 0 else width > 0 && height > 0)
        require(artifact == null || (artifact.pageId == page.id && artifact.source.name == page.source.name))
    }
}

@Serializable
data class SourceChapterDownload(
    @Serializable(with = SourceLongSerializer::class) val contentId: Long,
    val chapter: SourceChapter,
    val extensionRevision: String,
    val pages: List<SourceDownloadPage> = emptyList(),
    val contentTitle: String = "",
) {
    init {
        require(extensionRevision.matches(Regex("[0-9a-f]{64}")))
        require(pages.map { it.page.id }.distinct().size == pages.size)
        require(pages.all { it.page.source.name == chapter.source.name })
    }

    val completedPages: Int get() = pages.count { it.artifact != null }
    val isComplete: Boolean get() = pages.isNotEmpty() && completedPages == pages.size
}

/** Publication points at immutable page chunks; each platform implements its own atomic storage. */
@Serializable
data class SourceDownloadManifest(
    @Serializable(with = SourceLongSerializer::class) val contentId: Long,
    val chapter: SourceChapter,
    val extensionRevision: String,
    val contentTitle: String,
    val pageCount: Int,
    val chunks: List<String>,
    val version: Int = 1,
) {
    init {
        require(version == 1 && pageCount >= 0)
        require(extensionRevision.matches(Regex("[0-9a-f]{64}")))
        require(chunks.size == if (pageCount == 0) 0 else (pageCount - 1) / CHUNK_SIZE + 1)
        require(chunks.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    companion object { const val CHUNK_SIZE = 64 }
}

@Serializable
data class SourceDownloadChunk(val pages: List<SourceDownloadPage>) {
    init { require(pages.size in 1..SourceDownloadManifest.CHUNK_SIZE) }
}
