package org.skepsun.kototoro.cloudstream.desktop

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamCatalog
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPlatform
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPluginCompatibility
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPluginCompatibilityChecker
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRequestScope
import org.skepsun.kototoro.core.source.SourceUnavailableException
import org.skepsun.kototoro.dex.DexJarConverter
import java.io.Closeable
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipFile

/** `manifest.json` of a `.cs3` plugin. */
data class CloudstreamPluginManifest(
    val name: String?,
    val pluginClassName: String,
    val version: Int,
    val requiresResources: Boolean,
) {
    companion object {
        /** The plugin's manifest; null when [path] is no Cloudstream plugin archive. */
        fun read(path: Path): CloudstreamPluginManifest? = try {
            ZipFile(path.toFile()).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                if (zip.getEntry("classes.dex") == null) return null
                val json = Json.parseToJsonElement(zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) })
                    .jsonObject
                val className = json["pluginClassName"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return null
                CloudstreamPluginManifest(
                    name = json["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
                    pluginClassName = className,
                    version = json["version"]?.jsonPrimitive?.intOrNull ?: 0,
                    requiresResources = json["requiresResources"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }
}

/** One loaded plugin and the providers it registered. */
class CloudstreamPlugin internal constructor(
    /** Stable identity: the repository's internal name, else the manifest name; part of every source name. */
    val id: String,
    val path: Path,
    val sha256: String,
    val manifest: CloudstreamPluginManifest,
    val sources: List<CloudstreamSource>,
    internal val plugin: BasePlugin,
    internal val loader: URLClassLoader,
)

/**
 * Cloudstream plugins on the Windows host. Each `.cs3` keeps Android's d8 output; its `classes.dex` is converted to
 * class files once (cached by content hash) and loaded in its own class loader whose parent holds the official
 * library, the shims and the Android compatibility runtime — the same objects Android's `CloudstreamRuntimeManager`
 * hands plugins. Plugins register their providers into the library's global `APIHolder`, as on Android.
 */
class CloudstreamPluginRegistry(
    private val cache: Path,
    context: Any?,
    client: OkHttpClient,
) : Closeable {
    private val plugins = ConcurrentHashMap<String, CloudstreamPlugin>()
    private val catalogs = ConcurrentHashMap<String, CloudstreamCatalog>()
    private val context = context as? Context

    init {
        CloudstreamDesktopEnvironment.install(this.context, client)
    }

    fun installed(): List<CloudstreamPlugin> = plugins.values.sortedBy { it.id.lowercase() }

    fun sources(): List<CloudstreamSource> = plugins.values.flatMap { it.sources }.sortedBy { it.displayName.lowercase() }

    fun owns(sourceName: String): Boolean = plugins.values.any { plugin -> plugin.sources.any { it.name == sourceName } }

    fun pluginFor(sourceName: String): CloudstreamPlugin? =
        plugins.values.firstOrNull { plugin -> plugin.sources.any { it.name == sourceName } }

    fun source(sourceName: String): CloudstreamSource =
        sources().firstOrNull { it.name == sourceName } ?: throw SourceUnavailableException(sourceName)

    /** The shared catalog of one provider; it keeps the provider's paging state, so it lives as long as the plugin. */
    fun catalog(sourceName: String): CloudstreamCatalog {
        val source = source(sourceName)
        return catalogs.compute(sourceName) { _, known ->
            known?.takeIf { it.source == source } ?: CloudstreamCatalog(source, DesktopCloudstreamPlatform)
        }!!
    }

    /** Loads (or reloads) the plugin at [path] as [id]; a broken or incompatible archive is rejected with a reason. */
    @Synchronized
    fun load(path: Path, id: String, sha256: String): CloudstreamPlugin {
        val manifest = CloudstreamPluginManifest.read(path) ?: throw IOException("不是有效的 Cloudstream 插件（.cs3）")
        val compatibility = CloudstreamPluginCompatibilityChecker.inspect(path.toFile(), javaClass.classLoader)
        if (compatibility is CloudstreamPluginCompatibility.Incompatible) {
            throw IOException("插件与当前 Cloudstream 运行时不兼容：${compatibility.reason}")
        }
        unload(id)
        Files.createDirectories(cache)
        val converted = cache.resolve("$sha256-v${DexJarConverter.VERSION}.jar")
        if (!Files.isRegularFile(converted)) DexJarConverter.convert(path, converted)
        val loader = URLClassLoader(arrayOf(converted.toUri().toURL()), javaClass.classLoader)
        try {
            val instance = loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance() as BasePlugin
            // Providers and extractors are attributed to this name; it must be unique per loaded plugin.
            instance.filename = path.toAbsolutePath().toString()
            if (instance is Plugin) instance.load(requireNotNull(context) { "Cloudstream 插件需要兼容运行时" })
            else instance.load()
            val providers = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filter { it.sourcePlugin == instance.filename }
            }
            providers.forEach(MainAPI::init)
            val loaded = CloudstreamPlugin(id, path, sha256, manifest,
                providers.map { CloudstreamSource(it, path.fileName.toString(), id) }.distinctBy { it.name },
                instance, loader)
            plugins[id] = loaded
            return loaded
        } catch (error: Throwable) {
            forget(instanceFilename = path.toAbsolutePath().toString())
            loader.close()
            throw if (error is IOException) error else IOException("插件加载失败：${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    @Synchronized
    fun unload(id: String): Boolean {
        val loaded = plugins.remove(id) ?: return false
        runCatching { loaded.plugin.beforeUnload() }
        loaded.sources.forEach { catalogs.remove(it.name) }
        loaded.sources.forEach { APIHolder.removePluginMapping(it.api) }
        forget(loaded.plugin.filename)
        loaded.loader.close()
        return true
    }

    private fun forget(instanceFilename: String?) {
        synchronized(APIHolder.allProviders) { APIHolder.allProviders.removeIf { it.sourcePlugin == instanceFilename } }
        synchronized(extractorApis) { extractorApis.removeIf { it.sourcePlugin == instanceFilename } }
    }

    override fun close() {
        plugins.keys.toList().forEach(::unload)
    }
}

/** Process-wide Cloudstream setup: the library's HTTP client and context are globals, as in the official app. */
internal object CloudstreamDesktopEnvironment {
    @Synchronized
    fun install(context: Context?, client: OkHttpClient) {
        context?.let { CloudStreamApp.context = it }
        CloudstreamRequestScope.userAgent = USER_AGENT
        // The platform client: shared cookies and the automatic Cloudflare solver, plus Cloudstream's source headers.
        app.baseClient = client.newBuilder().apply { interceptors().add(0, CloudstreamRequestScope.interceptor()) }.build()
        app.defaultHeaders = mapOf("User-Agent" to USER_AGENT)
    }
}

/** Windows logging for the shared catalog; the shared HTTP client clears challenges itself. */
internal object DesktopCloudstreamPlatform : CloudstreamPlatform {
    private val logger = Logger.getLogger("Cloudstream")
    override fun warn(message: String, error: Throwable?) = logger.log(Level.WARNING, message, error)
    override fun error(message: String, error: Throwable?) = logger.log(Level.SEVERE, message, error)
}
