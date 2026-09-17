package org.skepsun.kototoro.reader.render.compose

import androidx.compose.ui.Modifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.image.ReaderTile
import org.skepsun.kototoro.reader.image.ReaderTileManager
import org.skepsun.kototoro.reader.image.TileKey
import org.skepsun.kototoro.reader.image.TileStore

class TileDrawModifierNodeTest {

    private class FakeTileStore : TileStore {
        val listeners = mutableSetOf<ReaderTileManager.Listener>()
        val tiles = mutableMapOf<TileKey, ReaderTile>()

        override fun tile(key: TileKey): ReaderTile? = tiles[key]

        override fun addListener(listener: ReaderTileManager.Listener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: ReaderTileManager.Listener) {
            listeners.remove(listener)
        }
    }

    @Test
    fun `onAttach registers listener on tile store`() {
        val store = FakeTileStore()
        val node = TileDrawModifierNode(store)

        node.onAttach()
        assertTrue(store.listeners.contains(node))
    }

    @Test
    fun `onDetach unregisters listener from tile store`() {
        val store = FakeTileStore()
        val node = TileDrawModifierNode(store)

        node.onAttach()
        assertTrue(store.listeners.contains(node))

        node.onDetach()
        assertFalse(store.listeners.contains(node))
    }

    @Test
    fun `update unregisters old store and updates reference`() {
        val store1 = FakeTileStore()
        val store2 = FakeTileStore()
        val node = TileDrawModifierNode(store1)

        node.onAttach()
        assertTrue(store1.listeners.contains(node))

        node.update(store2)
        assertFalse(store1.listeners.contains(node))
        assertSame(store2, node.tileStore)
    }

    @Test
    fun `update with same store does not detach`() {
        val store = FakeTileStore()
        val node = TileDrawModifierNode(store)

        node.onAttach()
        assertTrue(store.listeners.contains(node))

        node.update(store)
        assertTrue(store.listeners.contains(node))
    }

    @Test
    fun `modifier extension returns same modifier when tileStore is null`() {
        val base = Modifier
        val result = base.tileDrawBridge(null)
        assertSame(base, result)
    }

    @Test
    fun `modifier extension creates chained element when tileStore is present`() {
        val store = FakeTileStore()
        val base = Modifier
        val result = base.tileDrawBridge(store)
        assertFalse(base === result)
    }
}
