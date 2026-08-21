package org.skepsun.kototoro.settings.sources.extensions

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.ExtensionRepoService
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import javax.inject.Inject

@HiltViewModel
class ExtensionRepositoriesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val repoRepository: ExternalExtensionRepoRepository,
    private val repoService: ExtensionRepoService,
    private val installService: ExtensionInstallService,
) : BaseViewModel() {

    val type: ExternalExtensionType = enumValueOf(savedStateHandle.require<String>(ARG_EXTENSION_TYPE))
    private val historyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val historyKey = "${KEY_INPUT_HISTORY_PREFIX}_${type.name.lowercase()}"

    val repos: StateFlow<List<ExternalExtensionRepo>> = repoRepository.observeByType(type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val repoCount: StateFlow<Int> = repos.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val onMessage = MutableEventFlow<String>()
    val onTrustPrompt = MutableEventFlow<ExternalExtensionRepo>()

    // Mapping of repository baseUrl to its available RepoAvailableExtension if an update is available
    private val _updatesAvailable = MutableStateFlow<Map<String, RepoAvailableExtension>>(emptyMap())
    val updatesAvailable: StateFlow<Map<String, RepoAvailableExtension>> = _updatesAvailable
    private val _inputHistory = MutableStateFlow(loadInputHistory())
    val inputHistory: StateFlow<List<String>> = _inputHistory

    fun performUpdate(repo: ExternalExtensionRepo) {
        val updateExtension = _updatesAvailable.value[repo.baseUrl] ?: return
        launchLoadingJob(Dispatchers.IO) {
            try {
                when (installService.install(updateExtension)) {
                    is ExtensionInstallResult.RequiresInstaller -> {
                        onMessage.call(appContext.getString(R.string.unified_sources_repository_manual_refresh_only))
                    }
                    ExtensionInstallResult.Completed -> {
                        onMessage.call(appContext.getString(R.string.unified_sources_package_installed))
                        refresh()
                    }
                }
            } catch (e: Exception) {
                onMessage.call("Update failed: ${e.message}")
            }
        }
    }

    fun addRepo(indexUrl: String) {
        Log.d(TAG, "addRepo:start type=$type input=$indexUrl")
        recordInputHistory(indexUrl)
        launchLoadingJob(Dispatchers.IO) {
            when (val result = repoRepository.prepareAddRepo(type, indexUrl)) {
                is ExternalExtensionRepoRepository.PrepareAddRepoResult.Ready -> {
                    Log.d(TAG, "addRepo:trustPrompt type=$type baseUrl=${result.repo.baseUrl} name=${result.repo.displayName}")
                    onTrustPrompt.call(result.repo)
                }

                is ExternalExtensionRepoRepository.PrepareAddRepoResult.DuplicateFingerprint -> {
                    Log.d(TAG, "addRepo:duplicateFingerprint type=$type existing=${result.existingRepo.baseUrl}")
                    onMessage.call(
                        appContext.getString(
                            R.string.extension_repo_duplicate_fingerprint_message,
                            result.existingRepo.displayName,
                        ),
                    )
                }

                is ExternalExtensionRepoRepository.PrepareAddRepoResult.FetchFailed -> {
                    Log.e(TAG, "addRepo:fetchFailed type=$type message=${result.error.message}", result.error)
                    onMessage.call(result.error.getDisplayMessage(appContext.resources))
                }

                ExternalExtensionRepoRepository.PrepareAddRepoResult.InvalidUrl -> {
                    Log.d(TAG, "addRepo:invalidUrl type=$type input=$indexUrl")
                    onMessage.call(appContext.getString(R.string.extension_repo_invalid_url_message))
                }

                ExternalExtensionRepoRepository.PrepareAddRepoResult.RepoAlreadyExists -> {
                    Log.d(TAG, "addRepo:alreadyExists type=$type input=$indexUrl")
                    onMessage.call(appContext.getString(R.string.extension_repo_already_exists_message))
                }
            }
        }
    }

    fun confirmAddRepo(repo: ExternalExtensionRepo) {
        Log.d(TAG, "confirmAddRepo:start type=${repo.type} baseUrl=${repo.baseUrl} name=${repo.displayName}")
        launchLoadingJob(Dispatchers.IO) {
            when (val result = repoRepository.confirmAddRepo(repo)) {
                is ExternalExtensionRepoRepository.AddRepoResult.Success -> {
                    Log.d(TAG, "confirmAddRepo:success type=${repo.type} baseUrl=${repo.baseUrl}")
                    onMessage.call(appContext.getString(R.string.extension_repo_added_message, result.repo.displayName))
                    if (repo.type == ExternalExtensionType.JAR) {
                        refresh() // Auto-trigger JAR download exactly like cold start
                    }
                }

                is ExternalExtensionRepoRepository.AddRepoResult.DuplicateFingerprint -> {
                    Log.d(TAG, "confirmAddRepo:duplicateFingerprint type=${repo.type} existing=${result.existingRepo.baseUrl}")
                    onMessage.call(
                        appContext.getString(
                            R.string.extension_repo_duplicate_fingerprint_message,
                            result.existingRepo.displayName,
                        ),
                    )
                }

                is ExternalExtensionRepoRepository.AddRepoResult.FetchFailed -> {
                    Log.e(TAG, "confirmAddRepo:fetchFailed type=${repo.type} message=${result.error.message}", result.error)
                    onMessage.call(result.error.getDisplayMessage(appContext.resources))
                }

                ExternalExtensionRepoRepository.AddRepoResult.InvalidUrl -> {
                    Log.d(TAG, "confirmAddRepo:invalidUrl type=${repo.type} baseUrl=${repo.baseUrl}")
                    onMessage.call(appContext.getString(R.string.extension_repo_invalid_url_message))
                }

                ExternalExtensionRepoRepository.AddRepoResult.RepoAlreadyExists -> {
                    Log.d(TAG, "confirmAddRepo:alreadyExists type=${repo.type} baseUrl=${repo.baseUrl}")
                    onMessage.call(appContext.getString(R.string.extension_repo_already_exists_message))
                }
            }
        }
    }

    fun deleteRepo(repo: ExternalExtensionRepo) {
        launchLoadingJob(Dispatchers.IO) {
            repoRepository.delete(repo)
            onMessage.call(appContext.getString(R.string.extension_repo_removed_message, repo.displayName))
        }
    }

    fun refresh() {
        launchLoadingJob(Dispatchers.IO) {
            repoRepository.refresh(type)
            if (type == ExternalExtensionType.JAR) {
                val available = repoRepository.getCatalogExtensions(type)
                val jarVersions = appContext.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
                val newUpdates = mutableMapOf<String, RepoAvailableExtension>()
                for (extension in available) {
                    // Detect if the remote version is strictly newer than installed version
                    if (extension.versionCode > jarVersions.getLong(extension.pkgName, -1L)) {
                        newUpdates[extension.repoUrl] = extension
                    }
                }
                _updatesAvailable.value = newUpdates
            }
        }
    }

    private companion object {
        const val TAG = "ExtensionRepo"
        const val PREFS_NAME = "extension_repo_input_history"
        const val KEY_INPUT_HISTORY_PREFIX = "extension_repo_input_history"
        const val MAX_HISTORY_SIZE = 8
        const val HISTORY_SEPARATOR = "\n"
    }

    private fun recordInputHistory(rawInput: String) {
        val normalized = normalizeHistoryInput(rawInput) ?: return
        val updated = buildList {
            add(normalized)
            addAll(_inputHistory.value.filterNot { it == normalized })
        }.take(MAX_HISTORY_SIZE)
        if (updated == _inputHistory.value) {
            return
        }
        historyPrefs.edit {
            putString(historyKey, updated.joinToString(HISTORY_SEPARATOR))
        }
        _inputHistory.value = updated
    }

    private fun loadInputHistory(): List<String> {
        return historyPrefs.getString(historyKey, null)
            ?.split(HISTORY_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    private fun normalizeHistoryInput(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return repoService.normalizeIndexUrl(trimmed)
            ?: trimmed.toHttpUrlOrNull()
                ?.takeIf { it.scheme == "https" }
                ?.newBuilder()
                ?.fragment(null)
                ?.query(null)
                ?.build()
                ?.toString()
    }
}
