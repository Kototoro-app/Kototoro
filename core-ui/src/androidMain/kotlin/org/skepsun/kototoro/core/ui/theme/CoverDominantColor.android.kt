package org.skepsun.kototoro.core.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun ImageBitmap.toReadableSoftwareBitmap(): ImageBitmap? {
    val androidBitmap = asAndroidBitmap()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && androidBitmap.config == Bitmap.Config.HARDWARE) {
        return androidBitmap.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
    }
    return this
}
