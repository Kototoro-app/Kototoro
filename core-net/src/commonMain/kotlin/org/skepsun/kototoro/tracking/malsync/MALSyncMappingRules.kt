package org.skepsun.kototoro.tracking.malsync

enum class MALSyncService(val apiPath: String?) {
    MAL("mal"),
    ANILIST("anilist"),
    KITSU("kitsu"),
    SHIKIMORI("shikimori"),
    BANGUMI(null),
    MANGAUPDATES(null),
}

enum class MALSyncKind(val slug: String) {
    MANGA("manga"),
    ANIME("anime"),
}

/** Platform JSON parsing retains its coercion and iteration behavior, projecting only mapping fields. */
data class MALSyncEntry(
    val siteKey: String,
    val entryKey: String,
    val identifier: String?,
    val title: String?,
    val url: String?,
)

data class MALSyncMapping(
    val service: MALSyncService,
    val remoteId: Long,
    val title: String?,
    val url: String?,
)

object MALSyncMappingRules {
    fun map(entries: List<MALSyncEntry>, source: MALSyncService): List<MALSyncMapping> = entries.mapNotNull { entry ->
        val service = when (entry.siteKey.lowercase()) {
            "mal" -> MALSyncService.MAL
            "anilist" -> MALSyncService.ANILIST
            "kitsu" -> MALSyncService.KITSU
            "shikimori" -> MALSyncService.SHIKIMORI
            "bangumi" -> MALSyncService.BANGUMI
            "mangaupdates" -> MALSyncService.MANGAUPDATES
            else -> return@mapNotNull null
        }
        if (service == source) return@mapNotNull null
        val id = entry.identifier?.takeIf { it.isNotBlank() }?.toLongOrNull()
            ?: entry.entryKey.toLongOrNull()
            ?: return@mapNotNull null
        MALSyncMapping(service, id, entry.title?.ifBlank { null }, entry.url?.ifBlank { null })
    }.distinctBy { it.service to it.remoteId }
}
