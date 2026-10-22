package org.skepsun.kototoro.core.extensions

import android.content.Context
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.ContentSource
import java.io.File
import java.lang.reflect.Method
import org.skepsun.kototoro.core.parser.tsuki.TsukiContentParserAdapter
import org.skepsun.kototoro.core.parser.tsuki.TsukiContentSource
import org.skepsun.kototoro.core.parser.tsuki.TsukiLoaderContextAdapter

/**
 * A custom ClassLoader that enforces parent delegation for the shared 'parser-api' classes.
 * This ensures that both the Host App and the Plugin JAR use the exact same Class references
 * in memory for interfaces like MangaParser, MangaSource, etc., allowing for zero-overhead
 * direct casting without java.lang.reflect.Proxy wrappers.
 */
class PluginClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // First, don't delegate the plugin's own generated parser factory and sources list back to the host,
        // because they are unique to each jar!
        if (name == "org.skepsun.kototoro.parsers.ContentParserFactoryKt" ||
            name == "org.koitharu.kotatsu.parsers.MangaParserFactoryKt" ||
            name == "tsuki.MangaParserFactoryKt" ||
            name == "org.skepsun.kototoro.parsers.model.ContentParserSource" ||
            name == "org.koitharu.kotatsu.parsers.model.MangaParserSource" ||
            name == "tsuki.model.MangaParserSource") {
            val c = findLoadedClass(name) ?: findClass(name)
            return c
        }

        // Enforce parent delegation for the entire parser-api shared library namespace
        if (name.startsWith("org.koitharu.kotatsu.parsers.model.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.config.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.webview.") ||
            name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
            name == "org.koitharu.kotatsu.parsers.MangaParser" ||
            name.startsWith("org.koitharu.kotatsu.parsers.util.LinkResolver") ||
            name.startsWith("org.skepsun.kototoro.parsers.model.") ||
            name.startsWith("org.skepsun.kototoro.parsers.config.") ||
            name == "org.skepsun.kototoro.parsers.ContentLoaderContext" ||
            name == "org.skepsun.kototoro.parsers.ContentParser" ||
            name.startsWith("org.skepsun.kototoro.parsers.util.LinkResolver")
        ) {
            return super.loadClass(name, resolve)
        }

        // For site implementations or core base classes embedded in the plugin, try loading from the plugin first.
        // This isolates the plugins from each other (e.g. yaka vs redo) and from the host.
        if (name.startsWith("org.koitharu.kotatsu.parsers.site.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.core.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.util.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.network.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.exception.") ||
            name.startsWith("org.koitharu.kotatsu.parsers.MangaParserFactory") ||
            name.startsWith("org.skepsun.kototoro.parsers.site.") ||
            name.startsWith("org.skepsun.kototoro.parsers.core.") ||
            name.startsWith("org.skepsun.kototoro.parsers.util.") ||
            name.startsWith("org.skepsun.kototoro.parsers.network.") ||
            name.startsWith("org.skepsun.kototoro.parsers.exception.") ||
            name.startsWith("org.skepsun.kototoro.parsers.ContentParserFactory")
        ) {
            try {
                return findClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        return super.loadClass(name, resolve)
    }
}

data class LoadedJarPlugin(
    val jarName: String,
    val classLoader: PluginClassLoader,
    val architecture: ParserPluginArchitecture,
    val factoryMethod: Method,
    val sources: List<Any>, // Either List<MangaSource> or List<ContentSource>
    val brokenSourceNames: Set<String>
)

enum class ParserPluginArchitecture {
    KOTATSU,
    KOTOTORO,
    TSUKI,
}

object JarExtensionLoader {

    private fun findParserFactoryMethod(
        factoryClass: Class<*>,
        contextClass: Class<*>,
    ): Method? {
        return factoryClass.declaredMethods.firstOrNull { method ->
            method.name.startsWith("newParser") &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == contextClass
        }
    }

    private inline fun <reified T> resolveFactorySources(
        factoryMethod: Method?,
        fallbackSourceClass: Class<*>? = null,
    ): List<T> {
        val sourceClass = factoryMethod?.parameterTypes?.firstOrNull() ?: fallbackSourceClass ?: return emptyList()
        if (!sourceClass.isEnum) {
            return emptyList()
        }
        return sourceClass.enumConstants?.filterIsInstance<T>().orEmpty()
    }

    fun loadFromDirectory(context: Context, pluginDir: File): List<LoadedJarPlugin> {
        val cacheDir = context.codeCacheDir.absolutePath
        val parentClassLoader = context.classLoader
        val plugins = mutableListOf<LoadedJarPlugin>()

        if (!pluginDir.exists()) return emptyList()

        val jarFiles = pluginDir
            .listFiles { file -> file.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        // Two JARs of one package expose the same classes and the same sources, and the stale one is
        // dropped after loading anyway. Dropping it before the scan keeps its whole DEX unread.
        val uniqueJarFiles = deduplicateJarPackages(jarFiles) { it.name }
        uniqueJarFiles.dropped.forEach { stale ->
            android.util.Log.w("KototoroInit", "Skipped duplicate JAR package before scan: ${stale.name}")
        }
        val brokenSourceCache = JarBrokenSourceCache.get(context)
        brokenSourceCache.retainOnly(uniqueJarFiles.kept)

        val loadStartedAt = android.os.SystemClock.elapsedRealtime()

        for (jarFile in uniqueJarFiles.kept) {
            jarFile.setReadOnly() // Fix Android 14+ classloader restrictions
            val dexClassLoader = PluginClassLoader(
                jarFile.absolutePath,
                cacheDir,
                null,
                parentClassLoader
            )

            // Reading the @Broken flags means loading every class of the DEX, so the result is
            // persisted per file identity and only recomputed when the JAR itself changes.
            val cachedBrokenSourceNames = brokenSourceCache.read(jarFile)
            val brokenSourceNames: Set<String> = if (cachedBrokenSourceNames != null) {
                android.util.Log.i(
                    "KototoroInit",
                    "Broken scan cache hit for ${jarFile.name} broken=${cachedBrokenSourceNames.size}",
                )
                cachedBrokenSourceNames
            } else {
                val scanStartedAt = android.os.SystemClock.elapsedRealtime()
                val scanned = scanBrokenSourceNames(dexClassLoader, jarFile)
                android.util.Log.i(
                    "KototoroInit",
                    "Scanned ${jarFile.name} classes=${scanned?.classCount ?: 0} " +
                        "scan_ms=${android.os.SystemClock.elapsedRealtime() - scanStartedAt} " +
                        "broken=${scanned?.brokenSourceNames?.size ?: 0}",
                )
                val scannedNames = scanned?.brokenSourceNames.orEmpty()
                if (scanned != null) {
                    brokenSourceCache.write(jarFile, scannedNames)
                }
                scannedNames
            }

            fun record(
                architecture: ParserPluginArchitecture,
                factoryMethod: Method,
                sources: List<Any>,
            ) {
                plugins.add(
                    LoadedJarPlugin(
                        jarName = jarFile.name,
                        classLoader = dexClassLoader,
                        architecture = architecture,
                        factoryMethod = factoryMethod,
                        sources = sources,
                        brokenSourceNames = brokenSourceNames,
                    ),
                )
                android.util.Log.i(
                    "KototoroInit",
                    "Loaded ${jarFile.name} architecture=$architecture sources=${sources.size}",
                )
            }

            // Try Kotatsu Parser Architecture
            try {
                val factoryClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
                val newParserMethod = findParserFactoryMethod(factoryClass, MangaLoaderContext::class.java)
                val fallbackSourceClass = tryFindEnumClass(dexClassLoader, "org.koitharu.kotatsu.parsers.model.MangaParserSource")
                val sources = resolveFactorySources<MangaSource>(newParserMethod, fallbackSourceClass)

                if (newParserMethod != null && sources.isNotEmpty()) {
                    record(ParserPluginArchitecture.KOTATSU, newParserMethod, sources)
                    continue // Success, move to next jar
                }
            } catch (e: Throwable) {
                // Ignore, try Kototoro architecture
                android.util.Log.d("KototoroInit", "Kotatsu architecture probe skipped for ${jarFile.name}: ${e.message}")
            }

            // Try Kototoro Parser Architecture
            try {
                val factoryClass = dexClassLoader.loadClass("org.skepsun.kototoro.parsers.ContentParserFactoryKt")
                val newParserMethod = findParserFactoryMethod(factoryClass, ContentLoaderContext::class.java)
                val fallbackSourceClass = tryFindEnumClass(dexClassLoader, "org.skepsun.kototoro.parsers.model.ContentParserSource")
                val sources = resolveFactorySources<ContentSource>(newParserMethod, fallbackSourceClass)

                if (newParserMethod != null && sources.isNotEmpty()) {
                    record(ParserPluginArchitecture.KOTOTORO, newParserMethod, sources)
                    continue
                }
            } catch (e: Throwable) {
                // A missing factory is expected while probing a JAR that uses another supported architecture.
                android.util.Log.d("KototoroInit", "Kototoro architecture probe skipped for ${jarFile.name}: ${e.message}")
            }

            // Try Usagi/Tsuki Parser Architecture (used by UMA)
            try {
                val factoryClass = dexClassLoader.loadClass("tsuki.MangaParserFactoryKt")
                val newParserMethod = findParserFactoryMethod(factoryClass, tsuki.MangaLoaderContext::class.java)
                val fallbackSourceClass = tryFindEnumClass(dexClassLoader, "tsuki.model.MangaParserSource")
                val sources = resolveFactorySources<tsuki.model.MangaSource>(newParserMethod, fallbackSourceClass)

                if (newParserMethod != null && sources.isNotEmpty()) {
                    record(ParserPluginArchitecture.TSUKI, newParserMethod, sources)
                    continue
                }
            } catch (e: Throwable) {
                android.util.Log.e(
                    "KototoroInit",
                    "Unsupported parser architecture in ${jarFile.name}; Kotatsu, Kototoro and Tsuki probes failed: ${e.message}",
                    e,
                )
            }
        }
        android.util.Log.i(
            "KototoroInit",
            "JAR load finished jars=${uniqueJarFiles.kept.size} plugins=${plugins.size} " +
                "total_ms=${android.os.SystemClock.elapsedRealtime() - loadStartedAt}",
        )
        return dropDuplicatePackages(plugins)
    }

    /**
     * Loads every class of the plugin DEX to read its `@Broken` marker. Returns `null` when the DEX
     * itself cannot be read; that result must not be cached, so the next start can retry.
     */
    private fun scanBrokenSourceNames(
        dexClassLoader: ClassLoader,
        jarFile: File,
    ): ScannedBrokenSources? {
        val brokenSourceNames = mutableSetOf<String>()
        var classCount = 0
        try {
            val dexFile = DexFile(jarFile.absolutePath)
            try {
                val entries = dexFile.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    classCount++
                    try {
                        val clazz = dexClassLoader.loadClass(className)

                        if (clazz.getAnnotation(org.koitharu.kotatsu.parsers.Broken::class.java) != null) {
                            val mangaParserAnn =
                                clazz.getAnnotation(org.koitharu.kotatsu.parsers.MangaSourceParser::class.java)
                            if (mangaParserAnn != null) {
                                brokenSourceNames.add(mangaParserAnn.name)
                            }
                        }

                        if (clazz.getAnnotation(org.skepsun.kototoro.parsers.Broken::class.java) != null) {
                            val contentParserAnn =
                                clazz.getAnnotation(org.skepsun.kototoro.parsers.ContentSourceParser::class.java)
                            if (contentParserAnn != null) {
                                brokenSourceNames.add(contentParserAnn.name)
                            }
                        }
                    } catch (_: Throwable) {
                        // A single unloadable class must not abort the scan for the whole JAR.
                    }
                }
            } finally {
                dexFile.close()
            }
        } catch (e: Throwable) {
            android.util.Log.e("KototoroInit", "Failed to parse DexFile for broken sources: ${e.message}", e)
            return null
        }
        return ScannedBrokenSources(brokenSourceNames, classCount)
    }

    private class ScannedBrokenSources(
        val brokenSourceNames: Set<String>,
        val classCount: Int,
    )

    /**
     * Two JARs of the same package expose the same classes and the same sources, so keeping both only
     * creates a duplicate plugin whose sources are all reported as shadowed. The newest build wins and
     * the stale files are left on disk for the installer to clean up.
     */
    private fun dropDuplicatePackages(plugins: List<LoadedJarPlugin>): List<LoadedJarPlugin> {
        val result = deduplicateJarPackages(plugins) { it.jarName }
        if (result.dropped.isEmpty()) {
            return result.kept
        }
        val winnerByPackage = result.kept.associateBy { it.jarName.jarPackageKey() }
        result.dropped.forEach { duplicate ->
            val packageKey = duplicate.jarName.jarPackageKey()
            android.util.Log.w(
                "KototoroInit",
                "Skipped duplicate JAR package: ${duplicate.jarName} " +
                    "(version=${duplicate.jarName.jarVersionCode()}), " +
                    "keeping ${winnerByPackage[packageKey]?.jarName} " +
                    "(version=${winnerByPackage[packageKey]?.jarName?.jarVersionCode()})",
            )
        }
        return result.kept
    }

    private fun tryFindEnumClass(cl: ClassLoader, name: String): Class<*>? {
        return try {
            cl.loadClass(name)
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    fun instantiateMangaParser(plugin: LoadedJarPlugin, source: MangaSource, context: MangaLoaderContext): MangaParser {
        val enumClass = plugin.factoryMethod.parameterTypes[0]
        val matchingEnum = enumClass.enumConstants?.find { (it as? MangaSource)?.name == source.name }
            ?: throw IllegalArgumentException("Source missing in JAR: ${source.name}")
        plugin.factoryMethod.isAccessible = true
        return plugin.factoryMethod.invoke(null, matchingEnum, context) as MangaParser
    }

    fun instantiateContentParser(plugin: LoadedJarPlugin, source: ContentSource, context: ContentLoaderContext): ContentParser {
        if (plugin.architecture == ParserPluginArchitecture.TSUKI) {
            val tsukiSource = plugin.sources
                .filterIsInstance<tsuki.model.MangaSource>()
                .find { it.name == source.name }
                ?: throw IllegalArgumentException("Source missing in JAR: ${source.name}")
            val tsukiContext = TsukiLoaderContextAdapter(context, plugin)
            val parser = instantiateTsukiParser(plugin, tsukiSource, tsukiContext)
            return TsukiContentParserAdapter(parser, TsukiContentSource(tsukiSource), context)
        }
        val enumClass = plugin.factoryMethod.parameterTypes[0]
        val matchingEnum = enumClass.enumConstants?.find { (it as? ContentSource)?.name == source.name }
            ?: throw IllegalArgumentException("Source missing in JAR: ${source.name}")
        plugin.factoryMethod.isAccessible = true
        return plugin.factoryMethod.invoke(null, matchingEnum, context) as ContentParser
    }

    fun instantiateTsukiParser(
        plugin: LoadedJarPlugin,
        source: tsuki.model.MangaSource,
        context: tsuki.MangaLoaderContext,
    ): tsuki.MangaParser {
        val enumClass = plugin.factoryMethod.parameterTypes[0]
        val matchingEnum = enumClass.enumConstants?.find { (it as? tsuki.model.MangaSource)?.name == source.name }
            ?: throw IllegalArgumentException("Source missing in JAR: ${source.name}")
        plugin.factoryMethod.isAccessible = true
        return plugin.factoryMethod.invoke(null, matchingEnum, context) as tsuki.MangaParser
    }
}
