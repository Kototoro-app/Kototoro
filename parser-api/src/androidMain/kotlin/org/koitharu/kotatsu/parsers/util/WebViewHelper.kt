package org.koitharu.kotatsu.parsers.util

import org.koitharu.kotatsu.parsers.MangaLoaderContext

public class WebViewHelper(
	private val context: MangaLoaderContext,
) {

	public suspend fun getLocalStorageValue(domain: String, key: String): String? {
		return context.evaluateJs("$SCHEME_HTTPS://$domain/", "window.localStorage.getItem(\"$key\")", timeout = 10000L)
	}
}
