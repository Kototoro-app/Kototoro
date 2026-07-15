package org.skepsun.kototoro.space.data

import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionValidator
import org.skepsun.kototoro.space.domain.SpaceSourceAvailability
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceSessionValidator @Inject constructor(
	private val workResolver: WorkResolver,
) : SpaceSessionValidator {

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
		is SpaceRouteSnapshot.WorkDetails -> {
			val identity = workResolver.resolveByEntityId(entityId) ?: return null
			copy(requestedProjectionId = requestedProjectionId?.takeIf { it in identity.localMangaIds })
		}
		// Runtime source registries are transiently empty during cold start. Route restoration must not
		// destructively discard a valid saved destination before extension discovery finishes.
		is SpaceRouteSnapshot.ContentList -> this
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
