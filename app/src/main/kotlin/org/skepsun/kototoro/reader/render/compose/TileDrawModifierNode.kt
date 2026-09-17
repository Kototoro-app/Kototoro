package org.skepsun.kototoro.reader.render.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.image.ReaderTile
import org.skepsun.kototoro.reader.image.ReaderTileManager
import org.skepsun.kototoro.reader.image.TileKey
import org.skepsun.kototoro.reader.image.TileStore

/**
 * Connects tile store invalidations directly to the Draw phase via [DrawModifierNode.invalidateDraw].
 *
 * Implements ADR 0002 Constraint 3 & Phase 1C:
 * When background decode jobs produce new tiles or evict old ones, this node triggers a redraw
 * without causing Compose to re-execute Composition or Layout phases.
 */
class TileDrawModifierNode(
    var tileStore: TileStore?,
) : Modifier.Node(), DrawModifierNode, ReaderTileManager.Listener {

    override fun onAttach() {
        tileStore?.addListener(this)
    }

    override fun onDetach() {
        tileStore?.removeListener(this)
    }

    fun update(newStore: TileStore?) {
        if (tileStore !== newStore) {
            tileStore?.removeListener(this)
            tileStore = newStore
            if (isAttached) {
                newStore?.addListener(this)
                invalidateDraw()
            }
        }
    }

    override fun onTileReady(tile: ReaderTile) {
        if (isAttached) {
            coroutineScope.launch(Dispatchers.Main.immediate) {
                invalidateDraw()
            }
        }
    }

    override fun onTileDropped(key: TileKey) {
        if (isAttached) {
            coroutineScope.launch(Dispatchers.Main.immediate) {
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

/**
 * Modifier extension attaching the recomposition-free [TileDrawModifierNode] bridge.
 */
fun Modifier.tileDrawBridge(tileStore: TileStore?): Modifier {
    return if (tileStore != null) {
        this.then(TileDrawBridgeElement(tileStore))
    } else {
        this
    }
}

private data class TileDrawBridgeElement(
    val tileStore: TileStore,
) : ModifierNodeElement<TileDrawModifierNode>() {

    override fun create(): TileDrawModifierNode = TileDrawModifierNode(tileStore)

    override fun update(node: TileDrawModifierNode) {
        node.update(tileStore)
    }
}
