package org.skepsun.kototoro.sync.domain

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncAuthorityExecutor @Inject constructor() {

    data class ExecutionResult(
        val requestedAuthorities: List<String>,
        val disabledAuthorities: List<String>,
    )

    fun execute(
        account: Account,
        plan: SyncRequestPlanner.AuthorityExecutionPlan,
        authorityFavourites: String,
        authorityHistory: String,
    ) : ExecutionResult {
        plan.requestedAuthorities.forEach { authority ->
            ContentResolver.requestSync(account, authority, Bundle.EMPTY)
        }
        val disabledAuthorities = buildList(2) {
            if (plan.gcFavourites) add(authorityFavourites)
            if (plan.gcHistory) add(authorityHistory)
        }
        return ExecutionResult(
            requestedAuthorities = plan.requestedAuthorities,
            disabledAuthorities = disabledAuthorities,
        )
    }
}
