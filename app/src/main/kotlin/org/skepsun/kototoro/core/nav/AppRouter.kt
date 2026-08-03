package org.skepsun.kototoro.core.nav

import android.accounts.Account
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.util.Log
import androidx.annotation.CheckResult
import androidx.annotation.UiContext
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.findFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.alternatives.ui.compose.AlternativesSheetRoute
import org.skepsun.kototoro.backups.ui.restore.RestoreDialogRoute
import org.skepsun.kototoro.backups.domain.BackupRestoreFormat
import org.skepsun.kototoro.browser.BrowserActivity
import org.skepsun.kototoro.browser.cloudflare.CloudFlareActivity
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.image.CoilMemoryCacheKey
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.appUrl
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isBroken
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.model.parcelable.ParcelableContentPage
import org.skepsun.kototoro.core.model.parcelable.ParcelableContentListFilter
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.compose.rememberDrawablePainter
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.dialog.BigButtonsAlertDialog
import org.skepsun.kototoro.core.ui.dialog.ErrorDetailsActivity
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.util.ext.connectivityManager
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getThemeDrawable
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toSerializableThrowable
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.toFileOrNull
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.details.ui.DetailsActivity
import org.skepsun.kototoro.details.ui.related.RelatedContentActivity
import org.skepsun.kototoro.details.ui.scrobbling.ScrobblingInfoSheetRoute
import org.skepsun.kototoro.download.ui.compose.DownloadDialog
import org.skepsun.kototoro.download.ui.list.DownloadsActivity
import org.skepsun.kototoro.favourites.ui.FavouritesActivity
import org.skepsun.kototoro.favourites.ui.categories.FavouriteCategoriesActivity
import org.skepsun.kototoro.favourites.ui.categories.edit.FavouritesCategoryEditActivity
import org.skepsun.kototoro.favourites.ui.categories.select.compose.FavoriteCategoryDialogRoute
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.filter.ui.sheet.FilterSheetRoute
import org.skepsun.kototoro.filter.ui.tags.TagsCatalogRoute
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.preset.SourcePresetListActivity
import org.skepsun.kototoro.history.ui.HistoryActivity
import org.skepsun.kototoro.image.ui.ImageActivity
import org.skepsun.kototoro.list.ui.config.ListConfigRoute
import org.skepsun.kototoro.list.ui.config.ListConfigSection
import org.skepsun.kototoro.local.ui.compose.ImportDialog
import org.skepsun.kototoro.local.ui.info.compose.LocalInfoDialogRoute
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.welcome.WelcomeRoute
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.ellipsize
import org.skepsun.kototoro.parsers.util.isNullOrEmpty
import org.skepsun.kototoro.parsers.util.mapToArray
import org.skepsun.kototoro.reader.novel.NovelReaderActivity
import org.skepsun.kototoro.space.ui.ImmersiveSpaceSwitcherTransition
import org.skepsun.kototoro.space.ui.EXTRA_IMMERSIVE_SESSION_SPACE_ID
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentDataRepository
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.ui.colorfilter.ColorFilterConfigActivity
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.ui.config.ScrobblerConfigActivity
import org.skepsun.kototoro.scrobbling.common.ui.selector.ScrobblingSelectorSheetRoute
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.ui.ContentListActivity
import org.skepsun.kototoro.search.ui.multi.SearchActivity
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistActivity
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.about.AppUpdateActivity
import org.skepsun.kototoro.settings.override.OverrideConfigActivity
import org.skepsun.kototoro.settings.reader.ReaderTapGridConfigActivity
import org.skepsun.kototoro.settings.sources.auth.SourceAuthActivity
import org.skepsun.kototoro.settings.sources.catalog.SourcesCatalogActivity
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.settings.storage.ContentDirectorySelectRoute
import org.skepsun.kototoro.settings.storage.ContentDirectorySelectViewModel
import org.skepsun.kototoro.settings.storage.directories.ContentDirectoriesActivity
import org.skepsun.kototoro.settings.tracker.categories.TrackerCategoriesConfigRoute
import org.skepsun.kototoro.stats.ui.StatsActivity
import org.skepsun.kototoro.stats.ui.sheet.compose.ContentStatsRoute

import java.io.File
import androidx.appcompat.R as appcompatR

@Composable
private fun AppRouterChoiceDialog(
    icon: @Composable () -> Unit,
    title: String,
    options: List<String>,
    dismissLabel: String,
    onDismissRequest: () -> Unit,
    onOptionSelected: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = icon,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    TextButton(
                        onClick = { onOptionSelected(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(option, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(dismissLabel)
            }
        },
    )
}

class AppRouter private constructor(
    private val activity: FragmentActivity?,
    private val fragment: Fragment?,
) {

    constructor(activity: FragmentActivity) : this(activity, null)

    constructor(fragment: Fragment) : this(null, fragment)

    private val settings: AppSettings by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(checkNotNull(contextOrNull())).settings
    }

    private val mangaRepositoryFactory: ContentRepository.Factory by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(checkNotNull(contextOrNull())).mangaRepositoryFactory
    }

    private val contentDataRepository: ContentDataRepository by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(checkNotNull(contextOrNull())).contentDataRepository
    }

    private val workResolver: WorkResolver by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(checkNotNull(contextOrNull())).workResolver
    }

    private val jsonSourceManager: org.skepsun.kototoro.core.jsonsource.JsonSourceManager by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(checkNotNull(contextOrNull())).jsonSourceManager
    }

    private val spaceFeatureFlagsRepository: org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(
            checkNotNull(contextOrNull()),
        ).spaceFeatureFlagsRepository
    }

    private val spaceRepository: org.skepsun.kototoro.space.domain.SpaceRepository by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(
            checkNotNull(contextOrNull()),
        ).spaceRepository
    }

    private fun prepareImmersiveIntent(intent: Intent): Intent {
        ImmersiveSpaceSwitcherTransition.attachDetailsOrigin(intent)
        intent.putExtra(EXTRA_HAS_IN_APP_CALLER, true)
        val immersiveSwitchEnabled = spaceFeatureFlagsRepository.flags.value.effectiveImmersiveSwitchEnabled
        val flags = immersiveTaskFlags(immersiveSwitchEnabled)
        if (immersiveSwitchEnabled) {
            intent.putExtra(EXTRA_IMMERSIVE_SESSION_SPACE_ID, spaceRepository.activeSpace.value.value)
        }
        if (flags != 0) {
            intent.addFlags(flags)
        }
        return intent
    }

    /** Activities **/

    fun openList(source: ContentSource, filter: ContentListFilter?, sortOrder: SortOrder?) {
        startActivity(listIntent(contextOrNull() ?: return, source, filter, sortOrder))
    }

    fun openList(tag: ContentTag) = openList(tag.source, ContentListFilter(tags = setOf(tag)), null)

    fun openSearch(
        query: String,
        kind: SearchKind = SearchKind.SIMPLE,
        sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>? = null,
        contentKinds: Set<SearchContentKind>? = null,
        advancedTitle: String? = null,
        advancedTags: String? = null,
        advancedAuthor: String? = null,
        pinnedOnly: Boolean = false,
        hideEmpty: Boolean = false,
    ) {
        val intent = Intent(contextOrNull() ?: return, SearchActivity::class.java)
            .putExtra(KEY_QUERY, query)
            .putExtra(KEY_KIND, kind)
            .putExtra(KEY_ADVANCED_TITLE, advancedTitle)
            .putExtra(KEY_ADVANCED_TAGS, advancedTags)
            .putExtra(KEY_ADVANCED_AUTHOR, advancedAuthor)
            .putExtra(KEY_PINNED_ONLY, pinnedOnly)
            .putExtra(KEY_HIDE_EMPTY, hideEmpty)
        if (!sourceTypes.isNullOrEmpty()) {
            intent.putExtra(KEY_SOURCE_TYPES, org.skepsun.kototoro.search.domain.sourceTypesToNames(sourceTypes))
        }
        if (!contentKinds.isNullOrEmpty()) {
            intent.putExtra(KEY_CONTENT_KINDS, org.skepsun.kototoro.search.domain.searchContentKindsToNames(contentKinds))
        }
        startActivity(intent)
    }

    fun openSearch(source: ContentSource, query: String) = openList(source, ContentListFilter(query = query), null)

    fun openSourcePresets() {
        startActivity(SourcePresetListActivity::class.java)
    }

    fun openDetails(manga: Content, anchor: View? = null) {
        val context = contextOrNull() ?: return
        val intent = detailsIntent(context, DetailsOrigin.LocalMangaContent(ParcelableContent(manga)))
        startActivity(intent, null)
    }

    fun openResolvedDetails(
        manga: Content,
        anchor: View? = null,
        sharedElementKey: String? = null,
    ) {
        val lifecycleOwner = getLifecycleOwner() ?: return
        lifecycleOwner.lifecycleScope.launch {
            when (val origin = resolveDetailsOriginForContent(manga)) {
                is DetailsOrigin.EntityGraph -> {
                    openEntityDetails(
                        entityId = origin.entityId,
                        initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId ?: manga.id,
                        sharedElementKey = sharedElementKey,
                    )
                }
                is DetailsOrigin.LocalMangaContent -> openDetails(manga, anchor)
                is DetailsOrigin.LocalMangaId -> openDetails(origin.mangaId)
                else -> openDetails(manga, anchor)
            }
        }
    }

    fun openTemporaryDetails(manga: Content) {
        val context = contextOrNull() ?: return
        val intent = detailsIntent(context, DetailsOrigin.LocalMangaContent(ParcelableContent(manga)))
            .putExtra(KEY_TEMPORARY_DETAILS, true)
        startActivity(intent, null)
    }

    fun openDetails(mangaId: Long) {
        startActivity(detailsIntent(contextOrNull() ?: return, DetailsOrigin.LocalMangaId(mangaId)))
    }

    fun openDetails(link: Uri) {
        startActivity(
            Intent(contextOrNull() ?: return, DetailsActivity::class.java)
                .setData(link),
        )
    }

    fun openEntityDetails(
        entityId: Long,
        preferredLocalMangaId: Long? = null,
        initialProjectionLocalMangaId: Long? = null,
        service: ScrobblerService? = null,
        remoteId: Long? = null,
        url: String? = null,
        sharedElementKey: String? = null,
    ) {
        val origin = DetailsOrigin.EntityGraph(
            entityId = entityId,
            preferredLocalMangaId = preferredLocalMangaId,
            initialProjectionLocalMangaId = initialProjectionLocalMangaId,
            serviceId = service?.id?.toString(),
            remoteId = remoteId,
            url = url,
        )
        PendingDetailsNavigation.set(origin, sharedElementKey)
        startActivity(
            detailsIntent(
                contextOrNull() ?: return,
                origin,
            ),
        )
    }

    fun openTrackingEntityDetails(
        service: ScrobblerService,
        entityType: EntityType,
        remoteId: Long,
        name: String,
        altName: String? = null,
        coverUrl: String? = null,
        url: String? = null,
    ) {
        startActivity(
            detailsIntent(
                contextOrNull() ?: return,
                DetailsOrigin.TrackingEntity(
                    serviceId = service.id.toString(),
                    entityTypeName = entityType.name,
                    remoteId = remoteId,
                    name = name,
                    altName = altName,
                    coverUrl = coverUrl,
                    url = url,
                ),
            ),
        )
    }

    fun openTrackingSiteDetails(service: ScrobblerService, remoteId: Long, url: String? = null) {
        startActivity(detailsIntent(contextOrNull() ?: return, DetailsOrigin.TrackingItem(service.id.toString(), remoteId, url)))
    }

    fun openTrackingSiteRawDetails(service: ScrobblerService, remoteId: Long, url: String? = null) {
        startActivity(detailsIntent(contextOrNull() ?: return, DetailsOrigin.TrackingItem(service.id.toString(), remoteId, url)))
    }

	fun openTrackingDiscover(service: ScrobblerService, forceLoad: Boolean = false) {
		startActivity(
			Intent(contextOrNull() ?: return, org.skepsun.kototoro.discover.ui.TrackingDiscoverActivity::class.java)
				.putExtra(KEY_ID, service.name)
				.putExtra(KEY_FORCE_LOAD, forceLoad),
		)
	}

	fun openTrackingDiscoveryCategory(service: ScrobblerService, categoryId: String, titleResId: Int) {
		startActivity(
			Intent(contextOrNull() ?: return, org.skepsun.kototoro.discover.ui.category.DiscoverCategoryActivity::class.java)
				.putExtra(KEY_ID, service.name)
				.putExtra(KEY_KIND, categoryId)
				.putExtra(KEY_TITLE, titleResId)
				.putExtra(KEY_SOURCE, (if (service.name == "BANGUMI") "TRACKING_BANGUMI_" else "TRACKING_SHIKIMORI_") + categoryId)
		)
	}

	fun openReader(
		manga: Content,
		anchor: View? = null,
		contentTypeOverride: ContentType? = null,
	) {
		val source = manga.source.unwrap()
        val contentType = contentTypeOverride ?: if (manga.looksLikeLocalVideoContent()) {
            ContentType.VIDEO
        } else {
            getContentType(source)
        }
        if (contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL) {
            startActivity(
                prepareImmersiveIntent(
                    Intent(contextOrNull() ?: return, NovelReaderActivity::class.java)
                        .putExtra(KEY_MANGA, ParcelableContent(manga))
                        .putExtra(KEY_ID, manga.id),
                ),
                anchor?.let { scaleUpActivityOptionsOf(it) },
            )
            return
        }
        if (contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO) {
            val url = manga.publicUrl
            val lastSegment = url.toUriOrNull()?.lastPathSegment ?: url
            val isDirectStream = lastSegment.endsWith(".m3u8", ignoreCase = true) ||
                lastSegment.endsWith(".mp4", ignoreCase = true)

            if (isDirectStream) {
                // 鐩撮摼瑙嗛锛氳嫢宸插姞杞界珷鑺傦紝鍒欓檮甯﹂?ReaderState 浠ヤ究缁熻/淇濆瓨杩涘害
                val state = runCatching {
                    val chapters = manga.chapters
                    if (!chapters.isNullOrEmpty()) {
                        org.skepsun.kototoro.reader.ui.ReaderState(manga, null)
                    } else null
                }.getOrNull()
                openVideo(
                    url = url,
                    manga = manga,
                    anchor = anchor,
                    state = state,
                )
            } else {
                // 闈炵洿閾撅細闇€瑕佸姞杞界珷鑺傛墠鑳借В鏋怳RL
                // 濡傛灉绔犺妭鏈姞杞斤紝鍏堝姞杞界珷?
                if (manga.chapters.isNullOrEmpty()) {
                    // 寮傛鍔犺浇绔犺妭鍚庡啀鎵撳紑鎾斁?
                    val lifecycleOwner = (activity as? LifecycleOwner) ?: (fragment as? LifecycleOwner)
                    lifecycleOwner?.lifecycleScope?.launch {
                        try {
                            val repo = mangaRepositoryFactory.create(manga.source)
                            val details = repo.getDetails(manga)
                            val mangaWithChapters = details.copy(chapters = details.chapters)
                            
                            openVideo(
                                url = url,
                                manga = mangaWithChapters,
                                anchor = anchor,
                                state = runCatching {
                                    val chapters = mangaWithChapters.chapters
                                    if (!chapters.isNullOrEmpty()) {
                                        org.skepsun.kototoro.reader.ui.ReaderState(mangaWithChapters, null)
                                    } else null
                                }.getOrNull(),
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("AppRouter", "Failed to load chapters for video", e)
                            // 鍏滃簳锛氫粛鐒跺皾璇曟墦寮€锛岃VideoPlayerActivity澶勭悊閿欒
                            openVideo(
                                url = url,
                                manga = manga,
                                anchor = anchor,
                                state = null,
                            )
                        }
                    }
                } else {
                    // 绔犺妭宸插姞杞斤紝鐩存帴鎵撳?
                    openVideo(
                        url = url,
                        manga = manga,
                        anchor = anchor,
                        state = runCatching {
                            val chapters = manga.chapters
                            if (!chapters.isNullOrEmpty()) {
                                org.skepsun.kototoro.reader.ui.ReaderState(manga, null)
                            } else null
                        }.getOrNull(),
                    )
                }
            }
        } else {
            openReader(
                ReaderIntent.Builder(contextOrNull() ?: return)
                    .manga(manga)
                    .build(),
                anchor,
            )
        }
    }

	private fun resolveVideoStartUrl(manga: Content, state: ReaderState?): String {
		return state
			?.let { currentState -> manga.chapters?.firstOrNull { it.id == currentState.chapterId }?.url }
			?.takeIf { it.isNotBlank() }
			?: manga.publicUrl
	}

	fun openReader(intent: ReaderIntent, anchor: View? = null) {
		val activityIntent = intent.intent
		// Intercept video sources when ReaderIntent carries a Content extra and route accordingly
		runCatching {
			val parcelable = activityIntent.getParcelableExtraCompat<ParcelableContent>(KEY_MANGA)
			val manga = parcelable?.manga ?: run {
				val contentIntent = ContentIntent(activityIntent)
				val mangaId = contentIntent.mangaId
				if (mangaId != ContentIntent.ID_NONE) {
					kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
						contentDataRepository.findDisplayContentById(mangaId, withChapters = false)
							?: contentDataRepository.findContentById(mangaId, withChapters = false)
					}
				} else null
			}
			if (manga != null) {
                // 瀵硅棰戝唴瀹瑰拰EPUB鍐呭锛氫紶?ReaderState锛屼紭鍏堜娇鐢ㄥ巻鍙茶褰曚腑鐨勭姸?
                val source = manga.source.unwrap()
                val history = activityIntent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)
                
                val contentType = if (manga.looksLikeLocalVideoContent()) {
                    ContentType.VIDEO
                } else {
                    getContentType(source)
                }
                
                if (contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL) {
                    val state = if (history != null) {
                        // 浣跨敤鍘嗗彶璁板綍涓殑鐘舵€侊紙鍖呭惈姝ｇ‘鐨勭珷鑺侷D?
                        history
                    } else {
                        // 鍚﹀垯浣跨敤Intent涓惡甯︾殑鐘舵?
                        activityIntent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)
                    }
                    val novelIntent = Intent(contextOrNull() ?: return, NovelReaderActivity::class.java)
                        .putExtra(KEY_MANGA, ParcelableContent(manga))
                        .putExtra(KEY_ID, manga.id)
                    // 浼犻€扲eaderState
                    if (state != null) {
                        novelIntent.putExtra(ReaderIntent.EXTRA_STATE, state)
                    }
                    startActivity(
                        prepareImmersiveIntent(novelIntent),
                        anchor?.let { scaleUpActivityOptionsOf(it) },
                    )
                    return
                }
				if (contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO) {
                    val state = activityIntent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)
                    val url = resolveVideoStartUrl(manga, state)
                    val lastSegment = url.toUriOrNull()?.lastPathSegment ?: url
                    val isDirectStream = lastSegment.endsWith(".m3u8", ignoreCase = true) ||
                        lastSegment.endsWith(".mp4", ignoreCase = true)

                    if (isDirectStream) {
                        openVideo(
                            url = url,
                            manga = manga,
                            anchor = anchor,
                            state = state,
                        )
                    } else {
                        // 闈炵洿閾撅細闇€瑕佸姞杞界珷鑺傛墠鑳借В鏋怳RL
                        // 濡傛灉绔犺妭鏈姞杞斤紝鍏堝姞杞界珷?
                        if (manga.chapters.isNullOrEmpty()) {
                            // 寮傛鍔犺浇绔犺妭鍚庡啀鎵撳紑鎾斁?
                            val lifecycleOwner = (activity as? LifecycleOwner) ?: (fragment as? LifecycleOwner)
                            lifecycleOwner?.lifecycleScope?.launch {
                                try {
                                    val repo = mangaRepositoryFactory.create(manga.source)
                                    val details = repo.getDetails(manga)
                                    val mangaWithChapters = details.copy(chapters = details.chapters)
                                    val resolvedUrl = resolveVideoStartUrl(mangaWithChapters, state)
                                    
                                    openVideo(
                                        url = resolvedUrl,
                                        manga = mangaWithChapters,
                                        anchor = anchor,
                                        state = state,
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("AppRouter", "Failed to load chapters for video", e)
                                    // 鍏滃簳锛氫粛鐒跺皾璇曟墦寮€锛岃VideoPlayerActivity澶勭悊閿欒
                                    openVideo(
                                        url = url,
                                        manga = manga,
                                        anchor = anchor,
                                        state = state,
                                    )
                                }
                            }
                        } else {
                            // 绔犺妭宸插姞杞斤紝鐩存帴鎵撳?
                            openVideo(
                                url = url,
                                manga = manga,
                                anchor = anchor,
                                state = state,
                            )
                        }
                    }
                    return
                }
            }
        }.getOrElse { /* ignore and fallback to reader */ }
        if (settings.isReaderMultiTaskEnabled && activityIntent.data != null) {
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        startActivity(
            prepareImmersiveIntent(activityIntent),
            anchor?.let { view -> scaleUpActivityOptionsOf(view) },
        )
    }

    fun openAlternatives(manga: Content) {
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                AlternativesSheetRoute(
                    manga = manga,
                    onOpenDetails = {
                        composeActivity.dismissComposeModal()
                        openDetails(it)
                    },
                    onOpenSourceSearch = { source, query ->
                        openSearch(source, query)
                    },
                    onDismissRequest = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun openRelated(manga: Content) {
        startActivity(
            Intent(contextOrNull(), RelatedContentActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableContent(manga))
                .putExtra(KEY_ID, manga.id),
        )
    }

    fun openImage(url: String, source: ContentSource?, anchor: View? = null, preview: CoilMemoryCacheKey? = null) {
        startActivity(
            Intent(contextOrNull(), ImageActivity::class.java)
                .setData(Uri.parse(url))
                .putExtra(KEY_SOURCE, source?.name)
                .putExtra(KEY_PREVIEW, preview),
            anchor?.let { scaleUpActivityOptionsOf(it) },
        )
    }

    fun openNovelInlineImage(
        imagePath: String,
        source: ContentSource?,
        epubFilePath: String?,
        chapterPath: String?,
        headers: Map<String, String>,
        anchor: View? = null,
    ) {
        val intent = Intent(contextOrNull(), ImageActivity::class.java)
            .putExtra(KEY_SOURCE, source?.name)
            .putExtra(KEY_IMAGE_PATH, imagePath)
            .putExtra(KEY_EPUB_FILE_PATH, epubFilePath)
            .putExtra(KEY_CHAPTER_PATH, chapterPath)
            .putExtra(KEY_IMAGE_HEADERS, HashMap(headers))
        imagePath.toUriOrNull()?.let { intent.data = it }
        startActivity(
            intent,
            anchor?.let { scaleUpActivityOptionsOf(it) },
        )
    }

    fun openVideo(
        url: String,
        source: ContentSource?,
        title: String? = null,
        anchor: View? = null,
        state: ReaderState? = null,
    ) {
        val ctx = contextOrNull() ?: return
        startActivity(
            prepareImmersiveIntent(
                Intent(ctx, org.skepsun.kototoro.video.ui.VideoPlayerActivity::class.java)
                    .setData(Uri.parse(url))
                    .putExtra(KEY_URL, url)
                    .putExtra(KEY_SOURCE, source?.name)
                    .putExtra(KEY_TITLE, title)
                    .putExtra(ReaderIntent.EXTRA_STATE, state),
            ),
            null,
        )
    }

    fun openVideo(
        url: String,
        manga: Content,
        anchor: View? = null,
        state: ReaderState? = null,
    ) {
        val ctx = contextOrNull() ?: return
        startActivity(
            prepareImmersiveIntent(
                Intent(ctx, org.skepsun.kototoro.video.ui.VideoPlayerActivity::class.java)
                    .setData(Uri.parse(url))
                    .putExtra(KEY_URL, url)
                    .putExtra(KEY_SOURCE, manga.source.name)
                    .putExtra(KEY_TITLE, manga.title)
                    .putExtra(KEY_ID, manga.id)
                    .putExtra(KEY_MANGA, ParcelableContent(manga, withChapters = !manga.chapters.isNullOrEmpty()))
                    .putExtra(ReaderIntent.EXTRA_STATE, state),
            ),
            null,
        )
    }

    fun openAppUpdate() = startActivity(AppUpdateActivity::class.java)



    fun openSourcesCatalog() = startActivity(SourcesCatalogActivity::class.java)

    fun openDownloads() = startActivity(DownloadsActivity::class.java)

    fun openDirectoriesSettings() = startActivity(ContentDirectoriesActivity::class.java)

    fun openBrowser(url: String, source: ContentSource?, title: String?) {
        startActivity(browserIntent(contextOrNull() ?: return, url, source, title))
    }

    fun openBrowser(manga: Content) = openBrowser(
        url = manga.publicUrl,
        source = manga.source,
        title = manga.title,
    )

    fun openColorFilterConfig(manga: Content, page: ContentPage) {
        startActivity(
            Intent(contextOrNull(), ColorFilterConfigActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableContent(manga))
                .putExtra(KEY_PAGES, ParcelableContentPage(page)),
        )
    }

    fun openHistory(groupTab: BrowseGroupTab? = null) {
        startActivity(historyIntent(contextOrNull() ?: return, groupTab))
    }

    fun openFavorites() = startActivity(FavouritesActivity::class.java)


    fun openFavorites(category: FavouriteCategory) {
        startActivity(
            Intent(contextOrNull() ?: return, FavouritesActivity::class.java)
                .putExtra(KEY_ID, category.id)
                .putExtra(KEY_TITLE, category.title),
        )
    }

    fun openFavoriteCategories() = startActivity(FavouriteCategoriesActivity::class.java)

    fun openFavoriteCategoryEdit(categoryId: Long) {
        startActivity(
            Intent(contextOrNull() ?: return, FavouritesCategoryEditActivity::class.java)
                .putExtra(KEY_ID, categoryId),
        )
    }

    fun openFavoriteCategoryCreate() = openFavoriteCategoryEdit(FavouritesCategoryEditActivity.NO_ID)


    fun openContentOverrideConfig(manga: Content) {
        val intent = overrideEditIntent(contextOrNull() ?: return, manga)
        startActivity(intent)
    }

    fun openSettings() {
        val hostActivity = activity
        startActivity(
            Intent(contextOrNull() ?: return, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_USE_HORIZONTAL_ROUTE_TRANSITION, true),
        )
        hostActivity?.applyHorizontalRouteOpenTransition()
    }

    fun openEntityOrganizeSettings(selectedContentIds: Set<Long> = emptySet()) {
        val hostActivity = activity
        startActivity(
            SettingsActivity.newEntityOrganizeIntent(
                context = contextOrNull() ?: return,
                selectedContentIds = selectedContentIds,
            ),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openTranslationSettings() {
        val hostActivity = activity
        startActivity(
            translationSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openReaderSettings() {
        val hostActivity = activity
        startActivity(
            readerSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openSyncSettings() {
        val hostActivity = activity
        startActivity(
            syncSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openPeriodicBackupSettings() {
        openSyncSettings()
    }

    fun openProxySettings() {
        val hostActivity = activity
        startActivity(
            proxySettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openDownloadsSetting() {
        val hostActivity = activity
        startActivity(
            downloadsSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openSourceSettings(source: ContentSource) {
        startActivity(sourceSettingsIntent(contextOrNull() ?: return, source))
    }

    fun openSuggestionsSettings() {
        val hostActivity = activity
        startActivity(
            suggestionsSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openTrackingAccountsSettings() {
        val hostActivity = activity
        startActivity(
            trackingAccountsSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openSourcesSettings() {
        val hostActivity = activity
        startActivity(
            sourcesSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openGlobalTagBlacklist() {
        startActivity(GlobalTagBlacklistActivity.newIntent(contextOrNull() ?: return))
    }

    fun openDiscordSettings() {
        val hostActivity = activity
        startActivity(
            discordSettingsIntent(contextOrNull() ?: return),
            hostActivity?.let(::activityTransitionOptionsOf),
        )
    }

    fun openReaderTapGridSettings() = startActivity(ReaderTapGridConfigActivity::class.java)

    fun openScrobblerSettings(scrobbler: ScrobblerService) {
        startActivity(
            Intent(contextOrNull() ?: return, ScrobblerConfigActivity::class.java)
                .putExtra(KEY_ID, scrobbler.id),
        )
    }

    fun openScrobblerBinding(
        scrobbler: ScrobblerService,
        remoteId: Long,
        title: String,
        url: String?,
    ) {
        startActivity(
            Intent(contextOrNull() ?: return, ScrobblerConfigActivity::class.java)
                .putExtra(KEY_ID, scrobbler.id)
                .putExtra(KEY_REMOTE_ID, remoteId)
                .putExtra(KEY_TITLE, title)
                .putExtra(KEY_URL, url),
        )
    }

    fun openSourceAuth(source: ContentSource) {
        startActivity(sourceAuthIntent(contextOrNull() ?: return, source))
    }

    fun openManageSources() {
        startActivity(
            manageSourcesIntent(contextOrNull() ?: return),
        )
    }

    fun openStatistic() = startActivity(StatsActivity::class.java)

    @CheckResult
    fun openExternalBrowser(url: String, chooserTitle: CharSequence? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = url.toUriOrNull() ?: return false
        return startActivitySafe(
            if (!chooserTitle.isNullOrEmpty()) {
                Intent.createChooser(intent, chooserTitle)
            } else {
                intent
            },
        )
    }

    @CheckResult
    fun openSystemSyncSettings(account: Account): Boolean {
        val args = Bundle(1)
        args.putParcelable(ACCOUNT_KEY, account)
        val intent = Intent(ACTION_ACCOUNT_SYNC_SETTINGS)
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args)
        return startActivitySafe(intent)
    }

    /** Dialogs **/

    fun showDownloadDialog(manga: Content, snackbarHost: View? = null) = showDownloadDialog(setOf(manga), snackbarHost)

    fun showDownloadDialog(manga: Collection<Content>, snackbarHost: View? = null) {
        if (manga.isEmpty()) {
            return
        }
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            val mangaList = manga.toList()
            composeActivity.showComposeModal {
                DownloadDialog(
                    mangaList = mangaList,
                    snackbarHostState = composeActivity.snackbarHostState,
                    onOpenDownloads = ::openDownloads,
                    onDismiss = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun showLocalInfoDialog(manga: Content) {
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                LocalInfoDialogRoute(
                    manga = manga,
                    onDismissRequest = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun showDirectorySelectDialog(contentType: String = ContentDirectorySelectViewModel.CONTENT_TYPE_MANGA) {
        val composeActivity = activity as? BaseComposeActivity ?: return
        composeActivity.showComposeModal {
            ContentDirectorySelectRoute(
                contentType = contentType,
                onDismiss = composeActivity::dismissComposeModal,
                onError = { error ->
                    composeActivity.lifecycleScope.launch {
                        composeActivity.snackbarHostState.showSnackbar(error.getDisplayMessage(composeActivity.resources))
                    }
                },
            )
        }
    }

    fun showFavoriteDialog(manga: Content) = showFavoriteDialog(setOf(manga))

    fun showFavoriteDialog(manga: Collection<Content>) {
        if (manga.isEmpty()) {
            return
        }
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            val mangaList = manga.toList()
            composeActivity.showComposeModal {
                FavoriteCategoryDialogRoute(
                    manga = mangaList,
                    onManageCategories = ::openFavoriteCategories,
                    onDismiss = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun showTagDialog(tag: ContentTag) {
        val context = contextOrNull() ?: return
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                AppRouterChoiceDialog(
                    icon = {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_tag),
                            contentDescription = null,
                        )
                    },
                    title = tag.title,
                    options = listOf(
                        stringResource(R.string.search_on_s, tag.source.getTitle(composeActivity)),
                        stringResource(R.string.search_everywhere),
                    ),
                    dismissLabel = stringResource(R.string.close),
                    onDismissRequest = composeActivity::dismissComposeModal,
                    onOptionSelected = { which ->
                        composeActivity.dismissComposeModal()
                        when (which) {
                            0 -> openList(tag)
                            1 -> openSearch(tag.title, SearchKind.TAG)
                        }
                    },
                )
            }
            return
        }
        buildAlertDialog(context) {
            setIcon(R.drawable.ic_tag)
            setTitle(tag.title)
            setItems(
                arrayOf(
                    context.getString(R.string.search_on_s, tag.source.getTitle(context)),
                    context.getString(R.string.search_everywhere),
                ),
            ) { _, which ->
                when (which) {
                    0 -> openList(tag)
                    1 -> openSearch(tag.title, SearchKind.TAG)
                }
            }
            setNegativeButton(R.string.close, null)
            setCancelable(true)
        }.show()
    }

    fun showAuthorDialog(author: String, source: ContentSource) {
        val context = contextOrNull() ?: return
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                AppRouterChoiceDialog(
                    icon = {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_user),
                            contentDescription = null,
                        )
                    },
                    title = author,
                    options = listOf(
                        stringResource(R.string.search_on_s, source.getTitle(composeActivity)),
                        stringResource(R.string.search_everywhere),
                    ),
                    dismissLabel = stringResource(R.string.close),
                    onDismissRequest = composeActivity::dismissComposeModal,
                    onOptionSelected = { which ->
                        composeActivity.dismissComposeModal()
                        when (which) {
                            0 -> openList(source, ContentListFilter(author = author), null)
                            1 -> openSearch(author, SearchKind.AUTHOR)
                        }
                    },
                )
            }
            return
        }
        buildAlertDialog(context) {
            setIcon(R.drawable.ic_user)
            setTitle(author)
            setItems(
                arrayOf(
                    context.getString(R.string.search_on_s, source.getTitle(context)),
                    context.getString(R.string.search_everywhere),
                ),
            ) { _, which ->
                when (which) {
                    0 -> openList(source, ContentListFilter(author = author), null)
                    1 -> openSearch(author, SearchKind.AUTHOR)
                }
            }
            setNegativeButton(R.string.close, null)
            setCancelable(true)
        }.show()
    }

    fun showShareDialog(manga: Content) {
        if (manga.isBroken) {
            return
        }
        if (manga.isLocal) {
            manga.url.toUriOrNull()?.toFileOrNull()?.let {
                shareFile(it)
            }
            return
        }
        val context = contextOrNull() ?: return
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                AppRouterChoiceDialog(
                    icon = {
                        Icon(
                            painter = rememberDrawablePainter(
                                composeActivity.getThemeDrawable(appcompatR.attr.actionModeShareDrawable),
                            ),
                            contentDescription = null,
                        )
                    },
                    title = stringResource(R.string.share),
                    options = listOf(
                        stringResource(R.string.link_to_manga_in_app),
                        stringResource(R.string.link_to_manga_on_s, manga.source.getTitle(composeActivity)),
                    ),
                    dismissLabel = stringResource(android.R.string.cancel),
                    onDismissRequest = composeActivity::dismissComposeModal,
                    onOptionSelected = { which ->
                        composeActivity.dismissComposeModal()
                        when (which) {
                            0 -> shareLink(manga.appUrl.toString(), manga.title)
                            1 -> shareLink(manga.publicUrl, manga.title)
                        }
                    },
                )
            }
            return
        }
        buildAlertDialog(context) {
            setIcon(context.getThemeDrawable(appcompatR.attr.actionModeShareDrawable))
            setTitle(R.string.share)
            setItems(
                arrayOf(
                    context.getString(R.string.link_to_manga_in_app),
                    context.getString(R.string.link_to_manga_on_s, manga.source.getTitle(context)),
                ),
            ) { _, which ->
                val link = when (which) {
                    0 -> manga.appUrl.toString()
                    1 -> manga.publicUrl
                    else -> return@setItems
                }
                shareLink(link, manga.title)
            }
            setNegativeButton(android.R.string.cancel, null)
            setCancelable(true)
        }.show()
    }

    fun showErrorDialog(error: Throwable, url: String? = null) {
        startActivitySafe(
            Intent(contextOrNull(), ErrorDetailsActivity::class.java)
                .putExtra(KEY_ERROR, error.toSerializableThrowable() as java.io.Serializable)
                .putExtra(KEY_URL, url),
        )
    }

	fun showBackupRestoreDialog(
		fileUri: Uri,
		restoreFormat: BackupRestoreFormat = BackupRestoreFormat.KOTOTORO_CURRENT,
	) {
        val composeActivity = (activity ?: fragment?.activity) as? BaseComposeActivity ?: return
        composeActivity.showComposeModal {
			RestoreDialogRoute(
				uri = fileUri,
				restoreFormat = restoreFormat,
                onRestoreStarted = {
                    closeWelcomeSheet()
                    composeActivity.dismissComposeModal()
                },
                onUnsupported = {
                    composeActivity.lifecycleScope.launch {
                        composeActivity.snackbarHostState.showSnackbar(
                            composeActivity.getString(R.string.operation_not_supported),
                        )
                    }
                },
                onDismiss = composeActivity::dismissComposeModal,
            )
        }
    }

    fun showImportDialog() {
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                ImportDialog(
                    onDismissRequest = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun showFilterSheet(): Boolean {
        if (!isFilterSupported()) {
            return false
        }
        val composeActivity = activity as? BaseComposeActivity
        val filterOwner = activity as? FilterCoordinator.Owner
        if (composeActivity != null && filterOwner != null) {
            val modalKey = FILTER_SHEET_MODAL_KEY
            composeActivity.showComposeModal(key = modalKey) {
                FilterSheetRoute(
                    filter = filterOwner.filterCoordinator,
                    isEmbedded = false,
                    onDismiss = { composeActivity.dismissComposeModal(modalKey) },
                    onOpenTagCatalog = { groupTitle, excludeMode ->
                        showTagsCatalogSheet(excludeMode = excludeMode, groupTitle = groupTitle)
                    },
                )
            }
            return true
        }
        return false
    }

    fun showTagsCatalogSheet(excludeMode: Boolean, groupTitle: String? = null) {
        if (!isFilterSupported()) {
            return
        }
        val composeActivity = activity as? BaseComposeActivity
        val filterOwner = activity as? FilterCoordinator.Owner
        if (composeActivity != null && filterOwner != null) {
            val modalKey = buildTagsCatalogModalKey(excludeMode = excludeMode, groupTitle = groupTitle)
            composeActivity.showComposeModal(key = modalKey) {
                TagsCatalogRoute(
                    filter = filterOwner.filterCoordinator,
                    isExcludeTag = excludeMode,
                    groupTitle = groupTitle,
                    onDismiss = { composeActivity.dismissComposeModal(modalKey) },
                )
            }
            return
        }
    }

    fun showListConfigSheet(section: ListConfigSection) {
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                ListConfigRoute(
                    section = section,
                    onDismissRequest = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun showStatisticSheet(manga: Content) {
        val composeActivity = activity as? BaseComposeActivity
        if (composeActivity != null) {
            composeActivity.showComposeModal {
                ContentStatsRoute(
                    manga = manga,
                    onOpenDetails = {
                        composeActivity.dismissComposeModal()
                        openDetails(manga)
                    },
                    onDismissRequest = composeActivity::dismissComposeModal,
                )
            }
            return
        }
    }

    fun showWelcomeSheet() {
        val composeActivity = (activity ?: fragment?.activity) as? BaseComposeActivity ?: return
        composeActivity.dismissComposeModal(WELCOME_MODAL_KEY)
        composeActivity.showComposeModal(key = WELCOME_MODAL_KEY) {
            WelcomeRoute(
                onDismissRequest = { composeActivity.dismissComposeModal(WELCOME_MODAL_KEY) },
                onRestoreBackup = { uri ->
                    composeActivity.dismissComposeModal(WELCOME_MODAL_KEY)
                    showBackupRestoreDialog(uri)
                },
                onOpenDocumentUnsupported = {
                    composeActivity.lifecycleScope.launch {
                        composeActivity.snackbarHostState.showSnackbar(
                            composeActivity.getString(R.string.operation_not_supported),
                        )
                    }
                },
            )
        }
    }

    fun showScrobblingSelectorSheet(manga: Content, scrobblerService: ScrobblerService?) {
        val composeActivity = (activity ?: fragment?.activity) as? BaseComposeActivity ?: return
        composeActivity.showComposeModal {
            ScrobblingSelectorSheetRoute(
                manga = manga,
                scrobblerServiceId = scrobblerService?.id ?: -1,
                onDismissRequest = composeActivity::dismissComposeModal,
            )
        }
    }

    fun showScrobblingInfoSheet(scrobblerService: ScrobblerService) {
        val composeActivity = (activity ?: fragment?.activity) as? BaseComposeActivity ?: return
        composeActivity.showComposeModal {
            ScrobblingInfoSheetRoute(
                scrobblerServiceId = scrobblerService.id,
                onDismissRequest = composeActivity::dismissComposeModal,
            )
        }
    }

    fun showTrackerCategoriesConfigSheet() {
        val composeActivity = (activity ?: fragment?.activity) as? BaseComposeActivity ?: return
        composeActivity.showComposeModal {
            TrackerCategoriesConfigRoute(
                onDismissRequest = composeActivity::dismissComposeModal,
            )
        }
    }

    fun askForDownloadOverMeteredNetwork(onConfirmed: (allow: Boolean) -> Unit) {
        val context = contextOrNull() ?: return
        when (settings.allowDownloadOnMeteredNetwork) {
            TriStateOption.ENABLED -> onConfirmed(true)
            TriStateOption.DISABLED -> onConfirmed(false)
            TriStateOption.ASK -> {
                if (!context.connectivityManager.isActiveNetworkMetered) {
                    onConfirmed(true)
                    return
                }
                val listener = DialogInterface.OnClickListener { _, which ->
                    when (which) {
                        DialogInterface.BUTTON_POSITIVE -> {
                            settings.allowDownloadOnMeteredNetwork = TriStateOption.ENABLED
                            onConfirmed(true)
                        }

                        DialogInterface.BUTTON_NEUTRAL -> {
                            onConfirmed(true)
                        }

                        DialogInterface.BUTTON_NEGATIVE -> {
                            settings.allowDownloadOnMeteredNetwork = TriStateOption.DISABLED
                            onConfirmed(false)
                        }
                    }
                }
                BigButtonsAlertDialog.Builder(context)
                    .setIcon(R.drawable.ic_network_cellular)
                    .setTitle(R.string.download_cellular_confirm)
                    .setPositiveButton(R.string.allow_always, listener)
                    .setNeutralButton(R.string.allow_once, listener)
                    .setNegativeButton(R.string.dont_allow, listener)
                    .create()
                    .show()
            }
        }
    }

    /** Public utils **/

    fun isFilterSupported(): Boolean = when {
        fragment != null -> FilterCoordinator.find(fragment) != null
        activity != null -> activity is FilterCoordinator.Owner
        else -> false
    }

    fun closeWelcomeSheet(): Boolean {
        val composeActivity = (activity ?: fragment?.activity) as? BaseComposeActivity ?: return false
        composeActivity.dismissComposeModal(WELCOME_MODAL_KEY)
        return true
    }

    private fun getContentType(source: ContentSource): ContentType {
        return source.getContentType()
    }

    private suspend fun resolveDetailsOriginForContent(content: Content): DetailsOrigin {
        return withContext(Dispatchers.IO) {
            val entityId = workResolver.resolveByMangaId(content.id).entityId
            val canResolveProjection = entityId != null &&
                contentDataRepository.findContentById(content.id, withChapters = false) != null
            if (entityId != null && canResolveProjection) {
                DetailsOrigin.EntityGraph(
                    entityId = entityId,
                    initialProjectionLocalMangaId = content.id,
                )
            } else {
                DetailsOrigin.LocalMangaContent(ParcelableContent(content))
            }
        }
    }

    /** Private utils **/

    private fun startActivity(intent: Intent, options: Bundle? = null) {
        fragment?.also {
            if (it.isAdded) {
                it.startActivity(intent, options)
            }
        } ?: activity?.startActivity(intent, options)
    }

    private fun startActivitySafe(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun startActivity(activityClass: Class<out Activity>) {
        startActivity(Intent(contextOrNull() ?: return, activityClass))
    }

    private fun getFragmentManager(): FragmentManager? = runCatching {
        fragment?.childFragmentManager ?: activity?.supportFragmentManager
    }.onFailure { exception ->
        exception.printStackTraceDebug()
    }.getOrNull()

    fun shareLink(link: String, title: String) {
        val context = contextOrNull() ?: return
        ShareCompat.IntentBuilder(context)
            .setText(link)
            .setType(TYPE_TEXT)
            .setChooserTitle(context.getString(R.string.share_s, title.ellipsize(12)))
            .startChooser()
    }

    private fun shareFile(file: File) { // TODO directory sharing support
        val context = contextOrNull() ?: return
        val intentBuilder = ShareCompat.IntentBuilder(context)
            .setType(TYPE_CBZ)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        intentBuilder.addStream(uri)
        intentBuilder.setChooserTitle(context.getString(R.string.share_s, file.name))
        intentBuilder.startChooser()
    }

    @UiContext
    private fun contextOrNull(): Context? = activity ?: fragment?.context

    private fun getLifecycleOwner(): LifecycleOwner? = activity ?: fragment?.viewLifecycleOwner

    private fun DialogFragment.showDistinct(): Boolean {
        val fm = this@AppRouter.getFragmentManager() ?: return false
        val tag = javaClass.fragmentTag()
        val existing = fm.findFragmentByTag(tag) as? DialogFragment?
        if (existing != null && existing.isVisible && existing.arguments == this.arguments) {
            return false
        }
        show(fm, tag)
        return true
    }

    private fun DialogFragment.show() {
        show(
            this@AppRouter.getFragmentManager() ?: return,
            javaClass.fragmentTag(),
        )
    }

    private fun Fragment.findFragmentByTagRecursive(fragmentTag: String): Fragment? {
        childFragmentManager.findFragmentByTag(fragmentTag)?.let {
            return it
        }
        val parent = parentFragment
        return if (parent != null) {
            parent.findFragmentByTagRecursive(fragmentTag)
        } else {
            parentFragmentManager.findFragmentByTag(fragmentTag)
        }
    }

    companion object {
        private const val WELCOME_MODAL_KEY = "welcome-sheet-modal"
        private const val FILTER_SHEET_MODAL_KEY = "filter-sheet-modal"

        fun from(view: View): AppRouter? = runCatching {
            AppRouter(view.findFragment())
        }.getOrElse {
            (view.context.findActivity() as? FragmentActivity)?.let(::AppRouter)
        }

        fun detailsIntent(context: Context, origin: DetailsOrigin) = Intent(context, DetailsActivity::class.java)
            .putExtra(KEY_DETAILS_ORIGIN, origin)

        fun detailsIntent(context: Context, content: org.skepsun.kototoro.parsers.model.Content) = detailsIntent(context, org.skepsun.kototoro.details.ui.model.DetailsOrigin.LocalMangaContent(org.skepsun.kototoro.core.model.parcelable.ParcelableContent(content)))

        fun listIntent(context: Context, source: ContentSource, filter: ContentListFilter?, sortOrder: SortOrder?): Intent =
            Intent(context, ContentListActivity::class.java)
                .setAction(ACTION_MANGA_EXPLORE)
                .putExtra(KEY_SOURCE, source.name)
                .apply {
                    if (!filter.isNullOrEmpty()) {
                        putExtra(KEY_FILTER, ParcelableContentListFilter(filter))
                    }
                    if (sortOrder != null) {
                        putExtra(KEY_SORT_ORDER, sortOrder)
                    }
                }

        fun cloudFlareResolveIntent(
            context: Context,
            exception: CloudFlareProtectedException,
            hidden: Boolean = false,
        ): Intent =
            Intent(context, CloudFlareActivity::class.java).apply {
                data = Uri.parse(exception.url)
                putExtra(KEY_SOURCE, exception.source.name)
                putExtra(CloudFlareActivity.EXTRA_HIDDEN, hidden)
                exception.headers[CommonHeaders.USER_AGENT]?.let {
                    putExtra(KEY_USER_AGENT, it)
                }
            }

        fun browserIntent(
            context: Context,
            url: String,
            source: ContentSource?,
            title: String?
        ): Intent = Intent(context, BrowserActivity::class.java)
            .setData(Uri.parse(url))
            .putExtra(KEY_TITLE, title)
            .putExtra(KEY_SOURCE, source?.name)


        fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)

        fun historyIntent(context: Context, groupTab: BrowseGroupTab? = null) =
            Intent(context, HistoryActivity::class.java).apply {
                if (groupTab != null) {
                    putExtra(KEY_GROUP_TAB, groupTab.id)
                }
            }

        fun readerSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_READER)

        fun translationSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_TRANSLATION)

        fun suggestionsSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SUGGESTIONS)

        fun entityOrganizeSettingsIntent(
            context: Context,
            selectedContentIds: Set<Long> = emptySet(),
        ) = SettingsActivity.newEntityOrganizeIntent(
            context = context,
            selectedContentIds = selectedContentIds,
        )

        fun trackerSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_TRACKER)

        fun trackingAccountsSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_TRACKING_ACCOUNTS)

        fun syncSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SYNC_SETTINGS)

        fun periodicBackupSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_PERIODIC_BACKUP)

        fun discordSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_MANAGE_DISCORD)

        fun proxySettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_PROXY)

        fun historySettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_HISTORY)

        fun sourcesSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SOURCES)

        fun manageSourcesIntent(context: Context) =
            SettingsActivity.newUnifiedSourcesIntent(context)

        fun downloadsSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_MANAGE_DOWNLOADS)

        fun sourceSettingsIntent(context: Context, source: ContentSource): Intent = when (source) {
            is ContentSourceInfo -> sourceSettingsIntent(context, source.mangaSource)
            is ExternalContentSource -> {
                val kind = inferUnifiedSourceKind(source.packageName)
                SettingsActivity.newUnifiedSourcesIntent(context, initialRepositoryKind = kind)
            }

            else -> Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SOURCE)
                .putExtra(KEY_SOURCE, source.name)
        }

        private fun inferUnifiedSourceKind(packageName: String): UnifiedSourceKind? {
            return when {
                packageName.startsWith("eu.kanade.tachiyomi.animeextension") -> UnifiedSourceKind.ANIYOMI
                packageName.startsWith("eu.kanade.tachiyomi") -> UnifiedSourceKind.MIHON
                packageName.startsWith("ireader") -> UnifiedSourceKind.IREADER
                else -> null
            }
        }

        fun sourceAuthIntent(context: Context, source: ContentSource): Intent {
            return Intent(context, SourceAuthActivity::class.java)
                .putExtra(KEY_SOURCE, source.name)
        }

        fun overrideEditIntent(context: Context, manga: Content): Intent =
            Intent(context, OverrideConfigActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableContent(manga, withDescription = false))

        fun isShareSupported(manga: Content): Boolean = when {
            manga.isBroken -> false
            manga.isLocal -> manga.url.toUriOrNull()?.toFileOrNull() != null
            else -> true
        }

        fun shortContentUrl(mangaId: Long): Uri = Uri.Builder()
            .scheme("kototoro")
            .path("manga")
            .appendQueryParameter("id", mangaId.toString())
            .build()

        fun searchIntent(
            context: Context,
            query: String,
            kind: SearchKind = SearchKind.SIMPLE,
            sourceTypes: Set<org.skepsun.kototoro.core.jsonsource.SourceType>? = null,
            contentKinds: Set<SearchContentKind>? = null,
            pickMode: Boolean = false,
            advancedTitle: String? = null,
            advancedTags: String? = null,
            advancedAuthor: String? = null,
            pinnedOnly: Boolean = false,
            hideEmpty: Boolean = false,
        ): Intent {
            val intent = Intent(context, SearchActivity::class.java)
                .putExtra(KEY_QUERY, query)
                .putExtra(KEY_KIND, kind)
                .putExtra(KEY_PICK_MODE, pickMode)
                .putExtra(KEY_ADVANCED_TITLE, advancedTitle)
                .putExtra(KEY_ADVANCED_TAGS, advancedTags)
                .putExtra(KEY_ADVANCED_AUTHOR, advancedAuthor)
                .putExtra(KEY_PINNED_ONLY, pinnedOnly)
                .putExtra(KEY_HIDE_EMPTY, hideEmpty)
            if (!sourceTypes.isNullOrEmpty()) {
                intent.putExtra(KEY_SOURCE_TYPES, org.skepsun.kototoro.search.domain.sourceTypesToNames(sourceTypes))
            }
            if (!contentKinds.isNullOrEmpty()) {
                intent.putExtra(KEY_CONTENT_KINDS, org.skepsun.kototoro.search.domain.searchContentKindsToNames(contentKinds))
            }
            return intent
        }

        const val KEY_DATA = "data"
        const val KEY_ENTITY_ID = "entity_id"
        const val KEY_ENTRIES = "entries"
        const val KEY_ERROR = "error"
        const val KEY_EPUB_FILE_PATH = "epub_file_path"
        const val KEY_EXCLUDE = "exclude"
        const val KEY_FILE = "file"
        const val KEY_FILTER = "filter"
        const val KEY_FORCE_LOAD = "force_load"
        const val KEY_ID = "id"
        const val KEY_IMAGE_HEADERS = "image_headers"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_INDEX = "index"
        const val KEY_IS_BOTTOMTAB = "is_btab"
        const val KEY_KIND = "kind"
        const val KEY_LIST_SECTION = "list_section"
        const val KEY_DETAILS_ORIGIN = "details_origin"
        internal const val EXTRA_HAS_IN_APP_CALLER =
            "org.skepsun.kototoro.extra.HAS_IN_APP_CALLER"
        const val KEY_MANGA = "manga"
        const val KEY_MANGA_LIST = "manga_list"
        const val KEY_TEMPORARY_DETAILS = "temporary_details"
        const val KEY_PAGES = "pages"
        const val KEY_PREVIEW = "preview"
        const val KEY_PICK_MODE = "pick_mode"
        const val KEY_PINNED_ONLY = "pinned_only"
        const val KEY_QUERY = "query"
        const val KEY_ADVANCED_TITLE = "advanced_title"
        const val KEY_ADVANCED_TAGS = "advanced_tags"
        const val KEY_ADVANCED_AUTHOR = "advanced_author"
        const val KEY_REMOTE_ID = "remote_id"
        const val KEY_READER_MODE = "reader_mode"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_SOURCE = "source"
        const val KEY_SOURCE_TYPES = "source_types"
        const val KEY_CONTENT_KINDS = "content_kinds"
        const val KEY_HIDE_EMPTY = "hide_empty"
        const val KEY_GROUP_TAB = "group_tab"
        const val KEY_GROUP_TITLE = "group_title"
        const val KEY_TAB = "tab"
        const val KEY_TITLE = "title"
        const val KEY_URL = "url"
        const val KEY_CHAPTER_PATH = "chapter_path"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_SUCCESS_COOKIE_NAME = "success_cookie_name"
        const val KEY_SUCCESS_COOKIE_URL = "success_cookie_url"
        const val KEY_BROWSER_WAIT_TOKEN = "browser_wait_token"
        const val KEY_BROWSER_HTML = "browser_html"
        const val KEY_BROWSER_REFETCH_AFTER_SUCCESS = "browser_refetch_after_success"

        val ACTION_HISTORY = "${BuildConfig.APPLICATION_ID}.action.MANAGE_HISTORY"
        val ACTION_MANAGE_DOWNLOADS = "${BuildConfig.APPLICATION_ID}.action.MANAGE_DOWNLOADS"
        val ACTION_MANAGE_SOURCES = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SOURCES_LIST"
        val ACTION_ENTITY_ORGANIZE = "${BuildConfig.APPLICATION_ID}.action.ENTITY_ORGANIZE"
        val ACTION_MANGA_EXPLORE = "${BuildConfig.APPLICATION_ID}.action.EXPLORE_MANGA"
        val ACTION_PROXY = "${BuildConfig.APPLICATION_ID}.action.MANAGE_PROXY"
        val ACTION_READER = "${BuildConfig.APPLICATION_ID}.action.MANAGE_READER_SETTINGS"
        val ACTION_SOURCE = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SOURCE_SETTINGS"
        val ACTION_SOURCES = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SOURCES"
        val ACTION_MANAGE_DISCORD = "${BuildConfig.APPLICATION_ID}.action.MANAGE_DISCORD"
        val ACTION_SUGGESTIONS = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SUGGESTIONS"
        val ACTION_SYNC_SETTINGS = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SYNC_SETTINGS"
        val ACTION_TRACKER = "${BuildConfig.APPLICATION_ID}.action.MANAGE_TRACKER"
        val ACTION_TRACKING_ACCOUNTS = "${BuildConfig.APPLICATION_ID}.action.MANAGE_TRACKING_ACCOUNTS"
        val ACTION_TRANSLATION = "${BuildConfig.APPLICATION_ID}.action.MANAGE_TRANSLATION"
        val ACTION_PERIODIC_BACKUP = "${BuildConfig.APPLICATION_ID}.action.MANAGE_PERIODIC_BACKUP"

        private const val ACCOUNT_KEY = "account"
        private const val ACTION_ACCOUNT_SYNC_SETTINGS = "android.settings.ACCOUNT_SYNC_SETTINGS"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        private const val TYPE_TEXT = "text/plain"
        private const val TYPE_IMAGE = "image/*"
        private const val TYPE_CBZ = "application/x-cbz"

        private fun buildTagsCatalogModalKey(excludeMode: Boolean, groupTitle: String?): String {
            return buildString {
                append("tags-catalog-modal:")
                append(excludeMode)
                append(':')
                append(groupTitle.orEmpty())
            }
        }

        private fun Class<out Fragment>.fragmentTag() = name // TODO

        private inline fun <reified F : Fragment> fragmentTag() = F::class.java.fragmentTag()
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun immersiveTaskFlags(enabled: Boolean): Int = 0
