package org.skepsun.kototoro.settings.sources

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.extensions.jarBaseName
import org.skepsun.kototoro.core.extensions.resolveJarPriorityOrder
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import javax.inject.Inject

@HiltViewModel
class SourcesSettingsViewModel @Inject constructor(
    sourcesRepository: ContentSourcesRepository,
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) : BaseViewModel() {

    private val linksHandlerActivity = ComponentName(context, "org.skepsun.kototoro.details.ui.DetailsByLinkActivity")

    val enabledSourcesCount = sourcesRepository.observeEnabledSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)

    val availableSourcesCount = sourcesRepository.observeAvailableSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)

    val builtInSourcesCount = sourcesRepository.observeBuiltInSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val jsonSourcesCount = sourcesRepository.observeJsonSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val mihonSourcesCount = sourcesRepository.observeMihonSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val aniyomiSourcesCount = sourcesRepository.observeAniyomiSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val ireaderSourcesCount = sourcesRepository.observeIReaderSourcesCount()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val extensionsSummary = combine(mihonSourcesCount, aniyomiSourcesCount, ireaderSourcesCount) { mihonCount, aniyomiCount, ireaderCount ->
        context.getString(R.string.extensions_summary_pattern, mihonCount, aniyomiCount, ireaderCount)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, "")

    val isLinksEnabled = MutableStateFlow(isLinksEnabled())
    val installedJarNames: StateFlow<List<String>> = GlobalExtensionManager.installedJarNames

    fun refreshLinksEnabled() {
        isLinksEnabled.value = isLinksEnabled()
    }

    init {
        loadPlugins()
    }

    fun setLinksEnabled(isEnabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            linksHandlerActivity,
            if (isEnabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        isLinksEnabled.value = isLinksEnabled()
    }

    private fun isLinksEnabled(): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(linksHandlerActivity)
        return state == COMPONENT_ENABLED_STATE_ENABLED || state == COMPONENT_ENABLED_STATE_DEFAULT
    }

    val installedPlugins = MutableLiveData<List<File>>()

    fun loadPlugins() {
        viewModelScope.launch(Dispatchers.IO) {
            val pluginsDir = File(context.filesDir, "plugins")
            val jarFiles = pluginsDir
                .listFiles { file -> file.extension.equals("jar", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            installedPlugins.postValue(jarFiles)
        }
    }

    fun resolveJarPriorityOrder(currentValue: String): List<String> {
        return resolveJarPriorityOrder(installedJarNames.value, currentValue)
    }

    fun persistJarPriorityOrder(jarNames: List<String>) {
        val normalized = jarNames
            .map { it.jarBaseName() }
            .distinct()
            .joinToString(",")
        settings.jarPriorityOrder = normalized
    }

    fun deletePlugin(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (file.delete()) {
                GlobalExtensionManager.initialize(context)
                loadPlugins()
            }
        }
    }

    fun importPlugin(uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val documentFile = DocumentFile.fromSingleUri(context, uri) ?: throw Exception("Invalid file URI")
                val fileName = documentFile.name ?: "plugin_${System.currentTimeMillis()}.jar"
                val pluginsDir = File(context.filesDir, "plugins")
                if (!pluginsDir.exists()) pluginsDir.mkdirs()

                val destinationFile = File(pluginsDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Cannot open input stream")

                // Re-initialize manager
                GlobalExtensionManager.initialize(context)
                launch(Dispatchers.Main) {
                    onResult(Result.success(fileName))
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }
}
