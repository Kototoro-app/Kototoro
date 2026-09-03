package org.skepsun.kototoro.settings.nav

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.MAX_MAIN_NAV_ITEM_COUNT
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ActivityRecreationHandle
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.parsers.util.move
import org.skepsun.kototoro.settings.nav.model.NavItemAddModel
import org.skepsun.kototoro.settings.nav.model.NavItemConfigModel
import javax.inject.Inject

@HiltViewModel
class NavConfigViewModel @Inject constructor(
    private val settings: AppSettings,
    private val activityRecreationHandle: ActivityRecreationHandle,
) : BaseViewModel() {

    private val items = MutableStateFlow(settings.mainNavItems)

    val configuredItems: StateFlow<List<NavItemConfigModel>> = items.map { snapshot ->
        snapshot.map {
            NavItemConfigModel(it, getUnavailabilityHint(it))
        }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.WhileSubscribed(5000),
        items.value.map { NavItemConfigModel(it, getUnavailabilityHint(it)) },
    )

    val availableItems: StateFlow<List<NavItem>> = items.map { snapshot ->
        NavItem.entries.filterNot { item -> item in snapshot || item == NavItem.DISCOVER }
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.WhileSubscribed(5000),
        NavItem.entries.filterNot { item -> item in items.value || item == NavItem.DISCOVER },
    )

    val canShowAddAction: StateFlow<Boolean> = items.map { snapshot ->
        snapshot.size < NavItem.entries.size
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.WhileSubscribed(5000),
        items.value.size < NavItem.entries.size,
    )

    val canAddAction: StateFlow<Boolean> = items.map { snapshot ->
        snapshot.size < MAX_MAIN_NAV_ITEM_COUNT
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.WhileSubscribed(5000),
        items.value.size < MAX_MAIN_NAV_ITEM_COUNT,
    )

    val content: StateFlow<List<ListModel>> = items.map { snapshot ->
        buildContent(snapshot)
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.WhileSubscribed(5000),
        buildContent(items.value),
    )

    private var commitJob: Job? = null

    /** True while a debounced activity recreation is still owed; see [commit]. */
    private var recreatePending = false

    fun reorder(fromPos: Int, toPos: Int) {
        items.value = items.value.toMutableList().apply {
            move(fromPos, toPos)
            commit(this)
        }
    }

    fun moveUp(index: Int) {
        if (index <= 0) return
        reorder(index, index - 1)
    }

    fun moveDown(index: Int) {
        if (index >= items.value.lastIndex) return
        reorder(index, index + 1)
    }

    fun addItem(item: NavItem) {
        if (items.value.size >= MAX_MAIN_NAV_ITEM_COUNT || item in items.value) return
        items.value = items.value.plus(item).also {
            commit(it)
        }
    }

    fun removeItem(item: NavItem) {
        val newList = items.value.toMutableList()
        newList.remove(item)
        if (newList.isEmpty()) {
            newList.add(NavItem.HOME)
        }
        items.value = newList
        commit(newList)
    }

    private fun commit(value: List<NavItem>) {
        // Persist synchronously. The setter is a plain SharedPreferences putString and
        // every commit writes the full list (last write wins), so there is nothing to
        // debounce here. Writing inside the debounced job below meant that leaving the
        // screen within the 500 ms window cancelled viewModelScope mid-delay and
        // silently dropped the change: the nav config "did not take effect" until the
        // user tried again and happened to stay longer.
        settings.mainNavItems = value
        val prevJob = commitJob
        recreatePending = true
        // Debounce only the activity recreation, so rapid reorders coalesce into one.
        commitJob = launchJob {
            prevJob?.cancelAndJoin()
            delay(500)
            activityRecreationHandle.recreate(MainActivity::class.java)
            // Cleared only on successful completion. A cancelled job never reaches this
            // line, so the flag stays set and onCleared() can fire the owed recreate.
            recreatePending = false
        }
    }

    override fun onCleared() {
        // ViewModel.clear() cancels viewModelScope *before* calling onCleared(), so an
        // isActive check on the job is always false here. Instead: if a debounced
        // recreate is still owed (the user left within the 500 ms window), fire it now.
        // The main shell reads mainNavItems non-reactively, so without the recreation
        // the bottom nav would keep showing the stale set until the next unrelated
        // recreation - while the preference already says otherwise.
        if (recreatePending) {
            activityRecreationHandle.recreate(MainActivity::class.java)
        }
        super.onCleared()
    }

    private fun getUnavailabilityHint(item: NavItem) = if (item.isAvailable(settings)) {
        0
    } else when (item) {
        NavItem.FEED -> R.string.check_for_new_chapters_disabled
        NavItem.SUGGESTIONS -> R.string.suggestions_unavailable_text
        else -> 0
    }

    private fun buildContent(snapshot: List<NavItem>): List<ListModel> = buildList(snapshot.size + 1) {
        snapshot.mapTo(this) {
            NavItemConfigModel(it, getUnavailabilityHint(it))
        }
        if (size < NavItem.entries.size) {
            add(NavItemAddModel(size < MAX_MAIN_NAV_ITEM_COUNT))
        }
    }
}
