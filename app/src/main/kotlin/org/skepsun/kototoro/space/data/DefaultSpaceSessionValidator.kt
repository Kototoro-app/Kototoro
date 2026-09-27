package org.skepsun.kototoro.space.data

import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionValidator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceSessionValidator @Inject constructor() : SpaceSessionValidator {

    override suspend fun validate(snapshot: SpaceSessionSnapshot): SpaceSessionSnapshot {
        val validatedStacks = snapshot.stacks.mapNotNull { (stackKey, routes) ->
            if (stackKey !in VALID_TOP_LEVEL_KEYS) return@mapNotNull null
            val validated = routes.validatePrefix(stackKey)
            (stackKey to validated).takeIf { validated.isNotEmpty() }
        }.toMap()
        val selectedTopLevel = snapshot.selectedTopLevel.takeIf {
            it in VALID_TOP_LEVEL_KEYS && it in validatedStacks
        } ?: DEFAULT_TOP_LEVEL_KEY
        return snapshot.copy(
            selectedTopLevel = selectedTopLevel,
            resumeRoute = snapshot.resumeRoute?.validate(),
            stacks = validatedStacks,
        )
    }

    private suspend fun List<SpaceRouteSnapshot>.validatePrefix(
        stackKey: String,
    ): List<SpaceRouteSnapshot> {
        val result = ArrayList<SpaceRouteSnapshot>(size)
        for ((index, route) in withIndex()) {
            val validated = route.validate() ?: break
            if (index == 0 && validated != SpaceRouteSnapshot.TopLevel(stackKey)) break
            result += validated
        }
        return result
    }

    private suspend fun SpaceRouteSnapshot.validate(): SpaceRouteSnapshot? = when (this) {
        is SpaceRouteSnapshot.TopLevel -> takeIf { key in VALID_TOP_LEVEL_KEYS }
        // `WorkDetails` no longer names an entity, it names the manga to reopen. There is no in-memory identity registry left to
        // validate it against, and route restoration must not destructively discard a
        // valid saved destination before the details page has had a chance to resolve it,
        // so the route is kept as saved — the same policy as ContentList and Search.
        is SpaceRouteSnapshot.WorkDetails -> this
        // Runtime source registries are transiently empty during cold start. Route restoration must not
        // destructively discard a valid saved destination before extension discovery finishes.
        is SpaceRouteSnapshot.ContentList -> this
        // Search queries and their display filters are plain data; keep them as saved.
        is SpaceRouteSnapshot.Search -> this
    }

    private companion object {
        const val DEFAULT_TOP_LEVEL_KEY = "home"
        val VALID_TOP_LEVEL_KEYS = setOf(
            "home",
            "history",
            "favorites",
            "explore",
            "discover",
            "feed",
            "local",
            "suggestions",
            "bookmarks",
            "updated",
        )
    }
}
