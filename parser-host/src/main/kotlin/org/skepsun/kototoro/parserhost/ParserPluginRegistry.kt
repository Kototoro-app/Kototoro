package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.MangaParser as KTMangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource as KTMangaSource
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceUnavailableException
import org.skepsun.kototoro.parserhost.kotatsu.KotatsuContentParserAdapter
import org.skepsun.kototoro.parserhost.kotatsu.KotatsuLoaderContextAdapter
import org.skepsun.kototoro.parserhost.kotatsu.KotatsuParserSource
import org.skepsun.kototoro.parserhost.tsuki.TsukiContentParserAdapter
import org.skepsun.kototoro.parserhost.tsuki.TsukiContentSource
import org.skepsun.kototoro.parserhost.tsuki.TsukiLoaderContextAdapter
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.ContentSource
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Path
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A source a plugin declares; [source] is its wire identity, the rest is presentation and provenance. */
data class ParserSourceInfo(
    val source: SourceRef,
    val title: String,
    val pluginId: String,
    val architecture: ParserPluginArchitecture,
)

data class LoadedParserPlugin(
    val id: String,
    val metadata: ParserPluginMetadata,
    val sources: List<ParserSourceInfo>,
)

/** A source resolved for one call; valid until the call's lease ends. */
class ParserHandle internal constructor(val parser: ContentParser, val info: ParserSourceInfo)

/**
 * Loads plugin JARs into isolated class loaders and resolves source names to live parsers.
 *
 * Several plugins may declare the same source (kototoro-parsers and kotatsu-parsers share most sites); [priority]
 * decides which plugin serves a name, exactly as `jar_priority_order` does on Android. A plugin that is replaced
 * or unloaded keeps its class loader open until the calls already running on it finish.
 */
class ParserPluginRegistry(
    private val platform: ParserPlatform,
    private val priority: List<String> = DEFAULT_PRIORITY,
    private val inspector: ParserPluginInspector = ParserPluginInspector(),
    private val hostLoader: ClassLoader = ParserPluginRegistry::class.java.classLoader,
) : Closeable {

    private class Plugin(
        val info: LoadedParserPlugin,
        val loader: ParserPluginClassLoader,
        val factory: Method,
        val constants: Map<String, Any>,
    ) {
        var activeCalls = 0
        var retired = false
        val parsers = HashMap<String, ContentParser>()
    }

    private val plugins = linkedMapOf<String, Plugin>()
    private var resolved: Map<String, Plugin> = emptyMap()
    private var resolvedInfos: List<ParserSourceInfo> = emptyList()
    private val context = ParserLoaderContext(platform, ::siblingParser)
    private var closed = false

    @Synchronized
    fun load(path: Path, id: String, expectedSha256: String? = null): LoadedParserPlugin =
        install(path, id, expectedSha256, replace = false) {}

    /** Builds and validates before replacing; a failed [beforePublish] leaves the previous plugin serving. */
    @Synchronized
    fun replace(path: Path, id: String, expectedSha256: String? = null, beforePublish: (LoadedParserPlugin) -> Unit): LoadedParserPlugin =
        install(path, id, expectedSha256, replace = true, beforePublish)

    @Synchronized
    fun installed(): List<LoadedParserPlugin> {
        requireOpen()
        return plugins.values.map { it.info }
    }

    /** One entry per source name, after priority resolution. */
    @Synchronized
    fun sources(): List<ParserSourceInfo> {
        requireOpen()
        return resolvedInfos
    }

    @Synchronized
    fun unload(id: String): Boolean {
        requireOpen()
        val plugin = plugins.remove(id) ?: return false
        retire(plugin)
        recompute()
        return true
    }

    /** Runs [block] with the parser for [sourceName]; the plugin stays loaded until [block] returns. */
    suspend fun <T> withParser(sourceName: String, block: suspend (ParserHandle) -> T): T {
        val (plugin, handle) = acquire(sourceName)
        try {
            return withContext(ContextLoaderElement(plugin.loader)) { block(handle) }
        } finally {
            release(plugin)
        }
    }

    /** True when [sourceName] is currently served by some plugin. */
    @Synchronized
    fun owns(sourceName: String): Boolean = !closed && sourceName in resolved

    /** The plugin that currently serves [sourceName] after priority resolution. */
    @Synchronized
    fun pluginFor(sourceName: String): LoadedParserPlugin? = if (closed) null else resolved[sourceName]?.info

    private fun install(
        path: Path,
        id: String,
        expectedSha256: String?,
        replace: Boolean,
        beforePublish: (LoadedParserPlugin) -> Unit,
    ): LoadedParserPlugin {
        requireOpen()
        require(id.matches(ID_PATTERN)) { "Invalid plugin id" }
        val metadata = inspector.inspect(path)
        expectedSha256?.let { inspector.verify(metadata, it) }
        val previous = plugins[id]
        if (previous != null && !replace) throw ParserPluginException(ParserPluginFailure.ALREADY_LOADED)
        val loader = ParserPluginClassLoader(path.toAbsolutePath().toUri().toURL(), hostLoader)
        val plugin = try {
            withContextLoader(loader) { build(id, metadata, loader) }
        } catch (error: Throwable) {
            try { loader.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            val cause = if (error is InvocationTargetException) error.targetException else error
            when (cause) {
                is ParserPluginException -> throw cause
                is Exception, is LinkageError -> throw ParserPluginException(ParserPluginFailure.CONSTRUCTION_FAILED, cause)
                else -> throw cause
            }
        }
        try {
            // The caller's own failure (e.g. persisting the install record) is not a plugin construction failure.
            beforePublish(plugin.info)
        } catch (error: Throwable) {
            try { loader.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        plugins[id] = plugin
        previous?.let(::retire)
        recompute()
        return plugin.info
    }

    private fun build(id: String, metadata: ParserPluginMetadata, loader: ParserPluginClassLoader): Plugin {
        val architecture = metadata.architecture
        val factoryClass = loader.loadClass(architecture.factoryClass)
        val contextClass = loader.loadClass(architecture.contextClass)
        val factory = factoryClass.declaredMethods.firstOrNull { method ->
            method.name.startsWith("newParser") && method.parameterTypes.size == 2 && method.parameterTypes[1] == contextClass
        } ?: throw ParserPluginException(ParserPluginFailure.UNSUPPORTED_ARCHITECTURE)
        factory.isAccessible = true
        val enumClass = factory.parameterTypes[0]
        if (!enumClass.isEnum) throw ParserPluginException(ParserPluginFailure.NO_SOURCES)
        val constants = enumClass.enumConstants.orEmpty().associateBy { (it as Enum<*>).name }
        if (constants.isEmpty()) throw ParserPluginException(ParserPluginFailure.NO_SOURCES)
        val sources = constants.values.map { constant ->
            val source = contentSource(architecture, constant)
            ParserSourceInfo(SourceRef(source.name, source.locale, source.contentType.name), titleOf(source, constant), id, architecture)
        }
        return Plugin(LoadedParserPlugin(id, metadata, sources), loader, factory, constants)
    }

    private fun contentSource(architecture: ParserPluginArchitecture, constant: Any): ContentSource = when (architecture) {
        ParserPluginArchitecture.KOTOTORO -> constant as ContentSource
        ParserPluginArchitecture.KOTATSU -> KotatsuParserSource(constant as KTMangaSource)
        ParserPluginArchitecture.TSUKI -> TsukiContentSource(constant as tsuki.model.MangaSource)
    }

    private fun titleOf(source: ContentSource, constant: Any): String = when (source) {
        is KotatsuParserSource -> source.title
        is TsukiContentSource -> source.title
        else -> try {
            constant.javaClass.getMethod("getTitle").invoke(constant) as? String
        } catch (_: ReflectiveOperationException) {
            null
        } ?: source.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun recompute() {
        val rank = priority.withIndex().associate { (index, name) -> name.lowercase() to index }
        val ordered = plugins.values.sortedWith(
            compareBy<Plugin>({ rank[it.info.id.lowercase()] ?: Int.MAX_VALUE }, { it.info.id.lowercase() }),
        )
        val map = linkedMapOf<String, Plugin>()
        val infos = mutableListOf<ParserSourceInfo>()
        for (plugin in ordered) {
            for (info in plugin.info.sources) {
                if (map.putIfAbsent(info.source.name, plugin) == null) infos += info
            }
        }
        resolved = map
        resolvedInfos = infos
    }

    @Synchronized
    private fun acquire(sourceName: String): Pair<Plugin, ParserHandle> {
        requireOpen()
        val plugin = resolved[sourceName] ?: throw SourceUnavailableException(sourceName)
        plugin.activeCalls++
        try {
            val info = plugin.info.sources.first { it.source.name == sourceName }
            return plugin to ParserHandle(parserOf(plugin, sourceName), info)
        } catch (error: Throwable) {
            release(plugin)
            throw error
        }
    }

    /** A parser asking for a sibling source (e.g. a mirror site); the caller's own plugin lease covers the call. */
    @Synchronized
    private fun siblingParser(name: String): ContentParser {
        val plugin = resolved[name] ?: throw SourceUnavailableException(name)
        return parserOf(plugin, name)
    }

    private fun parserOf(plugin: Plugin, name: String): ContentParser = synchronized(plugin.parsers) {
        plugin.parsers.getOrPut(name) { create(plugin, name) }
    }

    private fun create(plugin: Plugin, name: String): ContentParser {
        if (plugin.constants[name] == null) throw SourceUnavailableException(name)
        return withContextLoader(plugin.loader) {
            try {
                when (plugin.info.metadata.architecture) {
                    ParserPluginArchitecture.KOTOTORO -> instantiate(plugin, name, context) as ContentParser
                    ParserPluginArchitecture.KOTATSU -> {
                        val adapter = KotatsuLoaderContextAdapter(context) { source, ctx -> instantiate(plugin, source.name, ctx) as KTMangaParser }
                        val raw = instantiate(plugin, name, adapter) as KTMangaParser
                        KotatsuContentParserAdapter(raw, KotatsuParserSource(plugin.constants.getValue(name) as KTMangaSource), context)
                    }
                    ParserPluginArchitecture.TSUKI -> {
                        val adapter = TsukiLoaderContextAdapter(
                            context,
                            { plugin.constants.values.filterIsInstance<tsuki.model.MangaSource>() },
                        ) { source, ctx -> instantiate(plugin, source.name, ctx) as tsuki.MangaParser }
                        val raw = instantiate(plugin, name, adapter) as tsuki.MangaParser
                        TsukiContentParserAdapter(raw, TsukiContentSource(plugin.constants.getValue(name) as tsuki.model.MangaSource), context)
                    }
                }
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private fun instantiate(plugin: Plugin, name: String, context: Any): Any =
        plugin.factory.invoke(null, plugin.constants[name] ?: throw SourceUnavailableException(name), context)

    @Synchronized
    private fun release(plugin: Plugin) {
        plugin.activeCalls--
        if (plugin.retired && plugin.activeCalls == 0) plugin.loader.close()
    }

    private fun retire(plugin: Plugin) {
        plugin.retired = true
        if (plugin.activeCalls == 0) plugin.loader.close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        for (plugin in plugins.values) {
            try { retire(plugin) } catch (error: Exception) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        plugins.clear()
        resolved = emptyMap()
        resolvedInfos = emptyList()
        failure?.let { throw it }
    }

    private fun requireOpen() {
        if (closed) throw ParserPluginException(ParserPluginFailure.CLOSED)
    }

    private inline fun <T> withContextLoader(loader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        return try { block() } finally { thread.contextClassLoader = previous }
    }

    private class ContextLoaderElement(private val loader: ClassLoader) :
        ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ContextLoaderElement>

        override fun updateThreadContext(context: CoroutineContext): ClassLoader? {
            val thread = Thread.currentThread()
            return thread.contextClassLoader.also { thread.contextClassLoader = loader }
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
            Thread.currentThread().contextClassLoader = oldState
        }
    }

    companion object {
        /** Same default as the Android `jar_priority_order`. */
        val DEFAULT_PRIORITY = listOf("kototoro-parsers", "kotatsu-parsers-redo", "uma", "kotatsu-parsers")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
