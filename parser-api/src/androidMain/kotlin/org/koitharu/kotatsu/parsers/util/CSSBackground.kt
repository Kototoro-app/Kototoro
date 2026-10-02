package org.koitharu.kotatsu.core.parser

import androidx.annotation.Keep
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.splitByWhitespace

/**
 * Utility class for parsing the `background` property of css
 */
@Keep
public class CSSBackground private constructor(
	public val url: String,
	public val left: Int,
	public val top: Int,
	public val width: Int,
	public val height: Int,
) {

	public val right: Int
		get() = left + width

	public val bottom: Int
		get() = top + height

	@Keep
	public companion object {

		fun parse(element: Element): CSSBackground? {
			val style = element.attrOrNull("style") ?: return null
			val attrs = style.split(';').associate {
				val trimmed = it.trim()
				trimmed.substringBefore(':') to trimmed.substringAfter(':', "")
			}
			val width = attrs["width"]?.toPx() ?: return null
			val height = attrs["height"]?.toPx() ?: return null
			val bg = attrs["background"]?.substringAfter("url")?.splitByWhitespace() ?: return null
			val url = bg.firstOrNull()?.removeSurrounding("(", ")")?.nullIfEmpty() ?: return null
			val x = bg.getOrNull(1)?.toPx() ?: 0
			val y = bg.getOrNull(2)?.toPx() ?: 0
			return CSSBackground(
				url = url,
				left = -x,
				top = y,
				width = width,
				height = height,
			)
		}

		private fun String.toPx(): Int? {
			return removeSuffix("px").toIntOrNull()
		}
	}
}
