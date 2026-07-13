package org.skepsun.kototoro.core.parser.favicon

import android.net.Uri
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceListSource

const val URI_SCHEME_FAVICON = "favicon"

fun ContentSource.directFaviconUriOrNull(): Uri? {
	if (this is JsonSourceListSource) {
		iconUrl?.normalizeLnReaderIconUrl()?.let { iconUrl ->
			return directFaviconUri(name, iconUrl)
		}
	}
	if (this is JsonContentSource) {
		entity.iconUrl?.normalizeLnReaderIconUrl()?.let { iconUrl ->
			return directFaviconUri(name, iconUrl)
		}
	}
	return null
}

fun ContentSource.faviconUri(): Uri {
	directFaviconUriOrNull()?.let {
		return it
	}
	// 给 JSON 源添加后缀以避开旧缓存，占位符不会一直命中
	val key = if (this is JsonContentSource && name.startsWith("JSON_")) "${name}_json" else name
	return Uri.fromParts(URI_SCHEME_FAVICON, key, null)
}

private fun directFaviconUri(sourceName: String, iconUrl: String): Uri {
	return Uri.Builder()
		.scheme(URI_SCHEME_FAVICON)
		.encodedAuthority(sourceName)
		.appendQueryParameter("url", iconUrl)
		.build()
}

private fun String.normalizeLnReaderIconUrl(): String? {
	val value = trim().takeIf { it.isNotBlank() } ?: return null
	if (value.startsWith("http://") || value.startsWith("https://")) return value
	return value
		.removePrefix("/")
		.takeIf { it.startsWith("src/") || it.startsWith("multisrc/") }
		?.let { "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/public/static/$it" }
}
