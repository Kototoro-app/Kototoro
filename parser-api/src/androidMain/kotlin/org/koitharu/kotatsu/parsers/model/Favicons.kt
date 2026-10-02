package org.koitharu.kotatsu.parsers.model

public class Favicons(
	favicons: Collection<Favicon>,
	@JvmField public val referer: String?,
) : Collection<Favicon> {

	private val icons = favicons.sortedDescending()

	override val size: Int
		get() = icons.size

	override fun contains(element: Favicon): Boolean = icons.contains(element)

	override fun containsAll(elements: Collection<Favicon>): Boolean = icons.containsAll(elements)

	override fun isEmpty(): Boolean = icons.isEmpty()

	override fun iterator(): Iterator<Favicon> = icons.iterator()

	public operator fun minus(victim: Favicon): Favicons = Favicons(
		favicons = icons.filterNot { it == victim },
		referer = referer,
	)

	/**
	 * Finds a favicon whose size in pixels is greater than or equal to the specified size.
	 * If such icon is not available returns the largest icon
	 * @param size in pixels
	 * @param types supported file types, e.g. png, svg, ico. May be null but not empty
	 */
	@JvmOverloads
	public fun find(size: Int, types: Set<String>? = null): Favicon? {
		if (icons.isEmpty()) {
			return null
		}
		var result: Favicon? = null
		for (icon in icons) {
			if (types != null && icon.type !in types) {
				continue
			}
			if (result == null || icon.size >= size) {
				result = icon
			} else {
				break
			}
		}
		return result
	}

	public companion object {

		@JvmStatic
		public val EMPTY: Favicons = Favicons(emptySet(), null)

		@JvmStatic
		public fun single(url: String): Favicons = Favicons(setOf(Favicon(url, 0, null)), null)
	}
}
