package org.skepsun.kototoro.reader.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yalantis.ucrop.callback.BitmapCropCallback
import com.yalantis.ucrop.view.CropImageView
import com.yalantis.ucrop.view.GestureCropImageView
import com.yalantis.ucrop.view.OverlayView
import com.yalantis.ucrop.view.TransformImageView
import com.yalantis.ucrop.view.UCropView
import org.skepsun.kototoro.R
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.notes.domain.BookNotesRepository
import org.skepsun.kototoro.core.ui.adaptive.TvFilterChip
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import java.io.File
import javax.inject.Inject
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class PageCropActivity : BaseComposeActivity(), TransformImageView.TransformImageListener {

    @Inject
    lateinit var bookNotesRepository: BookNotesRepository

    private lateinit var cropImageView: GestureCropImageView
    private lateinit var overlayView: OverlayView
    private lateinit var outputUri: Uri
    private lateinit var compressFormat: Bitmap.CompressFormat
    private var compressQuality: Int = DEFAULT_COMPRESS_QUALITY
    private var originalRatio: Float = CropImageView.SOURCE_IMAGE_ASPECT_RATIO
    private var isCropping by mutableStateOf(false)
    private var isImageLoaded by mutableStateOf(false)
    private var selectedRatio by mutableFloatStateOf(CropImageView.SOURCE_IMAGE_ASPECT_RATIO)
    private var isAnnotationMode = false
    private var mangaId = 0L
    private var chapterId = 0L
    private var chapterIndex = 0
    private var page = 0
    private var noteText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceUri = intent.getParcelableExtra<Uri>(EXTRA_SOURCE_URI)
        val destinationUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_URI)
        if (sourceUri == null || destinationUri == null) {
            cancelCrop()
            return
        }
        outputUri = destinationUri
        compressFormat = parseCompressFormat(intent.getStringExtra(EXTRA_COMPRESS_FORMAT))
        compressQuality = intent.getIntExtra(EXTRA_COMPRESS_QUALITY, DEFAULT_COMPRESS_QUALITY)
        val sourceWidth = intent.getIntExtra(EXTRA_SOURCE_WIDTH, 0)
        val sourceHeight = intent.getIntExtra(EXTRA_SOURCE_HEIGHT, 0)
        isAnnotationMode = intent.getBooleanExtra(EXTRA_IS_ANNOTATION_MODE, false)
        mangaId = intent.getLongExtra(EXTRA_MANGA_ID, 0L)
        chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, 0L)
        chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
        page = intent.getIntExtra(EXTRA_PAGE, 0)
        originalRatio = if (sourceWidth > 0 && sourceHeight > 0) {
            sourceWidth.toFloat() / sourceHeight.toFloat()
        } else {
            CropImageView.SOURCE_IMAGE_ASPECT_RATIO
        }
        selectedRatio = originalRatio

        // setComposeContent (not setContent) is what publishes LocalUiPresentationConfig: without it
        // every TV helper in this screen silently takes the phone branch, so the remote cannot leave
        // the note field and the ratio chips never grow a focus ring.
        setComposeContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp),
                ) {
                    TvTextButton(onClick = ::cancelCrop) { Text(stringResource(android.R.string.cancel)) }
                    Text(
                        text = stringResource(if (isAnnotationMode) R.string.crop_and_annotate else R.string.crop_pages),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TvTextButton(onClick = ::saveCrop, enabled = isImageLoaded && !isCropping) {
                        Text(stringResource(if (isAnnotationMode) R.string.save_note else R.string.save))
                    }
                }
                AndroidView(
                    factory = { context ->
                        UCropView(context, null).also { cropView ->
                            cropImageView = cropView.cropImageView
                            overlayView = cropView.overlayView
                            overlayView.setFreestyleCropEnabled(true)
                            cropImageView.setTransformImageListener(this@PageCropActivity)
                            cropImageView.setImageToWrapCropBoundsAnimDuration(WRAP_ANIM_DURATION_MS)
                            cropImageView.setMaxScaleMultiplier(MAX_SCALE_MULTIPLIER)
                            // uCrop sizes the crop frame from the first layout only. When the window is still
                            // settling (e.g. launched during a rotation), re-apply the ratio at the final size.
                            cropView.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                                val resized = r - l != oldR - oldL || b - t != oldB - oldT
                                if (resized && isImageLoaded) cropView.post { applyAspectRatio(selectedRatio) }
                            }
                            runCatching { cropImageView.setImageUri(sourceUri, destinationUri) }
                                .onFailure { cancelCrop() }
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (isAnnotationMode) {
                    androidx.compose.material3.OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.manga_note_hint),
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        maxLines = 2,
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                            focusedContainerColor = Color(0x33000000),
                            unfocusedContainerColor = Color(0x22000000),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            // The note is the one thing a remote has to type here, and a Compose field
                            // keeps the arrow keys for its caret: without this the field is a dead end
                            // and the ratio chips below it can never be reached.
                            .tvTextInputFocusEscape(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(8.dp),
                ) {
                    RatioChip(stringResource(com.yalantis.ucrop.R.string.ucrop_label_original), originalRatio)
                    RatioChip("1:1", 1f)
                    RatioChip("4:3", 4f / 3f)
                    RatioChip("16:9", 16f / 9f)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RatioChip(label: String, ratio: Float) {
        // On TV the crop frame itself cannot be dragged by a remote, so these presets are the only
        // way to change it and they have to be both reachable and visibly focused.
        TvFilterChip(
            selected = selectedRatio == ratio,
            onClick = { applyAspectRatio(ratio) },
            label = label,
        )
    }

    override fun onLoadComplete() {
        applyAspectRatio(originalRatio)
        isImageLoaded = true
    }

    override fun onLoadFailure(e: Exception) = cancelCrop()

    override fun onRotate(currentAngle: Float) = Unit

    override fun onScale(currentScale: Float) = Unit

    private fun saveCrop() {
        if (!isImageLoaded || isCropping) return
        isCropping = true
        cropImageView.cropAndSaveImage(
            compressFormat,
            compressQuality,
            object : BitmapCropCallback {
                override fun onBitmapCropped(
                    resultUri: Uri,
                    imageWidth: Int,
                    imageHeight: Int,
                    offsetX: Int,
                    offsetY: Int,
                ) {
                    if (isAnnotationMode) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val notesDir = File(filesDir, "notes").apply { mkdirs() }
                            val noteFile = File(notesDir, "manga_${mangaId}_${System.currentTimeMillis()}.jpg")
                            runCatching {
                                contentResolver.openInputStream(resultUri)?.use { input ->
                                    noteFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                bookNotesRepository.saveMangaCropNote(
                                    mangaId = mangaId,
                                    chapterId = chapterId,
                                    chapterIndex = chapterIndex,
                                    page = page,
                                    imagePath = noteFile.absolutePath,
                                    note = noteText.ifBlank { null },
                                )
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@PageCropActivity, R.string.manga_note_saved, Toast.LENGTH_SHORT).show()
                                setResult(Activity.RESULT_OK, Intent().setData(resultUri))
                                finish()
                            }
                        }
                    } else {
                        setResult(Activity.RESULT_OK, Intent().setData(resultUri))
                        finish()
                    }
                }

                override fun onCropFailure(t: Throwable) = cancelCrop()
            },
        )
    }

    private fun cancelCrop() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun applyAspectRatio(ratio: Float) {
        if (!::cropImageView.isInitialized || !::overlayView.isInitialized) return
        val targetRatio = if (ratio > 0f) ratio else CropImageView.SOURCE_IMAGE_ASPECT_RATIO
        selectedRatio = ratio
        overlayView.setTargetAspectRatio(targetRatio)
        cropImageView.setTargetAspectRatio(targetRatio)
        resetScaleToMin()
        cropImageView.setImageToWrapCropBounds(true)
    }

    private fun resetScaleToMin() {
        val minScale = cropImageView.minScale
        val currentScale = cropImageView.currentScale
        if (currentScale > minScale && cropImageView.width > 0 && cropImageView.height > 0) {
            cropImageView.postScale(
                minScale / currentScale,
                cropImageView.width / 2f,
                cropImageView.height / 2f,
            )
        }
    }

    private fun parseCompressFormat(formatName: String?): Bitmap.CompressFormat =
        runCatching { Bitmap.CompressFormat.valueOf(formatName.orEmpty()) }
            .getOrDefault(Bitmap.CompressFormat.PNG)

    companion object {
        internal const val EXTRA_SOURCE_URI = "page_crop_source_uri"
        internal const val EXTRA_OUTPUT_URI = "page_crop_output_uri"
        internal const val EXTRA_COMPRESS_FORMAT = "page_crop_compress_format"
        internal const val EXTRA_COMPRESS_QUALITY = "page_crop_compress_quality"
        internal const val EXTRA_SOURCE_WIDTH = "page_crop_source_width"
        internal const val EXTRA_SOURCE_HEIGHT = "page_crop_source_height"
        internal const val EXTRA_IS_ANNOTATION_MODE = "page_crop_is_annotation_mode"
        internal const val EXTRA_MANGA_ID = "page_crop_manga_id"
        internal const val EXTRA_CHAPTER_ID = "page_crop_chapter_id"
        internal const val EXTRA_CHAPTER_INDEX = "page_crop_chapter_index"
        internal const val EXTRA_PAGE = "page_crop_page"

        private const val DEFAULT_COMPRESS_QUALITY = 95
        private const val WRAP_ANIM_DURATION_MS = 180L
        private const val MAX_SCALE_MULTIPLIER = 20f
    }
}
