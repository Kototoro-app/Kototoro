package org.skepsun.kototoro.reader.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

class PageCropActivity : BaseComposeActivity(), TransformImageView.TransformImageListener {

    private lateinit var cropImageView: GestureCropImageView
    private lateinit var overlayView: OverlayView
    private lateinit var outputUri: Uri
    private lateinit var compressFormat: Bitmap.CompressFormat
    private var compressQuality: Int = DEFAULT_COMPRESS_QUALITY
    private var originalRatio: Float = CropImageView.SOURCE_IMAGE_ASPECT_RATIO
    private var isCropping by mutableStateOf(false)
    private var isImageLoaded by mutableStateOf(false)
    private var selectedRatio by mutableFloatStateOf(CropImageView.SOURCE_IMAGE_ASPECT_RATIO)

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
        originalRatio = if (sourceWidth > 0 && sourceHeight > 0) {
            sourceWidth.toFloat() / sourceHeight.toFloat()
        } else {
            CropImageView.SOURCE_IMAGE_ASPECT_RATIO
        }
        selectedRatio = originalRatio

        setContent {
            KototoroTheme {
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
                        TextButton(onClick = ::cancelCrop) { Text(stringResource(android.R.string.cancel)) }
                        Text(
                            text = stringResource(R.string.crop_pages),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = ::saveCrop, enabled = isImageLoaded && !isCropping) {
                            Text(stringResource(R.string.save))
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
                                runCatching { cropImageView.setImageUri(sourceUri, destinationUri) }
                                    .onFailure { cancelCrop() }
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
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
    }

    @androidx.compose.runtime.Composable
    private fun RatioChip(label: String, ratio: Float) {
        FilterChip(
            selected = selectedRatio == ratio,
            onClick = { applyAspectRatio(ratio) },
            label = { Text(label) },
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
                    setResult(Activity.RESULT_OK, Intent().setData(resultUri))
                    finish()
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

        private const val DEFAULT_COMPRESS_QUALITY = 95
        private const val WRAP_ANIM_DURATION_MS = 180L
        private const val MAX_SCALE_MULTIPLIER = 20f
    }
}
