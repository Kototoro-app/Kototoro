package org.skepsun.kototoro.reader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import androidx.annotation.WorkerThread
import androidx.core.net.toFile
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yalantis.ucrop.callback.BitmapCropCallback
import com.yalantis.ucrop.view.CropImageView
import com.yalantis.ucrop.view.GestureCropImageView
import com.yalantis.ucrop.view.OverlayView
import com.yalantis.ucrop.view.TransformImageView
import com.yalantis.ucrop.view.UCropView
import com.yalantis.ucrop.view.setCropFrame
import org.skepsun.kototoro.R
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.notes.domain.BookNotesRepository
import org.skepsun.kototoro.core.ui.adaptive.TvFilterChip
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import java.io.File
import kotlin.math.abs
import javax.inject.Inject
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

@AndroidEntryPoint
class PageCropActivity : BaseComposeActivity(), TransformImageView.TransformImageListener {

    @Inject
    lateinit var bookNotesRepository: BookNotesRepository

    private lateinit var cropImageView: GestureCropImageView
    private lateinit var overlayView: OverlayView
    private lateinit var outputUri: Uri
    private lateinit var sourceUri: Uri
    /** What the editor shows: the page itself or the page stitched with its neighbours. */
    private var editedUri by mutableStateOf(Uri.EMPTY)
    private var sourceRatio: Float = CropImageView.SOURCE_IMAGE_ASPECT_RATIO
    private var previousUri: Uri? = null
    private var nextUri: Uri? = null
    private var includePrevious by mutableStateOf(false)
    private var includeNext by mutableStateOf(false)
    private var stitchDirection by mutableStateOf(StitchDirection.TOP_TO_BOTTOM)
    private var isDirectionMenuOpen by mutableStateOf(false)
    private var isStitching by mutableStateOf(false)
    private var stitchJob: Job? = null
    private var stitchedFile: File? = null
    private lateinit var compressFormat: Bitmap.CompressFormat
    private var compressQuality: Int = DEFAULT_COMPRESS_QUALITY
    private var originalRatio: Float = CropImageView.SOURCE_IMAGE_ASPECT_RATIO
    private var isCropping by mutableStateOf(false)
    private var isImageLoaded by mutableStateOf(false)
    private var selectedRatio by mutableFloatStateOf(CropImageView.SOURCE_IMAGE_ASPECT_RATIO)
    /** Kept apart from [selectedRatio]: a stitched image can be exactly square, like the 1:1 preset. */
    private var isOriginalRatioSelected by mutableStateOf(true)
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
        this.sourceUri = sourceUri
        editedUri = sourceUri
        outputUri = destinationUri
        previousUri = intent.getParcelableExtra(EXTRA_PREVIOUS_SOURCE_URI)
        nextUri = intent.getParcelableExtra(EXTRA_NEXT_SOURCE_URI)
        stitchDirection = intent.getStringExtra(EXTRA_STITCH_DIRECTION)
            ?.let { name -> StitchDirection.entries.find { it.name == name } }
            ?: StitchDirection.TOP_TO_BOTTOM
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
        sourceRatio = originalRatio
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
                    TvTextButton(onClick = ::saveCrop, enabled = isImageLoaded && !isCropping && !isStitching) {
                        Text(stringResource(if (isAnnotationMode) R.string.save_note else R.string.save))
                    }
                }
                // uCrop reports a load only for the first image a view lays out, so a new image needs a new view.
                key(editedUri) {
                    AndroidView(
                        factory = { context ->
                            UCropView(context, null).also { cropView ->
                                cropImageView = cropView.cropImageView
                                overlayView = cropView.overlayView
                                overlayView.setFreestyleCropEnabled(true)
                                keepFrameOnImageWhileDragging()
                                cropImageView.setTransformImageListener(this@PageCropActivity)
                                cropImageView.setImageToWrapCropBoundsAnimDuration(WRAP_ANIM_DURATION_MS)
                                cropImageView.setMaxScaleMultiplier(MAX_SCALE_MULTIPLIER)
                                // A pinch easily twists the page by accident and a page is never meant to be
                                // turned; the crop is also taken from the source file, which assumes upright.
                                cropImageView.isRotateEnabled = false
                                // uCrop sizes the crop frame from the first layout only. When the window is still
                                // settling (e.g. launched during a rotation), re-apply the ratio at the final size.
                                cropView.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                                    val resized = r - l != oldR - oldL || b - t != oldB - oldT
                                    if (resized && isImageLoaded) {
                                        cropView.post { applyAspectRatio(selectedRatio, isOriginalRatioSelected) }
                                    }
                                }
                                runCatching { cropImageView.setImageUri(editedUri, destinationUri) }
                                    .onFailure { cancelCrop() }
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
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
                if (previousUri != null || nextUri != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        val enabled = isImageLoaded && !isCropping && !isStitching
                        if (previousUri != null) {
                            TvFilterChip(
                                selected = includePrevious,
                                onClick = { setAdjacentPages(!includePrevious, includeNext, stitchDirection) },
                                label = stringResource(R.string.crop_include_previous_page),
                                enabled = enabled,
                            )
                        }
                        if (nextUri != null) {
                            TvFilterChip(
                                selected = includeNext,
                                onClick = { setAdjacentPages(includePrevious, !includeNext, stitchDirection) },
                                label = stringResource(R.string.crop_include_next_page),
                                enabled = enabled,
                            )
                        }
                        Box {
                            TvFilterChip(
                                selected = false,
                                onClick = { isDirectionMenuOpen = true },
                                label = stringResource(stitchDirection.titleRes),
                                enabled = enabled,
                            )
                            DropdownMenu(
                                expanded = isDirectionMenuOpen,
                                onDismissRequest = { isDirectionMenuOpen = false },
                            ) {
                                StitchDirection.entries.forEach { direction ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(direction.titleRes)) },
                                        trailingIcon = if (direction == stitchDirection) {
                                            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                                        } else {
                                            null
                                        },
                                        onClick = {
                                            isDirectionMenuOpen = false
                                            if (direction != stitchDirection) {
                                                setAdjacentPages(includePrevious, includeNext, direction)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(8.dp),
                ) {
                    RatioChip(
                        label = stringResource(com.yalantis.ucrop.R.string.ucrop_label_original),
                        ratio = originalRatio,
                        isOriginal = true,
                    )
                    RatioChip("1:1", 1f)
                    RatioChip("4:3", 4f / 3f)
                    RatioChip("16:9", 16f / 9f)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RatioChip(label: String, ratio: Float, isOriginal: Boolean = false) {
        // On TV the crop frame itself cannot be dragged by a remote, so these presets are the only
        // way to change it and they have to be both reachable and visibly focused.
        TvFilterChip(
            selected = if (isOriginal) isOriginalRatioSelected else !isOriginalRatioSelected && selectedRatio == ratio,
            onClick = { applyAspectRatio(ratio, isOriginal) },
            label = label,
        )
    }

    override fun onLoadComplete() {
        isImageLoaded = true
        // uCrop lays out the image before its overlay: wait for both so the frame and the image agree.
        cropImageView.post { applyAspectRatio(originalRatio, original = true) }
    }

    override fun onLoadFailure(e: Exception) = cancelCrop()

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stitchedFile?.delete()
        }
    }

    /**
     * Reloads the editor with the neighbouring pages lined up with the page in [direction], so a crop can
     * cover a panel the page boundary cuts in half. The crop frame restarts on the new image.
     */
    private fun setAdjacentPages(previous: Boolean, next: Boolean, direction: StitchDirection) {
        if (!::cropImageView.isInitialized || isCropping) return
        includePrevious = previous
        includeNext = next
        stitchDirection = direction
        if (!previous && !next && editedUri == sourceUri) return
        stitchJob?.cancel()
        isStitching = true
        isImageLoaded = false
        stitchJob = lifecycleScope.launch {
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) { buildStitchedSource(previous, next, direction) }
            }
            isStitching = false
            result.onSuccess { (uri, ratio) ->
                originalRatio = ratio
                showImage(uri)
            }.onFailure { error ->
                error.printStackTraceDebug()
                Toast.makeText(this@PageCropActivity, R.string.error_occurred, Toast.LENGTH_SHORT).show()
                includePrevious = false
                includeNext = false
                originalRatio = sourceRatio
                showImage(sourceUri)
            }
        }
    }

    /**
     * uCrop lets the frame be dragged past the image and then scales the image up to fill it, which
     * moves the part the reader was framing. Let uCrop drag the frame, then stop it at the image edges.
     * The frame reaches the image view only on release, already inside the image, so the image stays.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun keepFrameOnImageWhileDragging() {
        overlayView.setOnTouchListener { view, event ->
            val overlay = view as OverlayView
            val before = overlay.cropViewRect.toFrameBox()
            val handled = overlay.onTouchEvent(event)
            if (handled && event.actionMasked == MotionEvent.ACTION_MOVE) {
                val image = shownImageBounds()
                val frame = overlay.cropViewRect.toFrameBox()
                val kept = image?.let { keepFrameOnImage(frame, before, it) }
                if (kept != null && kept != frame) {
                    overlay.setCropFrame(RectF(kept.left, kept.top, kept.right, kept.bottom))
                }
            }
            // Returning false hands the event to onTouchEvent once more, which ignores a touch it
            // already declined: the image underneath then takes the pinch or pan.
            handled
        }
    }

    /** The image on screen in the overlay's coordinates, which include the image view's padding. */
    private fun shownImageBounds(): FrameBox? {
        val bitmap = cropImageView.viewBitmap ?: return null
        val image = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        cropImageView.imageMatrix.mapRect(image)
        image.offset(cropImageView.paddingLeft.toFloat(), cropImageView.paddingTop.toFloat())
        return image.toFrameBox()
    }

    private fun RectF.toFrameBox() = FrameBox(left, top, right, bottom)

    /** A new image gets a new crop view; the one already shown just becomes usable again. */
    private fun showImage(uri: Uri) {
        if (uri == editedUri) {
            isImageLoaded = true
        } else {
            editedUri = uri
        }
    }

    @WorkerThread
    private fun buildStitchedSource(previous: Boolean, next: Boolean, direction: StitchDirection): Pair<Uri, Float> {
        stitchedFile?.delete()
        stitchedFile = null
        if (!previous && !next) {
            return sourceUri to sourceRatio
        }
        val files = buildList {
            if (previous) add(previousUri.requireFile())
            add(sourceUri.requireFile())
            if (next) add(nextUri.requireFile())
        }
        val extension = if (compressFormat == Bitmap.CompressFormat.JPEG) "jpg" else "png"
        val output = File.createTempFile("stitch_", ".$extension", sourceUri.requireFile().parentFile)
        stitchedFile = output
        val (width, height) = stitchPages(
            files = files,
            baseIndex = if (previous) 1 else 0,
            direction = direction,
            output = output,
            format = if (compressFormat == Bitmap.CompressFormat.JPEG) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            },
            quality = compressQuality,
        )
        return Uri.fromFile(output) to width.toFloat() / height.toFloat()
    }

    private fun Uri?.requireFile(): File = checkNotNull(this?.toFile()) { "No page to stitch" }

    private val StitchDirection.titleRes: Int
        get() = when (this) {
            StitchDirection.TOP_TO_BOTTOM -> R.string.crop_stitch_top_to_bottom
            StitchDirection.LEFT_TO_RIGHT -> R.string.crop_stitch_left_to_right
            StitchDirection.RIGHT_TO_LEFT -> R.string.crop_stitch_right_to_left
        }

    override fun onRotate(currentAngle: Float) = Unit

    override fun onScale(currentScale: Float) = Unit

    private fun saveCrop() {
        if (!isImageLoaded || isCropping) return
        isCropping = true
        // Settle the image first, as uCrop does before its own crop: the frame must lie on the image.
        cropImageView.cancelAllAnimations()
        cropImageView.setImageToWrapCropBounds(false)
        val viewBitmap = cropImageView.viewBitmap
        val sourceFile = runCatching { editedUri.toFile() }.getOrNull()
        if (viewBitmap == null || sourceFile == null || abs(cropImageView.currentAngle) > MAX_UPRIGHT_ANGLE) {
            saveCropWithUCrop()
            return
        }
        // The frame is in overlay coordinates and the image matrix in content ones: uCrop's own crop
        // moves the frame in by the image view's padding the same way.
        val crop = RectF(overlayView.cropViewRect).apply {
            left -= cropImageView.paddingLeft
            top -= cropImageView.paddingTop
            right -= cropImageView.paddingRight
            bottom -= cropImageView.paddingBottom
        }
        val image = RectF(0f, 0f, viewBitmap.width.toFloat(), viewBitmap.height.toFloat())
        cropImageView.imageMatrix.mapRect(image)
        val shownWidth = viewBitmap.width
        val shownHeight = viewBitmap.height
        lifecycleScope.launch {
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
                    if (!isSameOrientation(shownWidth, shownHeight, bounds.outWidth, bounds.outHeight)) {
                        return@withContext false
                    }
                    val region = cropRegionInSource(
                        cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                        imageLeft = image.left, imageTop = image.top, imageRight = image.right,
                        imageBottom = image.bottom,
                        sourceWidth = bounds.outWidth,
                        sourceHeight = bounds.outHeight,
                    ) ?: return@withContext false
                    cropPageRegion(
                        source = sourceFile,
                        region = region,
                        sourceMimeType = bounds.outMimeType,
                        output = outputUri.toFile(),
                        format = compressFormat,
                        quality = compressQuality,
                    )
                    true
                }
            }
            result.onFailure { it.printStackTraceDebug() }
            if (result.getOrDefault(false)) {
                onCropped(outputUri)
            } else {
                saveCropWithUCrop()
            }
        }
    }

    /** uCrop crops the downsampled copy it shows, so it is only the fallback for what the source path cannot do. */
    private fun saveCropWithUCrop() {
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
                ) = onCropped(resultUri)

                override fun onCropFailure(t: Throwable) = cancelCrop()
            },
        )
    }

    private fun onCropped(resultUri: Uri) {
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

    private fun cancelCrop() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * Fits the frame to [ratio] and the image into it in one step. uCrop answers every frame change with
     * an animated fit aimed at the geometry of that moment and cancels only the latest one, so animated
     * fits queued during a layout pass could finish last and leave the image off the frame.
     */
    private fun applyAspectRatio(ratio: Float, original: Boolean) {
        if (!::cropImageView.isInitialized || !::overlayView.isInitialized) return
        val targetRatio = if (ratio > 0f) ratio else CropImageView.SOURCE_IMAGE_ASPECT_RATIO
        selectedRatio = ratio
        isOriginalRatioSelected = original
        // The overlay pushes its new frame to the image view, which takes the ratio from it as well.
        overlayView.setTargetAspectRatio(targetRatio)
        cropImageView.cancelAllAnimations()
        resetScaleToMin()
        cropImageView.setImageToWrapCropBounds(false)
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
        internal const val EXTRA_PREVIOUS_SOURCE_URI = "page_crop_previous_source_uri"
        internal const val EXTRA_NEXT_SOURCE_URI = "page_crop_next_source_uri"
        internal const val EXTRA_STITCH_DIRECTION = "page_crop_stitch_direction"

        private const val DEFAULT_COMPRESS_QUALITY = 95
        private const val WRAP_ANIM_DURATION_MS = 180L
        private const val MAX_SCALE_MULTIPLIER = 20f
        private const val MAX_UPRIGHT_ANGLE = 0.01f
    }
}
