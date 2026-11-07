package com.yalantis.ucrop.view

import android.graphics.RectF
import com.yalantis.ucrop.util.RectUtils

/**
 * Moves the crop frame to [rect] the way uCrop 2.2.8 moves it during a drag. uCrop keeps the frame's
 * corners and centre in protected fields for touch hit tests and caches its grid lines, all refreshed
 * by a private method; this package-level helper refreshes the same state without reflection, which a
 * release build would break by renaming the private method.
 */
internal fun OverlayView.setCropFrame(rect: RectF) {
    cropViewRect.set(rect)
    mCropGridCorners = RectUtils.getCornersFromRect(cropViewRect)
    mCropGridCenter = RectUtils.getCenterFromRect(cropViewRect)
    // Re-setting the row count drops the cached grid lines, which are rebuilt from the frame on draw.
    setCropGridRowCount(OverlayView.DEFAULT_CROP_GRID_ROW_COUNT)
    invalidate()
}
