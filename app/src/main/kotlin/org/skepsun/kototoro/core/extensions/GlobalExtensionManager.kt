package org.skepsun.kototoro.core.extensions

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.ContentSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PluginMangaSource(
    val originalSource: MangaSource,
    val jarName: String,
    val isBroken: Boolean
) : MangaSource by originalSource {
    val id: String get() = "$jarName:${originalSource.name}"
}

data class PluginContentSource(
    val originalSource: ContentSource,
    val jarName: String,
    val isBroken: Boolean
) : ContentSource by originalSource {
    val id: String get() = "$jarName:${originalSource.name}"
}

object GlobalExtensionManager {
    private val mangaPlugins = ConcurrentHashMap<String, LoadedJarPlugin>()
    private val contentPlugins = ConcurrentHashMap<String, LoadedJarPlugin>()

    @Volatile
    var version: Int = 0
        private set

    private val _updates = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<Unit> = _updates

    private val _mangaSources = MutableStateFlow<List<PluginMangaSource>>(emptyList())
    val mangaSources: StateFlow<List<PluginMangaSource>> = _mangaSources.asStateFlow()

    private val _contentSources = MutableStateFlow<List<PluginContentSource>>(emptyList())
    val contentSources: StateFlow<List<PluginContentSource>> = _contentSources.asStateFlow()

    private val _installedJarNames = MutableStateFlow<List<String>>(emptyList())
    val installedJarNames: StateFlow<List<String>> = _installedJarNames.asStateFlow()

    private val allLoadedMangaSources = mutableListOf<PluginMangaSource>()
    private val allLoadedContentSources = mutableListOf<PluginContentSource>()
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun initialize(context: Context) {
        val pluginDir = File(context.filesDir, "plugins")
        val plugins = JarExtensionLoader.loadFromDirectory(context, pluginDir)

        allLoadedMangaSources.clear()
        allLoadedContentSources.clear()

        mangaPlugins.clear()
        contentPlugins.clear()

        for (plugin in plugins) {
            if (plugin.architecture == ParserPluginArchitecture.KOTATSU) {
                mangaPlugins[plugin.jarName] = plugin
                val wrapped = plugin.sources.map { 
                    val source = it as MangaSource
                    PluginMangaSource(source, plugin.jarName, plugin.brokenSourceNames.contains(source.name)) 
                }
                allLoadedMangaSources.addAll(wrapped)
            } else {
                contentPlugins[plugin.jarName] = plugin
                val wrapped = plugin.sources.map {
                    val source = when (it) {
                        is ContentSource -> it
                        is tsuki.model.MangaSource -> org.skepsun.kototoro.core.parser.tsuki.TsukiContentSource(it)
                        else -> error("Unsupported parser source type: ${it.javaClass.name}")
                    }
                    val isBroken = plugin.brokenSourceNames.contains(source.name) ||
                        (it as? tsuki.model.MangaSource)?.isBroken == true
                    PluginContentSource(source, plugin.jarName, isBroken)
                }
                allLoadedContentSources.addAll(wrapped)
            }
        }
        _installedJarNames.value = plugins.map { it.jarName }.distinct().sortedBy { it.lowercase() }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefsListener == null) {
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "jar_priority_order") {
                    applyDeduplication(prefs)
                    publishRegistryUpdate()
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        
        applyDeduplication(prefs)
        publishRegistryUpdate()
    }

    private fun applyDeduplication(prefs: SharedPreferences) {
        val priorityOrder = prefs.getString("jar_priority_order", DEFAULT_JAR_PRIORITY_ORDER_VALUE).orEmpty()
        val candidates = buildList {
            allLoadedMangaSources.forEach { add(JarSourceCandidate(it.name, it.jarName, mangaSource = it)) }
            allLoadedContentSources.forEach { add(JarSourceCandidate(it.name, it.jarName, contentSource = it)) }
        }
        val selectedSources = selectPreferredJarSources(
            sources = candidates,
            priorityOrder = priorityOrder,
            sourceName = JarSourceCandidate::sourceName,
            jarName = JarSourceCandidate::jarName,
        )

        _mangaSources.value = selectedSources.mapNotNull { it.mangaSource }
        _contentSources.value = selectedSources.mapNotNull { it.contentSource }
    }

    private fun publishRegistryUpdate() {
        version++
        _updates.tryEmit(Unit)
    }

    fun getMangaParser(source: MangaSource, context: MangaLoaderContext): MangaParser {
        val pluginSource = source as? PluginMangaSource ?: 
            _mangaSources.value.find { it.originalSource == source || it.name == source.name }
            ?: throw IllegalArgumentException("No PluginMangaSource found for: ${source.name}")
        val plugin = mangaPlugins[pluginSource.jarName] ?: throw IllegalStateException("JAR missing: ${pluginSource.jarName}")
        return JarExtensionLoader.instantiateMangaParser(plugin, pluginSource.originalSource, context)
    }

    fun getContentParser(source: ContentSource, context: ContentLoaderContext): ContentParser {
        val pluginSource = source as? PluginContentSource ?: 
            _contentSources.value.find { it.originalSource == source || it.name == source.name }
            ?: throw IllegalArgumentException("No PluginContentSource found for: ${source.name}")
        val plugin = contentPlugins[pluginSource.jarName] ?: throw IllegalStateException("JAR missing: ${pluginSource.jarName}")
        return JarExtensionLoader.instantiateContentParser(plugin, pluginSource.originalSource, context)
    }

    private data class JarSourceCandidate(
        val sourceName: String,
        val jarName: String,
        val mangaSource: PluginMangaSource? = null,
        val contentSource: PluginContentSource? = null,
    )
}
