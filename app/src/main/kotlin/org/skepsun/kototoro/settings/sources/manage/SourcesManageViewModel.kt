package org.skepsun.kototoro.settings.sources.manage

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.removeObserverAsync
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.ContentSource
// Removed import org.skepsun.kototoro.parsers.util.move
import org.skepsun.kototoro.settings.sources.model.SourceConfigItem
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import javax.inject.Inject

@HiltViewModel
class SourcesManageViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val repository: ContentSourcesRepository,
    private val listProducer: SourcesListProducer,
    private val repoRepository: ExternalExtensionRepoRepository,
    private val installService: ExtensionInstallService,
) : BaseViewModel() {

    val content = listProducer.list
    val onActionDone = MutableEventFlow<ReversibleAction>()
    private var commitJob: Job? = null

    private val _jarUpdatesAvailable = MutableStateFlow<List<RepoAvailableExtension>>(emptyList())
    val jarUpdatesAvailable = _jarUpdatesAvailable.asStateFlow()

    private val _isUpdatingJars = MutableStateFlow(false)
    val isUpdatingJars = _isUpdatingJars.asStateFlow()

    init {
        launchJob(Dispatchers.Default) {
            database.invalidationTracker.addObserver(listProducer)
        }
        checkForJarUpdates()
    }

    fun checkForJarUpdates() {
        launchJob(Dispatchers.IO) {
            try {
                val available = repoRepository.getCatalogExtensions(ExternalExtensionType.JAR)
                val jarVersions = appContext.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
                val newUpdates = available.filter { extension ->
                    extension.versionCode > jarVersions.getLong(extension.pkgName, -1L)
                }
                _jarUpdatesAvailable.value = newUpdates
            } catch (e: Exception) {
                // Silently fail, to not spam users if offline
            }
        }
    }

    fun updateAllJars() {
        val updates = _jarUpdatesAvailable.value
        if (updates.isEmpty() || _isUpdatingJars.value) return

        _isUpdatingJars.value = true
        launchJob(Dispatchers.IO) {
            try {
                updates.forEach { extension ->
                    installService.install(extension)
                }
            } catch (e: Exception) {
                // Ignore errors per plugin, allow continuation
            } finally {
                _jarUpdatesAvailable.value = emptyList()
                _isUpdatingJars.value = false
                checkForJarUpdates() // Refresh if any failed
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        database.invalidationTracker.removeObserverAsync(listProducer)
    }

    fun saveSourcesOrder(snapshot: List<SourceConfigItem>) {
        val prevJob = commitJob
        commitJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val newSourcesList = snapshot.mapNotNull { x ->
                if (x is SourceConfigItem.SourceItem && x.isDraggable) {
                    x.source
                } else {
                    null
                }
            }
            repository.setPositions(newSourcesList)
        }
    }

    fun canReorder(oldPos: Int, newPos: Int): Boolean {
        val snapshot: List<SourceConfigItem> = content.value
        val oldPosItem = snapshot.getOrNull(oldPos) as? SourceConfigItem.SourceItem ?: return false
        val newPosItem = snapshot.getOrNull(newPos) as? SourceConfigItem.SourceItem ?: return false
        return oldPosItem.isEnabled && newPosItem.isEnabled && oldPosItem.isPinned == newPosItem.isPinned
    }

    fun setEnabled(source: ContentSource, isEnabled: Boolean) {
        launchJob(Dispatchers.Default) {
            val rollback = repository.setSourcesEnabled(setOf(source), isEnabled)
            if (!isEnabled) {
                onActionDone.call(ReversibleAction(R.string.source_disabled, rollback))
            }
        }
    }

    fun setPinned(source: ContentSource, isPinned: Boolean) {
        launchJob(Dispatchers.Default) {
            val rollback = repository.setIsPinned(setOf(source), isPinned)
            val message = if (isPinned) R.string.source_pinned else R.string.source_unpinned
            onActionDone.call(ReversibleAction(message, rollback))
        }
    }

    fun bringToTop(source: ContentSource) {
        val snapshot: List<SourceConfigItem> = content.value
        launchJob(Dispatchers.Default) {
            var oldPos = -1
            var newPos = -1
            for ((i, x) in snapshot.withIndex()) {
                if (x !is SourceConfigItem.SourceItem) {
                    continue
                }
                if (newPos == -1) {
                    newPos = i
                }
                if (x.source == source) {
                    oldPos = i
                    break
                }
            }
            @Suppress("KotlinConstantConditions")
            if (oldPos != -1 && newPos != -1) {
                reorderSources(oldPos, newPos)
                val revert = ReversibleAction(R.string.moved_to_top) {
                    reorderSources(newPos, oldPos)
                }
                commitJob?.join()
                onActionDone.call(revert)
            }
        }
    }

    fun disableAll() {
        launchJob(Dispatchers.Default) {
            repository.disableAllSources()
        }
    }

    fun performSearch(query: String?) {
        listProducer.setQuery(query?.trim().orEmpty())
    }

    fun onTipClosed(item: SourceConfigItem.Tip) {
        launchJob(Dispatchers.Default) {
            settings.closeTip(item.key)
        }
    }

    private fun reorderSources(oldPos: Int, newPos: Int) {
        val snapshot: MutableList<SourceConfigItem> = ArrayList(content.value)
        if ((snapshot[oldPos] as? SourceConfigItem.SourceItem)?.isDraggable != true) {
            return
        }
        if ((snapshot[newPos] as? SourceConfigItem.SourceItem)?.isDraggable != true) {
            return
        }
        snapshot.add(newPos, snapshot.removeAt(oldPos))
        saveSourcesOrder(snapshot)
    }
}
