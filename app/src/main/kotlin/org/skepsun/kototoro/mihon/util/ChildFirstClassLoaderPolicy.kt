package org.skepsun.kototoro.mihon.util

internal object ChildFirstClassLoaderPolicy {

	private val parentPackages = setOf(
		"java.",
		"javax.",
		"kotlin.",
		"kotlinx.coroutines.",
		"android.",
		"androidx.",
		"org.json.",
		"org.jsoup.",
		"okhttp3.",
		"okio.",
		"rx.",
		"eu.kanade.tachiyomi.source.",
		"eu.kanade.tachiyomi.network.",
		"eu.kanade.tachiyomi.util.",
		"uy.kohesive.injekt.",
		"ireader.core.",
		"io.ktor.",
		"com.fleeksoft.",
	)

	fun shouldDelegateToParent(className: String): Boolean = parentPackages.any(className::startsWith)
}
