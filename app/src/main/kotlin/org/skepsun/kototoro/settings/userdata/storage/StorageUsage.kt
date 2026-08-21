package org.skepsun.kototoro.settings.userdata.storage

enum class StorageUsageCategory {
    LOCAL_MANGA,
    LOCAL_NOVELS,
    LOCAL_VIDEOS,
    THUMBS_CACHE,
    FAVICONS_CACHE,
    PAGES_CACHE,
    NOVELS_CACHE,
    VIDEO_CACHE,
        VIDEO_PROXY_CACHE,
        TORRENT_CACHE,
        DANMAKU_CACHE,
    TTS_CACHE,
    SUPER_RESOLUTION_CACHE,
    HTTP_CACHE,
    AI_MODELS,
    OTHER_CACHE,
    AVAILABLE,
}

data class StorageUsage(
    val items: List<Item>,
) {
    data class Item(
        val category: StorageUsageCategory,
        val bytes: Long,
        val percent: Float,
    )

    fun find(category: StorageUsageCategory): Item? = items.firstOrNull { it.category == category }
}
