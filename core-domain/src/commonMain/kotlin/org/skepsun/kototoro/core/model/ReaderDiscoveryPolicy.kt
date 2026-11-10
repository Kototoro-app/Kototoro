package org.skepsun.kototoro.core.model

/** Classifies provider category identifiers, never user queries or work titles. */
object ReaderDiscoveryPolicy {
    fun acceptsCategory(categoryId: String?, serviceName: String): Boolean {
        val service = serviceName.uppercase()
        if (service == "SIMKL") return false
        if (categoryId.isNullOrBlank()) return true
        val id = categoryId.lowercase()
        if (service == "KITSU" && !id.contains("manga")) return false
        return listOf("anime", "movie", "ova", "tv", "calendar", "real", "seasonal").none(id::contains)
    }
}
