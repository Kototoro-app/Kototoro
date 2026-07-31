package org.skepsun.kototoro.image.ui

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import org.skepsun.kototoro.R

data class ImageErrorState(
    val message: String,
    val iconRes: Int,
)

@Composable
fun ImageViewerScreen(
    imageModel: Any?,
    imageLoader: ImageLoader,
    showMenu: Boolean,
    isSaving: Boolean,
    isLoading: Boolean,
    error: ImageErrorState?,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onRetry: () -> Unit,
    onMenuAnchorCreated: (View) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model = imageModel,
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        error?.let { imageError ->
            ImageErrorContent(
                error = imageError,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val systemBars = WindowInsets.systemBars.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current
        val screenPadding = dimensionResource(R.dimen.screen_padding)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = systemBars.calculateStartPadding(layoutDirection) + screenPadding,
                    top = systemBars.calculateTopPadding() + screenPadding,
                    end = systemBars.calculateEndPadding(layoutDirection) + screenPadding,
                ),
        ) {
            ImageActionButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }

            if (showMenu) {
                AndroidView(
                    factory = { context ->
                        View(context).also(onMenuAnchorCreated)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .clip(CircleShape),
                )
                ImageActionButton(
                    onClick = onMenu,
                    enabled = !isSaving,
                    contentDescription = stringResource(R.string.show_menu),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .shadow(
                elevation = 1.dp,
                shape = CircleShape,
            )
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .clip(CircleShape)
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
    }
    // The content description belongs to the button rather than the icon for accessibility.
}

@Composable
private fun ImageErrorContent(
    error: ImageErrorState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(dimensionResource(R.dimen.screen_padding)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(error.iconRes),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            FilledTonalButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = stringResource(R.string.try_again))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ImageErrorContentPreview() {
    MaterialTheme {
        ImageErrorContent(
            error = ImageErrorState(
                message = "Unable to load image",
                iconRes = R.drawable.ic_error_large,
            ),
            onRetry = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
