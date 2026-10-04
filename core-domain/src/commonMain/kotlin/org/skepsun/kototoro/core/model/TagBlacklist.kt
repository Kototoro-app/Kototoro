package org.skepsun.kototoro.core.model

/** Platform-resolved tag matching consumed by the shared list rules. */
fun interface TagBlacklist {
    fun containsTagTitle(title: String): Boolean

    companion object {
        val Empty = TagBlacklist { false }
    }
}
