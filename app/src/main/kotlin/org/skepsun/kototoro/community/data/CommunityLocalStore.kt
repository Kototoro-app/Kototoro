package org.skepsun.kototoro.community.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.community.domain.CommunityCommentFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this reader chose not to see (plan §7.2): authors they blocked and comments they reported. Both take effect at
 * once and stay on this device; a report also goes to the relays for the index to weigh.
 */
@Singleton
class CommunityLocalStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val filter: CommunityCommentFilter
        get() = CommunityCommentFilter(
            blockedPubkeys = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty(),
            blockedEvents = prefs.getStringSet(KEY_REPORTED, emptySet()).orEmpty(),
        )

    fun block(pubkey: String) = add(KEY_BLOCKED, pubkey)

    fun hide(eventId: String) = add(KEY_REPORTED, eventId)

    fun clear() = prefs.edit { clear() }

    private fun add(key: String, value: String) {
        val current = prefs.getStringSet(key, emptySet()).orEmpty()
        prefs.edit { putStringSet(key, current + value) }
    }

    private companion object {
        const val PREFS = "community_local"
        const val KEY_BLOCKED = "blocked_pubkeys"
        const val KEY_REPORTED = "reported_events"
    }
}
