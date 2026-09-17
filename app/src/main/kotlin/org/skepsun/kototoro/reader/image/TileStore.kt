package org.skepsun.kototoro.reader.image

/**
 * Read-only view of a resident tile cache queried by rendering backends.
 *
 * Conforms to ADR 0002 Constraint 3 & Constraint 4:
 * Decouples tile storage and eviction from rendering frameworks.
 */
interface TileStore {
    /** Returns the decoded tile for [key], or null if not yet resident. */
    fun tile(key: TileKey): ReaderTile?

    fun addListener(listener: ReaderTileManager.Listener)
    fun removeListener(listener: ReaderTileManager.Listener)
}
