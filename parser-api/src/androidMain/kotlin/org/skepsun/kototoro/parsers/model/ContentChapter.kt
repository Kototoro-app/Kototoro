package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.util.formatSimple
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty

public data class ContentChapter(
	/**
	 * An unique id of chapter
	 */
	@JvmField public val id: Long,
	/**
	 * User-readable name of chapter if provided by parser or null instead
	 * Do not pass manga title or chapter number here
	 */
	@JvmField public val title: String?,
	/**
	 * Chapter number starting from 1, 0 if unknown
	 */
	@JvmField public val number: Float,
	/**
	 * Volume number starting from 1, 0 if unknown
	 */
	@JvmField public val volume: Int,
	/**
	 * Relative url to chapter (**without** a domain) or any other uri.
	 * Used principally in parsers
	 */
	@JvmField public val url: String,
	/**
	 * User-readable name of scanlator (releaser) or null if unknown
	 */
	@JvmField public val scanlator: String?,
	/**
	 * Chapter upload date in milliseconds
	 */
	@JvmField public val uploadDate: Long,
	/**
	 * User-readable name of branch.
	 * A branch is a group of chapters that overlap (e.g. different languages)
	 */
	@JvmField public val branch: String?,
	@JvmField public val source: ContentSource,
	/**
	 * Opaque source-owned metadata preserved across storage and adapter round trips.
	 */
	@JvmField public val sourceData: String? = null,
	/**
	 * 该章节可下载的电子书格式候选。空 = 普通在线章节（图片/文本页）。
	 * 下载型书源（libgen/Z-Library/archive.org...）填充此字段；宿主按格式
	 * 选择阅读模态（文本模态 EPUB/FB2/TXT 展开内部章节；页面模态 PDF/DJVU 按页渲染）。
	 */
	@JvmField public val ebookFormats: List<EbookFormat> = emptyList(),
) {
	@Deprecated(
		message = "Binary compatibility bridge for parsers compiled before sourceData",
		level = DeprecationLevel.HIDDEN,
	)
	public constructor(
		id: Long,
		title: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
	) : this(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
		sourceData = null,
	)

	@Deprecated(
		message = "Binary compatibility bridge for parsers compiled with sourceData but before ebookFormats",
		level = DeprecationLevel.HIDDEN,
	)
	public constructor(
		id: Long,
		title: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
		sourceData: String?,
	) : this(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
		sourceData = sourceData,
	)

	/**
	 * 二进制兼容桥：对应 kototoro-parsers 解析器 jar 中主构造
	 * `(9固定 + ebookFormats: List<EbookFormat> = emptyList())` 的完整构造器
	 * （解析器显式传 ebookFormats 时的调用签名，如 Z-Library/LibraryGenesis）。
	 *
	 * jar 端主构造第 10 参数是 `List<EbookFormat>`，宿主主构造第 10 参数是
	 * `String sourceData`（宿主私有），两者签名不同；此桥以 List 为第 10 参，
	 * 供动态加载的解析器按 jar 侧签名构造章节。
	 */
	@Deprecated(
		message = "Binary compatibility bridge for parsers compiled with ebookFormats as 10th constructor param",
		level = DeprecationLevel.HIDDEN,
	)
	public constructor(
		id: Long,
		title: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
		ebookFormats: List<EbookFormat>,
	) : this(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
		sourceData = null,
		ebookFormats = ebookFormats,
	)

	@Deprecated("Use title instead of name", ReplaceWith("ContentChapter(id, title, number, volume, url, scanlator, uploadDate, branch, source)"))
	public constructor(
		id: Long,
		name: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
		@Suppress("UNUSED_PARAMETER") dummy: Boolean,
	) : this(
		id = id,
		title = name,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
	)

	/**
	 * 二进制兼容桥：对应 kototoro-parsers 解析器 jar 中主构造
	 * `(9固定 + ebookFormats: List<EbookFormat> = emptyList())` 的 `$default`
	 * 合成构造器（省略 ebookFormats 时 Kotlin 编译器生成的调用签名）。
	 *
	 * jar 端（如旧版 GodaParser）省略第 10 个带默认值的参数时，字节码会调用
	 * `<init>(9固定, List, int, DefaultConstructorMarker)`；宿主需要提供同签名
	 * 构造器才能让动态加载的解析器正常构造章节。
	 */
	@Deprecated(
		message = "Binary compatibility bridge for parsers compiled with ebookFormats as trailing default",
		level = DeprecationLevel.HIDDEN,
	)
	public constructor(
		id: Long,
		title: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
		ebookFormats: List<EbookFormat>,
		mask: Int,
		@Suppress("UNUSED_PARAMETER") marker: kotlin.jvm.internal.DefaultConstructorMarker,
	) : this(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
		sourceData = null,
		ebookFormats = if (mask and (1 shl 9) != 0) emptyList() else ebookFormats,
	)

	@Deprecated("Use title instead", ReplaceWith("title"))
	val name: String
		get() = title.ifNullOrEmpty {
			buildString {
				if (volume > 0) append("Vol ").append(volume).append(' ')
				if (number > 0) append("Chapter ").append(number.formatSimple()) else append("Unnamed")
			}
		}

	public fun numberString(): String? = if (number > 0f) {
		number.formatSimple()
	} else {
		null
	}

	public fun volumeString(): String? = if (volume > 0) {
		volume.toString()
	} else {
		null
	}

	public companion object {
		@Suppress("UNUSED_PARAMETER")
		@JvmStatic
		public fun `copy$default`(
			chapter: ContentChapter,
			id: Long,
			title: String?,
			number: Float,
			volume: Int,
			url: String?,
			scanlator: String?,
			uploadDate: Long,
			branch: String?,
			source: ContentSource?,
			mask: Int,
			marker: Any?,
		): ContentChapter {
			return chapter.copy(
				id = id.takeUnless { mask and (1 shl 0) != 0 } ?: chapter.id,
				title = if (mask and (1 shl 1) != 0) chapter.title else title,
				number = number.takeUnless { mask and (1 shl 2) != 0 } ?: chapter.number,
				volume = volume.takeUnless { mask and (1 shl 3) != 0 } ?: chapter.volume,
				url = if (mask and (1 shl 4) != 0) chapter.url else requireNotNull(url),
				scanlator = if (mask and (1 shl 5) != 0) chapter.scanlator else scanlator,
				uploadDate = uploadDate.takeUnless { mask and (1 shl 6) != 0 } ?: chapter.uploadDate,
				branch = if (mask and (1 shl 7) != 0) chapter.branch else branch,
				source = if (mask and (1 shl 8) != 0) chapter.source else requireNotNull(source),
				sourceData = chapter.sourceData,
				ebookFormats = chapter.ebookFormats,
			)
		}
	}
}
