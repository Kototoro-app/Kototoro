package org.skepsun.kototoro.reader.render.compose

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import org.skepsun.kototoro.reader.core.PageId

/**
 * Bridges Android Drawable animation callbacks directly to Compose's Draw phase.
 *
 * Implements ADR 0002 Constraint 3:
 * - Triggers [DrawModifierNode.invalidateDraw] without any Recomposition or Layout phases.
 * - Coordinates [Animatable.start] and [Animatable.stop] based on visible viewport state to
 *   prevent CPU/battery drain from off-screen animations.
 */
class AnimatedDrawBridge(
    var autoUpdateVisiblePages: Boolean = true,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var drawNode: DrawModifierNode? = null
    private val registeredDrawables = HashMap<PageId, Drawable>()

    internal fun attachNode(node: DrawModifierNode) {
        drawNode = node
    }

    internal fun detachNode(node: DrawModifierNode) {
        if (drawNode === node) {
            stopAll()
            drawNode = null
        }
    }

    fun register(pageId: PageId, drawable: Drawable) {
        val existing = registeredDrawables[pageId]
        if (existing === drawable) return
        existing?.callback = null
        if (existing is Animatable) existing.stop()

        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                drawNode?.invalidateDraw()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                mainHandler.postAtTime(what, who, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                mainHandler.removeCallbacks(what, who)
            }
        }
        registeredDrawables[pageId] = drawable
    }

    fun updateVisiblePages(visiblePageIds: Set<PageId>) {
        for ((pageId, drawable) in registeredDrawables) {
            if (drawable is Animatable) {
                val shouldRun = pageId in visiblePageIds
                if (shouldRun && !drawable.isRunning) {
                    drawable.start()
                } else if (!shouldRun && drawable.isRunning) {
                    drawable.stop()
                }
            }
        }
    }

    fun unregister(pageId: PageId) {
        val drawable = registeredDrawables.remove(pageId) ?: return
        drawable.callback = null
        if (drawable is Animatable && drawable.isRunning) {
            drawable.stop()
        }
    }

    fun stopAll() {
        for (drawable in registeredDrawables.values) {
            drawable.callback = null
            if (drawable is Animatable && drawable.isRunning) {
                drawable.stop()
            }
        }
        registeredDrawables.clear()
    }
}

fun Modifier.animatedDrawBridge(bridge: AnimatedDrawBridge?): Modifier {
    return if (bridge != null) {
        this.then(AnimatedDrawModifierElement(bridge))
    } else {
        this
    }
}

private data class AnimatedDrawModifierElement(
    val bridge: AnimatedDrawBridge,
) : ModifierNodeElement<AnimatedDrawModifierNode>() {
    override fun create(): AnimatedDrawModifierNode = AnimatedDrawModifierNode(bridge)

    override fun update(node: AnimatedDrawModifierNode) {
        node.updateBridge(bridge)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "animatedDrawBridge"
    }
}

internal class AnimatedDrawModifierNode(
    private var bridge: AnimatedDrawBridge,
) : Modifier.Node(), DrawModifierNode {

    override fun onAttach() {
        bridge.attachNode(this)
    }

    override fun onDetach() {
        bridge.detachNode(this)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }

    fun updateBridge(newBridge: AnimatedDrawBridge) {
        if (bridge !== newBridge) {
            bridge.detachNode(this)
            bridge = newBridge
            bridge.attachNode(this)
        }
    }
}
