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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A source a plugin declares; [source] is its wire identity, the rest is presentation and provenance. */
data class ParserSourceInfo(
    val source: SourceRef,
    val title: String,
    val pluginId: String,
    val architecture: ParserPluginArchitecture,
    /** The plugin marks this parser `@Broken`; Android keeps it installed and lets the user show it on demand. */
    val broken: Boolean = false,
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
 *
 * With a [catalogDirectory], each verified plugin's source list is cached under its SHA-256. A later [load] of the
 * same artifact registers the sources from that catalog without opening the JAR; the plugin is hashed, inspected and
 * class-loaded when one of its sources is first called. Sources that are never used (switched off in the app) never
 * load their plugin, which keeps a large plugin's thousand-source enum off the start-up path.
 */
class ParserPluginRegistry(
    private val platform: ParserPlatform,
    private val priority: List<String> = DEFAULT_PRIORITY,
    private val inspector: ParserPluginInspector = ParserPluginInspector(),
    private val hostLoader: ClassLoader = ParserPluginRegistry::class.java.classLoader,
    private val catalogDirectory: Path? = null,
) : Closeable {

    private class Classes(
        val loader: ParserPluginClassLoader,
        val factory: Method,
        val constants: Map<String, Any>,
    )

    /** [classes] is null until the first call when the sources came from a catalog. */
    private class Plugin(val info: LoadedParserPlugin, val path: Path, @Volatile var classes: Classes?) {
        var activeCalls = 0
        var retired = false
        val parsers = HashMap<String, ContentParser>()

        /** Closes the class loader once nothing runs on a retired plugin; callers hold the registry lock. */
        fun closeIfIdle() {
            if (retired && activeCalls == 0) synchronized(this) { classes?.loader?.close() }
        }
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

    /**
     * Runs [block] with the parser for [sourceName]; the plugin stays loaded until [block] returns. A plugin known
     * only from its catalog is loaded here, outside the registry lock, so other plugins keep serving meanwhile.
     */
    suspend fun <T> withParser(sourceName: String, block: suspend (ParserHandle) -> T): T {
        val plugin = acquire(sourceName)
        try {
            val classes = classesOf(plugin)
            val info = plugin.info.sources.first { it.source.name == sourceName }
            val handle = ParserHandle(parserOf(plugin, classes, sourceName), info)
            return withContext(ContextLoaderElement(classes.loader)) { block(handle) }
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
        val previous = plugins[id]
        if (previous != null && !replace) throw ParserPluginException(ParserPluginFailure.ALREADY_LOADED)
        // An install or update always builds and validates the artifact in full; a start-up load may use the catalog.
        val cached = expectedSha256?.let(::readCatalog)?.takeIf { !replace }
        val plugin = if (cached != null) {
            Plugin(LoadedParserPlugin(id, cached.first, cached.second.map { it.copy(pluginId = id) }), path, null)
        } else {
            val metadata = inspector.inspect(path)
            expectedSha256?.let { inspector.verify(metadata, it) }
            val (classes, sources) = open(path, metadata)
            Plugin(LoadedParserPlugin(id, metadata, sources.map { it.copy(pluginId = id) }), path, classes)
        }
        try {
            // The caller's own failure (e.g. persisting the install record) is not a plugin construction failure.
            beforePublish(plugin.info)
        } catch (error: Throwable) {
            try { plugin.classes?.loader?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        if (cached == null) writeCatalog(plugin.info)
        plugins[id] = plugin
        previous?.let(::retire)
        recompute()
        return plugin.info
    }

    /** Class-loads an inspected plugin and enumerates its sources; any failure closes its loader. */
    private fun open(path: Path, metadata: ParserPluginMetadata): Pair<Classes, List<ParserSourceInfo>> {
        val loader = ParserPluginClassLoader(path.toAbsolutePath().toUri().toURL(), hostLoader)
        return try {
            withContextLoader(loader) { build(metadata, path, loader) }
        } catch (error: Throwable) {
            try { loader.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            val cause = if (error is InvocationTargetException) error.targetException else error
            when (cause) {
                is ParserPluginException -> throw cause
                is Exception, is LinkageError -> throw ParserPluginException(ParserPluginFailure.CONSTRUCTION_FAILED, cause)
                else -> throw cause
            }
        }
    }

    /**
     * The classes of a plugin registered from its catalog, loaded on its first call. The artifact must still have the
     * catalogued hash and architecture; a failure is reported to that call and the next call tries again.
     */
    private fun classesOf(plugin: Plugin): Classes = plugin.classes ?: synchronized(plugin) {
        plugin.classes ?: run {
            val start = System.nanoTime()
            val metadata = inspector.inspect(plugin.path)
            inspector.verify(metadata, plugin.info.metadata.sha256)
            if (metadata.architecture != plugin.info.metadata.architecture) {
                throw ParserPluginException(ParserPluginFailure.UNSUPPORTED_ARCHITECTURE)
            }
            open(plugin.path, metadata).first.also {
                plugin.classes = it
                System.err.println("[source-host] plugin ${plugin.info.id} (on first use): ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        }
    }

    private fun build(metadata: ParserPluginMetadata, path: Path, loader: ParserPluginClassLoader): Pair<Classes, List<ParserSourceInfo>> {
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
        val broken = brokenSourceNames(path, loader)
        val sources = constants.values.map { constant ->
            val source = contentSource(architecture, constant)
            ParserSourceInfo(SourceRef(source.name, source.locale, source.contentType.name), titleOf(source, constant), "",
                architecture, broken = source.name in broken)
        }
        return Classes(loader, factory, constants) to sources
    }

    /**
     * Android's `@Broken` markers, read from the plugin's own classes instead of a DEX scan.
     *
     * Android loads every class of the extension and caches the annotated source names per file; here the JAR entries
     * are searched for the annotation descriptor first, so only the handful of annotated parsers are class-loaded.
     * The annotation itself is the host's copy (the shared ABI is parent-first), so the comparison is by identity.
     */
    private fun brokenSourceNames(path: Path, loader: ParserPluginClassLoader): Set<String> {
        val names = mutableSetOf<String>()
        try {
            JarFile(path.toFile()).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    if (!bytes.containsBytes(BROKEN_DESCRIPTOR) && !bytes.containsBytes(KOTATSU_BROKEN_DESCRIPTOR)) {
                        continue
                    }
                    val type = try {
                        loader.loadClass(entry.name.removeSuffix(".class").replace('/', '.'))
                    } catch (_: Throwable) {
                        continue // A class that cannot load is its own problem; the scan keeps going.
                    }
                    val name = try {
                        val kototoro = type.getAnnotation(org.skepsun.kototoro.parsers.Broken::class.java) != null
                        val kotatsu = type.getAnnotation(org.koitharu.kotatsu.parsers.Broken::class.java) != null
                        if (!kototoro && !kotatsu) null else {
                            type.getAnnotation(org.skepsun.kototoro.parsers.ContentSourceParser::class.java)?.name
                                ?: type.getAnnotation(org.koitharu.kotatsu.parsers.MangaSourceParser::class.java)?.name
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (name != null) names += name
                }
            }
        } catch (_: Exception) {
            // A jar that cannot be enumerated has no broken markers to report; the load itself already failed elsewhere.
        }
        return names
    }

    // Catalog: a header line, then one tab-separated line per source (name, locale, content type, title).

    private fun catalogFile(sha256: String): Path? = catalogDirectory?.resolve("${sha256.lowercase()}.catalog")

    private fun readCatalog(sha256: String): Pair<ParserPluginMetadata, List<ParserSourceInfo>>? {
        val file = catalogFile(sha256)?.takeIf { Files.isRegularFile(it) } ?: return null
        return try {
            val lines = Files.readAllLines(file)
            val header = lines.first().split('\t')
            if (header.size != 6 || header[0] != CATALOG_MAGIC || header[1] != CATALOG_VERSION ||
                !header[3].equals(sha256, ignoreCase = true)) return null
            val architecture = ParserPluginArchitecture.valueOf(header[2])
            val metadata = ParserPluginMetadata(architecture, header[3], header[4].toInt(), header[5].toInt())
            val sources = lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val fields = line.split('\t').map(::unescape)
                require(fields.size == 5)
                ParserSourceInfo(SourceRef(fields[0], fields[1], fields[2]), fields[3], "", architecture,
                    broken = fields[4] == "1")
            }
            if (sources.isEmpty()) null else metadata to sources
        } catch (_: Exception) {
            null // A damaged catalog only costs a full load, which rewrites it.
        }
    }

    private fun writeCatalog(plugin: LoadedParserPlugin) {
        val file = catalogFile(plugin.metadata.sha256) ?: return
        try {
            Files.createDirectories(file.parent)
            val text = buildString {
                val metadata = plugin.metadata
                append(listOf(CATALOG_MAGIC, CATALOG_VERSION, metadata.architecture.name, metadata.sha256,
                    metadata.classCount, metadata.maximumClassVersion).joinToString("\t")).append('\n')
                for (info in plugin.sources) {
                    append(listOf(info.source.name, info.source.locale, info.source.contentType, info.title,
                        if (info.broken) "1" else "0").joinToString("\t", transform = ::escape)).append('\n')
                }
            }
            val temporary = Files.createTempFile(file.parent, "catalog-", ".tmp")
            try {
                Files.writeString(temporary, text)
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: Exception) {
            // Best effort: without a catalog the next start loads the plugin in full, as before.
        }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\' || index == value.length) { append(char); continue }
            when (val next = value[index++]) {
                't' -> append('\t')
                'n' -> append('\n')
                else -> append(next)
            }
        }
    }

    /** True when [marker] appears anywhere in the class file bytes; a cheap pre-filter before class loading. */
    private fun ByteArray.containsBytes(marker: ByteArray): Boolean {
        if (marker.isEmpty() || size < marker.size) return false
        outer@ for (start in 0..size - marker.size) {
            for (index in marker.indices) {
                if (this[start + index] != marker[index]) continue@outer
            }
            return true
        }
        return false
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
    private fun acquire(sourceName: String): Plugin {
        requireOpen()
        val plugin = resolved[sourceName] ?: throw SourceUnavailableException(sourceName)
        plugin.activeCalls++
        return plugin
    }

    /** A parser asking for a sibling source (e.g. a mirror site); the caller's own plugin lease covers the call. */
    private fun siblingParser(name: String): ContentParser {
        val plugin = synchronized(this) { resolved[name] } ?: throw SourceUnavailableException(name)
        return parserOf(plugin, classesOf(plugin), name)
    }

    private fun parserOf(plugin: Plugin, classes: Classes, name: String): ContentParser = synchronized(plugin.parsers) {
        plugin.parsers.getOrPut(name) { create(plugin, classes, name) }
    }

    private fun create(plugin: Plugin, classes: Classes, name: String): ContentParser {
        if (classes.constants[name] == null) throw SourceUnavailableException(name)
        return withContextLoader(classes.loader) {
            try {
                when (plugin.info.metadata.architecture) {
                    ParserPluginArchitecture.KOTOTORO -> instantiate(classes, name, context) as ContentParser
                    ParserPluginArchitecture.KOTATSU -> {
                        val adapter = KotatsuLoaderContextAdapter(context) { source, ctx -> instantiate(classes, source.name, ctx) as KTMangaParser }
                        val raw = instantiate(classes, name, adapter) as KTMangaParser
                        KotatsuContentParserAdapter(raw, KotatsuParserSource(classes.constants.getValue(name) as KTMangaSource), context)
                    }
                    ParserPluginArchitecture.TSUKI -> {
                        val adapter = TsukiLoaderContextAdapter(
                            context,
                            { classes.constants.values.filterIsInstance<tsuki.model.MangaSource>() },
                        ) { source, ctx -> instantiate(classes, source.name, ctx) as tsuki.MangaParser }
                        val raw = instantiate(classes, name, adapter) as tsuki.MangaParser
                        TsukiContentParserAdapter(raw, TsukiContentSource(classes.constants.getValue(name) as tsuki.model.MangaSource), context)
                    }
                }
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private fun instantiate(classes: Classes, name: String, context: Any): Any =
        classes.factory.invoke(null, classes.constants[name] ?: throw SourceUnavailableException(name), context)

    @Synchronized
    private fun release(plugin: Plugin) {
        plugin.activeCalls--
        plugin.closeIfIdle()
    }

    private fun retire(plugin: Plugin) {
        plugin.retired = true
        plugin.closeIfIdle()
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
        /** Short ids (bundled / desktop) and the repository package names the same plugins install under. */
        val DEFAULT_PRIORITY = listOf(
            "kototoro-parsers", "org.skepsun.kototoro.parsers",
            "kotatsu-parsers-redo", "org.skepsun.kototoro.parsers.redo",
            "uma", "github.InvalidDavid.UMA.uma",
            "kotatsu-parsers",
        )
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private const val CATALOG_MAGIC = "kototoro-parser-catalog"
        /** 2 adds the per-source `broken` column; a v1 catalog is rewritten by the next full load. */
        private const val CATALOG_VERSION = "2"
        private val BROKEN_DESCRIPTOR = "Lorg/skepsun/kototoro/parsers/Broken;".toByteArray()
        private val KOTATSU_BROKEN_DESCRIPTOR = "Lorg/koitharu/kotatsu/parsers/Broken;".toByteArray()
    }
}
