package org.skepsun.kototoro.reader.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import android.content.Intent

data class PageCropRequest(
    val source: Uri,
    val destination: Uri,
    val compressFormat: Bitmap.CompressFormat,
    val compressQuality: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val isAnnotationMode: Boolean = false,
    val mangaId: Long = 0L,
    val chapterId: Long = 0L,
    val chapterIndex: Int = 0,
    val page: Int = 0,
    /** The pages before and after [source] that the editor may stack onto it. */
    val previousSource: Uri? = null,
    val nextSource: Uri? = null,
    val stitchDirection: StitchDirection = StitchDirection.TOP_TO_BOTTOM,
)

class PageCropContract : ActivityResultContract<PageCropRequest, Uri?>() {

    override fun createIntent(context: Context, input: PageCropRequest): Intent {
        return Intent(context, PageCropActivity::class.java).apply {
            putExtra(PageCropActivity.EXTRA_SOURCE_URI, input.source)
            putExtra(PageCropActivity.EXTRA_OUTPUT_URI, input.destination)
            putExtra(PageCropActivity.EXTRA_COMPRESS_FORMAT, input.compressFormat.name)
            putExtra(PageCropActivity.EXTRA_COMPRESS_QUALITY, input.compressQuality)
            putExtra(PageCropActivity.EXTRA_SOURCE_WIDTH, input.sourceWidth)
            putExtra(PageCropActivity.EXTRA_SOURCE_HEIGHT, input.sourceHeight)
            putExtra(PageCropActivity.EXTRA_IS_ANNOTATION_MODE, input.isAnnotationMode)
            putExtra(PageCropActivity.EXTRA_MANGA_ID, input.mangaId)
            putExtra(PageCropActivity.EXTRA_CHAPTER_ID, input.chapterId)
            putExtra(PageCropActivity.EXTRA_CHAPTER_INDEX, input.chapterIndex)
            putExtra(PageCropActivity.EXTRA_PAGE, input.page)
            putExtra(PageCropActivity.EXTRA_PREVIOUS_SOURCE_URI, input.previousSource)
            putExtra(PageCropActivity.EXTRA_NEXT_SOURCE_URI, input.nextSource)
            putExtra(PageCropActivity.EXTRA_STITCH_DIRECTION, input.stitchDirection.name)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) {
            return null
        }
        return intent?.data
    }
}
