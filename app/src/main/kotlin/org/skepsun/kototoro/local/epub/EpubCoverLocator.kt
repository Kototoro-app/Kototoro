package org.skepsun.kototoro.local.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * Finds the archive entry holding an EPUB's cover image.
 *
 * Order: the package's declared cover (EPUB 3 `properties="cover-image"`, then the EPUB 2
 * `<meta name="cover">` item), then an image whose name is `cover`, then the first image in path
 * order. Only entries that exist in the archive are returned.
 */
internal object EpubCoverLocator {

	private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "jxl")

	fun locate(zip: ZipFile): String? {
		val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
		return locate(names) { name ->
			zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
		}
	}

	fun locate(entries: Collection<String>, read: (String) -> String?): String? {
		val images = entries.filter { it.substringAfterLast('.', "").lowercase() in imageExtensions }
		if (images.isEmpty()) return null
		declaredCover(entries, read)?.let { return it }
		return images.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.').equals("cover", ignoreCase = true) }
			?: images.minOrNull()
	}

	private fun declaredCover(entries: Collection<String>, read: (String) -> String?): String? = runCatching {
		val container = read("META-INF/container.xml") ?: return null
		val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
			.selectFirst("rootfile[full-path]")
			?.attr("full-path")
			?.takeIf(String::isNotBlank)
			?: return null
		val opf = Jsoup.parse(read(opfPath) ?: return null, "", Parser.xmlParser())
		val items = opf.getElementsByTag("item") + opf.getElementsByTag("opf:item")
		val coverItem = items.firstOrNull { item -> "cover-image" in item.attr("properties").split(' ') }
			?: opf.getElementsByTag("meta")
				.firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
				?.attr("content")
				?.let { id -> items.firstOrNull { it.attr("id") == id } }
		val href = coverItem?.attr("href")?.takeIf(String::isNotBlank) ?: return null
		resolve(opfPath.substringBeforeLast('/', ""), URLDecoder.decode(href.replace("+", "%2B"), "UTF-8"))
			.takeIf { it in entries }
	}.getOrNull()

	private fun resolve(baseDir: String, href: String): String {
		val parts = ArrayList<String>()
		for (segment in "$baseDir/$href".split('/')) {
			when (segment) {
				"", "." -> Unit
				".." -> parts.removeLastOrNull()
				else -> parts += segment
			}
		}
		return parts.joinToString("/")
	}
}
