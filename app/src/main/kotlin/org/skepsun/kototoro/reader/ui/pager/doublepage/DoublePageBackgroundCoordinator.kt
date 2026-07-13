package org.skepsun.kototoro.reader.ui.pager.doublepage

import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground

internal data class DoublePageBackgroundKey(
	val firstPageKey: Long,
	val secondPageKey: Long?,
)

internal class DoublePageBackgroundCoordinator {

	private data class Registration(
		val groupKey: DoublePageBackgroundKey,
		val pageKey: Long,
	)

	private data class Group(
		val colors: MutableMap<Long, Int> = mutableMapOf(),
		val holders: MutableSet<DoublePageHolder> = mutableSetOf(),
	)

	private val groups = mutableMapOf<DoublePageBackgroundKey, Group>()
	private val registrations = mutableMapOf<DoublePageHolder, Registration>()

	fun register(holder: DoublePageHolder, groupKey: DoublePageBackgroundKey, pageKey: Long) {
		unregister(holder)
		registrations[holder] = Registration(groupKey, pageKey)
		groups.getOrPut(groupKey) { Group() }.holders += holder
		applyGroupBackground(groupKey)
	}

	fun unregister(holder: DoublePageHolder) {
		val registration = registrations.remove(holder) ?: return
		groups[registration.groupKey]?.holders?.remove(holder)
	}

	fun onColorResolved(holder: DoublePageHolder, color: Int) {
		val registration = registrations[holder] ?: return
		val group = groups.getOrPut(registration.groupKey) { Group() }
		group.colors[registration.pageKey] = color
		applyGroupBackground(registration.groupKey)
	}

	private fun applyGroupBackground(groupKey: DoublePageBackgroundKey) {
		val group = groups[groupKey] ?: return
		val firstColor = group.colors[groupKey.firstPageKey]
		val secondColor = groupKey.secondPageKey?.let(group.colors::get)
		val resolved = when {
			firstColor != null -> ReaderAutoBackground.merge(firstColor, secondColor)
			secondColor != null -> secondColor
			else -> return
		}
		group.holders.forEach { it.applyCoordinatedBackground(resolved) }
	}
}
