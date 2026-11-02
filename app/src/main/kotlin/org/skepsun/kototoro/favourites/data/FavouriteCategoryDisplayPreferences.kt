package org.skepsun.kototoro.favourites.data

import android.content.Context
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.skepsun.kototoro.core.util.ext.observeChanges
import org.skepsun.kototoro.space.domain.SpaceId
import javax.inject.Inject

/**
 * Device-local answer to "which favourite categories does this Space show?".
 *
 * Favourite categories are shared between manga, anime and novels, and stay shared: this
 * preference only decides what a Space hides from its own tab strip and its
 * "add to favourites" dialog. Nothing here reaches the category tables, the backup format
 * or the sync protocol, so restoring a backup or pairing with another device never has to
 * reconcile it — it is display state, exactly like the list mode.
 *
 * The hidden set (not the visible set) is stored on purpose: a Space that was never
 * configured shows every category, and a category created later appears in every Space
 * without extra bookkeeping.
 */
@Reusable
class FavouriteCategoryDisplayPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Category ids hidden by [spaceId]. Emits the current value first and then on every
     * change; the empty set means "show everything", i.e. the unconfigured state.
     */
    fun observeHiddenCategoryIds(spaceId: SpaceId): Flow<Set<Long>> = prefs.observeChanges()
        .onStart { emit(null) }
        .map { getHiddenCategoryIds(spaceId) }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    fun getHiddenCategoryIds(spaceId: SpaceId): Set<Long> {
        val raw = prefs.getStringSet(keyOf(spaceId), null) ?: return emptySet()
        return raw.mapNotNullTo(LinkedHashSet(raw.size)) { it.toLongOrNull() }
    }

    /** Stores [ids] as the hidden set; an empty set removes the key entirely. */
    fun setHiddenCategoryIds(spaceId: SpaceId, ids: Set<Long>) {
        prefs.edit {
            if (ids.isEmpty()) {
                remove(keyOf(spaceId))
            } else {
                putStringSet(keyOf(spaceId), ids.mapTo(LinkedHashSet(ids.size)) { it.toString() })
            }
        }
    }

    fun setCategoryHidden(spaceId: SpaceId, categoryId: Long, isHidden: Boolean) {
        val current = getHiddenCategoryIds(spaceId).toMutableSet()
        if (if (isHidden) current.add(categoryId) else current.remove(categoryId)) {
            setHiddenCategoryIds(spaceId, current)
        }
    }

    fun showAll(spaceId: SpaceId) = setHiddenCategoryIds(spaceId, emptySet())

    private fun keyOf(spaceId: SpaceId): String = KEY_HIDDEN_PREFIX + spaceId.value

    private companion object {

        const val PREFS_NAME = "favourite_category_display"
        const val KEY_HIDDEN_PREFIX = "hidden:"
    }
}
