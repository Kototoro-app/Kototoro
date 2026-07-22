package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import org.skepsun.kototoro.core.ui.image.TrimTransformation
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

/** Applies the same crop-then-split order as the legacy reader viewport. */
internal class ComposeReaderPageTransformation(
	private val isCropEnabled: Boolean,
	private val split: ReaderPageSplit,
) : Transformation() {

	override val cacheKey: String = "ComposeReaderPageTransformation-$isCropEnabled-${split.name}"

	override suspend fun transform(input: Bitmap, size: Size): Bitmap {
		val cropped = if (isCropEnabled) TrimTransformation().transform(input, size) else input
		if (split == ReaderPageSplit.NONE || cropped.width < 2) return cropped
		val halfWidth = cropped.width / 2
		val left = if (split == ReaderPageSplit.LEFT) 0 else cropped.width - halfWidth
		return Bitmap.createBitmap(cropped, left, 0, halfWidth, cropped.height)
	}
}
