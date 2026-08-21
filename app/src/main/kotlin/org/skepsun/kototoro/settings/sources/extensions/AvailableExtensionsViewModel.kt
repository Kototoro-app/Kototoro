package org.skepsun.kototoro.settings.sources.extensions

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import javax.inject.Inject

@HiltViewModel
class AvailableExtensionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val repoRepository: ExternalExtensionRepoRepository,
    private val installService: ExtensionInstallService,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
    private val signatureValidator: InstalledExtensionSignatureValidator,
    private val ireaderExtensionManager: IReaderExtensionManager,
) : BaseViewModel() {

    val type: ExternalExtensionType = enumValueOf(savedStateHandle.require<String>(ARG_EXTENSION_TYPE))

    private val rawExtensions = MutableStateFlow<List<RepoAvailableExtension>>(emptyList())

    val repoCount: StateFlow<Int> = repoRepository.observeByType(type)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val installedExtensions: StateFlow<Map<String, InstalledExtensionVersionInfo>> = observeInstalledExtensionInfoMap(
        type = type,
        mihonExtensionManager = mihonExtensionManager,
        aniyomiExtensionManager = aniyomiExtensionManager,
        ireaderExtensionManager = ireaderExtensionManager,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val items: StateFlow<List<AvailableExtensionListItem>> = combine(
        rawExtensions,
        installedExtensions,
        installService.downloadStates,
    ) { available, installed, downloads ->
        available.map { extension ->
            val installedSearchKey = type.normalizePackageNameForMatching(extension.pkgName)
            val installedInfo = installed[installedSearchKey]
            val state = extension.resolveAvailableState(
                installedInfo = installedInfo,
                isInstalling = extension.pkgName in downloads,
            )
            AvailableExtensionListItem(
                extension = extension,
                installedVersionName = installedInfo?.versionName,
                state = state,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableCount: StateFlow<Int> = items.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val updateCount: StateFlow<Int> = items.map { list -> list.count { it.state == AvailableExtensionState.UPDATE_AVAILABLE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val onInstallIntent = MutableEventFlow<Intent>()
    val onMessage = MutableEventFlow<String>()

    init {
        refresh()
    }

    fun refresh() {
        launchLoadingJob(Dispatchers.IO) {
            repoRepository.refresh(type)
            rawExtensions.value = repoRepository.getAvailableExtensions(type)
        }
    }

    fun install(item: AvailableExtensionListItem) {
        val extension = item.extension
        if (item.state == AvailableExtensionState.INSTALLED || extension.pkgName in installService.downloadStates.value) {
            return
        }
        launchLoadingJob(Dispatchers.IO) {
            try {
                when (val result = installService.install(extension)) {
                    is ExtensionInstallResult.RequiresInstaller -> {
                        result.session.awaitUserAction()?.let { onInstallIntent.call(it) }
                        result.session.awaitCompletion()
                        refresh()
                        onMessage.call(appContext.getString(R.string.unified_sources_package_installed))
                    }
                    ExtensionInstallResult.Completed -> {
                        refresh()
                        onMessage.call(appContext.getString(R.string.unified_sources_package_installed))
                    }
                }
            } catch (e: Throwable) {
                errorEvent.call(e)
            }
        }
    }
}

data class AvailableExtensionListItem(
    val extension: RepoAvailableExtension,
    val installedVersionName: String?,
    val state: AvailableExtensionState,
)

enum class AvailableExtensionState {
    AVAILABLE,
    UPDATE_AVAILABLE,
    INSTALLED,
    INSTALLING,
}
