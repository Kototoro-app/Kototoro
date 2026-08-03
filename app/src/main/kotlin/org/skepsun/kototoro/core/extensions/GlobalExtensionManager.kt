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
        val priorityStr = prefs.getString("jar_priority_order", "kototoro-parsers,kotatsu-parsers-redo,kotatsu-parsers") ?: ""
        val priorityList = priorityStr
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val loadedJarNames = (allLoadedMangaSources.map { it.jarName } + allLoadedContentSources.map { it.jarName })
            .distinct()

        fun getPriorityScore(jarName: String): Int {
            val baseName = jarName.removeSuffix(".jar")
            val index = priorityList.indexOf(baseName)
            return if (index == -1) priorityList.size + loadedJarNames.indexOf(jarName).coerceAtLeast(Int.MAX_VALUE / 2) else index
        }

        val deduplicatedMangaSources = allLoadedMangaSources
            .groupBy { it.originalSource.name }
            .map { (_, sources) ->
                sources.minWithOrNull(
                    compareBy<PluginMangaSource> { getPriorityScore(it.jarName) }
                        .thenBy { loadedJarNames.indexOf(it.jarName).coerceAtLeast(Int.MAX_VALUE / 2) }
                        .thenBy { it.jarName.lowercase() },
                )!!
            }

        val deduplicatedContentSources = allLoadedContentSources
            .groupBy { it.originalSource.name }
            .map { (_, sources) ->
                sources.minWithOrNull(
                    compareBy<PluginContentSource> { getPriorityScore(it.jarName) }
                        .thenBy { loadedJarNames.indexOf(it.jarName).coerceAtLeast(Int.MAX_VALUE / 2) }
                        .thenBy { it.jarName.lowercase() },
                )!!
            }

        _mangaSources.value = deduplicatedMangaSources
        _contentSources.value = deduplicatedContentSources
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
}
