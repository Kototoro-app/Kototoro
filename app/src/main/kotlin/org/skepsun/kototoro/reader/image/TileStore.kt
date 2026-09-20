package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.PageId

/**
 * Read-only view of a resident tile cache queried by rendering backends.
 *
 * Conforms to ADR 0002 Constraint 3 & Constraint 4:
 * Decouples tile storage and eviction from rendering frameworks.
 */
interface TileStore {
    /**
     * Tile lifecycle observer. Belongs to the [TileStore] contract, not to any concrete
     * manager: renderers subscribe through the store without knowing the decode engine
     * (dependency inversion for the future `:scene-image` split, improvement plan §8.2).
     */
    interface Listener {
        /** A tile finished decoding and is now resident. */
        fun onTileReady(tile: ReaderTile) {}

        /** A resident tile left the working set (evicted or released); its payload was released. */
        fun onTileDropped(key: TileKey) {}

        /** Opening the region decode session for a page failed (reported once per failure). */
        fun onSessionFailed(pageId: PageId, error: Throwable) {}

        /** Decoding one tile failed; other tiles of the page may still succeed. */
        fun onTileFailed(pageId: PageId, key: TileKey, error: Throwable) {}
    }

    /** Returns the decoded tile for [key], or null if not yet resident. */
    fun tile(key: TileKey): ReaderTile?

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
}
