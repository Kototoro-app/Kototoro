package org.skepsun.kototoro.community.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Bridges the gap between a successful publish acknowledgement and relay queries returning the event. */
class CommunityRecentPublications {
    private val comments = MutableStateFlow<List<NostrEvent>>(emptyList())
    private val profiles = MutableStateFlow<Map<String, NostrEvent>>(emptyMap())

    fun remember(event: NostrEvent) {
        when (event.kind) {
            CommunityEventSchema.KIND_COMMENT -> comments.update { (listOf(event) + it).distinctBy(NostrEvent::id).take(100) }
            CommunityProfiles.KIND -> profiles.update { CommunityProfiles.latest(it.values.toList() + event) }
        }
    }

    fun comments(keys: List<String>): List<NostrEvent> =
        comments.value.filter { event -> event.tagValues("I").any { it in keys } }

    fun profiles(authors: List<String>): List<NostrEvent> = authors.distinct().mapNotNull(profiles.value::get)
}
