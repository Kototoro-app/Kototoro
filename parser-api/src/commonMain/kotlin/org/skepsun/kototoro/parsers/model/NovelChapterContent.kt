package org.skepsun.kototoro.parsers.model

/**
 * 小说章节的完整内容，用于离线下载与本地渲染。
 * - html: 渲染用的完整 HTML（img 可为原始 URL，调用方可替换为本地路径）
 * - images: 章节中涉及的图片资源（包含必要的请求头信息）
 */
public data class NovelChapterContent(
    val html: String,
    val images: List<NovelImage> = emptyList(),
) {
    public data class NovelImage(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    )
}
