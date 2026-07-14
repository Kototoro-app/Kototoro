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
	private val sourceRegistry: SpaceSourceAvailability,
) : SpaceSessionValidator {

	override suspend fun validate(snapshot: SpaceSessionSnapshot): SpaceSessionSnapshot {
		val sourceAvailabilityCache = HashMap<String, Boolean>()
		val validatedStacks = snapshot.stacks.mapNotNull { (stackKey, routes) ->
			if (stackKey !in VALID_TOP_LEVEL_KEYS) return@mapNotNull null
			val validated = routes.validatePrefix(stackKey, sourceAvailabilityCache)
			(stackKey to validated).takeIf { validated.isNotEmpty() }
		}.toMap()
		val selectedTopLevel = snapshot.selectedTopLevel.takeIf {
			it in VALID_TOP_LEVEL_KEYS && it in validatedStacks
		} ?: DEFAULT_TOP_LEVEL_KEY
		return snapshot.copy(
			selectedTopLevel = selectedTopLevel,
			resumeRoute = snapshot.resumeRoute?.validate(sourceAvailabilityCache),
			stacks = validatedStacks,
		)
	}

	private suspend fun List<SpaceRouteSnapshot>.validatePrefix(
		stackKey: String,
		sourceAvailability: MutableMap<String, Boolean>,
	): List<SpaceRouteSnapshot> {
		val result = ArrayList<SpaceRouteSnapshot>(size)
		for ((index, route) in withIndex()) {
			val validated = route.validate(sourceAvailability) ?: break
			if (index == 0 && validated != SpaceRouteSnapshot.TopLevel(stackKey)) break
			result += validated
		}
		return result
	}

	private suspend fun SpaceRouteSnapshot.validate(
		sourceAvailability: MutableMap<String, Boolean>,
	): SpaceRouteSnapshot? = when (this) {
		is SpaceRouteSnapshot.TopLevel -> takeIf { key in VALID_TOP_LEVEL_KEYS }
		is SpaceRouteSnapshot.WorkDetails -> {
			val identity = workResolver.resolveByEntityId(entityId) ?: return null
			copy(requestedProjectionId = requestedProjectionId?.takeIf { it in identity.localMangaIds })
		}
		is SpaceRouteSnapshot.ContentList -> takeIf {
			val available = sourceAvailability[sourceName] ?: sourceRegistry.isAvailable(sourceName).also {
				sourceAvailability[sourceName] = it
			}
			available
		}
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
