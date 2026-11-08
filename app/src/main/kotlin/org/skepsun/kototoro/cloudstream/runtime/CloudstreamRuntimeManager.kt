package org.skepsun.kototoro.cloudstream.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.DefaultActivityLifecycleCallbacks
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
import kotlin.concurrent.thread
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
    private val deferredPluginPaths = LinkedHashSet<String>()

    init {
        // The backup agent builds its own manager, possibly without an Application; it never shows activities.
        val application = context.applicationContext as? Application
        if (application != null) {
            CloudstreamPluginGuard.install(pluginOf = ::pluginBehind, onRestartRequested = ::onPluginRestartRequested)
        }
        application?.registerActivityLifecycleCallbacks(
            object : DefaultActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is AppCompatActivity && synchronized(this@CloudstreamRuntimeManager) {
                            deferredPluginPaths.isNotEmpty()
                        }
                    ) {
                        thread(name = "cloudstream-deferred-plugins") { loadDeferredPlugins(activity) }
                    }
                }
            },
        )
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

        deferredPluginPaths.clear()
        pluginFiles.forEach { file ->
            val compatibility = CloudstreamPluginCompatibilityChecker.inspect(file, context.classLoader)
            if (compatibility is CloudstreamPluginCompatibility.Incompatible) {
                if (!compatibility.isLinkageGap) {
                    quarantinePlugin(file, compatibility.reason)
                    return@forEach
                }
                // Android links lazily: the plugin loads, and only code reaching the missing API fails.
                writeDiagnostic("load:linkage-gap file=${file.name} ${compatibility.reason}")
            }
            val host = pluginHost()
            runCatching { loadPlugin(file, host = host) }
                .onFailure { error ->
                    writeDiagnostic("load:failed file=${file.name} error=${error.javaClass.name}:${error.message}")
                    if (host is AppCompatActivity) {
                        Log.e(TAG, "Failed to load Cloudstream plugin ${file.name}", error)
                        quarantinePlugin(
                            file = file,
                            reason = "Plugin load failed: ${error.javaClass.name}:${error.message}",
                        )
                    } else {
                        // Plugins load with Cloudstream's activity and may cast to it (CNC Verse: `context as
                        // AppCompatActivity`); at cold start there is none yet, so retry once one resumes.
                        deferredPluginPaths += file.absolutePath
                        writeDiagnostic("load:deferred file=${file.name} until an activity resumes")
                    }
                }
        }
        publishSources()
        writeDiagnostic(
            "initialize:done loaded=${loadedPlugins.size} sources=${_sources.value.joinToString(",") { "${it.pluginPackageName}/${it.name}" }}",
        )
    }

    /** A plugin asked to restart the app to apply its settings: loading it again applies them (on the main thread). */
    private fun onPluginRestartRequested(archiveName: String) {
        thread(name = "cloudstream-plugin-reload") {
            writeDiagnostic("restart:intercepted file=$archiveName")
            val reloaded = runCatching { reloadPluginArchive(archiveName) }
                .onFailure { Log.e(TAG, "Failed to reload Cloudstream plugin $archiveName", it) }
                .getOrDefault(false)
            if (reloaded) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cloudstream_plugin_reloaded, archiveName.substringBeforeLast('.')),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    /** The archive of the loaded plugin whose code is on [error]'s stack, if any. */
    private fun pluginBehind(error: Throwable): String? {
        val plugins = loadedPlugins.values.toList()
        if (plugins.isEmpty()) return null
        generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).flatMap { it.stackTrace.asSequence() }
            .map(StackTraceElement::getClassName)
            .distinct()
            .forEach { className ->
                plugins.firstOrNull { loaded ->
                    runCatching { loaded.classLoader.loadClass(className).classLoader === loaded.classLoader }
                        .getOrDefault(false)
                }?.let { return File(it.filePath).name }
            }
        return null
    }

    /** Retries the plugins [initialize] could not load without an activity; failing now quarantines them. */
    @Synchronized
    private fun loadDeferredPlugins(activity: AppCompatActivity) {
        if (deferredPluginPaths.isEmpty()) return
        val paths = deferredPluginPaths.toList()
        deferredPluginPaths.clear()
        configureCloudstreamEnvironment()
        paths.map(::File).filter(File::isFile).forEach { file ->
            runCatching { loadPlugin(file, host = activity) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load deferred Cloudstream plugin ${file.name}", error)
                    writeDiagnostic("load:failed file=${file.name} error=${error.javaClass.name}:${error.message}")
                    quarantinePlugin(
                        file = file,
                        reason = "Plugin load failed: ${error.javaClass.name}:${error.message}",
                    )
                }
        }
        publishSources()
    }

    /**
     * Cloudstream hands plugins its `MainActivity`, which Kototoro's base activities extend; the last resumed one
     * stands in for it when there is one.
     */
    private fun pluginHost(): Context {
        val activity = CommonActivity.INSTANCE.activity as? AppCompatActivity
        return activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: context
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

    /** Whether the plugin providing [source] has a settings screen of its own. */
    fun hasPluginSettings(source: CloudstreamSource): Boolean =
        (loadedPluginOf(source)?.plugin as? Plugin)?.openSettings != null

    /**
     * Readies the settings of the plugin providing [source] to open over [activity]. Plugins keep the activity they
     * loaded with and open their dialogs through its fragment manager (CNC Verse), as Cloudstream has a single
     * activity; here that one may be stopped or gone, so a plugin loaded with another context is loaded again with
     * [activity] first. Runs off the main thread; open the returned callback on it.
     */
    @Synchronized
    fun preparePluginSettings(source: CloudstreamSource, activity: AppCompatActivity): ((Context) -> Unit)? {
        var loaded = loadedPluginOf(source) ?: return null
        if (loaded.host.get() !== activity) {
            val file = File(loaded.filePath)
            configureCloudstreamEnvironment()
            unloadPlugin(loaded.filePath)
            try {
                loadPlugin(file, loaded.packageName, activity)
            } finally {
                publishSources()
            }
            loaded = loadedPlugins[file.absolutePath] ?: return null
        }
        return (loaded.plugin as? Plugin)?.openSettings
    }

    /** Loads the plugin providing [source] again, so settings that decide its providers take effect. */
    fun reloadPlugin(source: CloudstreamSource) {
        reloadPluginArchive(source.pluginFileName)
    }

    @Synchronized
    private fun reloadPluginArchive(archiveName: String): Boolean {
        val loaded = loadedPlugins.values.firstOrNull { File(it.filePath).name == archiveName } ?: return false
        configureCloudstreamEnvironment()
        unloadPlugin(loaded.filePath)
        try {
            loadPlugin(File(loaded.filePath), loaded.packageName, pluginHost())
        } finally {
            publishSources()
        }
        return true
    }

    private fun loadedPluginOf(source: CloudstreamSource): LoadedCloudstreamPlugin? =
        loadedPlugins.values.firstOrNull { File(it.filePath).name == source.pluginFileName }

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
                loadPlugin(target, packageName, pluginHost())
                check(loadedPlugins.containsKey(target.absolutePath)) { "Cloudstream plugin did not load: $archiveName" }
            }
        } catch (error: Throwable) {
            // File rollback completes before rebuilding the previous runtime. Keep the original failure visible.
            runCatching { initialize() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        publishSources()
    }

    private fun loadPlugin(file: File, packageName: String = file.inferPackageName(), host: Context) {
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
                plugin.load(host)
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
            host = WeakReference(host),
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
        /** The context the plugin's `load` got, which it may keep for its dialogs. */
        val host: WeakReference<Context>,
    )

    companion object {
        private const val TAG = "CloudstreamRuntime"
        private const val MAX_CAUSE_DEPTH = 8
    }
}
