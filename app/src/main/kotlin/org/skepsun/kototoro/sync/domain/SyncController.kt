package org.skepsun.kototoro.sync.domain

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
import android.content.Context
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.logSyncFlow
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncController @Inject constructor(
	@ApplicationContext context: Context,
	private val appSettings: AppSettings,
	private val syncRequestPlanner: SyncRequestPlanner,
	private val syncGcCoordinator: SyncGcCoordinator,
	private val syncAuthorityExecutor: SyncAuthorityExecutor,
) : InvalidationTracker.Observer(
	arrayOf(
		TABLE_WORK_HISTORY,
		TABLE_WORK_FAVOURITES,
		TABLE_FAVOURITE_CATEGORIES,
	),
) {

	private val authorityHistory = context.getString(R.string.sync_authority_history)
	private val authorityFavourites = context.getString(R.string.sync_authority_favourites)
	private val am = AccountManager.get(context)
	private val accountType = context.getString(R.string.account_type_sync)
	private val mutex = Mutex()
	override fun onInvalidated(tables: Set<String>) {
		logSyncFlow(TAG, event = "request_skipped", reason = "legacy_sync_suppressed", "tables" to tables.joinToString())
		disableLegacySyncAuthorities()
	}

	fun isEnabled(account: Account): Boolean {
		if (isLegacySyncSuppressed()) {
			return false
		}
		return ContentResolver.getMasterSyncAutomatically() && (ContentResolver.getSyncAutomatically(
			account,
			authorityFavourites,
		) || ContentResolver.getSyncAutomatically(
			account,
			authorityHistory,
		))
	}

	fun getLastSync(account: Account, authority: String): Long {
		val key = "last_sync_" + authority.substringAfterLast('.')
		val rawValue = am.getUserData(account, key) ?: return 0L
		return rawValue.toLongOrNull() ?: 0L
	}

	fun observeSyncStatus(): Flow<Boolean> = callbackFlow {
		val handle = ContentResolver.addStatusChangeListener(SYNC_OBSERVER_TYPE_ACTIVE) { which ->
			trySendBlocking(which and SYNC_OBSERVER_TYPE_ACTIVE != 0)
		}
		awaitClose { ContentResolver.removeStatusChangeListener(handle) }
	}

	suspend fun requestFullSync() = withContext(Dispatchers.Default) {
		if (isLegacySyncSuppressed()) {
			logSyncFlow(TAG, event = "request_skipped", reason = "legacy_sync_suppressed_manual")
			disableLegacySyncAuthorities()
			return@withContext
		}
		logSyncFlow(TAG, event = "request_full_sync")
		requestSyncImpl(favourites = true, history = true)
	}

	private fun requestSync(favourites: Boolean, history: Boolean) = processLifecycleScope.launch(Dispatchers.Default) {
		requestSyncImpl(favourites = favourites, history = history)
	}

	private suspend fun requestSyncImpl(favourites: Boolean, history: Boolean) = mutex.withLock {
		if (isLegacySyncSuppressed()) {
			logSyncFlow(TAG, event = "request_skipped", reason = "legacy_sync_suppressed_impl")
			disableLegacySyncAuthorities()
			return
		}
		if (!favourites && !history) {
			logSyncFlow(TAG, event = "request_skipped", reason = "no_authority_selected")
			return
		}
		val account = peekAccount()
		if (account == null || !ContentResolver.getMasterSyncAutomatically()) {
			logSyncFlow(
				TAG,
				event = "gc_fallback",
				reason = null,
				"favourites" to favourites,
				"history" to history,
				"accountPresent" to (account != null),
				"masterSync" to ContentResolver.getMasterSyncAutomatically(),
			)
			syncGcCoordinator.gcIfNeeded(
				favourites = favourites,
				history = history,
				gcFavourites = favourites,
				gcHistory = history,
			)
			return
		}
		val executionPlan = syncRequestPlanner.planAuthorityExecution(
			favouritesRequested = favourites,
			historyRequested = history,
			authorityFavourites = authorityFavourites,
			authorityHistory = authorityHistory,
			isFavouritesAuthorityEnabled = ContentResolver.getSyncAutomatically(account, authorityFavourites),
			isHistoryAuthorityEnabled = ContentResolver.getSyncAutomatically(account, authorityHistory),
		)
		val result = syncAuthorityExecutor.execute(
			account = account,
			plan = executionPlan,
			authorityFavourites = authorityFavourites,
			authorityHistory = authorityHistory,
		)
		result.requestedAuthorities.forEach { authority ->
			logSyncFlow(TAG, event = "request_authority", reason = null, "authority" to authority)
		}
		result.disabledAuthorities.forEach { authority ->
			logSyncFlow(TAG, event = "gc_authority_disabled", reason = null, "authority" to authority)
		}
		if (executionPlan.gcHistory || executionPlan.gcFavourites) {
			syncGcCoordinator.gcIfNeeded(
				favourites = favourites,
				history = history,
				gcFavourites = executionPlan.gcFavourites,
				gcHistory = executionPlan.gcHistory,
			)
		}
	}

	private fun peekAccount(): Account? {
		return am.getAccountsByType(accountType).firstOrNull()
	}

	private fun isLegacySyncSuppressed(): Boolean {
		return true
	}

	private fun disableLegacySyncAuthorities() {
		val account = peekAccount() ?: return
		runCatching {
			ContentResolver.setSyncAutomatically(account, authorityFavourites, false)
			ContentResolver.setSyncAutomatically(account, authorityHistory, false)
		}
	}

	private fun isSyncActiveOrPending(authority: String): Boolean {
		val account = peekAccount() ?: return false
		return ContentResolver.isSyncActive(account, authority) || ContentResolver.isSyncPending(account, authority)
	}

	companion object {

		private const val TAG = "SyncController"

		@JvmStatic
		fun setLastSync(context: Context, account: Account, authority: String, time: Long) {
			val key = "last_sync_" + authority.substringAfterLast('.')
			val am = AccountManager.get(context)
			am.setUserData(account, key, time.toString())
		}
	}
}
