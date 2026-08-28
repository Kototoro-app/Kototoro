package org.skepsun.kototoro.core.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.Image
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.compose.AsyncImage
import coil3.asImage
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.crossfade
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.skepsun.kototoro.core.jsonsource.JsonSourceListSource
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.getOriginLabel
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.BaseAppHolder
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.parser.favicon.faviconUri
import org.skepsun.kototoro.core.extensions.PluginContentSource
import org.skepsun.kototoro.core.extensions.PluginMangaSource
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource
import org.skepsun.kototoro.core.ui.image.FaviconDrawable
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.parsers.model.ContentSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore

private data class ContentSourceResolutionSnapshot(
    val mihonChanges: Int,
    val aniyomiChanges: Int,
    val ireaderChanges: Int,
    val tsundokuChanges: Int,
    val jsonSources: Map<String, ContentSource>,
    /** sourceKey -> display name for sources whose extension is gone (e.g. imported backups). */
    val originNames: Map<String, String> = emptyMap(),
)

private val LocalContentSourceResolutionSnapshot =
    staticCompositionLocalOf<ContentSourceResolutionSnapshot?> { null }

/**
 * Collects extension and JSON-source changes once for a themed Compose hierarchy.
 * Individual cards only read this snapshot instead of starting their own collectors.
 */
@Composable
fun ContentSourceResolutionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val entryPoint = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        )
    }
    val mihonChanges by entryPoint.mihonExtensionManager().changes.collectAsStateWithLifecycle()
    val aniyomiChanges by entryPoint.aniyomiExtensionManager().changes.collectAsStateWithLifecycle()
    val ireaderChanges by entryPoint.ireaderExtensionManager().changes.collectAsStateWithLifecycle()
    val tsundokuChanges by entryPoint.tsundokuExtensionManager().changes.collectAsStateWithLifecycle()
    val jsonSourceEntities by entryPoint.jsonSourceManager()
        .observeAllJsonSources()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val jsonSources = remember(jsonSourceEntities) {
        jsonSourceEntities.associate { entity -> entity.id to JsonContentSource(entity) }
    }
    val originNames by entryPoint.database().get()
        .getSourceOriginsDao()
        .observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val originNamesMap = remember(originNames) {
        originNames.mapNotNull { origin ->
            origin.displayName?.takeIf { it.isNotBlank() }?.let { origin.sourceKey to it }
        }.toMap()
    }
    val snapshot = remember(
        mihonChanges,
        aniyomiChanges,
        ireaderChanges,
        tsundokuChanges,
        jsonSources,
        originNamesMap,
    ) {
        ContentSourceResolutionSnapshot(
            mihonChanges = mihonChanges,
            aniyomiChanges = aniyomiChanges,
            ireaderChanges = ireaderChanges,
            tsundokuChanges = tsundokuChanges,
            jsonSources = jsonSources,
            originNames = originNamesMap,
        )
    }
    CompositionLocalProvider(LocalContentSourceResolutionSnapshot provides snapshot, content = content)
}

data class ContentSourceChipMeta(
    val iconRes: Int,
    val text: String,
)

@Composable
fun rememberResolvedContentSource(source: ContentSource): ContentSource {
    val name = source.name
    if (!name.startsWith("MIHON_") && !name.startsWith("ANIYOMI_") &&
        !name.startsWith("IREADER_") && !name.startsWith("CLOUDSTREAM_") &&
        !name.startsWith("TSUNDOKU_") && !name.startsWith("JSON_")
    ) {
        return source
    }
    val context = LocalContext.current
    val sharedSnapshot = LocalContentSourceResolutionSnapshot.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        )
    }
    val mihonChanges = sharedSnapshot?.mihonChanges
        ?: entryPoint.mihonExtensionManager().changes.value
    val aniyomiChanges = sharedSnapshot?.aniyomiChanges
        ?: entryPoint.aniyomiExtensionManager().changes.value
    val ireaderChanges = sharedSnapshot?.ireaderChanges
        ?: entryPoint.ireaderExtensionManager().changes.value
    val tsundokuChanges = sharedSnapshot?.tsundokuChanges
        ?: entryPoint.tsundokuExtensionManager().changes.value
    val jsonKey = remember(name) {
        name.takeIf { it.startsWith("JSON_") }
    }
    val resolvedJsonSource = jsonKey?.let { key -> sharedSnapshot?.jsonSources?.get(key) }
    return remember(
        name,
        mihonChanges,
        aniyomiChanges,
        ireaderChanges,
        tsundokuChanges,
        resolvedJsonSource?.name,
        resolvedJsonSource?.javaClass?.name,
    ) {
        when {
            resolvedJsonSource != null -> resolvedJsonSource
            else -> resolveDynamicContentSource(source, entryPoint) ?: source
        }
    }
}

/**
 * Display name persisted in `source_origins` for sources whose extension is not
 * installed (e.g. favorites imported from an external backup). Returns `null` when
 * the source is not a dynamic-extension key or no origin name is registered.
 */
@Composable
private fun rememberOriginDisplayName(source: ContentSource): String? {
    val name = source.name
    if (!name.startsWith("MIHON_") && !name.startsWith("ANIYOMI_") &&
        !name.startsWith("IREADER_") && !name.startsWith("CLOUDSTREAM_") &&
        !name.startsWith("TSUNDOKU_")
    ) {
        return null
    }
    val snapshot = LocalContentSourceResolutionSnapshot.current ?: return null
    return remember(name, snapshot.originNames) { snapshot.originNames[name] }
}

@Composable
fun rememberResolvedSourceTitle(source: ContentSource): String {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        )
    }
    val resolvedSource = rememberResolvedContentSource(source)
    val originName = rememberOriginDisplayName(source)
    val unresolved = resolvedSource === source
    return remember(source.name, resolvedSource.javaClass.name, originName, unresolved) {
        when {
            unresolved && originName != null -> originName
            else -> resolveSourceTitleForUi(
                context = context,
                source = resolvedSource,
                entryPoint = entryPoint,
            )
        }
    }
}

@Composable
fun rememberSourceChipMeta(source: ContentSource): ContentSourceChipMeta? {
    val context = LocalContext.current
    val resolvedSource = rememberResolvedContentSource(source)
    val originName = rememberOriginDisplayName(source)
    val unresolved = resolvedSource === source
    return remember(source.name, resolvedSource.javaClass.name, resolvedSource.locale, originName, unresolved) {
        val locale = resolvedSource.getLocale()
            ?.language
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
            .orEmpty()
        val origin = when {
            unresolved && originName != null -> originName
            else -> resolvedSource.getOriginLabel(context)
                ?: source.getOriginLabel(context)
                .orEmpty()
        }
        val text = when {
            locale.isNotBlank() -> locale
            origin.isNotBlank() -> origin
            else -> ""
        }
        text.takeIf { it.isNotBlank() }?.let {
            ContentSourceChipMeta(
                iconRes = resolvedSource.iconResForUi(),
                text = it,
            )
        }
    }
}

@Composable
fun ContentSourceIcon(
    source: ContentSource,
    modifier: Modifier = Modifier,
    styleResId: Int = R.style.FaviconDrawable_SourceIcon,
    animated: Boolean = false,
    loadEnabled: Boolean = true,
    throttleNetworkLoad: Boolean = false,
    contentDescription: String? = null,
) {
    val resolvedSource = rememberResolvedContentSource(source)
    ContentSourceResolvedIcon(
        source = resolvedSource,
        modifier = modifier,
        styleResId = styleResId,
        animated = animated,
        loadEnabled = loadEnabled,
        throttleNetworkLoad = throttleNetworkLoad,
        contentDescription = contentDescription,
    )
}

@Composable
fun ContentSourceResolvedIcon(
    source: ContentSource,
    modifier: Modifier = Modifier,
    styleResId: Int = R.style.FaviconDrawable_SourceIcon,
    animated: Boolean = false,
    loadEnabled: Boolean = true,
    throttleNetworkLoad: Boolean = false,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    val resolvedSource = source
    val listIconUrl = (resolvedSource as? JsonSourceListSource)?.iconUrl?.takeIf { it.isNotBlank() }
    val jarIdentity = resolvedSource.jarIdentityForIcon()
    val sourceIdentity = resolvedSource.sourceIconIdentity()
    val sourceFailureKey = remember(resolvedSource.name, sourceIdentity, jarIdentity, styleResId, listIconUrl) {
        val iconPart = listIconUrl?.let { "#${it.hashCode()}" }.orEmpty()
        "$sourceIdentity#$SOURCE_ICON_CACHE_VERSION#${resolvedSource.javaClass.name}#$jarIdentity#$styleResId$iconPart"
    }
    val fallbackDrawable = remember(
        resolvedSource.name,
        resolvedSource.javaClass.name,
        resolvedSource.locale,
        jarIdentity,
        styleResId,
    ) {
        FaviconDrawable(context, styleResId, resolveFallbackTitle(context, resolvedSource)).asImage()
    }
    val fallbackFactory: (ImageRequest) -> Image? = remember(fallbackDrawable) {
        { fallbackDrawable }
    }
    val useFallbackOnly = resolvedSource is JsonSourceListSource && listIconUrl == null
    val useNegativeCache = resolvedSource is JsonSourceListSource && listIconUrl != null

    var hasError by remember(sourceFailureKey) {
        androidx.compose.runtime.mutableStateOf(useNegativeCache && failedSourceIcons.contains(sourceFailureKey))
    }
    var isLoadGateOpen by remember(sourceFailureKey, throttleNetworkLoad, loadEnabled) {
        androidx.compose.runtime.mutableStateOf(loadEnabled && !throttleNetworkLoad)
    }
    var isLoadPermitHeld by remember(sourceFailureKey, throttleNetworkLoad, loadEnabled) {
        androidx.compose.runtime.mutableStateOf(false)
    }

    fun releaseLoadPermit() {
        if (isLoadPermitHeld) {
            sourceIconLoadSemaphore.release()
            isLoadPermitHeld = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(sourceFailureKey, throttleNetworkLoad, loadEnabled, hasError, useFallbackOnly) {
        if (!loadEnabled || !throttleNetworkLoad || hasError || useFallbackOnly || isLoadGateOpen) return@LaunchedEffect
        sourceIconLoadSemaphore.acquire()
        isLoadPermitHeld = true
        isLoadGateOpen = true
    }

    androidx.compose.runtime.DisposableEffect(sourceFailureKey, throttleNetworkLoad, loadEnabled) {
        onDispose {
            if (isLoadPermitHeld) {
                sourceIconLoadSemaphore.release()
                isLoadPermitHeld = false
            }
        }
    }

    val iconCacheKey = sourceFailureKey
    val memoryCacheKey = remember(iconCacheKey) { MemoryCache.Key(iconCacheKey) }
    val cachedIcon = remember(imageLoader, memoryCacheKey, hasError, useFallbackOnly) {
        imageLoader.memoryCache?.get(memoryCacheKey)?.image
            ?.takeUnless { hasError || useFallbackOnly }
    }

    val request: Any = remember(
        resolvedSource.name,
        resolvedSource.javaClass.name,
        resolvedSource.locale,
        styleResId,
        animated,
        hasError,
        useFallbackOnly,
        useNegativeCache,
        listIconUrl,
        jarIdentity,
    ) {
        if (hasError || useFallbackOnly) {
            fallbackDrawable
        } else {
            logSourceIconRequest(
                source = resolvedSource,
                cacheKey = iconCacheKey,
                directIconUrl = listIconUrl,
            )
            ImageRequest.Builder(context)
                .data(resolvedSource.faviconUri())
                .memoryCacheKey(iconCacheKey)
                .diskCacheKey(iconCacheKey)
                .crossfade(animated)
                .mangaSourceExtra(resolvedSource)
                .suppressCaptchaErrors()
                .placeholder(fallbackFactory)
                .fallback(fallbackFactory)
                .error(fallbackFactory)
                .build()
        }
    }

    if (cachedIcon != null) {
        val cachedPainter = rememberDrawablePainter(cachedIcon.asDrawable(context.resources))
        androidx.compose.foundation.Image(
            painter = cachedPainter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
        )
    } else if (hasError || useFallbackOnly || !loadEnabled || !isLoadGateOpen) {
        val fallbackPainter = rememberDrawablePainter(fallbackDrawable?.asDrawable(context.resources))
        androidx.compose.foundation.Image(
            painter = fallbackPainter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
        )
    } else {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            onSuccess = {
                releaseLoadPermit()
            },
            onError = {
                releaseLoadPermit()
                logSourceIconError(resolvedSource, it.result.throwable)
                if (useNegativeCache) {
                    failedSourceIcons[sourceFailureKey] = true
                    hasError = true
                }
            },
        )
    }
}

private val failedSourceIcons = ConcurrentHashMap<String, Boolean>()
private const val SOURCE_ICON_CACHE_VERSION = "v3"
private const val SOURCE_ICON_LOG_TAG = "SourceIcon"
private val sourceIconLoadSemaphore = Semaphore(permits = 4)

private fun ContentSource.jarIdentityForIcon(): String = when (this) {
    is PluginContentSource -> jarName
    is KotatsuParserSource -> (delegate as? PluginMangaSource)?.jarName.orEmpty()
    else -> ""
}

/**
 * Icon cache identity used to de-duplicate favicon loads.
 *
 * For APK-housed runtimes (Mihon/Aniyomi/IReader) a single package can expose the same
 * source many times as separate per-language instances, each with its own unique `name`
 * (e.g. `MIHON_{id}`). Keying the icon cache on `name` would then re-fetch the same
 * favicon once per language once you scroll past that block. Instead we key on
 * `package + base source name`, so every language variant of one source shares a single
 * memory/disk cache entry and a single negative-cache slot.
 */
private fun ContentSource.sourceIconIdentity(): String = when (this) {
    is org.skepsun.kototoro.mihon.model.MihonMangaSource ->
        "mihon:$pkgName:${catalogueSource.name}"

    is org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource ->
        "aniyomi:$pkgName:${animeCatalogueSource.name}"

    is org.skepsun.kototoro.ireader.model.IReaderMangaSource ->
        "ireader:$pkgName:${catalogueSource.name}"

    is org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource ->
        "tsundoku:$pkgName:${upstreamSource.name}"

    else -> name
}

private fun logSourceIconRequest(
    source: ContentSource,
    cacheKey: String,
    directIconUrl: String?,
) {
    if (source is JsonSourceListSource) return
    android.util.Log.d(
        SOURCE_ICON_LOG_TAG,
        "request source=${source.name} type=${source.javaClass.name} cacheKey=$cacheKey " +
            "direct=${!directIconUrl.isNullOrBlank()}",
    )
}

private fun logSourceIconError(source: ContentSource, throwable: Throwable?) {
    if (source is JsonSourceListSource) return
    android.util.Log.w(
        SOURCE_ICON_LOG_TAG,
        "error source=${source.name} type=${source.javaClass.name} " +
            "error=${throwable?.javaClass?.name}:${throwable?.message}",
    )
}

fun clearFailedContentSourceIcons() {
    failedSourceIcons.clear()
}

fun clearFailedContentSourceIcon(sourceName: String) {
    failedSourceIcons.keys.removeAll { it.startsWith("$sourceName#") }
}

fun resolveSourceTitleForUi(
    context: Context,
    source: ContentSource,
    entryPoint: BaseApp.BaseAppEntryPoint? = null,
): String {
    val resolvedSource = entryPoint?.let { resolveDynamicContentSource(source, it) } ?: source
    return resolveReadableSourceTitleInternal(
        context = context,
        originalSource = source,
        resolvedSource = resolvedSource,
    )
}

private fun resolveFallbackTitle(
    context: Context,
    source: ContentSource,
): String {
    val title = source.getTitle(context)
    return title.takeIf { it.isNotBlank() && !it.startsWith("Loading ", ignoreCase = true) }
        ?: source.getOriginLabel(context)
        ?: source.name
}

private fun resolveDynamicContentSource(
    source: ContentSource,
    entryPoint: BaseApp.BaseAppEntryPoint,
): ContentSource? = when {
    source.name.startsWith("MIHON_") -> entryPoint.mihonExtensionManager().getMihonMangaSourceByName(source.name)
    source.name.startsWith("ANIYOMI_") -> entryPoint.aniyomiExtensionManager().getAniyomiAnimeSourceByName(source.name)
    source.name.startsWith("IREADER_") -> entryPoint.ireaderExtensionManager().getIReaderMangaSourceByName(source.name)
    source.name.startsWith("TSUNDOKU_") -> entryPoint.tsundokuExtensionManager().resolveSource(source.name)
    source.name.startsWith("CLOUDSTREAM_") -> BaseAppHolder.get()?.findSourceByName(source.name)
    else -> null
}

private fun resolveReadableSourceTitleInternal(
    context: Context,
    originalSource: ContentSource,
    resolvedSource: ContentSource,
): String {
    val resolvedTitle = resolvedSource.getTitle(context)
    if (resolvedTitle.isNotBlank() && !resolvedTitle.startsWith("Loading ", ignoreCase = true)) {
        return resolvedTitle
    }
    val fallbackTitle = originalSource.getTitle(context)
    return if (fallbackTitle.startsWith("Loading ", ignoreCase = true)) {
        originalSource.getOriginLabel(context) ?: originalSource.name
    } else {
        fallbackTitle
    }
}

fun ContentSource.iconResForUi(): Int = when {
    name.startsWith("MIHON_") -> R.drawable.ic_source_mihon
    name.startsWith("ANIYOMI_") -> R.drawable.ic_source_aniyomi
    name.startsWith("CLOUDSTREAM_") -> R.drawable.ic_source_cloudstream
    name.startsWith("TSUNDOKU_") -> R.drawable.ic_source_tsundoku
    name.startsWith("JSON_TVBOX_") -> R.drawable.ic_source_tvbox
    name.startsWith("JSON_JS_") -> R.drawable.ic_source_js
    name.startsWith("JSON_LEGADO_") || name.startsWith("JSON_LEGADO_M_") -> R.drawable.ic_source_legado
    name.startsWith("JSON_LNREADER_") || name.startsWith("IREADER_") -> R.drawable.ic_source_ireader
    name.startsWith("LOCAL") -> R.drawable.ic_storage
    else -> R.drawable.ic_source_builtin
}
