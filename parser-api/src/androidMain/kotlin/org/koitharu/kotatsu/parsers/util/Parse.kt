@file:JvmName("ParseUtils")

package org.koitharu.kotatsu.parsers.util

import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.InternalParsersApi
import java.text.DateFormat

private val REGEX_SCHEME_PREFIX = Regex("^\\w{2,6}://", RegexOption.IGNORE_CASE)
internal const val SCHEME_HTTPS = "https"

/**
 * Parse [Response] body as html document using Jsoup
 * @see [parseJson]
 * @see [parseJsonArray]
 */
// TODO suspend
public fun Response.parseHtml(): Document = use { response ->
    val body = response.body
    val charset = body.contentType()?.charset()?.name()
    Jsoup.parse(body.byteStream(), charset, response.request.url.toString())
}

/**
 * Parse [Response] body as [JSONObject]
 * @see [parseJsonArray]
 * @see [parseHtml]
 */
public fun Response.parseJson(): JSONObject = use { response ->
    JSONObject(response.body.string())
}

/**
 * Parse [Response] body as [JSONArray]
 * @see [parseJson]
 * @see [parseHtml]
 */
public fun Response.parseJsonArray(): JSONArray = use { response ->
    JSONArray(response.body.string())
}

public fun Response.parseRaw(): String = use { response ->
    response.body.string()
}

public fun Response.parseBytes(): ByteArray = use { response ->
    response.body.bytes()
}

/**
 * Convert url to relative if it is on [domain]
 * @return an url relative to the [domain] or absolute, if domain is mismatching
 */
public fun String.toRelativeUrl(domain: String): String {
    if (isEmpty() || startsWith("/")) {
        return this
    }
    return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
}

/**
 * Convert url to absolute with specified domain
 * @return an absolute url with [domain] if this is relative
 */
public fun String.toAbsoluteUrl(domain: String): String = when {
    startsWith("//") -> "$SCHEME_HTTPS:$this"
    startsWith('/') -> "$SCHEME_HTTPS://$domain$this"
    REGEX_SCHEME_PREFIX.containsMatchIn(this) -> this
    else -> "$SCHEME_HTTPS://$domain/$this"
}

public fun concatUrl(host: String, path: String): String {
    val hostWithSlash = host.endsWith('/')
    val pathWithSlash = path.startsWith('/')
    val hostWithScheme = if (host.startsWith("//")) "https:$host" else host
    return when {
        hostWithSlash && pathWithSlash -> hostWithScheme + path.drop(1)
        !hostWithSlash && !pathWithSlash -> "$hostWithScheme/$path"
        else -> hostWithScheme + path
    }
}

@InternalParsersApi
public fun DateFormat.parseSafe(str: String?): Long = if (str.isNullOrEmpty()) {
    0L
} else {
    runCatching {
        parse(str)?.time ?: 0L
    }.onFailure {
        if (javaClass.desiredAssertionStatus()) {
            throw AssertionError("Cannot parse date $str", it)
        }
    }.getOrDefault(0L)
}

@Deprecated("Useless since OkHttp 5.0", replaceWith = ReplaceWith("body"))
public fun Response.requireBody(): ResponseBody = body
