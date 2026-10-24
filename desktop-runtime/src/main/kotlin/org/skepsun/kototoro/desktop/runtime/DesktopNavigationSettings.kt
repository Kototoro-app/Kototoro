package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.prefs.MainNavigationRules
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences

/**
 * The rail destinations a Windows user can reorder, hide or add back. The vocabulary is Windows' own (the shared
 * Android enum carries `R.id`/`R.drawable` handles), but the document is Android's: one comma-joined string under
 * `nav_main`, capped and deduplicated by [MainNavigationRules].
 *
 * "更多" is not listed here on purpose. It is the only way into every other page, so the rail always appends it after
 * the configured destinations instead of letting the user hide the exit.
 */
enum class DesktopNavItem {
    HOME,
    FAVOURITES,
    BROWSE,
    HISTORY,
    SUBSCRIPTIONS,
}

/** The default order, matching the rail Android's tablet shell ships and the Windows shell drew before this page. */
val defaultDesktopNavItems = listOf(
    DesktopNavItem.HOME,
    DesktopNavItem.FAVOURITES,
    DesktopNavItem.BROWSE,
    DesktopNavItem.HISTORY,
    DesktopNavItem.SUBSCRIPTIONS,
)

/**
 * The stored main-navigation list. [items] is always the shape the rail can render: no repeats, at most
 * [MainNavigationRules.MAX_ITEMS], never empty and never unknown — [from] resolves the stored string, and every edit
 * goes back through the shared rules before it is saved.
 */
data class DesktopNavigationSettings(val items: List<DesktopNavItem> = defaultDesktopNavItems) {

    val canAdd: Boolean get() = MainNavigationRules.canAdd(items)

    /** Destinations the add dialog may offer, in [DesktopNavItem] declaration order. */
    val available: List<DesktopNavItem>
        get() = MainNavigationRules.available(DesktopNavItem.entries, items)

    fun step(item: DesktopNavItem, direction: Int): DesktopNavigationSettings =
        copy(items = MainNavigationRules.step(items, item, direction))

    fun move(from: Int, to: Int): DesktopNavigationSettings =
        copy(items = MainNavigationRules.move(items, from, to))

    fun add(item: DesktopNavItem): DesktopNavigationSettings =
        copy(items = MainNavigationRules.add(items, item))

    fun remove(item: DesktopNavItem): DesktopNavigationSettings =
        copy(items = MainNavigationRules.remove(items, item, fallback = DesktopNavItem.HOME))

    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_MAIN to SourcePreferenceValue.Text(format(items)),
    )

    companion object {
        /** Android's key (`AppSettings.KEY_NAV_MAIN`) and its comma-joined format. */
        const val KEY_MAIN = "nav_main"

        fun format(items: Iterable<DesktopNavItem>): String = MainNavigationRules.format(items) { it.name }

        fun from(values: Map<String, SourcePreferenceValue>): DesktopNavigationSettings {
            val raw = (values[KEY_MAIN] as? SourcePreferenceValue.Text)?.value
            val items = MainNavigationRules.parse(
                raw = raw,
                resolve = { key -> DesktopNavItem.entries.find { it.name.equals(key, ignoreCase = true) } },
                fallback = defaultDesktopNavItems,
            )
            return DesktopNavigationSettings(items)
        }
    }
}

/**
 * The stored navigation list. Its own store (`desktop_nav`) keeps the rail document away from the library, appearance
 * and source stores; the key inside still matches Android's so the two platforms agree on the shape.
 */
class DesktopNavigationPreferences(private val preferences: SourcePreferences) {
    private val mutableSettings = MutableStateFlow(DesktopNavigationSettings.from(preferences.snapshot()))
    val settings: StateFlow<DesktopNavigationSettings> = mutableSettings.asStateFlow()

    fun current(): DesktopNavigationSettings = mutableSettings.value

    fun save(value: DesktopNavigationSettings): Boolean {
        val persisted = preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))
        mutableSettings.value = value
        return persisted
    }

    fun reload() {
        mutableSettings.value = DesktopNavigationSettings.from(preferences.snapshot())
    }
}
