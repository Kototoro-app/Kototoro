package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Rect

internal class ReaderTextMergeCoordinator(
	private val shouldMergeFragments: (TextFragment, TextFragment) -> Boolean,
	private val mergeRects: (List<Rect>) -> Rect?,
	private val composeMergedText: (List<TextFragment>, String) -> String,
) {

	fun merge(
		fragments: List<TextFragment>,
		sourceLang: String,
	): List<TextFragment> {
		if (fragments.isEmpty()) return emptyList()
		if (fragments.size == 1) return fragments

		val parent = IntArray(fragments.size) { it }
		val edgeDistances = linkedMapOf<Pair<Int, Int>, Float>()

		fun find(x: Int): Int {
			var current = x
			while (parent[current] != current) {
				parent[current] = parent[parent[current]]
				current = parent[current]
			}
			return current
		}

		fun union(a: Int, b: Int) {
			val rootA = find(a)
			val rootB = find(b)
			if (rootA != rootB) {
				parent[rootB] = rootA
			}
		}

		for (i in fragments.indices) {
			for (j in i + 1 until fragments.size) {
				if (shouldMergeFragments(fragments[i], fragments[j])) {
					edgeDistances[i to j] = normalizedDistance(fragments[i], fragments[j])
				}
			}
		}

		applyEdgeDistancePruning(edgeDistances, fragments.size)

		for ((edge, _) in edgeDistances) {
			union(edge.first, edge.second)
		}

		val groups = linkedMapOf<Int, MutableList<TextFragment>>()
		for (index in fragments.indices) {
			groups.getOrPut(find(index)) { mutableListOf() }.add(fragments[index])
		}

		return groups.values.mapNotNull { group ->
			val mergedRect = mergeRects(group.map { it.rect }) ?: return@mapNotNull null
			val mergedText = composeMergedText(group, sourceLang).trim()
			if (mergedText.isEmpty()) {
				null
			} else {
				TextFragment(
					rect = mergedRect,
					text = mergedText,
					directionHint = majorityDirection(group),
					angleHintDegrees = majorityAngle(group),
					isAxisAligned = group.all { it.isAxisAligned },
					quadPoints = mergeQuadPoints(group, mergedRect),
				)
			}
		}.sortedWith(compareBy<TextFragment> { it.rect.top }.thenBy { it.rect.left })
	}

	private fun majorityDirection(group: List<TextFragment>): TextDirectionHint {
		if (group.isEmpty()) return TextDirectionHint.UNKNOWN
		val counts = group.groupingBy { it.directionHint }.eachCount()
		val vertical = counts[TextDirectionHint.VERTICAL] ?: 0
		val horizontal = counts[TextDirectionHint.HORIZONTAL] ?: 0
		return when {
			vertical > horizontal -> TextDirectionHint.VERTICAL
			horizontal > vertical -> TextDirectionHint.HORIZONTAL
			else -> TextDirectionHint.UNKNOWN
		}
	}

	private fun applyEdgeDistancePruning(
		edgeDistances: MutableMap<Pair<Int, Int>, Float>,
		nodeCount: Int,
	) {
		if (edgeDistances.isEmpty() || nodeCount < 3) return
		val removeEdges = linkedSetOf<Pair<Int, Int>>()
		for (node in 0 until nodeCount) {
			val neighbors = edgeDistances.entries
				.filter { (edge, _) -> edge.first == node || edge.second == node }
				.map { entry ->
					val neighbor = if (entry.key.first == node) entry.key.second else entry.key.first
					Triple(entry.key, neighbor, entry.value)
				}
				.sortedBy { it.third }
			if (neighbors.size < 2) continue
			val minDistance = neighbors.first().third
			if (minDistance <= 0f) continue
			for ((edge, _, distance) in neighbors.drop(1)) {
				if (distance / minDistance > EDGE_RATIO_THRESHOLD) {
					removeEdges += edge
				}
			}
		}
		removeEdges.forEach { edgeDistances.remove(it) }
	}

	private fun majorityAngle(group: List<TextFragment>): Float {
		if (group.isEmpty()) return 0f
		val vertical = group.count { it.directionHint == TextDirectionHint.VERTICAL }
		val horizontal = group.count { it.directionHint == TextDirectionHint.HORIZONTAL }
		return if (vertical > horizontal) 90f else 0f
	}

	private fun mergeQuadPoints(group: List<TextFragment>, mergedRect: Rect): TextQuad {
		return group.firstNotNullOfOrNull { fragment ->
			fragment.quadPoints.takeIf { fragment.rect == mergedRect }
		} ?: rectToTextQuad(mergedRect)
	}

	private fun normalizedDistance(a: TextFragment, b: TextFragment): Float {
		val gapX = axisGap(a.rect.left, a.rect.right, b.rect.left, b.rect.right).toFloat()
		val gapY = axisGap(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom).toFloat()
		val minW = minOf(a.rect.width(), b.rect.width()).coerceAtLeast(1)
		val minH = minOf(a.rect.height(), b.rect.height()).coerceAtLeast(1)
		val rectDistance = minOf(gapX / minW, gapY / minH) + maxOf(gapX / minW, gapY / minH) * 0.25f
		val quadDistance = normalizedQuadDistance(a, b)
		return if (quadDistance < rectDistance) {
			quadDistance * 0.75f + rectDistance * 0.25f
		} else {
			rectDistance
		}
	}

	private fun normalizedQuadDistance(a: TextFragment, b: TextFragment): Float {
		val minPointDistance = minimumQuadPointDistance(a.quadPoints, b.quadPoints)
		val scale = minOf(
			a.rect.width().coerceAtLeast(1),
			a.rect.height().coerceAtLeast(1),
			b.rect.width().coerceAtLeast(1),
			b.rect.height().coerceAtLeast(1),
		).toFloat().coerceAtLeast(1f)
		return minPointDistance / scale
	}

	private fun minimumQuadPointDistance(a: TextQuad, b: TextQuad): Float {
		var best = Float.MAX_VALUE
		for ((ax, ay) in a.points) {
			for ((bx, by) in b.points) {
				val dx = ax - bx
				val dy = ay - by
				val distance = kotlin.math.sqrt(dx * dx + dy * dy)
				if (distance < best) {
					best = distance
				}
			}
		}
		return best
	}

	private fun axisGap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
		return when {
			aEnd < bStart -> bStart - aEnd
			bEnd < aStart -> aStart - bEnd
			else -> 0
		}
	}

	private companion object {
		const val EDGE_RATIO_THRESHOLD = 2.5f
	}
}
