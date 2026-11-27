package org.skepsun.kototoro.settings.tracker

import androidx.room.InvalidationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Closeable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.removeObserverAsync
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.work.TrackerWebhookNotifier
import org.skepsun.kototoro.tracker.work.TrackerWebhookResult
import org.skepsun.kototoro.tracker.work.TrackerWebhookUrl
import javax.inject.Inject
import org.skepsun.kototoro.tracker.domain.PERSISTENT_FAILURE_COUNT

/** What the "send test" button of the tracker webhook setting is doing right now. */
sealed interface WebhookTestState {
    data object Idle : WebhookTestState
    data object Sending : WebhookTestState
    data object Success : WebhookTestState
    data object InvalidUrl : WebhookTestState
    data class HttpError(val code: Int) : WebhookTestState
    data object NetworkError : WebhookTestState
}

@HiltViewModel
class TrackerSettingsViewModel @Inject constructor(
    private val repository: TrackingRepository,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val webhookNotifier: TrackerWebhookNotifier,
) : BaseViewModel() {

    val categoriesCount = MutableStateFlow<IntArray?>(null)

    /** Tracked works that failed [PERSISTENT_FAILURE_COUNT] checks or more in a row. */
    val failingCount = MutableStateFlow(0)

    val webhookTestState = MutableStateFlow<WebhookTestState>(WebhookTestState.Idle)

    init {
        updateCategoriesCount()
        launchJob(Dispatchers.Default) {
            failingCount.value = database.getTrackScheduleDao().findFailing(PERSISTENT_FAILURE_COUNT).size
        }
        val databaseObserver = DatabaseObserver(this)
        addCloseable(databaseObserver)
        launchJob(Dispatchers.Default) {
            database.invalidationTracker.addObserver(databaseObserver)
        }
    }

    /** Sends one test request to the configured webhook; never throws, the outcome is in [webhookTestState]. */
    fun sendWebhookTest() {
        if (webhookTestState.value == WebhookTestState.Sending) {
            return
        }
        val url = settings.trackerWebhookUrl
        if (!TrackerWebhookUrl.isValid(url)) {
            webhookTestState.value = WebhookTestState.InvalidUrl
            return
        }
        webhookTestState.value = WebhookTestState.Sending
        launchJob {
            webhookTestState.value = when (val result = webhookNotifier.sendTest(url)) {
                is TrackerWebhookResult.Sent -> WebhookTestState.Success
                is TrackerWebhookResult.Failed -> result.code?.let { WebhookTestState.HttpError(it) }
                    ?: WebhookTestState.NetworkError

                TrackerWebhookResult.InvalidUrl -> WebhookTestState.InvalidUrl
            }
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
