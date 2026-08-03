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

        val jarFiles = pluginDir.listFiles { file -> file.extension == "jar" } ?: emptyArray()

        for (jarFile in jarFiles) {
            jarFile.setReadOnly() // Fix Android 14+ classloader restrictions
            val dexClassLoader = PluginClassLoader(
                jarFile.absolutePath,
                cacheDir,
                null,
                parentClassLoader
            )

            // Parse DEX to find broken sources
            val brokenSourceNames = mutableSetOf<String>()
            try {
                val dexFile = DexFile(jarFile.absolutePath)
                val entries = dexFile.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    try {
                        val clazz = dexClassLoader.loadClass(className)
                        
                        val brokenKotatsu = clazz.getAnnotation(org.koitharu.kotatsu.parsers.Broken::class.java)
                        if (brokenKotatsu != null) {
                            val mangaParserAnn = clazz.getAnnotation(org.koitharu.kotatsu.parsers.MangaSourceParser::class.java)
                            if (mangaParserAnn != null) {
                                brokenSourceNames.add(mangaParserAnn.name)
                            }
                        }
                        
                        val brokenKototoro = clazz.getAnnotation(org.skepsun.kototoro.parsers.Broken::class.java)
                        if (brokenKototoro != null) {
                            val contentParserAnn = clazz.getAnnotation(org.skepsun.kototoro.parsers.ContentSourceParser::class.java)
                            if (contentParserAnn != null) {
                                brokenSourceNames.add(contentParserAnn.name)
                            }
                        }
                    } catch (e: Throwable) {
                        // ignore class loading errors
                    }
                }
                dexFile.close()
            } catch (e: Throwable) {
                android.util.Log.e("KototoroInit", "Failed to parse DexFile for broken sources: ${e.message}", e)
            }

            // Try Kotatsu Parser Architecture
            try {
                val factoryClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
                val newParserMethod = findParserFactoryMethod(factoryClass, MangaLoaderContext::class.java)
                val fallbackSourceClass = tryFindEnumClass(dexClassLoader, "org.koitharu.kotatsu.parsers.model.MangaParserSource")
                val sources = resolveFactorySources<MangaSource>(newParserMethod, fallbackSourceClass)

                if (newParserMethod != null && sources.isNotEmpty()) {
                    plugins.add(LoadedJarPlugin(jarFile.name, dexClassLoader, ParserPluginArchitecture.KOTATSU, newParserMethod, sources, brokenSourceNames))
                    android.util.Log.i("KototoroInit", "Loaded ${jarFile.name} architecture=KOTATSU sources=${sources.size}")
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
                    plugins.add(LoadedJarPlugin(jarFile.name, dexClassLoader, ParserPluginArchitecture.KOTOTORO, newParserMethod, sources, brokenSourceNames))
                    android.util.Log.i("KototoroInit", "Loaded ${jarFile.name} architecture=KOTOTORO sources=${sources.size}")
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
                    plugins.add(LoadedJarPlugin(jarFile.name, dexClassLoader, ParserPluginArchitecture.TSUKI, newParserMethod, sources, brokenSourceNames))
                    android.util.Log.i("KototoroInit", "Loaded ${jarFile.name} architecture=TSUKI sources=${sources.size}")
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
        return plugins
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
