package org.skepsun.kototoro.reader.core

/** User camera motion is independent of the slot's canonical page group and history anchor. */
data class PagedCameraTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    init {
        require(scale.isFinite() && scale in 1f..MAX_SCALE)
        require(offsetX.isFinite() && offsetY.isFinite())
    }

    fun constrained(slot: PagedSlot): PagedCameraTransform {
        val bounds = contentBounds(slot)
        val x = PagedPanBoundsResolver.resolvePanRange(bounds.left, bounds.right, slot.bounds.width, scale)
        val y = PagedPanBoundsResolver.resolvePanRange(bounds.top, bounds.bottom, slot.bounds.height, scale)
        return copy(offsetX = offsetX.coerceIn(x), offsetY = offsetY.coerceIn(y))
    }

    /** Zoom around a pointer in viewport pixels, then consume pan within the existing core bounds. */
    fun transform(slot: PagedSlot, factor: Float = 1f, panX: Float = 0f, panY: Float = 0f,
        anchorX: Float = slot.bounds.width / 2f, anchorY: Float = slot.bounds.height / 2f): PagedCameraTransform {
        require(factor.isFinite() && factor > 0f)
        require(panX.isFinite() && panY.isFinite() && anchorX.isFinite() && anchorY.isFinite())
        val previous = constrained(slot)
        val nextScale = (previous.scale * factor).coerceIn(1f, MAX_SCALE)
        val ratio = nextScale / previous.scale
        val x = anchorX - slot.bounds.width / 2f
        val y = anchorY - slot.bounds.height / 2f
        // Clamp before constructing the immutable value so extreme finite inputs cannot publish infinity.
        val bounds = contentBounds(slot)
        val rangeX = PagedPanBoundsResolver.resolvePanRange(bounds.left, bounds.right, slot.bounds.width, nextScale)
        val rangeY = PagedPanBoundsResolver.resolvePanRange(bounds.top, bounds.bottom, slot.bounds.height, nextScale)
        return PagedCameraTransform(nextScale,
            (x - (x - previous.offsetX) * ratio + panX).coerceIn(rangeX),
            (y - (y - previous.offsetY) * ratio + panY).coerceIn(rangeY))
    }

    fun viewport(slot: PagedSlot): ReaderViewport {
        val camera = constrained(slot)
        return ReaderViewport(slot.contentViewport(camera.scale, camera.offsetX, camera.offsetY), camera.scale)
    }

    companion object {
        const val MAX_SCALE = 5f

        /** Overflow starts at the reading edge, including native-size content at scale one. */
        fun initial(slot: PagedSlot, rightToLeft: Boolean = false): PagedCameraTransform {
            val bounds = contentBounds(slot)
            return PagedCameraTransform(offsetX = PagedPanBoundsResolver.resolveInitialOverflowOffset(
                bounds.left, bounds.right, slot.bounds.width, 1f, rightToLeft),
                offsetY = PagedPanBoundsResolver.resolveInitialOverflowOffset(
                    bounds.top, bounds.bottom, slot.bounds.height, 1f))
        }

        private fun contentBounds(slot: PagedSlot): FloatRect {
            require(slot.bounds.width.isFinite() && slot.bounds.width > 0f &&
                slot.bounds.height.isFinite() && slot.bounds.height > 0f)
            if (slot.placements.isEmpty()) return FloatRect.fromLtwh(0f, 0f, slot.bounds.width, slot.bounds.height)
            val bounds = FloatRect(slot.placements.minOf { it.boundsInSlot.left },
                slot.placements.minOf { it.boundsInSlot.top }, slot.placements.maxOf { it.boundsInSlot.right },
                slot.placements.maxOf { it.boundsInSlot.bottom })
            require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() })
            return bounds
        }
    }
}
