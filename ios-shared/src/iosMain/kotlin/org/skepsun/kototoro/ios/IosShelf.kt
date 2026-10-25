package org.skepsun.kototoro.ios

import org.skepsun.kototoro.library.domain.ShelfDownload
import org.skepsun.kototoro.library.domain.ShelfFilter
import org.skepsun.kototoro.library.domain.ShelfMacro
import org.skepsun.kototoro.library.domain.ShelfReading
import org.skepsun.kototoro.library.domain.ShelfRow

/** Android's library filter groups (shared `ShelfFilter`) for the iOS library page. */
object IosShelf {
    /** [reading], [download] and [macros] are the shared enum names. */
    fun filter(sources: List<String>, tags: List<String>, states: List<String>, reading: String, download: String,
               macros: List<String>): ShelfFilter = ShelfFilter(
        sources = sources.toSet(), tags = tags.toSet(), states = states.toSet(),
        reading = ShelfReading.entries.firstOrNull { it.name == reading } ?: ShelfReading.ALL,
        download = ShelfDownload.entries.firstOrNull { it.name == download } ?: ShelfDownload.ALL,
        macros = macros.mapNotNull { name -> ShelfMacro.entries.firstOrNull { it.name == name } }.toSet(),
    )

    fun accepts(filter: ShelfFilter, content: IosSourceContent, progress: Float?, onDevice: Boolean, newChapters: Int): Boolean =
        filter.accepts(ShelfRow(content.sourceName, content.tags, content.state, progress, onDevice, newChapters, content.isNsfw))
}
