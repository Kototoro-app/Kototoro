@file:JvmName("JsoupUtils")

package org.koitharu.kotatsu.parsers.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.jsoup.select.QueryParser
import org.jsoup.select.Selector
import org.koitharu.kotatsu.core.parser.CSSBackground
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.exception.ParseException
import kotlin.contracts.contract

public val Element.host: String?
	get() {
		val uri = baseUri()
		return if (uri.isEmpty()) {
			null
		} else {
			uri.toHttpUrlOrNull()?.host
		}
	}

/**
 * Return an attribute value or null if it is missing or empty
 * @see [Element.attr] which returns empty string instead of null
 */
public fun Element.attrOrNull(attributeKey: String): String? = attr(attributeKey).takeUnless { it.isBlank() }?.trim()

/**
 * Return an attribute value or throw an exception if it is missing
 * @see [Element.attr] which returns empty string instead
 */
public fun Element.attrOrThrow(attributeKey: String): String = if (hasAttr(attributeKey)) {
	attr(attributeKey)
} else {
	throw ParseException("Attribute \"$attributeKey\" is missing at element \"$this\"", baseUri())
}

/**
 * Return an attribute value as relative url or null if it is missing or empty
 * @see attrAsRelativeUrl
 * @see attrAsAbsoluteUrlOrNull
 * @see attrAsAbsoluteUrl
 */
public fun Element.attrAsRelativeUrlOrNull(attributeKey: String): String? {
	val attr = attrOrNull(attributeKey) ?: return null
	if (attr.isEmpty() || attr.startsWith("data:")) {
		return null
	}
	if (attr.startsWith('/')) {
		return attr
	}
	val host = baseUri().toHttpUrlOrNull()?.host ?: return null
	return attr.substringAfter(host)
}

/**
 * Return an attribute value as relative url or throw an exception if it is missing or empty
 * @throws IllegalArgumentException if attribute value is missing or empty
 * @see attrAsRelativeUrlOrNull
 * @see attrAsAbsoluteUrlOrNull
 * @see attrAsAbsoluteUrl
 */
public fun Element.attrAsRelativeUrl(attributeKey: String): String {
	return requireNotNull(attrAsRelativeUrlOrNull(attributeKey)) {
		"Cannot get relative url for $attributeKey: \"${attr(attributeKey)}\""
	}
}

/**
 * Return an attribute value as absolute url or null if it is missing or empty
 * @see attrAsAbsoluteUrl
 * @see attrAsRelativeUrl
 * @see attrAsRelativeUrlOrNull
 */
public fun Element.attrAsAbsoluteUrlOrNull(attributeKey: String): String? {
	val attr = attrOrNull(attributeKey) ?: return null
	if (attr.isEmpty() || attr.startsWith("data:")) {
		return null
	}
	return (baseUri().toHttpUrlOrNull()?.resolve(attr) ?: return null).toString()
}

/**
 * Return an attribute value as absolute url or throw an exception if it is missing or empty
 * @throws IllegalArgumentException if attribute value is missing or empty
 * @see attrAsAbsoluteUrlOrNull
 * @see attrAsRelativeUrl
 * @see attrAsRelativeUrlOrNull
 */
public fun Element.attrAsAbsoluteUrl(attributeKey: String): String {
	return parseNotNull(attrAsAbsoluteUrlOrNull(attributeKey)) {
		"Cannot get absolute url for $attributeKey: \"${attr(attributeKey)}\""
	}
}

/**
 * Return css value from `style` attribute or null if it is missing
 */
public fun Element.styleValueOrNull(property: String): String? {
	val regex = Regex("${Regex.escape(property)}\\s*:\\s*[^;]+")
	val css = attr("style").find(regex) ?: return null
	return css.substringAfter(':').removeSuffix(';').trim()
}

/**
 * Like a `expectFirst` but with detailed error message
 */
public fun Element.selectFirstOrThrow(cssQuery: String): Element = parseNotNull(Selector.selectFirst(cssQuery, this)) {
	"Cannot find \"$cssQuery\""
}

public fun Element.selectOrThrow(cssQuery: String): Elements {
	return Selector.select(cssQuery, this).ifEmpty {
		throw ParseException("Empty result for \"$cssQuery\"", baseUri())
	}
}

public fun Element.requireElementById(id: String): Element = parseNotNull(getElementById(id)) {
	"Cannot find \"#$id\""
}

public fun Element.selectLast(cssQuery: String): Element? = select(cssQuery).lastOrNull()

public fun Element.selectLastOrThrow(cssQuery: String): Element = parseNotNull(selectLast(cssQuery)) {
	"Cannot find \"$cssQuery\""
}

public fun Element.textOrNull(): String? = text().nullIfEmpty()

public fun Elements.textOrNull(): String? = text().nullIfEmpty()

public fun Element.ownTextOrNull(): String? = ownText().nullIfEmpty()

public fun Element.selectFirstParent(query: String): Element? {
	val selector = QueryParser.parse(query)
	val parents = parents()
	val root = parents.lastOrNull() ?: return null
	return parents.firstOrNull {
		selector.matches(root, it)
	}
}

public fun Element.selectFirstParentOrThrow(query: String): Element = parseNotNull(selectFirstParent(query)) {
	"Cannot find parent \"$query\""
}

/**
 * Return a first non-empty attribute value of [names] or null if it is missing or empty
 */
public fun Element.attrOrNull(vararg names: String): String? {
	for (name in names) {
		val value = attr(name)
		if (value.isNotEmpty()) {
			return value.trim()
		}
	}
	return null
}

@InternalParsersApi
@JvmOverloads
public fun Element.src(
	names: Array<String> = arrayOf(
		"data-src",
		"data-cfsrc",
		"data-original",
		"data-cdn",
		"data-sizes",
		"data-lazy-src",
		"data-srcset",
		"original-src",
		"data-wpfc-original-src",
		"src",
	),
): String? {
	for (name in names) {
		val value = attrAsAbsoluteUrlOrNull(name)
		if (value != null) {
			return value
		}
	}
	return null
}

@InternalParsersApi
public fun Element.requireSrc(): String = parseNotNull(src()) {
	"Image src not found"
}

public fun Element.backgroundOrNull(): CSSBackground? = CSSBackground.parse(this)

public fun Element.metaValue(itemprop: String): String? = getElementsByAttributeValue("itemprop", itemprop)
	.firstNotNullOfOrNull { element ->
		element.attrOrNull("content")
	}

public fun String.cssUrl(): String? {
	val fromIndex = indexOf("url(")
	if (fromIndex == -1) {
		return null
	}
	val toIndex = indexOf(')', startIndex = fromIndex)
	return if (toIndex == -1) {
		null
	} else {
		substring(fromIndex + 4, toIndex).trim()
	}
}

internal inline fun <T : Any> Element.parseNotNull(value: T?, lazyMessage: () -> String): T {
	contract {
		returns() implies (value != null)
	}

	if (value == null) {
		val message = lazyMessage()
		throw ParseException(message, baseUri())
	} else {
		return value
	}
}
