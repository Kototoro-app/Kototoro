package org.skepsun.kototoro.local.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * FB2 / TXT 纯文本电子书解析器（文本模态）。
 *
 * 输出与 [EpubContent] 同构（title/author/chapters），使宿主侧「下载→展开内部章节」
 * 链路对 EPUB/FB2/TXT 统一复用：映射表只关心 [EpubChapter.index/title/content]。
 *
 * - FB2（FictionBook 2.0）：XML，正文为 `<body><section>`，章节标题取自 `section > title`，
 *   段落取 `p`；忽略 description/notes 等外围结构。
 * - TXT：按常见章节标题正则（第X章/第X卷/Chapter N/Volume N）拆分，未命中时整本为一章。
 */
class EbookTextParser {

	/**
	 * 解析 FB2 XML 文件。
	 * @param file FB2 文件
	 * @return 与 EPUB 同构的内容，解析失败返回 null
	 */
	suspend fun parseFb2(file: File): EpubContent? = withContext(Dispatchers.IO) {
		try {
			if (!file.exists()) return@withContext null
			val xml = file.readText()
			val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

			val metaTitle = doc.selectFirst("description > title-info > book-title")?.text()?.trim()
			val author = doc.select("description > title-info > author")
				.joinToString(" ") { el ->
					listOfNotNull(
						el.selectFirst("first-name")?.text(),
						el.selectFirst("last-name")?.text(),
					).joinToString(" ")
				}.trim()

			val chapters = ArrayList<EpubChapter>()
			var index = 0
			// FB2 可能有多个 body（正文 + 注释），只取第一个正文 body 的 section 层级
			val mainBody = doc.selectFirst("body") ?: return@withContext null
			for (section in mainBody.select("section").ifEmpty { listOf(mainBody) }) {
				val title = section.selectFirst("title")?.text()?.trim()
					?: section.selectFirst("h1, h2, h3")?.text()?.trim()
					?: "Chapter ${chapters.size + 1}"
				val paragraphs = section.select("p")
					.filter { p -> p.parents().none { it.tagName().equals("title", ignoreCase = true) } }
					.mapNotNull { p ->
						p.text().trim().takeIf { it.isNotEmpty() }
					}
				if (paragraphs.isEmpty()) continue
				chapters.add(
					EpubChapter(
						index = index++,
						title = title,
						content = paragraphs.joinToString("\n\n"),
						href = null,
					),
				)
			}

			EpubContent(
				title = metaTitle ?: file.nameWithoutExtension,
				author = author.ifEmpty { "Unknown author" },
				chapters = chapters,
			)
		} catch (e: Exception) {
			// 与 EpubReaderImpl.logError 一致：当前 catch 直接记录，避免依赖 Android Log
			// （JVM 单元测试不在 mock 环境下运行时会抛 NotMockedException）
			System.err.println("EbookTextParser.parseFb2: ${e.message}")
			null
		}
	}

	/**
	 * 解析 TXT 纯文本，按章节标题拆分。
	 * @param file TXT 文件
	 * @return 与 EPUB 同构的内容，解析失败返回 null
	 */
	suspend fun parseTxt(file: File): EpubContent? = withContext(Dispatchers.IO) {
		try {
			if (!file.exists()) return@withContext null
			val text = file.readText()

			// 匹配 第1章 / 第 12 话 / 第十二章 / Chapter 3 / Volume 2 / Chapter Three 等
			val titleRegex = Regex(
				"""(?m)^\s*(第\s*[0-9０-９一二三四五六七八九十百千万]+\s*[章話话节節回卷](?:\s*[:：、.\-_—(（\[【]|\s+|$|(?![的是在中了和与跟把被对对于关于从到就又也还再却但但是而而且]))|Chapter\s+\d+|Volume\s+\d+|Part\s+\d+)[^\n。！？!?]{0,60}\s*$""",
			)
			val matches = titleRegex.findAll(text).toList()
			val lines = text.lines()

			val chapters = ArrayList<EpubChapter>()
			when {
				matches.isEmpty() -> {
					val content = text.trim()
					if (content.isNotEmpty()) {
						chapters.add(EpubChapter(index = 0, title = file.nameWithoutExtension, content = content))
					}
				}
				else -> {
					// 把每个标题行所在的行号作为切分点，递增搜索避免重复标题命中首行
					var searchFrom = 0
					val splitLines = matches.map { m ->
						val trimmed = m.value.trim()
						val found = (searchFrom until lines.size).firstOrNull { idx ->
							lines[idx].trim() == trimmed
						} ?: searchFrom
						searchFrom = (found + 1).coerceAtMost(lines.size)
						found
					}
					matches.forEachIndexed { i, m ->
						val start = splitLines[i]
						val end = splitLines.getOrNull(i + 1) ?: lines.size
						val title = m.value.trim()
						val startLine = (start + 1).coerceAtMost(lines.size)
						val endLine = end.coerceAtLeast(startLine)
						val body = lines.subList(startLine, endLine)
							.joinToString("\n")
							.trim()
						if (body.isNotEmpty()) {
							chapters.add(EpubChapter(index = chapters.size, title = title, content = body))
						}
					}
				}
			}

			EpubContent(
				title = file.nameWithoutExtension,
				author = "Unknown author",
				chapters = chapters,
			)
		} catch (e: Exception) {
			// 与 EpubReaderImpl.logError 一致：当前 catch 直接记录，避免依赖 Android Log
			// （JVM 单元测试不在 mock 环境下运行时会抛 NotMockedException）
			System.err.println("EbookTextParser.parseTxt: ${e.message}")
			null
		}
	}
}
