package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.Image as CoilImage
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.ui.compose.rememberDrawablePainter
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


@Composable
internal fun HomeHeroCoverImage(
    request: ImageRequest,
    cacheKey: String?,
    snapshotKey: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onSuccess: (SuccessResult) -> Unit,
    onError: (AsyncImagePainter.State.Error) -> Unit = {},
) {
    val context = LocalContext.current
    val imageLoader = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    val cachedImage = remember(imageLoader, cacheKey, snapshotKey) {
        cacheKey?.let { key ->
            imageLoader.memoryCache?.get(MemoryCache.Key(key))?.image
        } ?: HeroCoverSnapshotStore.get(snapshotKey)
    }
    var stableImage by remember(cacheKey, snapshotKey) { mutableStateOf<CoilImage?>(cachedImage) }
    val stablePainter = rememberDrawablePainter(stableImage?.asDrawable(context.resources))

    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        placeholder = stablePainter,
        error = stablePainter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        onSuccess = { result ->
            stableImage = result.result.image
            onSuccess(result.result)
        },
        onError = onError,
    )
}

