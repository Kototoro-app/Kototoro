package org.skepsun.kototoro.cloudstream.runtime

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.adaptive.resolveUiPresentationConfig
import org.skepsun.kototoro.core.network.webview.CloudflareSolveCoordinator
import org.skepsun.kototoro.core.network.webview.WebViewClearanceSolver
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import java.io.File
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe

@Singleton
class CloudstreamRuntimeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ContentHttpClient private val contentHttpClient: OkHttpClient,
    private val webViewExecutor: WebViewExecutor? = null,
    private val settings: AppSettings,
    private val solveCoordinator: CloudflareSolveCoordinator,
    private val clearanceSolver: WebViewClearanceSolver,
) {

    private val loadedPlugins = ConcurrentHashMap<String, LoadedCloudstreamPlugin>()
    private val _sources = MutableStateFlow<List<CloudstreamSource>>(emptyList())
    val sources: StateFlow<List<CloudstreamSource>> = _sources.asStateFlow()
    private val diagnosticsFile by lazy(LazyThreadSafetyMode.NONE) {
        File(File(context.filesDir, "cloudstream"), "runtime-diagnostics.txt")
    }
    private val preparedPluginsDir by lazy(LazyThreadSafetyMode.NONE) {
        File(context.codeCacheDir, "cloudstream/plugins")
    }
    private val optimizedDexDir by lazy(LazyThreadSafetyMode.NONE) {
        File(context.codeCacheDir, "cloudstream/dex")
    }

    @Synchronized
    fun initialize() {
        writeDiagnostic("initialize:start")
        configureCloudstreamEnvironment()
        val pluginsDir = File(File(context.filesDir, "cloudstream"), "plugins").apply { mkdirs() }
        val pluginFiles = pluginsDir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("cs3", ignoreCase = true) || it.extension.equals("zip", ignoreCase = true)) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        writeDiagnostic(
            "initialize:plugins dir=${pluginsDir.absolutePath} count=${pluginFiles.size} files=${
                pluginFiles.joinToString(",") { "${it.name}:${it.length()}" }
            }",
        )

        val activePaths = pluginFiles.mapTo(HashSet(pluginFiles.size)) { it.absolutePath }
        loadedPlugins.keys
            .filter { it !in activePaths }
            .forEach(::unloadPlugin)

        pluginFiles.forEach { file ->
            val compatibility = CloudstreamPluginCompatibilityChecker.inspect(file, context.classLoader)
            if (compatibility is CloudstreamPluginCompatibility.Incompatible) {
                quarantinePlugin(file, compatibility.reason)
                return@forEach
            }
            runCatching { loadPlugin(file) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load Cloudstream plugin ${file.name}", error)
                    writeDiagnostic("load:failed file=${file.name} error=${error.javaClass.name}:${error.message}")
                    quarantinePlugin(
                        file = file,
                        reason = "Plugin load failed: ${error.javaClass.name}:${error.message}",
                    )
                }
        }
        publishSources()
        writeDiagnostic(
            "initialize:done loaded=${loadedPlugins.size} sources=${_sources.value.joinToString(",") { "${it.pluginPackageName}/${it.name}" }}",
        )
    }

    private fun quarantinePlugin(
        file: File,
        reason: String,
    ) {
        unloadPlugin(file.absolutePath)
        val quarantineDir = File(File(context.filesDir, "cloudstream"), "quarantine").apply { mkdirs() }
        val quarantineFile = File(
            quarantineDir,
            "${file.nameWithoutExtension}-${file.runtimeSignatureHash()}.${file.extension}.incompatible",
        )
        val moved = file.renameTo(quarantineFile)
        Log.e(
            TAG,
            "Rejected incompatible Cloudstream plugin ${file.name}: $reason; quarantined=$moved",
        )
        writeDiagnostic(
            "load:rejected file=${file.name} reason=$reason " +
                "quarantined=$moved target=${quarantineFile.absolutePath}",
        )
    }

    private fun configureCloudstreamEnvironment() {
        setContext(WeakReference(context as Any))
        CloudStreamApp.context = context
        val cloudstreamUserAgent = com.lagradost.cloudstream3.USER_AGENT
        CloudstreamRequestContext.userAgent = cloudstreamUserAgent
        app.baseClient = contentHttpClient.newBuilder()
            .apply {
                interceptors().add(0, CloudstreamRequestContext.interceptor())
                webViewExecutor?.let {
                    interceptors().add(
                        1,
                        CloudstreamCloudflareInterceptor(it, settings, solveCoordinator, clearanceSolver),
                    )
                }
            }
            .build()
        app.defaultHeaders = mapOf("User-Agent" to cloudstreamUserAgent)
        // As the official app does at start-up: the library's YouTube extractor and YouTube plugins need it (#608).
        NewPipe.init(CloudstreamNewPipeDownloader(contentHttpClient))
        // Plugins adapt their dialogs with `Globals.isLayout(TV)`; answer with the app's own presentation mode.
        Globals.layoutProvider = {
            if (resolveUiPresentationConfig(context, settings).isTv) Globals.TV else Globals.PHONE
        }
        writeDiagnostic(
            "network:configured interceptors=${contentHttpClient.interceptors.joinToString(",") { it::class.java.simpleName }} " +
                "networkInterceptors=${contentHttpClient.networkInterceptors.joinToString(",") { it::class.java.simpleName }} " +
                "userAgent=${cloudstreamUserAgent.take(120)}",
        )
    }

    fun findSourceByName(name: String): CloudstreamSource? {
        return _sources.value.firstOrNull { it.name == name }
    }

    /** Activate before committing install metadata; failed updates restore the previous archive and runtime. */
    @Synchronized
    fun installPlugin(download: File, archiveName: String, packageName: String) {
        require(archiveName.isNotBlank() && File(archiveName).name == archiveName) { "Invalid Cloudstream archive name" }
        val directory = File(File(context.filesDir, "cloudstream"), "plugins").apply { mkdirs() }
        val target = File(directory, archiveName)
        configureCloudstreamEnvironment()
        try {
            replaceCloudstreamPluginArchive(download, target) {
                loadPlugin(target, packageName)
                check(loadedPlugins.containsKey(target.absolutePath)) { "Cloudstream plugin did not load: $archiveName" }
            }
        } catch (error: Throwable) {
            // File rollback completes before rebuilding the previous runtime. Keep the original failure visible.
            runCatching { initialize() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        publishSources()
    }

    private fun loadPlugin(file: File, packageName: String = file.inferPackageName()) {
        val manifest = requireNotNull(readManifest(file)) { "Missing Cloudstream plugin manifest: ${file.name}" }
        writeDiagnostic(
            "load:start file=${file.name} class=${manifest.pluginClassName} version=${manifest.version} requiresResources=${manifest.requiresResources}",
        )
        val existing = loadedPlugins[file.absolutePath]
        if (existing != null && existing.signature == file.runtimeSignature()) {
            writeDiagnostic("load:skip-unchanged file=${file.name}")
            return
        }
        if (existing != null) {
            unloadPlugin(file.absolutePath)
        }

        val preparedFile = prepareRuntimeArchive(file)
        val loader = DexClassLoader(
            preparedFile.absolutePath,
            optimizedDexDir.absolutePath,
            null,
            context.classLoader,
        )
        val pluginClass = loader.loadClass(manifest.pluginClassName)
        val plugin = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
        val providerCountBefore = synchronized(APIHolder.allProviders) { APIHolder.allProviders.size }
        val extractorCountBefore = synchronized(extractorApis) { extractorApis.size }
        plugin.filename = preparedFile.absolutePath
        val providers = try {
            if (manifest.requiresResources && plugin is Plugin) {
                plugin.resources = loadPluginResources(preparedFile)
            }
            if (plugin is Plugin) {
                plugin.load(context)
            } else {
                plugin.load()
            }
            synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filter { it.sourcePlugin == plugin.filename }
            }.also { apis -> apis.forEach(MainAPI::init) }
        } catch (error: Throwable) {
            // A plugin can register providers/extractors before throwing from load() or init().
            removePluginRegistrations(plugin.filename)
            runCatching { plugin.beforeUnload() }
            preparedFile.delete()
            throw error
        }
        val registeredExtractors = synchronized(extractorApis) {
            extractorApis.filter { it.sourcePlugin == plugin.filename }
        }
        loadedPlugins[file.absolutePath] = LoadedCloudstreamPlugin(
            filePath = file.absolutePath,
            plugin = plugin,
            classLoader = loader,
            signature = file.runtimeSignature(),
            preparedFilePath = preparedFile.absolutePath,
            providers = providers,
            extractors = registeredExtractors,
            manifest = manifest,
            packageName = packageName,
        )
        Log.i(
            TAG,
            "Loaded Cloudstream plugin=${file.name} providers=${providers.size} extractors=${registeredExtractors.size} " +
                "providersBefore=$providerCountBefore extractorsBefore=$extractorCountBefore",
        )
        writeDiagnostic(
            "load:success file=${file.name} providers=${providers.joinToString(",") { it.name }} extractors=${registeredExtractors.size}",
        )
    }

    private fun unloadPlugin(filePath: String) {
        val loaded = loadedPlugins.remove(filePath) ?: return
        writeDiagnostic("unload:start file=${File(filePath).name}")
        runCatching { loaded.plugin.beforeUnload() }
            .onFailure { Log.w(TAG, "Cloudstream plugin beforeUnload failed for ${loaded.filePath}", it) }

        removePluginRegistrations(loaded.plugin.filename)
        File(loaded.preparedFilePath).delete()
        writeDiagnostic("unload:done file=${File(filePath).name}")
    }

    private fun removePluginRegistrations(filename: String?) {
        if (filename == null) return
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.filter { it.sourcePlugin == filename }.forEach(APIHolder::removePluginMapping)
            APIHolder.allProviders.removeIf { it.sourcePlugin == filename }
        }
        synchronized(extractorApis) {
            extractorApis.removeIf { it.sourcePlugin == filename }
        }
        synchronized(VideoClickActionHolder.allVideoClickActions) {
            VideoClickActionHolder.allVideoClickActions.removeIf { it.sourcePlugin == filename }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadPluginResources(pluginFile: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val cookie = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
            .invoke(assets, pluginFile.absolutePath) as Int
        check(cookie != 0) { "Unable to add Cloudstream plugin resources: ${pluginFile.absolutePath}" }
        return Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
    }

    private fun publishSources() {
        _sources.value = loadedPlugins.values
            .asSequence()
            .flatMap { loaded ->
                loaded.providers.asSequence().map { api ->
                    CloudstreamSource(
                        api = api,
                        pluginFileName = File(loaded.filePath).name,
                        pluginPackageName = loaded.packageName,
                    )
                }
            }
            .distinctBy { it.name }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    private fun readManifest(file: File): PluginManifest? {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json")
            if (entry != null) {
                zip.getInputStream(entry).use { stream ->
                    InputStreamReader(stream).use { reader ->
                        val json = JSONObject(reader.readText())
                        val pluginClassName = json.optString("pluginClassName").takeIf { it.isNotBlank() } ?: return null
                        return PluginManifest(
                            name = json.optString("name").takeIf { it.isNotBlank() },
                            pluginClassName = pluginClassName,
                            version = json.optInt("version", 0),
                            requiresResources = json.optBoolean("requiresResources", false),
                        )
                    }
                }
            }
        }
        writeDiagnostic("manifest:missing file=${file.name}")
        return null
    }

    private fun prepareRuntimeArchive(sourceFile: File): File {
        preparedPluginsDir.mkdirs()
        optimizedDexDir.mkdirs()
        val preparedFile = File(preparedPluginsDir, sourceFile.nameWithoutExtension + "-" + sourceFile.runtimeSignatureHash() + ".cs3")
        if (!preparedFile.exists()) {
            preparedPluginsDir.listFiles()
                ?.filter { it.name.startsWith("${sourceFile.nameWithoutExtension}-") && it.name != preparedFile.name }
                ?.forEach(File::delete)
            sourceFile.copyTo(preparedFile, overwrite = true)
        }
        if (!preparedFile.setWritable(false, false) && !preparedFile.setReadOnly()) {
            throw IllegalStateException("Unable to mark Cloudstream plugin as read-only: ${preparedFile.absolutePath}")
        }
        writeDiagnostic(
            "prepare:file source=${sourceFile.name} prepared=${preparedFile.absolutePath} writable=${preparedFile.canWrite()}",
        )
        return preparedFile
    }

    private fun writeDiagnostic(message: String) {
        runCatching {
            diagnosticsFile.parentFile?.mkdirs()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            diagnosticsFile.appendText("$timestamp $message\n")
        }
    }

    private fun File.inferPackageName(): String {
        val prefs = context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
        return prefs.all.entries.firstOrNull { (key, value) ->
            key.endsWith(":archive") && value == name
        }?.key?.substringBefore(":archive")
            ?: nameWithoutExtension
    }

    private fun File.runtimeSignature(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.runtimeSignatureHash(): String {
        return runtimeSignature().take(16)
    }

    private data class PluginManifest(
        val name: String?,
        val pluginClassName: String,
        val version: Int,
        val requiresResources: Boolean,
    )

    private data class LoadedCloudstreamPlugin(
        val filePath: String,
        val plugin: BasePlugin,
        val classLoader: BaseDexClassLoader,
        val signature: String,
        val preparedFilePath: String,
        val providers: List<MainAPI>,
        val extractors: List<ExtractorApi>,
        val manifest: PluginManifest,
        val packageName: String,
    )

    companion object {
        private const val TAG = "CloudstreamRuntime"
    }
}
