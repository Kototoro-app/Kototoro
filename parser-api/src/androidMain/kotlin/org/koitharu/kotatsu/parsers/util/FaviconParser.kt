package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.model.Favicon
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.json.mapJSON

public class FaviconParser(
	private val webClient: WebClient,
	private val domain: String,
) {

	public suspend fun parseFavicons(): Favicons = withContext(Dispatchers.Default) {
		val url = "https://$domain"
		val doc = webClient.httpGet(url).parseHtml()
		val result = HashSet<Favicon>()
		val manifestLink = doc.getElementsByAttributeValue("rel", "manifest").firstOrNull()
			?.attrAsAbsoluteUrlOrNull("href")
		if (manifestLink != null) {
			runCatchingCancellable {
				parseManifest(manifestLink)
			}.onSuccess { manifest ->
				result += manifest
			}
		}
		val links = doc.getElementsByAttributeValueContaining("rel", "icon")
		links.mapNotNullTo(result) { link ->
			parseLink(link)
		}
		val touchIcons = doc.getElementsByAttributeValue("rel", "apple-touch-icon")
		touchIcons.mapNotNullTo(result) { link ->
			parseLink(link)
		}
		if (result.isEmpty()) {
			result.add(createFallback())
		}
		Favicons(result, url)
	}

	private fun parseLink(link: Element): Favicon? {
		val href = link.attrAsAbsoluteUrlOrNull("href")
		if (href == null || href.endsWith('/')) {
			return null
		}
		val sizes = link.attr("sizes")
		return Favicon(
			url = href,
			size = parseSize(sizes),
			rel = link.attrOrNull("rel"),
		)
	}

	private fun parseSize(sizes: String): Int {
		if (sizes.isEmpty() || sizes == "any") {
			return 0
		}
		return sizes.substringBefore(' ')
			.split('x', 'X', '*')
			.firstNotNullOfOrNull { it.toIntOrNull() }
			?: 0
	}

	private suspend fun parseManifest(url: String): List<Favicon> {
		val json = webClient.httpGet(url).parseJson()
		val icons = json.optJSONArray("icons") ?: return emptyList()
		return icons.mapJSON { jo ->
			Favicon(
				url = jo.getString("src").resolveLink(),
				size = parseSize(jo.getString("sizes")),
				rel = null,
			)
		}
	}

	private fun createFallback(): Favicon {
		val href = "https://$domain/favicon.ico"
		return Favicon(
			url = href,
			size = 0,
			rel = null,
		)
	}

	private fun String.resolveLink(): String = when {
		startsWith("http:") || startsWith("https:") -> this
		startsWith('/') -> "https://$domain$this"
		else -> "https://$domain/$this"
	}
}
