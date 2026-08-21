package org.skepsun.kototoro.settings.tracker

import androidx.room.InvalidationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Closeable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.removeObserverAsync
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import javax.inject.Inject

@HiltViewModel
class TrackerSettingsViewModel @Inject constructor(
    private val repository: TrackingRepository,
    private val database: MangaDatabase,
) : BaseViewModel() {

    val categoriesCount = MutableStateFlow<IntArray?>(null)

    init {
        updateCategoriesCount()
        val databaseObserver = DatabaseObserver(this)
        addCloseable(databaseObserver)
        launchJob(Dispatchers.Default) {
            database.invalidationTracker.addObserver(databaseObserver)
        }
    }

    private fun updateCategoriesCount() {
        launchJob(Dispatchers.Default) {
            categoriesCount.value = repository.getCategoriesCount()
        }
    }

    private class DatabaseObserver(private var vm: TrackerSettingsViewModel?) :
        InvalidationTracker.Observer(arrayOf(TABLE_FAVOURITE_CATEGORIES)),
        Closeable {

        override fun onInvalidated(tables: Set<String>) {
            vm?.updateCategoriesCount()
        }

        override fun close() {
            (vm ?: return).database.invalidationTracker.removeObserverAsync(this)
            vm = null
        }
    }
}
