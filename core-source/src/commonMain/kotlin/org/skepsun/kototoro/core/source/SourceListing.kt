package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable

/** The extension ecosystems a host can serve sources from; shown to users, never part of a source's identity. */
@Serializable
enum class SourceEcosystem { MIHON, KOTOTORO, KOTATSU, UMA, ANIYOMI, TSUNDOKU, CLOUDSTREAM }

/** One selectable source as a host presents it: identity plus presentation and provenance. */
@Serializable
data class SourceListing(
    val source: SourceRef,
    val displayName: String,
    val ecosystem: SourceEcosystem,
    val supportsLatest: Boolean,
    /** The ecosystem's own numeric identity when it has one (Mihon); 0 otherwise. */
    @Serializable(with = SourceLongSerializer::class) val sourceId: Long = 0,
)
