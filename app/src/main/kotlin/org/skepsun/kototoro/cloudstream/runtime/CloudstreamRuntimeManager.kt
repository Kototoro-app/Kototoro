package org.skepsun.kototoro.cloudstream.runtime

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
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
import java.io.File
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
class CloudstreamRuntimeManager @Inject constructor(
	@ApplicationContext private val context: Context,
	@ContentHttpClient private val contentHttpClient: OkHttpClient,
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

	fun initialize() {
		writeDiagnostic("initialize:start")
		setContext(WeakReference(context as Any))
		CloudStreamApp.context = context
		configureCloudstreamNetwork()
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
			runCatching { loadPlugin(file) }
				.onFailure { error ->
					Log.e(TAG, "Failed to load Cloudstream plugin ${file.name}", error)
					writeDiagnostic("load:failed file=${file.name} error=${error.javaClass.name}:${error.message}")
					unloadPlugin(file.absolutePath)
				}
		}
		publishSources()
		writeDiagnostic(
			"initialize:done loaded=${loadedPlugins.size} sources=${_sources.value.joinToString(",") { "${it.pluginPackageName}/${it.name}" }}",
		)
	}

	private fun configureCloudstreamNetwork() {
		val cloudstreamUserAgent = runCatching { WebSettings.getDefaultUserAgent(context) }
			.getOrNull()
			?: com.lagradost.cloudstream3.USER_AGENT
		CloudstreamRequestContext.userAgent = cloudstreamUserAgent
		app.baseClient = contentHttpClient.newBuilder()
			.apply {
				interceptors().add(0, CloudstreamRequestContext.interceptor())
			}
			.build()
		app.defaultHeaders = mapOf("User-Agent" to cloudstreamUserAgent)
		writeDiagnostic(
			"network:configured interceptors=${contentHttpClient.interceptors.joinToString(",") { it::class.java.simpleName }} " +
				"networkInterceptors=${contentHttpClient.networkInterceptors.joinToString(",") { it::class.java.simpleName }} " +
				"userAgent=${cloudstreamUserAgent.take(120)}",
		)
	}

	fun findSourceByName(name: String): CloudstreamSource? {
		return _sources.value.firstOrNull { it.name == name }
	}

	private fun loadPlugin(file: File) {
		val manifest = readManifest(file) ?: return
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
		if (plugin is Plugin) {
			plugin.load(context)
		} else {
			plugin.load()
		}
		val providers = synchronized(APIHolder.allProviders) {
			APIHolder.allProviders.filter { it.sourcePlugin == plugin.filename }
		}
		val registeredExtractors = synchronized(extractorApis) {
			extractorApis.filter { it.sourcePlugin == plugin.filename }
		}
		val packageName = file.inferPackageName()
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

		synchronized(APIHolder.apis) {
			APIHolder.apis = APIHolder.apis.filter { it.sourcePlugin != loaded.plugin.filename }
		}
		synchronized(APIHolder.allProviders) {
			APIHolder.allProviders.removeIf { it.sourcePlugin == loaded.plugin.filename }
		}
		synchronized(extractorApis) {
			extractorApis.removeIf { it.sourcePlugin == loaded.plugin.filename }
		}
		File(loaded.preparedFilePath).delete()
		publishSources()
		writeDiagnostic("unload:done file=${File(filePath).name}")
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
		return "${length()}:${lastModified()}"
	}

	private fun File.runtimeSignatureHash(): String {
		return runtimeSignature().hashCode().toUInt().toString(16)
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
