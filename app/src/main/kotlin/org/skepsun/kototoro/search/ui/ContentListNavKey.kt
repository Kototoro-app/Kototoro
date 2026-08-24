package org.skepsun.kototoro.search.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Back-stack routes for [ContentListActivity]'s local Navigation 3 host.
 *
 * The root [WorkListRoute] renders the source's work list; every opened work
 * pushes a [WorkDetailsRoute] whose unique [WorkDetailsRoute.sessionId] makes
 * each open its own navigation entry. Navigation 3 then gives every details
 * session a dedicated ViewModelStore — created when the entry is pushed,
 * retained across configuration changes, and cleared after the pop transition —
 * instead of reusing Activity-scoped ViewModels that kept showing the first
 * work's details.
 *
 * The origin payload itself is not carried in the route (it is not
 * serializable); the host keeps it in saveable state ([ContentListActivity]'s
 * `lastDetailsOrigin`) so a restored back-stack entry can re-seed the
 * `PendingDetailsNavigation` hand-off after process death.
 */
@Serializable
internal sealed interface ContentListRouteKey : NavKey

@Serializable
internal data object WorkListRoute : ContentListRouteKey

@Serializable
internal data class WorkDetailsRoute(
    val sessionId: Long,
) : ContentListRouteKey
