package org.skepsun.kototoro.parsers.util

import org.skepsun.kototoro.parsers.ContentLoaderContext

public class WebViewHelper(
	private val context: ContentLoaderContext,
) {

	public suspend fun getLocalStorageValue(domain: String, key: String): String? {
		return context.evaluateJs("$SCHEME_HTTPS://$domain/", "window.localStorage.getItem(\"$key\")")
	}
}
