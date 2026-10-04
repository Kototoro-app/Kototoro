package org.skepsun.kototoro.parserhost

import java.net.URL
import java.net.URLClassLoader

/**
 * Same delegation policy as the Android `PluginClassLoader`, on a plain JVM class loader.
 *
 * - The plugin owns its generated factory and source enum (every jar defines its own).
 * - The shared ABI (models, config, [org.skepsun.kototoro.parsers.ContentLoaderContext], ...) always comes from the
 *   host, so the host and the plugin see the very same classes and can cast without proxies.
 * - Site implementations and the helper/base classes embedded in the jar are loaded from the jar first, which
 *   isolates plugins of different vintages from each other and from the host.
 * - Everything else (JDK, okhttp, coroutines, Tsuki API, ...) is parent-first.
 */
internal class ParserPluginClassLoader(url: URL, parent: ClassLoader) : URLClassLoader(arrayOf(url), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        when {
            name in PLUGIN_OWNED -> loadOwn(name, resolve)
            isHostShared(name) -> super.loadClass(name, resolve)
            isPluginPreferred(name) -> try {
                loadOwn(name, resolve)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
            else -> super.loadClass(name, resolve)
        }
    }

    private fun loadOwn(name: String, resolve: Boolean): Class<*> {
        val loaded = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(loaded)
        return loaded
    }

    private fun isHostShared(name: String) = SHARED_PREFIXES.any(name::startsWith) || name in SHARED_CLASSES

    private fun isPluginPreferred(name: String) = PLUGIN_PREFIXES.any(name::startsWith)

    private companion object {
        val PLUGIN_OWNED = setOf(
            "org.skepsun.kototoro.parsers.ContentParserFactoryKt",
            "org.koitharu.kotatsu.parsers.MangaParserFactoryKt",
            "tsuki.MangaParserFactoryKt",
            "org.skepsun.kototoro.parsers.model.ContentParserSource",
            "org.koitharu.kotatsu.parsers.model.MangaParserSource",
            "tsuki.model.MangaParserSource",
        )
        val SHARED_PREFIXES = listOf(
            "org.koitharu.kotatsu.parsers.model.",
            "org.koitharu.kotatsu.parsers.config.",
            "org.koitharu.kotatsu.parsers.webview.",
            "org.koitharu.kotatsu.parsers.util.LinkResolver",
            "org.skepsun.kototoro.parsers.model.",
            "org.skepsun.kototoro.parsers.config.",
            "org.skepsun.kototoro.parsers.util.LinkResolver",
        )
        val SHARED_CLASSES = setOf(
            "org.koitharu.kotatsu.parsers.MangaLoaderContext",
            "org.koitharu.kotatsu.parsers.MangaParser",
            "org.skepsun.kototoro.parsers.ContentLoaderContext",
            "org.skepsun.kototoro.parsers.ContentParser",
        )
        val PLUGIN_PREFIXES = listOf(
            "org.koitharu.kotatsu.parsers.site.",
            "org.koitharu.kotatsu.parsers.core.",
            "org.koitharu.kotatsu.parsers.util.",
            "org.koitharu.kotatsu.parsers.network.",
            "org.koitharu.kotatsu.parsers.exception.",
            "org.koitharu.kotatsu.parsers.MangaParserFactory",
            "org.skepsun.kototoro.parsers.site.",
            "org.skepsun.kototoro.parsers.core.",
            "org.skepsun.kototoro.parsers.util.",
            "org.skepsun.kototoro.parsers.network.",
            "org.skepsun.kototoro.parsers.exception.",
            "org.skepsun.kototoro.parsers.ContentParserFactory",
        )
    }
}
