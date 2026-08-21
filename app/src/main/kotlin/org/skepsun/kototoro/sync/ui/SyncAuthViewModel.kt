package org.skepsun.kototoro.sync.ui

import android.accounts.AccountManager
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.sync.data.SyncAuthApi
import org.skepsun.kototoro.sync.domain.SyncAuthResult
import javax.inject.Inject

@HiltViewModel
class SyncAuthViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val api: SyncAuthApi,
) : BaseViewModel() {

    val onAccountAlreadyExists = MutableEventFlow<Unit>()
    val onTokenObtained = MutableEventFlow<SyncAuthResult>()
    val syncURL = MutableStateFlow(context.resources.getStringArray(R.array.sync_url_list).first())

    init {
        launchJob(Dispatchers.Default) {
            val am = AccountManager.get(context)
            val accounts = am.getAccountsByType(context.getString(R.string.account_type_sync))
            if (accounts.isNotEmpty()) {
                onAccountAlreadyExists.call(Unit)
            }
        }
    }

    fun obtainToken(email: String, password: String) {
        val urlValue = syncURL.value
        launchLoadingJob(Dispatchers.Default) {
            val token = api.authenticate(urlValue, email, password)
            val result = SyncAuthResult(syncURL.value, email, password, token)
            onTokenObtained.call(result)
        }
    }
}
