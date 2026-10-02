package org.koitharu.kotatsu.parsers.util

import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.ErrorMessages
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*


/**
 * Create a unique id for [Manga]/[MangaChapter]/[MangaPage].
 * @param url must be relative url, without a domain
 * @see [Manga.id]
 * @see [MangaChapter.id]
 * @see [MangaPage.id]
 */
@InternalParsersApi
public fun MangaParser.generateUid(url: String): Long {
	var h = LONG_HASH_SEED
	source.name.forEach { c ->
		h = 31 * h + c.code
	}
	url.forEach { c ->
		h = 31 * h + c.code
	}
	return h
}

/**
 * Create a unique id for [Manga]/[MangaChapter]/[MangaPage].
 * @param id an internal identifier
 * @see [Manga.id]
 * @see [MangaChapter.id]
 * @see [MangaPage.id]
 */
@InternalParsersApi
public fun MangaParser.generateUid(id: Long): Long {
	var h = LONG_HASH_SEED
	source.name.forEach { c ->
		h = 31 * h + c.code
	}
	h = 31 * h + id
	return h
}

@InternalParsersApi
public fun Element.parseFailed(message: String? = null): Nothing {
	throw ParseException(message, ownerDocument()?.location() ?: baseUri(), null)
}

@InternalParsersApi
public fun Set<MangaTag>?.oneOrThrowIfMany(): MangaTag? = oneOrThrowIfMany(
	ErrorMessages.FILTER_MULTIPLE_GENRES_NOT_SUPPORTED,
)

@InternalParsersApi
public fun Set<MangaState>?.oneOrThrowIfMany(): MangaState? = oneOrThrowIfMany(
	ErrorMessages.FILTER_MULTIPLE_STATES_NOT_SUPPORTED,
)

@InternalParsersApi
public fun Set<ContentType>?.oneOrThrowIfMany(): ContentType? = oneOrThrowIfMany(
	ErrorMessages.FILTER_MULTIPLE_CONTENT_TYPES_NOT_SUPPORTED,
)

@InternalParsersApi
public fun Set<Demographic>?.oneOrThrowIfMany(): Demographic? = oneOrThrowIfMany(
	ErrorMessages.FILTER_MULTIPLE_DEMOGRAPHICS_NOT_SUPPORTED,
)

@InternalParsersApi
public fun Set<ContentRating>?.oneOrThrowIfMany(): ContentRating? = oneOrThrowIfMany(
	ErrorMessages.FILTER_MULTIPLE_CONTENT_RATING_NOT_SUPPORTED,
)

private fun <T> Set<T>?.oneOrThrowIfMany(msg: String): T? = when {
	isNullOrEmpty() -> null
	size == 1 -> first()
	else -> throw IllegalArgumentException(msg)
}

@InternalParsersApi
public fun AbstractMangaParser.getDomain(subdomain: String): String {
	val domain = domain
	return subdomain + "." + domain.removePrefix("www.")
}

@InternalParsersApi
public fun MangaParser.urlBuilder(subdomain: String? = null): HttpUrl.Builder {
	return HttpUrl.Builder()
		.scheme(SCHEME_HTTPS)
		.host(if (subdomain == null) domain else "$subdomain.$domain")
}
