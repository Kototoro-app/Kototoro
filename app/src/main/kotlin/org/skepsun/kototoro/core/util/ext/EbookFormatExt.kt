package org.skepsun.kototoro.core.util.ext

import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.EbookFormat

/**
 * 电子书格式解析与阅读模态判定（宿主侧）。
 *
 * 解析器（jar）侧不再把格式放进 [ContentChapter.ebookFormats]（为保证二进制
 * ABI 稳定，jar 端 ContentChapter 保持 9 参构造），而是统一经
 * [ContentPage.preview] 携带（如 "EPUB"/"PDF"/"FB2"）。宿主侧字段
 * `ebookFormats` 仅由宿主内部填充，可能为空；此处提供统一入口，两条路径都兼容。
 *
 * 阅读模态：
 * - 文本模态（EPUB/FB2/TXT/MOBI/AZW3/DOCX）：下载后解析正文，展开为内部文本章节
 * - 页面模态（PDF/DJVU/CBZ/CBR）：下载后保存文件，按页渲染（如 PdfRenderer）逐页阅读
 */

/** 该章节的首要电子书格式（无候选返回 null，表示普通在线章节）。 */
public fun ContentChapter.primaryEbookFormat(): EbookFormat? = ebookFormats.firstOrNull()

/** 格式化候选是否完整覆盖该章节（至少一个已知格式；UNKNOWN 不算）。 */
public fun ContentChapter.hasRecognizedEbookFormat(): Boolean =
	ebookFormats.any { it != EbookFormat.UNKNOWN }

/**
 * 从页面标记回退解析格式（旧解析器兼容）：单页且 preview 为已知格式标记时视为下载型章节。
 */
public fun ContentPage.asLegacyEbookFormat(): EbookFormat? =
	EbookFormat.fromMarker(preview).takeUnless { it == EbookFormat.UNKNOWN }

/**
 * 判定章节是否为下载型电子书章节（文本或页面模态）。
 *
 * @param pages 已拉取的页面列表；为 null 时只依据章节自身的候选字段判断
 */
public fun ContentChapter.isEbookChapter(pages: List<ContentPage>? = null): Boolean {
	if (ebookFormats.isNotEmpty()) return hasRecognizedEbookFormat()
	return pages != null && pages.size == 1 && pages[0].asLegacyEbookFormat() != null
}

/**
 * 章节是否为文本模态电子书（EPUB/FB2/TXT...）——下载后展开内部文本章节。
 */
public fun ContentChapter.isTextEbookChapter(pages: List<ContentPage>? = null): Boolean {
	val format = primaryEbookFormat()
		?: pages?.singleOrNull()?.asLegacyEbookFormat()
	return format != null && format.isTextModal()
}

/**
 * 章节是否为页面模态电子书（PDF/DJVU...）——下载后按页渲染阅读。
 */
public fun ContentChapter.isPageEbookChapter(pages: List<ContentPage>? = null): Boolean {
	val format = primaryEbookFormat()
		?: pages?.singleOrNull()?.asLegacyEbookFormat()
	return format != null && format.isPageModal()
}

/** 文本模态格式集合。 */
public val TEXT_MODAL_FORMATS: Set<EbookFormat> = setOf(
	EbookFormat.EPUB,
	EbookFormat.FB2,
	EbookFormat.TXT,
	EbookFormat.MOBI,
	EbookFormat.AZW3,
	EbookFormat.DOCX,
)

/** 页面模态格式集合。 */
public val PAGE_MODAL_FORMATS: Set<EbookFormat> = setOf(
	EbookFormat.PDF,
	EbookFormat.DJVU,
	EbookFormat.CBZ,
	EbookFormat.CBR,
)

public fun EbookFormat.isTextModal(): Boolean = this in TEXT_MODAL_FORMATS

public fun EbookFormat.isPageModal(): Boolean = this in PAGE_MODAL_FORMATS
