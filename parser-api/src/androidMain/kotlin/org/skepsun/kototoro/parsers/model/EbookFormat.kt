package org.skepsun.kototoro.parsers.model

/**
 * 电子书文件格式（下载型书源的可选下载载体）。
 *
 * 与 kototoro-parsers 仓库中的同名枚举保持二进制兼容：
 * 解析器 jar 中按此包的枚举值构建 [ContentChapter.ebookFormats]，
 * 宿主侧据此选择阅读模态（文本模态 EPUB/FB2/TXT 展开内部章节；
 * 页面模态 PDF/DJVU/CBZ 按页渲染）。
 */
public enum class EbookFormat(
	val extension: String,
	val displayName: String,
) {
	EPUB("epub", "EPUB"),
	PDF("pdf", "PDF"),
	DJVU("djvu", "DJVU"),
	FB2("fb2", "FB2"),
	TXT("txt", "TXT"),
	MOBI("mobi", "MOBI"),
	AZW3("azw3", "AZW3"),
	CBZ("cbz", "CBZ"),
	CBR("cbr", "CBR"),
	DOCX("docx", "DOCX"),
	UNKNOWN("", "Unknown");

	companion object {
		@JvmStatic
		public fun fromExtension(extension: String?): EbookFormat? {
			val raw = extension?.trim()?.lowercase()
				?.substringAfterLast('.')
				?.takeIf { it.isNotEmpty() && it != "." }
				?: return null
			return entries.firstOrNull { it.extension == raw }
		}

		@JvmStatic
		public fun fromMarker(marker: String?): EbookFormat {
			return fromExtension(marker) ?: UNKNOWN
		}
	}
}
