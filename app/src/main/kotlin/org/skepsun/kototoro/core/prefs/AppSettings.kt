package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.FloatRange
import androidx.appcompat.app.AppCompatDelegate
import androidx.collection.ArraySet
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.network.DoHProvider
import org.skepsun.kototoro.core.ui.compose.PanoramaAnimationSpeedMaxPercent
import org.skepsun.kototoro.core.ui.compose.PanoramaAnimationSpeedMinPercent
import org.skepsun.kototoro.explore.data.SourcesSortOrder
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.core.prefs.VideoRendererMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionShader
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.find
import org.skepsun.kototoro.parsers.util.mapNotNullToSet
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.core.util.ext.connectivityManager
import org.skepsun.kototoro.core.util.ext.getEnumValue
import org.skepsun.kototoro.core.util.ext.getSafeFloat
import org.skepsun.kototoro.core.util.ext.observeChanges
import org.skepsun.kototoro.core.util.ext.putAll
import org.skepsun.kototoro.core.util.ext.putEnumValue
import org.skepsun.kototoro.core.util.ext.takeIfReadable
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.io.File
import java.net.Proxy
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_THUMBNAIL_ASPECT_RATIO_DEFAULT = 0.7f
private const val PAGE_THUMBNAIL_ASPECT_RATIO_MIN = 0.35f
private const val PAGE_THUMBNAIL_ASPECT_RATIO_MAX = 1f

private fun SharedPreferences.getSafeInt(key: String, defValue: Int): Int {
	return try {
		getInt(key, defValue)
	} catch (_: ClassCastException) {
		getLong(key, defValue.toLong()).toInt().also {
			edit { putInt(key, it) }
		}
	}
}

private fun SharedPreferences.getSafeLong(key: String, defValue: Long): Long {
	return try {
		getLong(key, defValue)
	} catch (_: ClassCastException) {
		when (val raw = all[key]) {
			is Int -> raw.toLong()
			is Long -> raw
			is Float -> raw.toLong()
			is String -> raw.toLongOrNull() ?: defValue
			else -> defValue
		}.also {
			edit { putLong(key, it) }
		}
	}
}

enum class TrackingMetadataSourceStrategy {
	LOCAL_THEN_API,
	API_THEN_LOCAL,
	LOCAL_ONLY,
	API_ONLY,
}

enum class AppFontPreset {
	SYSTEM,
	ROBOTO,
	ROBOTO_FLEX,
	GOOGLE_SANS,
	NOTO_SANS,
	INTER,
	SARASA_GOTHIC,
	LXGW_WENKAI,
	NOTO_SANS_CJK_SC,
	SOURCE_HAN_SERIF_SC,
}

enum class SpaceSwitcherPosition {
	TOP_LEFT,
	TOP_RIGHT,
	CENTER_LEFT,
	CENTER_RIGHT,
}

@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {

	val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
	private val mangaListBadgesDefault = ArraySet(context.resources.getStringArray(R.array.values_list_badges))

	init {
		clearDeprecatedAllSourcesEnabledFlag()
	}

	var hasSeenPluginWelcome: Boolean
		get() = prefs.getBoolean("has_seen_plugin_welcome", false)
		set(value) = prefs.edit { putBoolean("has_seen_plugin_welcome", value) }

	var listMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE, ListMode.GRID)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE, value) }

	var browseListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_BROWSE, ListMode.GRID)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_BROWSE, value) }

	var homeListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_HOME, ListMode.DETAILED_LIST)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_HOME, value) }

	var theme: Int
		get() = prefs.getString(KEY_THEME, null)?.toIntOrNull()
			?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
		set(value) = prefs.edit { putString(KEY_THEME, value.toString()) }

	var colorScheme: ColorScheme
		get() = prefs.getEnumValue(
			KEY_COLOR_THEME,
			if (interfaceStyle == InterfaceStyle.IOS) ColorScheme.IOS else ColorScheme.default,
		)
		set(value) = prefs.edit { putEnumValue(KEY_COLOR_THEME, value) }

	var isAmoledTheme: Boolean
		get() = prefs.getBoolean(KEY_THEME_AMOLED, false)
		set(value) = prefs.edit { putBoolean(KEY_THEME_AMOLED, value) }

	var backgroundStyle: BackgroundStyle
		get() = prefs.getEnumValue(KEY_BACKGROUND_STYLE, BackgroundStyle.DEFAULT)
		set(value) = prefs.edit { putEnumValue(KEY_BACKGROUND_STYLE, value) }

	var tabletUiMode: TabletUiMode
		get() = prefs.getEnumValue(KEY_TABLET_UI_MODE, TabletUiMode.RELAXED)
		set(value) = prefs.edit { putEnumValue(KEY_TABLET_UI_MODE, value) }

	var mainNavItems: List<NavItem>
		get() {
			val rawStr = prefs.getString(KEY_NAV_MAIN, null)
			if (rawStr == "HOME,HISTORY,FAVORITES,EXPLORE,FEED") {
				val newDefaults = listOf(NavItem.HOME, NavItem.FAVORITES, NavItem.EXPLORE)
				prefs.edit { putString(KEY_NAV_MAIN, newDefaults.joinToString(",") { it.name }) }
				return newDefaults
			}
			val raw = rawStr?.split(',')
			val items = if (raw.isNullOrEmpty()) {
				listOf(NavItem.HOME, NavItem.FAVORITES, NavItem.EXPLORE)
			} else {
				raw.mapNotNull { x -> NavItem.entries.find(x) }
				.filterNot { it == NavItem.DISCOVER }
				.ifEmpty { listOf(NavItem.HOME, NavItem.FAVORITES, NavItem.EXPLORE) }
			}
			return items.limitMainNavigationItems().also { limitedItems ->
				if (limitedItems.size != items.size) {
					prefs.edit { putString(KEY_NAV_MAIN, limitedItems.joinToString(",") { it.name }) }
				}
			}
		}
		set(value) {
			prefs.edit {
				putString(KEY_NAV_MAIN, value.limitMainNavigationItems().joinToString(",") { it.name })
			}
		}

	var isNavLabelsVisible: Boolean
		get() = prefs.getBoolean(KEY_NAV_LABELS, true)
		set(value) = prefs.edit { putBoolean(KEY_NAV_LABELS, value) }

	var isEntityGraphMigrated: Boolean
		get() = prefs.getBoolean(KEY_ENTITY_GRAPH_MIGRATED, false)
		set(value) = prefs.edit { putBoolean(KEY_ENTITY_GRAPH_MIGRATED, value) }

	var isLegacyFavouriteProjectionMigrationCompleted: Boolean
		get() = prefs.getBoolean(KEY_LEGACY_FAVOURITE_PROJECTION_MIGRATION_COMPLETED, false)
		set(value) = prefs.edit { putBoolean(KEY_LEGACY_FAVOURITE_PROJECTION_MIGRATION_COMPLETED, value) }

	var isNavBarPinned: Boolean
		get() = prefs.getBoolean(KEY_NAV_PINNED, true)
		set(value) = prefs.edit { putBoolean(KEY_NAV_PINNED, value) }

	var isNavFloating: Boolean
		get() = prefs.getBoolean(KEY_NAV_FLOATING, true)
		set(value) = prefs.edit { putBoolean(KEY_NAV_FLOATING, value) }

	var isNavFloatingAdaptiveWidth: Boolean
		get() = prefs.getBoolean(KEY_NAV_FLOATING_ADAPTIVE_WIDTH, true)
		set(value) = prefs.edit { putBoolean(KEY_NAV_FLOATING_ADAPTIVE_WIDTH, value) }

	var isMainFabEnabled: Boolean
		get() = prefs.getBoolean(KEY_MAIN_FAB, true)
		set(value) = prefs.edit { putBoolean(KEY_MAIN_FAB, value) }

	var isNavExpressivePillEnabled: Boolean
		get() = prefs.getBoolean(
			KEY_NAV_EXPRESSIVE_PILL,
			interfaceStyle == InterfaceStyle.IOS || interfaceStyle == InterfaceStyle.MATERIAL_3_EXPRESSIVE,
		)
		set(value) = prefs.edit { putBoolean(KEY_NAV_EXPRESSIVE_PILL, value) }

	@Deprecated("Use interfaceStyle instead")
	var isMaterialExpressiveComponentsEnabled: Boolean
		get() = prefs.getBoolean(KEY_MATERIAL_EXPRESSIVE_COMPONENTS, false)
		set(value) = prefs.edit { putBoolean(KEY_MATERIAL_EXPRESSIVE_COMPONENTS, value) }

	var interfaceStyle: InterfaceStyle
		get() = prefs.getEnumValue(
			KEY_INTERFACE_STYLE,
			InterfaceStyle.IOS,
		).normalized()
		set(value) {
			val normalizedValue = value.normalized()
			prefs.edit {
				putEnumValue(KEY_INTERFACE_STYLE, normalizedValue)
				putBoolean(
					KEY_MATERIAL_EXPRESSIVE_COMPONENTS,
					normalizedValue == InterfaceStyle.MATERIAL_3_EXPRESSIVE,
				)
				if (normalizedValue == InterfaceStyle.IOS && !prefs.contains(KEY_COLOR_THEME)) {
					putEnumValue(KEY_COLOR_THEME, ColorScheme.IOS)
				} else if (normalizedValue == InterfaceStyle.MATERIAL_3_EXPRESSIVE &&
					prefs.getString(KEY_COLOR_THEME, null) == ColorScheme.IOS.name
				) {
					putEnumValue(KEY_COLOR_THEME, ColorScheme.default)
				}
				if (normalizedValue == InterfaceStyle.IOS && !prefs.contains(KEY_BACKGROUND_STYLE)) {
					putEnumValue(KEY_BACKGROUND_STYLE, BackgroundStyle.DYNAMIC_TONAL_GLASS)
				}
			}
		}

	var appFontPreset: AppFontPreset
		get() = prefs.getEnumValue(KEY_APP_FONT_PRESET, AppFontPreset.SYSTEM)
		set(value) = prefs.edit { putEnumValue(KEY_APP_FONT_PRESET, value) }

	var expressiveAppFontPreset: AppFontPreset
		get() = prefs.getEnumValue(KEY_EXPRESSIVE_APP_FONT_PRESET, AppFontPreset.SARASA_GOTHIC)
		set(value) = prefs.edit { putEnumValue(KEY_EXPRESSIVE_APP_FONT_PRESET, value) }

	var navHeight: Int
		get() = prefs.getSafeInt(KEY_NAV_HEIGHT, 80).coerceIn(48, 88)
		set(value) = prefs.edit { putInt(KEY_NAV_HEIGHT, value.coerceIn(48, 88)) }

	var navFloatingHeight: Int
		get() = prefs.getSafeInt(KEY_NAV_FLOATING_HEIGHT, 52).coerceIn(48, 84)
		set(value) = prefs.edit { putInt(KEY_NAV_FLOATING_HEIGHT, value.coerceIn(48, 84)) }

	var gridSize: Int
		get() = prefs.getSafeInt(KEY_GRID_SIZE, 100).coerceIn(50, 150)
		set(value) = prefs.edit { putInt(KEY_GRID_SIZE, value.coerceIn(50, 150)) }

	var gridSizePages: Int
		get() = prefs.getSafeInt(KEY_GRID_SIZE_PAGES, 100).coerceIn(50, 150)
		set(value) = prefs.edit { putInt(KEY_GRID_SIZE_PAGES, value.coerceIn(50, 150)) }

	var pageThumbnailAspectRatio: Float
		get() = prefs.getSafeFloat(KEY_PAGE_THUMBNAIL_ASPECT_RATIO, PAGE_THUMBNAIL_ASPECT_RATIO_DEFAULT)
			.coerceIn(PAGE_THUMBNAIL_ASPECT_RATIO_MIN, PAGE_THUMBNAIL_ASPECT_RATIO_MAX)
		set(value) = prefs.edit {
			putFloat(
				KEY_PAGE_THUMBNAIL_ASPECT_RATIO,
				value.coerceIn(PAGE_THUMBNAIL_ASPECT_RATIO_MIN, PAGE_THUMBNAIL_ASPECT_RATIO_MAX),
			)
		}

	var isPageThumbnailsFitPreview: Boolean
		get() = prefs.getBoolean(KEY_PAGE_THUMBNAILS_FIT_PREVIEW, false)
		set(value) = prefs.edit { putBoolean(KEY_PAGE_THUMBNAILS_FIT_PREVIEW, value) }

	var isQuickFilterEnabled: Boolean
		get() = prefs.getBoolean(KEY_QUICK_FILTER, true)
		set(value) = prefs.edit { putBoolean(KEY_QUICK_FILTER, value) }

	var isShowLanguagePresetFilter: Boolean
		get() = prefs.getBoolean(KEY_SHOW_LANGUAGE_PRESET_FILTER, true)
		set(value) = prefs.edit { putBoolean(KEY_SHOW_LANGUAGE_PRESET_FILTER, value) }

	var hiddenLanguagePreset: String?
		get() = prefs.getString(KEY_HIDDEN_LANGUAGE_PRESET, null)
		set(value) = prefs.edit { putString(KEY_HIDDEN_LANGUAGE_PRESET, value) }

	var isShowContentTypeFilter: Boolean
		get() = prefs.getBoolean(KEY_SHOW_CONTENT_TYPE_FILTER, true)
		set(value) = prefs.edit { putBoolean(KEY_SHOW_CONTENT_TYPE_FILTER, value) }

	var hiddenContentType: String?
		get() = prefs.getString(KEY_HIDDEN_CONTENT_TYPE, null)
		set(value) = prefs.edit { putString(KEY_HIDDEN_CONTENT_TYPE, value) }

	var isShowSourceTagFilter: Boolean
		get() = prefs.getBoolean(KEY_SHOW_SOURCE_TAG_FILTER, true)
		set(value) = prefs.edit { putBoolean(KEY_SHOW_SOURCE_TAG_FILTER, value) }

	var hiddenSourceTag: String?
		get() = prefs.getString(KEY_HIDDEN_SOURCE_TAG, null)
		set(value) = prefs.edit { putString(KEY_HIDDEN_SOURCE_TAG, value) }

	var activeSourcePresetId: Long
		get() = try {
			prefs.getLong(KEY_ACTIVE_SOURCE_PRESET_ID, -1L)
		} catch (_: ClassCastException) {
			// After backup restore, JSON may deserialize Long as Int
			val intValue = prefs.getInt(KEY_ACTIVE_SOURCE_PRESET_ID, -1)
			intValue.toLong().also { activeSourcePresetId = it }
		}
		set(value) = prefs.edit { putLong(KEY_ACTIVE_SOURCE_PRESET_ID, value) }

	var isDescriptionExpanded: Boolean
		get() = !prefs.getBoolean(KEY_COLLAPSE_DESCRIPTION, true)
		set(value) = prefs.edit { putBoolean(KEY_COLLAPSE_DESCRIPTION, !value) }

	var isPanoramaCoverEnabled: Boolean
		get() = prefs.getBoolean(KEY_PANORAMA_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_PANORAMA_ENABLED, value) }

	var panoramaCoverBlur: Int
		get() = prefs.getSafeInt(KEY_PANORAMA_BLUR, 35).coerceIn(0, 100)
		set(value) = prefs.edit { putInt(KEY_PANORAMA_BLUR, value.coerceIn(0, 100)) }

	var panoramaTransitionIntensity: Int
		get() = prefs.getSafeInt(KEY_PANORAMA_TRANSITION_INTENSITY, 100).coerceIn(0, 100)
		set(value) = prefs.edit { putInt(KEY_PANORAMA_TRANSITION_INTENSITY, value.coerceIn(0, 100)) }

	var isPanoramaCoverAnimationEnabled: Boolean
		get() = prefs.getBoolean(KEY_PANORAMA_ANIMATION_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_PANORAMA_ANIMATION_ENABLED, value) }

	var panoramaAnimationSpeed: Int
		get() = prefs.getSafeInt(KEY_PANORAMA_ANIMATION_SPEED, 100).coerceIn(
			PanoramaAnimationSpeedMinPercent,
			PanoramaAnimationSpeedMaxPercent,
		)
		set(value) = prefs.edit {
			putInt(
				KEY_PANORAMA_ANIMATION_SPEED,
				value.coerceIn(
					PanoramaAnimationSpeedMinPercent,
					PanoramaAnimationSpeedMaxPercent,
				),
			)
		}

	var panoramaCoverExtraHeight: Int
		get() = prefs.getSafeInt(KEY_PANORAMA_EXTRA_HEIGHT, 0).coerceIn(0, 100)
		set(value) = prefs.edit { putInt(KEY_PANORAMA_EXTRA_HEIGHT, value.coerceIn(0, 100)) }

	var panoramaBottomGradientAlpha: Int
		get() = prefs.getSafeInt(KEY_PANORAMA_BOTTOM_GRADIENT_ALPHA, 10).coerceIn(0, 100)
		set(value) = prefs.edit { putInt(KEY_PANORAMA_BOTTOM_GRADIENT_ALPHA, value.coerceIn(0, 100)) }

	var browsePanoramaBottomGradientAlpha: Int
		get() = prefs.getSafeInt(KEY_BROWSE_PANORAMA_BOTTOM_GRADIENT_ALPHA, 100)
			.coerceIn(0, 100)
		set(value) = prefs.edit { putInt(KEY_BROWSE_PANORAMA_BOTTOM_GRADIENT_ALPHA, value.coerceIn(0, 100)) }

	var browsePanoramaBlendHeight: Int
		get() = prefs.getSafeInt(KEY_BROWSE_PANORAMA_BLEND_HEIGHT, 220).coerceIn(48, 220)
		set(value) = prefs.edit { putInt(KEY_BROWSE_PANORAMA_BLEND_HEIGHT, value.coerceIn(48, 220)) }

	var isPanoramaDownsampleEnabled: Boolean
		get() = prefs.getBoolean(KEY_PANORAMA_DOWNSAMPLE, true)
		set(value) = prefs.edit { putBoolean(KEY_PANORAMA_DOWNSAMPLE, value) }

	var isDetailsPanoramaLimitedToInfoCardMidpoint: Boolean
		get() = prefs.getBoolean(KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT, false)
		set(value) = prefs.edit {
			putBoolean(KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT, value)
			if (!value) {
				putBoolean(KEY_DETAILS_PANORAMA_SCROLL_LINKED, false)
			}
		}

	var isDetailsPanoramaScrollLinkedEnabled: Boolean
		get() = prefs.getBoolean(KEY_DETAILS_PANORAMA_SCROLL_LINKED, true)
		set(value) = prefs.edit {
			putBoolean(
				KEY_DETAILS_PANORAMA_SCROLL_LINKED,
				value && isDetailsPanoramaLimitedToInfoCardMidpoint,
			)
		}


	var historyListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_HISTORY, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_HISTORY, value) }

	var suggestionsListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_SUGGESTIONS, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_SUGGESTIONS, value) }

	var favoritesListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_FAVORITES, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_FAVORITES, value) }

	var isTagsWarningsEnabled: Boolean
		get() = prefs.getBoolean(KEY_TAGS_WARNINGS, true)
		set(value) = prefs.edit { putBoolean(KEY_TAGS_WARNINGS, value) }

	var isNsfwContentDisabled: Boolean
		get() = prefs.getBoolean(KEY_DISABLE_NSFW, true)
		set(value) = prefs.edit { putBoolean(KEY_DISABLE_NSFW, value) }

	var globalTagBlacklist: Set<String>
		get() = prefs.getStringSet(KEY_GLOBAL_TAG_BLACKLIST, emptySet())
			.orEmpty()
			.mapToSet(String::trim)
			.filterTo(LinkedHashSet(), String::isNotEmpty)
		set(value) = prefs.edit {
			putStringSet(
				KEY_GLOBAL_TAG_BLACKLIST,
				value.map(String::trim).filter(String::isNotEmpty).toSet(),
			)
		}

	var isHistoryExcludeNsfw: Boolean
		get() = prefs.getBoolean(KEY_HISTORY_EXCLUDE_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_HISTORY_EXCLUDE_NSFW, value) }

	var isFavouritesExcludeNsfw: Boolean
		get() = prefs.getBoolean(KEY_FAVOURITES_EXCLUDE_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_FAVOURITES_EXCLUDE_NSFW, value) }

	var isFeedExcludeNsfw: Boolean
		get() = prefs.getBoolean(KEY_FEED_EXCLUDE_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_FEED_EXCLUDE_NSFW, value) }

	var appLocales: LocaleListCompat
		get() {
			val raw = prefs.getString(KEY_APP_LOCALE, null)
			return LocaleListCompat.forLanguageTags(raw)
		}
		set(value) {
			prefs.edit {
				putString(KEY_APP_LOCALE, value.toLanguageTags())
			}
		}

	var contentLanguages: Set<String>
		get() = prefs.getStringSet(KEY_CONTENT_LANGUAGES, null) ?: setOf("zh", "en", "ja", "")
		set(value) = prefs.edit { putStringSet(KEY_CONTENT_LANGUAGES, value) }

	enum class GitHubMirror(val value: String) {
		NATIVE("native"),
		KKGITHUB("kkgithub"),
		GHPROXY("ghproxy"),
		GHPROXY_NET("ghproxy_net");
		companion object {
			fun fromValue(value: String?): GitHubMirror =
				entries.find { it.value == value } ?: NATIVE
		}
	}

	var gitHubMirror: GitHubMirror
		get() = GitHubMirror.fromValue(prefs.getString(KEY_GITHUB_MIRROR, GitHubMirror.NATIVE.value))
		set(value) = prefs.edit { putString(KEY_GITHUB_MIRROR, value.value) }

	enum class HuggingFaceMirror(val value: String) {
		NATIVE("native"),
		HF_MIRROR("hf_mirror");
		companion object {
			fun fromValue(value: String?): HuggingFaceMirror =
				entries.find { it.value == value } ?: NATIVE
		}
	}

	var huggingFaceMirror: HuggingFaceMirror
		get() = HuggingFaceMirror.fromValue(prefs.getString(KEY_HUGGINGFACE_MIRROR, HuggingFaceMirror.NATIVE.value))
		set(value) = prefs.edit { putString(KEY_HUGGINGFACE_MIRROR, value.value) }

	enum class BangumiMirror(val value: String) {
		BANGUMI_LOL("bangumi_lol"),
		NATIVE("native"),
		CUSTOM("custom");

		companion object {
			fun fromValue(value: String?): BangumiMirror = when (value) {
				"bangumi_one", "bgmmi_anibt" -> BANGUMI_LOL
				else -> entries.find { it.value == value } ?: BANGUMI_LOL
			}
		}
	}

	var bangumiMirror: BangumiMirror
		get() = BangumiMirror.fromValue(prefs.getString(KEY_BANGUMI_MIRROR, BangumiMirror.BANGUMI_LOL.value))
		set(value) = prefs.edit { putString(KEY_BANGUMI_MIRROR, value.value) }

	var bangumiMirrorCustomBase: String?
		get() = prefs.getString(KEY_BANGUMI_MIRROR_CUSTOM_BASE, null)
		set(value) = prefs.edit { putString(KEY_BANGUMI_MIRROR_CUSTOM_BASE, value?.trim()?.takeIf { it.isNotBlank() }) }

	var extensionLanguages: Set<String>
		get() = prefs.getStringSet(KEY_EXTENSION_LANGUAGES, null) ?: emptySet()
		set(value) = prefs.edit { putStringSet(KEY_EXTENSION_LANGUAGES, value) }

	var isLocalApkHotReloadEnabled: Boolean
		get() = prefs.getBoolean(KEY_LOCAL_APK_HOT_RELOAD, false)
		set(value) = prefs.edit { putBoolean(KEY_LOCAL_APK_HOT_RELOAD, value) }

	var lnReaderRepoUrls: Set<String>
		get() = prefs.getStringSet(KEY_LNREADER_REPOS, null)
			?: setOf(org.skepsun.kototoro.core.lnreader.LNReaderRepository.OFFICIAL_REPO_URL)
		set(value) = prefs.edit { putStringSet(KEY_LNREADER_REPOS, value) }

	var isReaderAutoscrollPauseOnUi: Boolean
		get() = prefs.getBoolean(KEY_READER_AUTOSCROLL_PAUSE_ON_UI, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_AUTOSCROLL_PAUSE_ON_UI, value) }

	var isReaderDoubleOnLandscape: Boolean
		get() = prefs.getBoolean(KEY_READER_DOUBLE_PAGES, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_DOUBLE_PAGES, value) }

	var isReaderDoubleOnFoldable: Boolean
		get() = prefs.getBoolean(KEY_READER_DOUBLE_FOLDABLE, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_DOUBLE_FOLDABLE, value) }

	var isReaderDoubleCoverPage: Boolean
		get() = prefs.getBoolean(KEY_READER_DOUBLE_COVER_PAGE, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_DOUBLE_COVER_PAGE, value) }

	var isReaderSplitPagesEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_SPLIT_PAGES, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_SPLIT_PAGES, value) }

	@get:FloatRange(0.0, 1.0)
	var readerDoublePagesSensitivity: Float
		get() = prefs.getSafeFloat(KEY_READER_DOUBLE_PAGES_SENSITIVITY, 0.5f)
		set(@FloatRange(0.0, 1.0) value) = prefs.edit { putFloat(KEY_READER_DOUBLE_PAGES_SENSITIVITY, value) }

	val readerScreenOrientation: Int
		get() = prefs.getString(KEY_READER_ORIENTATION, null)?.toIntOrNull()
			?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

	val isReaderVolumeButtonsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_VOLUME_BUTTONS, false)

	val isReaderZoomButtonsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_ZOOM_BUTTONS, false)

	val isReaderControlLabelsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_CONTROL_LABELS, false)

	val isReaderControlAlwaysLTR: Boolean
		get() = prefs.getBoolean(KEY_READER_CONTROL_LTR, false)

	val isReaderNavigationInverted: Boolean
		get() = prefs.getBoolean(KEY_READER_NAVIGATION_INVERTED, false)

	val isReaderFullscreenEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_FULLSCREEN, true)

	val isReaderOptimizationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_OPTIMIZE, false)

	val isReaderPreloadReductionEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_REDUCE_PRELOAD, false)

	val readerControls: Set<ReaderControl>
		get() = prefs.getStringSet(KEY_READER_CONTROLS, null)
			?.mapNotNullTo(EnumSet.noneOf(ReaderControl::class.java)) { value ->
				ReaderControl.entries.find { it.name == value }
			}
			?.let(ReaderControl::limitFloatingControls)
			?: ReaderControl.FLOATING_DEFAULT

	var isOfflineCheckDisabled: Boolean
		get() = prefs.getBoolean(KEY_OFFLINE_DISABLED, false)
		set(value) = prefs.edit { putBoolean(KEY_OFFLINE_DISABLED, value) }

	var isAllFavouritesVisible: Boolean
		get() = prefs.getBoolean(KEY_ALL_FAVOURITES_VISIBLE, true)
		set(value) = prefs.edit { putBoolean(KEY_ALL_FAVOURITES_VISIBLE, value) }

	var isTrackerEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_TRACKER_ENABLED, value) }

	var isTrackerWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_WIFI_ONLY, false)
		set(value) = prefs.edit { putBoolean(KEY_TRACKER_WIFI_ONLY, value) }

	var trackerFrequencyFactor: Float
		get() = prefs.getString(KEY_TRACKER_FREQUENCY, null)?.toFloatOrNull() ?: 1f
		set(value) = prefs.edit { putString(KEY_TRACKER_FREQUENCY, value.toString()) }

	var isTrackerNotificationsEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_NOTIFICATIONS, true)
		set(value) = prefs.edit { putBoolean(KEY_TRACKER_NOTIFICATIONS, value) }

	var preferredTrackingSite: ScrobblerService
		get() = prefs.getEnumValue(KEY_PREFERRED_TRACKING_SITE, ScrobblerService.BANGUMI)
		set(value) = prefs.edit { putEnumValue(KEY_PREFERRED_TRACKING_SITE, value) }

	var trackingMetadataSourceStrategy: TrackingMetadataSourceStrategy
		get() = prefs.getEnumValue(KEY_TRACKING_METADATA_SOURCE_STRATEGY, TrackingMetadataSourceStrategy.LOCAL_THEN_API)
		set(value) = prefs.edit { putEnumValue(KEY_TRACKING_METADATA_SOURCE_STRATEGY, value) }

	var isTrackerNsfwDisabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_NO_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_TRACKER_NO_NSFW, value) }

	var trackerDownloadStrategy: TrackerDownloadStrategy
		get() = prefs.getEnumValue(KEY_TRACKER_DOWNLOAD, TrackerDownloadStrategy.DISABLED)
		set(value) = prefs.edit { putEnumValue(KEY_TRACKER_DOWNLOAD, value) }

	var notificationSound: Uri
		get() = prefs.getString(KEY_NOTIFICATIONS_SOUND, null)?.toUriOrNull()
			?: Settings.System.DEFAULT_NOTIFICATION_URI
		set(value) = prefs.edit { putString(KEY_NOTIFICATIONS_SOUND, value.toString()) }

	var notificationVibrate: Boolean
		get() = prefs.getBoolean(KEY_NOTIFICATIONS_VIBRATE, false)
		set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS_VIBRATE, value) }

	var notificationLight: Boolean
		get() = prefs.getBoolean(KEY_NOTIFICATIONS_LIGHT, true)
		set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS_LIGHT, value) }

	var readerAnimation: ReaderAnimation
		get() = prefs.getEnumValue(KEY_READER_ANIMATION, ReaderAnimation.DEFAULT)
		set(value) = prefs.edit { putEnumValue(KEY_READER_ANIMATION, value) }

	var readerBackground: ReaderBackground
		get() = prefs.getEnumValue(KEY_READER_BACKGROUND, ReaderBackground.AUTO)
		set(value) = prefs.edit { putEnumValue(KEY_READER_BACKGROUND, value) }

	var videoDecoderMode: VideoDecoderMode
		get() = prefs.getEnumValue(KEY_VIDEO_DECODER_MODE, VideoDecoderMode.HARDWARE)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_DECODER_MODE, value) }

	var videoRendererMode: VideoRendererMode
		get() = prefs.getEnumValue(KEY_VIDEO_RENDERER_MODE, VideoRendererMode.AUTO)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_RENDERER_MODE, value) }

	var videoBackground: ReaderBackground
		get() = prefs.getEnumValue(KEY_VIDEO_BACKGROUND, ReaderBackground.DEFAULT)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_BACKGROUND, value) }

	var videoSuperResolutionMode: VideoSuperResolutionMode
		get() = prefs.getEnumValue(KEY_VIDEO_SUPER_RES_MODE, VideoSuperResolutionMode.BALANCED)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_SUPER_RES_MODE, value) }

	var videoSuperResolutionShader: VideoSuperResolutionShader
		get() = prefs.getEnumValue(KEY_VIDEO_SUPER_RES_SHADER, VideoSuperResolutionShader.MODE_A)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_SUPER_RES_SHADER, value) }

	var videoSuperResolutionQualityShader: VideoSuperResolutionShader
		get() = prefs.getEnumValue(KEY_VIDEO_SUPER_RES_QUALITY_SHADER, VideoSuperResolutionShader.MODE_A)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_SUPER_RES_QUALITY_SHADER, value) }

	var videoSuperResolutionBalancedShader: VideoSuperResolutionShader
		get() = prefs.getEnumValue(KEY_VIDEO_SUPER_RES_BALANCED_SHADER, VideoSuperResolutionShader.MODE_B)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_SUPER_RES_BALANCED_SHADER, value) }

	var videoSuperResolutionPerformanceShader: VideoSuperResolutionShader
		get() = prefs.getEnumValue(KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER, VideoSuperResolutionShader.MODE_C)
		set(value) = prefs.edit { putEnumValue(KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER, value) }

	var videoSuperResolutionCustomShaders: String
		get() = prefs.getString(KEY_VIDEO_SUPER_RES_CUSTOM_SHADERS, "") ?: ""
		set(value) = prefs.edit { putString(KEY_VIDEO_SUPER_RES_CUSTOM_SHADERS, value) }

	var videoDanmakuEnabled: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_ENABLED, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_ENABLED, value) }

	var videoDanmakuSizePercent: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_SIZE, 100)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_SIZE, value) }

	var videoDanmakuSpeedPercent: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_SPEED, 100)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_SPEED, value) }

	var videoDanmakuOpacityPercent: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_OPACITY, 100)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_OPACITY, value) }

	var videoDanmakuStrokePercent: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_STROKE, 50)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_STROKE, value) }

	var videoDanmakuShowScroll: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_SHOW_SCROLL, true)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_SHOW_SCROLL, value) }

	var videoDanmakuShowTop: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_SHOW_TOP, true)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_SHOW_TOP, value) }

	var videoDanmakuShowBottom: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_SHOW_BOTTOM, true)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_SHOW_BOTTOM, value) }

	var videoDanmakuMaxScrollLines: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_MAX_SCROLL_LINES, 0)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_MAX_SCROLL_LINES, value) }

	var videoDanmakuMaxTopLines: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_MAX_TOP_LINES, 0)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_MAX_TOP_LINES, value) }

	var videoDanmakuMaxBottomLines: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_MAX_BOTTOM_LINES, 0)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_MAX_BOTTOM_LINES, value) }

	var videoDanmakuMaxScreenNum: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_MAX_SCREEN_NUM, 0)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_MAX_SCREEN_NUM, value) }

	var videoDanmakuSourceDanDan: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_SOURCE_DANDAN, true)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_SOURCE_DANDAN, value) }

	var videoDanmakuSourceBilibili: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_SOURCE_BILIBILI, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_SOURCE_BILIBILI, value) }

	var videoDanmakuSourceQq: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DANMAKU_SOURCE_QQ, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DANMAKU_SOURCE_QQ, value) }

	var videoPlaybackSpeed: Float
		get() = prefs.getSafeInt(KEY_VIDEO_PLAYBACK_SPEED, 100) / 100f
		set(value) = prefs.edit { putInt(KEY_VIDEO_PLAYBACK_SPEED, (value * 100).toInt()) }

	var videoDefaultSpeed: Float
		get() = prefs.getSafeInt(KEY_VIDEO_DEFAULT_SPEED, 100) / 100f
		set(value) = prefs.edit { putInt(KEY_VIDEO_DEFAULT_SPEED, (value * 100).toInt()) }

	var videoSeekForwardMs: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SEEK_FORWARD_MS, 10_000)
		set(value) = prefs.edit { putInt(KEY_VIDEO_SEEK_FORWARD_MS, value) }

	var videoSeekBackwardMs: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SEEK_BACKWARD_MS, 10_000)
		set(value) = prefs.edit { putInt(KEY_VIDEO_SEEK_BACKWARD_MS, value) }

	var videoVolumeBoostEnabled: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_VOLUME_BOOST, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_VOLUME_BOOST, value) }

	var videoAutoNextEnabled: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_AUTO_NEXT, true)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_AUTO_NEXT, value) }

	var videoLandscapeSensorEnabled: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_LANDSCAPE_SENSOR, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_LANDSCAPE_SENSOR, value) }

	var videoCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_VIDEO_CACHE_MB, 1024)
		set(value) = prefs.edit { putInt(KEY_VIDEO_CACHE_MB, value) }

	var thumbsCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_THUMBS_CACHE_MB, 256)
		set(value) = prefs.edit { putInt(KEY_THUMBS_CACHE_MB, value.coerceIn(32, 2048)) }

	var faviconCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_FAVICON_CACHE_MB, 8)
		set(value) = prefs.edit { putInt(KEY_FAVICON_CACHE_MB, value.coerceIn(4, 128)) }

	var pagesCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_PAGES_CACHE_MB, 200)
		set(value) = prefs.edit { putInt(KEY_PAGES_CACHE_MB, value.coerceIn(64, 4096)) }

	var novelCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_NOVEL_CACHE_MB, 100)
		set(value) = prefs.edit { putInt(KEY_NOVEL_CACHE_MB, value.coerceIn(32, 2048)) }

	var httpCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_HTTP_CACHE_MB_LIMIT, 250)
		set(value) = prefs.edit { putInt(KEY_HTTP_CACHE_MB_LIMIT, value.coerceIn(32, 2048)) }

	var videoProxyCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_VIDEO_PROXY_CACHE_MB, 1024)
		set(value) = prefs.edit { putInt(KEY_VIDEO_PROXY_CACHE_MB, value.coerceIn(128, 4096)) }

	var videoDanmakuCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_VIDEO_DANMAKU_CACHE_MB, 64)
		set(value) = prefs.edit { putInt(KEY_VIDEO_DANMAKU_CACHE_MB, value.coerceIn(16, 1024)) }

	var ttsCacheSizeMb: Int
		get() = prefs.getSafeInt(KEY_TTS_CACHE_MB, 100)
		set(value) = prefs.edit { putInt(KEY_TTS_CACHE_MB, value.coerceIn(32, 2048)) }

	var videoAspectRatio: Int
		get() = prefs.getSafeInt(KEY_VIDEO_ASPECT_RATIO, 0)
		set(value) = prefs.edit { putInt(KEY_VIDEO_ASPECT_RATIO, value) }

	var videoDoubleTapSeekEnabled: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_DOUBLE_TAP_SEEK_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_DOUBLE_TAP_SEEK_ENABLED, value) }

	var videoSubtitleFontSize: Float
		get() = prefs.getSafeFloat(KEY_VIDEO_SUBTITLE_FONT_SIZE, 18f)
		set(value) = prefs.edit { putFloat(KEY_VIDEO_SUBTITLE_FONT_SIZE, value) }

	var videoSubtitleBold: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_SUBTITLE_BOLD, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_SUBTITLE_BOLD, value) }

	var videoSubtitleItalic: Boolean
		get() = prefs.getBoolean(KEY_VIDEO_SUBTITLE_ITALIC, false)
		set(value) = prefs.edit { putBoolean(KEY_VIDEO_SUBTITLE_ITALIC, value) }

	var videoSubtitleTextColor: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SUBTITLE_TEXT_COLOR, android.graphics.Color.WHITE)
		set(value) = prefs.edit { putInt(KEY_VIDEO_SUBTITLE_TEXT_COLOR, value) }

	var videoSubtitleBorderColor: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SUBTITLE_BORDER_COLOR, android.graphics.Color.BLACK)
		set(value) = prefs.edit { putInt(KEY_VIDEO_SUBTITLE_BORDER_COLOR, value) }

	var videoSubtitleBorderSize: Float
		get() = prefs.getSafeFloat(KEY_VIDEO_SUBTITLE_BORDER_SIZE, 8f)
		set(value) = prefs.edit { putFloat(KEY_VIDEO_SUBTITLE_BORDER_SIZE, value) }

	var videoSubtitleBgColor: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SUBTITLE_BG_COLOR, 0x66000000)
		set(value) = prefs.edit { putInt(KEY_VIDEO_SUBTITLE_BG_COLOR, value) }

	var videoSubtitleAlignX: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SUBTITLE_ALIGN_X, 1) // 0=left, 1=center, 2=right
		set(value) = prefs.edit { putInt(KEY_VIDEO_SUBTITLE_ALIGN_X, value) }

	var videoSubtitlePosition: Int
		get() = prefs.getSafeInt(KEY_VIDEO_SUBTITLE_POSITION, 80)
		set(value) = prefs.edit { putInt(KEY_VIDEO_SUBTITLE_POSITION, value) }

	@get:FloatRange(0.3, 1.0)
	var videoControlsAlpha: Float
		get() = prefs.getSafeInt(KEY_VIDEO_CONTROLS_ALPHA, 90) / 100f
		set(@FloatRange(0.3, 1.0) value) = prefs.edit { putInt(KEY_VIDEO_CONTROLS_ALPHA, (value * 100).toInt()) }

	var preferredVideoQuality: String
		get() = prefs.getString(KEY_VIDEO_PREFERRED_QUALITY, "1080p, 720p, 480p") ?: "1080p, 720p, 480p"
		set(value) = prefs.edit { putString(KEY_VIDEO_PREFERRED_QUALITY, value) }

	@get:FloatRange(0.0, 1.0)
	var videoGradientAlpha: Float
		get() = prefs.getSafeInt(KEY_VIDEO_GRADIENT_ALPHA, 70) / 100f
		set(@FloatRange(0.0, 1.0) value) = prefs.edit { putInt(KEY_VIDEO_GRADIENT_ALPHA, (value * 100).toInt()) }

	val defaultReaderMode: ReaderMode
		get() = prefs.getEnumValue(KEY_READER_MODE, ReaderMode.STANDARD)

	val isReaderModeDetectionEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_MODE_DETECT, true)

	var isHistoryGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_HISTORY_GROUPING, true)
		set(value) = prefs.edit { putBoolean(KEY_HISTORY_GROUPING, value) }

	var isUpdatedGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_UPDATED_GROUPING, true)
		set(value) = prefs.edit { putBoolean(KEY_UPDATED_GROUPING, value) }

	var isFeedHeaderVisible: Boolean
		get() = prefs.getBoolean(KEY_FEED_HEADER, true)
		set(value) = prefs.edit { putBoolean(KEY_FEED_HEADER, value) }

	var showAllUpdates: Boolean
		get() = prefs.getBoolean(KEY_SHOW_ALL_UPDATES, false)
		set(value) = prefs.edit { putBoolean(KEY_SHOW_ALL_UPDATES, value) }

	var feedLimit: Int
		get() = prefs.getInt(KEY_FEED_LIMIT, 200)
		set(value) = prefs.edit { putInt(KEY_FEED_LIMIT, value) }

	var feedLastOpenTime: Long
		get() = prefs.getLong(KEY_FEED_LAST_OPEN_TIME, 0L)
		set(value) = prefs.edit { putLong(KEY_FEED_LAST_OPEN_TIME, value) }

	var progressIndicatorMode: ProgressIndicatorMode
		get() = prefs.getEnumValue(KEY_PROGRESS_INDICATORS, ProgressIndicatorMode.PERCENT_READ)
		set(value) = prefs.edit { putEnumValue(KEY_PROGRESS_INDICATORS, value) }

	enum class LoadingCircleStyle(val value: String) {
		THICK_STRAIGHT("thick_straight"),
		THICK_WAVY("thick_wavy"),
		THIN_STRAIGHT("thin_straight"),
		THIN_WAVY("thin_wavy");

		companion object {
			fun fromValue(value: String?): LoadingCircleStyle =
				entries.find { it.value == value } ?: THICK_STRAIGHT
		}
	}

	var loadingCircleStyle: LoadingCircleStyle
		get() = LoadingCircleStyle.fromValue(prefs.getString(KEY_LOADING_CIRCLE_STYLE, LoadingCircleStyle.THICK_STRAIGHT.value))
		set(value) = prefs.edit { putString(KEY_LOADING_CIRCLE_STYLE, value.value) }

	var railAnimationIntensityPercent: Int
		get() = prefs.getSafeInt(KEY_RAIL_ANIMATION_INTENSITY, 100).coerceIn(0, 300)
		set(value) = prefs.edit { putInt(KEY_RAIL_ANIMATION_INTENSITY, value.coerceIn(0, 300)) }

	var isVerticalListRailAnimationEnabled: Boolean
		get() = prefs.getBoolean(KEY_VERTICAL_LIST_RAIL_ANIMATION, false)
		set(value) = prefs.edit { putBoolean(KEY_VERTICAL_LIST_RAIL_ANIMATION, value) }

	var cornerRadius: Int
		get() = prefs.getString(KEY_POPUP_RADIUS, "-1")?.toIntOrNull()
			?.takeIf { it in CORNER_RADIUS_ALLOWED_VALUES } ?: -1
		set(value) = prefs.edit { putString(KEY_POPUP_RADIUS, value.takeIf { it in CORNER_RADIUS_ALLOWED_VALUES }?.toString() ?: "-1") }

	var badgesTopLeft: Set<String>
		get() = prefs.getStringSet(KEY_BADGES_TOP_LEFT, setOf("tracker")) ?: setOf("tracker")
		set(value) = prefs.edit { putStringSet(KEY_BADGES_TOP_LEFT, value) }

	var badgesTopRight: Set<String>
		get() = prefs.getStringSet(KEY_BADGES_TOP_RIGHT, setOf("score", "pin")) ?: setOf("score", "pin")
		set(value) = prefs.edit { putStringSet(KEY_BADGES_TOP_RIGHT, value) }

	var badgesBottomLeft: Set<String>
		get() = prefs.getStringSet(KEY_BADGES_BOTTOM_LEFT, setOf("favorite", "saved")) ?: setOf("favorite", "saved")
		set(value) = prefs.edit { putStringSet(KEY_BADGES_BOTTOM_LEFT, value) }

	var badgesBottomRight: Set<String>
		get() = prefs.getStringSet(KEY_BADGES_BOTTOM_RIGHT, setOf("nsfw")) ?: setOf("nsfw")
		set(value) = prefs.edit { putStringSet(KEY_BADGES_BOTTOM_RIGHT, value) }

	var popupRadius: Int
		get() = cornerRadius
		set(value) { cornerRadius = value }


	var glassImmersiveStrengthPercent: Int
		get() = prefs.getSafeInt(
			KEY_GLASS_IMMERSIVE_STRENGTH,
			100,
		).coerceIn(0, 100)
		set(value) = prefs.edit { putInt(KEY_GLASS_IMMERSIVE_STRENGTH, value.coerceIn(0, 100)) }

	var isGlassEffectEnabled: Boolean
		get() = prefs.getBoolean(KEY_GLASS_EFFECT_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_GLASS_EFFECT_ENABLED, value) }

	var incognitoModeForNsfw: TriStateOption
		get() = prefs.getEnumValue(KEY_INCOGNITO_NSFW, TriStateOption.ASK)
		set(value) = prefs.edit { putEnumValue(KEY_INCOGNITO_NSFW, value) }

	var isIncognitoModeEnabled: Boolean
		get() = prefs.getBoolean(KEY_INCOGNITO_MODE, false)
		set(value) = prefs.edit { putBoolean(KEY_INCOGNITO_MODE, value) }

	val isReaderMultiTaskEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_MULTITASK, false)

	var isChaptersReverse: Boolean
		get() = prefs.getBoolean(KEY_REVERSE_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_REVERSE_CHAPTERS, value) }

	var isChaptersGridView: Boolean
		get() = prefs.getBoolean(KEY_GRID_VIEW_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_GRID_VIEW_CHAPTERS, value) }

	var isHideReadChapters: Boolean
		get() = prefs.getBoolean(KEY_HIDE_READ_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_HIDE_READ_CHAPTERS, value) }

	var isMergeRepeatedChapters: Boolean
		get() = prefs.getBoolean(KEY_MERGE_REPEATED_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_MERGE_REPEATED_CHAPTERS, value) }

	val zoomMode: ZoomMode
		get() = prefs.getEnumValue(KEY_ZOOM_MODE, ZoomMode.FIT_CENTER)

	var trackSources: Set<String>
		get() = prefs.getStringSet(KEY_TRACK_SOURCES, null) ?: setOf(TRACK_FAVOURITES)
		set(value) = prefs.edit { putStringSet(KEY_TRACK_SOURCES, value) }

	var appPassword: String?
		get() = prefs.getString(KEY_APP_PASSWORD, null)
		set(value) = prefs.edit {
			if (value != null) putString(KEY_APP_PASSWORD, value) else remove(KEY_APP_PASSWORD)
		}

	var isAppPasswordNumeric: Boolean
		get() = prefs.getBoolean(KEY_APP_PASSWORD_NUMERIC, false)
		set(value) = prefs.edit { putBoolean(KEY_APP_PASSWORD_NUMERIC, value) }

	var searchSuggestionTypes: Set<SearchSuggestionType>
		get() = prefs.getStringSet(KEY_SEARCH_SUGGESTION_TYPES, null)?.let { stringSet ->
			stringSet.mapNotNullTo(EnumSet.noneOf(SearchSuggestionType::class.java)) { x ->
				SearchSuggestionType.entries.firstOrNull { it.name == x }
			}.ifEmpty {
				if (stringSet.isEmpty()) emptySet() else EnumSet.allOf(SearchSuggestionType::class.java)
			}
		} ?: EnumSet.allOf(SearchSuggestionType::class.java)
		set(value) = prefs.edit { putStringSet(KEY_SEARCH_SUGGESTION_TYPES, value.mapToSet { it.name }) }

	var isBiometricProtectionEnabled: Boolean
		get() = prefs.getBoolean(KEY_PROTECT_APP_BIOMETRIC, true)
		set(value) = prefs.edit { putBoolean(KEY_PROTECT_APP_BIOMETRIC, value) }

	var isMirrorSwitchingEnabled: Boolean
		get() = prefs.getBoolean(KEY_MIRROR_SWITCHING, false)
		set(value) = prefs.edit { putBoolean(KEY_MIRROR_SWITCHING, value) }

	var isExitConfirmationEnabled: Boolean
		get() = prefs.getBoolean(KEY_EXIT_CONFIRM, false)
		set(value) = prefs.edit { putBoolean(KEY_EXIT_CONFIRM, value) }

	var isDynamicShortcutsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SHORTCUTS, true)
		set(value) = prefs.edit { putBoolean(KEY_SHORTCUTS, value) }

	val isUnstableUpdatesAllowed: Boolean
		get() = prefs.getBoolean(KEY_UPDATES_UNSTABLE, false)

	var isPagesTabEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_TAB, true)
		set(value) = prefs.edit { putBoolean(KEY_PAGES_TAB, value) }

	var isDetailsTranslateButtonVisible: Boolean
		get() = prefs.getBoolean(KEY_DETAILS_TRANSLATE_BUTTON, true)
		set(value) = prefs.edit { putBoolean(KEY_DETAILS_TRANSLATE_BUTTON, value) }

	var isModernDetailsDockEnabled: Boolean
		get() = prefs.getBoolean(KEY_MODERN_DETAILS_DOCK, true)
		set(value) = prefs.edit { putBoolean(KEY_MODERN_DETAILS_DOCK, value) }

	var defaultDetailsTab: Int
		get() = if (isPagesTabEnabled) {
			val raw = prefs.getString(KEY_DETAILS_TAB, null)?.toIntOrNull() ?: -1
			if (raw == -1) {
				lastDetailsTab
			} else {
				raw
			}.coerceIn(0, 2)
		} else {
			0
		}
		set(value) = prefs.edit { putString(KEY_DETAILS_TAB, value.toString()) }

	var lastDetailsTab: Int
		get() = prefs.getSafeInt(KEY_DETAILS_LAST_TAB, 0)
		set(value) = prefs.edit { putInt(KEY_DETAILS_LAST_TAB, value) }

	val isContentPrefetchEnabled: Boolean
		get() {
			if (isBackgroundNetworkRestricted()) {
				return false
			}
			val policy =
				NetworkPolicy.from(prefs.getString(KEY_PREFETCH_CONTENT, null), NetworkPolicy.NEVER)
			return policy.isNetworkAllowed(connectivityManager)
		}

	var contentPrefetchPolicy: NetworkPolicy
		get() = NetworkPolicy.from(prefs.getString(KEY_PREFETCH_CONTENT, null), NetworkPolicy.NEVER)
		set(value) = prefs.edit {
			putString(KEY_PREFETCH_CONTENT, when (value) {
				NetworkPolicy.ALWAYS -> "1"
				NetworkPolicy.NON_METERED -> "2"
				NetworkPolicy.NEVER -> "0"
			})
		}

	var sourcesSortOrder: SourcesSortOrder
		get() = prefs.getEnumValue(KEY_SOURCES_ORDER, SourcesSortOrder.MANUAL)
		set(value) = prefs.edit { putEnumValue(KEY_SOURCES_ORDER, value) }

	var isSourcesGroupedByLanguage: Boolean
		get() = prefs.getBoolean(KEY_SOURCES_GROUPED_BY_LANGUAGE, false)
		set(value) = prefs.edit { putBoolean(KEY_SOURCES_GROUPED_BY_LANGUAGE, value) }

	var isSourcesGridMode: Boolean
		get() = prefs.getBoolean(KEY_SOURCES_GRID, true)
		set(value) = prefs.edit { putBoolean(KEY_SOURCES_GRID, value) }

	var isEmptySourcesHiddenInExplore: Boolean
		get() = prefs.getBoolean(KEY_EXPLORE_HIDE_EMPTY_SOURCES, false)
		set(value) = prefs.edit { putBoolean(KEY_EXPLORE_HIDE_EMPTY_SOURCES, value) }

	var isShowSourceOnCards: Boolean
		get() = prefs.getBoolean(KEY_SHOW_SOURCE_ON_CARDS, false)
		set(value) {
			prefs.edit { putBoolean(KEY_SHOW_SOURCE_ON_CARDS, value) }
		}

	var showExtraInfoOnCards: Boolean
		get() = prefs.getBoolean(KEY_SHOW_EXTRA_INFO_ON_CARDS, false)
		set(value) {
			prefs.edit { putBoolean(KEY_SHOW_EXTRA_INFO_ON_CARDS, value) }
		}

	var isSharedElementTransitionsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SHARED_ELEMENT_TRANSITIONS, false)
		set(value) {
			prefs.edit { putBoolean(KEY_SHARED_ELEMENT_TRANSITIONS, value) }
		}

	var isReducedVisualEffectsEnabled: Boolean
		get() = prefs.getBoolean(KEY_REDUCED_VISUAL_EFFECTS, false)
		set(value) = prefs.edit { putBoolean(KEY_REDUCED_VISUAL_EFFECTS, value) }

	var sourcesVersion: Int
		get() = prefs.getSafeInt(KEY_SOURCES_VERSION, 0)
		set(value) = prefs.edit { putInt(KEY_SOURCES_VERSION, value) }

	var isAllSourcesEnabled: Boolean
		get() = false
		set(@Suppress("UNUSED_PARAMETER") value) {
			clearDeprecatedAllSourcesEnabledFlag()
		}

	var jarPriorityOrder: String
		get() = prefs.getString(KEY_JAR_PRIORITY_ORDER, DEFAULT_JAR_PRIORITY_ORDER).orEmpty()
		set(value) = prefs.edit { putString(KEY_JAR_PRIORITY_ORDER, value) }

	var isExtensionsGridMode: Boolean
		get() = prefs.getBoolean(KEY_EXTENSIONS_GRID, false)
		set(value) = prefs.edit { putBoolean(KEY_EXTENSIONS_GRID, value) }

	var isShowBrokenSources: Boolean
		get() = prefs.getBoolean(KEY_SHOW_BROKEN_SOURCES, false)
		set(value) = prefs.edit { putBoolean(KEY_SHOW_BROKEN_SOURCES, value) }

	val isPagesNumbersEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_NUMBERS, false)

	var isReaderTranslationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_TRANSLATION_ENABLED, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_TRANSLATION_ENABLED, value) }

	var isReaderTranslationShowTranslated: Boolean
		get() = prefs.getBoolean(KEY_READER_TRANSLATION_SHOW_TRANSLATED, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_TRANSLATION_SHOW_TRANSLATED, value) }

	val isReaderTranslationDebugLogsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_TRANSLATION_DEBUG_LOGS, false)

	var isReaderTranslationQualityFilterEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_TRANSLATION_QUALITY_FILTER_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_TRANSLATION_QUALITY_FILTER_ENABLED, value) }

	var readerTranslationSourceLanguage: String
		get() = prefs.getString(KEY_READER_TRANSLATION_SOURCE_LANG, "auto") ?: "auto"
		set(value) = prefs.edit { putString(KEY_READER_TRANSLATION_SOURCE_LANG, value) }

	var readerTranslationTargetLanguage: String
		get() = prefs.getString(KEY_READER_TRANSLATION_TARGET_LANG, "zh") ?: "zh"
		set(value) = prefs.edit { putString(KEY_READER_TRANSLATION_TARGET_LANG, value) }

	var readerTranslationOcrMode: ReaderOcrMode
		get() = prefs.getEnumValue(
			KEY_READER_TRANSLATION_OCR_MODE,
			if (prefs.getString(KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID, null).isNullOrBlank() ||
				prefs.getString(KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID, null) == "MLKIT"
			) {
				ReaderOcrMode.BASIC
			} else {
				ReaderOcrMode.ADVANCED
			},
		)
		set(value) = prefs.edit { putEnumValue(KEY_READER_TRANSLATION_OCR_MODE, value) }

	val readerTranslationOcrEngine: ReaderOcrEngine
		get() = when (readerTranslationOcrMode) {
			ReaderOcrMode.BASIC -> ReaderOcrEngine.MLKIT
			ReaderOcrMode.ADVANCED -> ReaderOcrEngine.PADDLE
		}

	val readerTranslationMode: ReaderTranslationMode
		get() = when (prefs.getEnumValue(KEY_READER_TRANSLATION_MODE, ReaderTranslationMode.LOCAL_ONLY)) {
			ReaderTranslationMode.LOCAL_FIRST -> ReaderTranslationMode.LOCAL_ONLY
			else -> prefs.getEnumValue(KEY_READER_TRANSLATION_MODE, ReaderTranslationMode.LOCAL_ONLY)
		}

	val readerTranslationPipelineMode: org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode
		get() = org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode.TWO_STAGE

	val readerTranslationApiEndpoint: String
		get() = prefs.getString(KEY_READER_TRANSLATION_API_ENDPOINT, "") ?: ""

	val readerTranslationApiKey: String
		get() = prefs.getString(KEY_READER_TRANSLATION_API_KEY, "") ?: ""

	val readerTranslationApiModel: String
		get() = prefs.getString(KEY_READER_TRANSLATION_API_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"

	val readerTranslationApiProviderPreset: String
		get() = prefs.getString(KEY_READER_TRANSLATION_API_PROVIDER_PRESET, "CUSTOM")
			?.trim()
			?.uppercase()
			?.takeIf { it.isNotBlank() }
			?: "CUSTOM"

	val readerTranslationApiCustomHeaders: String
		get() = prefs.getString(KEY_READER_TRANSLATION_API_CUSTOM_HEADERS, "") ?: ""

	val readerE2eApiEndpoint: String
		get() = prefs.getString(KEY_READER_E2E_API_ENDPOINT, "") ?: ""

	val readerE2eApiKey: String
		get() = prefs.getString(KEY_READER_E2E_API_KEY, "") ?: ""

	val readerE2eApiModel: String
		get() = prefs.getString(KEY_READER_E2E_API_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"

	val readerE2eApiProviderPreset: String
		get() = prefs.getString(KEY_READER_E2E_API_PROVIDER_PRESET, "GEMINI") ?: "GEMINI"

	val readerE2eApiCustomHeaders: String
		get() = prefs.getString(KEY_READER_E2E_API_CUSTOM_HEADERS, "") ?: ""

	val readerE2eApiConcurrency: Int
		get() = prefs.getString(KEY_READER_E2E_API_CONCURRENCY, "3")?.toIntOrNull() ?: 3

	val readerTranslationBubbleGroupingTuning: String
		get() = prefs.getString(KEY_READER_TRANSLATION_BUBBLE_GROUPING_TUNING, "BALANCED") ?: "BALANCED"

	val readerTranslationOcrPipelineStrategy: String
		get() = prefs.getString(KEY_READER_TRANSLATION_OCR_PIPELINE_STRATEGY, "PAGE_TEXT_FIRST") ?: "PAGE_TEXT_FIRST"

	var isReaderTranslationBubbleDetectorEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_TRANSLATION_BUBBLE_DETECTOR_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_TRANSLATION_BUBBLE_DETECTOR_ENABLED, value) }

	var isReaderTranslationBubbleGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_TRANSLATION_BUBBLE_GROUPING_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_TRANSLATION_BUBBLE_GROUPING_ENABLED, value) }

	val readerTranslationOverlayCompactness: String
		get() = prefs.getString(KEY_READER_TRANSLATION_OVERLAY_COMPACTNESS, "BALANCED") ?: "BALANCED"

	val readerTranslationRenderStyle: String
		get() = prefs.getString(KEY_READER_TRANSLATION_RENDER_STYLE, "COMPACT_OVERLAY") ?: "COMPACT_OVERLAY"

	val readerTranslationPaddleModelPath: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_MODEL_PATH, "") ?: ""

	val readerTranslationAdvancedRecModelId: String
		get() = when (val modelId = prefs.getString(KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID, "AUTO")) {
			"en_ppocrv5_mobile_rec_onnx" -> "latin_ppocrv5_mobile_rec_onnx"
			"korean_ppocrv3_mobile_rec_onnx" -> "korean_ppocrv5_mobile_rec_onnx"
			"AUTO",
			"mangaocr_2025_onnx",
			"manga_48px_ctc_onnx",
			"ppocrv6_medium_rec_onnx",
			"latin_ppocrv5_mobile_rec_onnx",
			"korean_ppocrv5_mobile_rec_onnx",
			"thai_ppocrv5_mobile_rec_onnx",
			-> modelId
			else -> "AUTO"
		}

	val readerTranslationPaddleOfficialModelId: String
		get() = when (readerTranslationOcrMode) {
			ReaderOcrMode.BASIC -> "MLKIT"
			ReaderOcrMode.ADVANCED -> readerTranslationAdvancedRecModelId
		}

	val readerTranslationAdvancedDetModelId: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID, DEFAULT_READER_TRANSLATION_PADDLE_DET_MODEL_ID)
			?.takeIf {
				it == "comic_text_detector_onnx" ||
					it == "manga_default_det_20241225_onnx"
			}
			?: DEFAULT_READER_TRANSLATION_PADDLE_DET_MODEL_ID

	val readerTranslationPaddleDetModelId: String
		get() = when (readerTranslationOcrMode) {
			ReaderOcrMode.BASIC -> "MLKIT"
			ReaderOcrMode.ADVANCED -> readerTranslationAdvancedDetModelId
		}

	val readerTranslationOcrDetectionMaxSide: Int
		get() = DEFAULT_READER_TRANSLATION_OCR_DETECTION_MAX_SIDE

	val readerTranslationOcrDetectionThreshold: Float
		get() = DEFAULT_READER_TRANSLATION_OCR_DETECTION_THRESHOLD

	val readerTranslationOcrMinBoxSize: Int
		get() = DEFAULT_READER_TRANSLATION_OCR_MIN_BOX_SIZE

	val readerTranslationOcrRecognitionThreshold: Float
		get() = DEFAULT_READER_TRANSLATION_OCR_RECOGNITION_THRESHOLD

	val readerTranslationOcrRecognitionMaxWidth: Int
		get() = DEFAULT_READER_TRANSLATION_OCR_RECOGNITION_MAX_WIDTH

	val readerTranslationOcrRecognitionBatchSize: Int
		get() = DEFAULT_READER_TRANSLATION_OCR_RECOGNITION_BATCH_SIZE

	val readerTranslationPaddleModelUrl: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_MODEL_URL, null) 
			?: context.getString(R.string.reader_translation_paddle_model_url_default)

	val readerTranslationPaddleModelVersion: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_MODEL_VERSION, null)
			?: context.getString(R.string.reader_translation_paddle_model_version_default)

	val readerTranslationPaddleModelSha256: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_MODEL_SHA256, null)
			?: context.getString(R.string.reader_translation_paddle_model_sha256_default)

	val readerTranslationPaddleDetModelUrl: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_DET_MODEL_URL, "") ?: ""

	val readerTranslationPaddleDetModelVersion: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_DET_MODEL_VERSION, "") ?: ""

	val readerTranslationPaddleDetModelSha256: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_DET_MODEL_SHA256, "") ?: ""

	val readerTranslationPaddleRecModelUrl: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_REC_MODEL_URL, "") ?: ""

	val readerTranslationPaddleRecModelVersion: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_REC_MODEL_VERSION, "") ?: ""

	val readerTranslationPaddleRecModelSha256: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_REC_MODEL_SHA256, "") ?: ""

	val readerTranslationPaddleClsModelUrl: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_URL, "") ?: ""

	val readerTranslationPaddleClsModelVersion: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_VERSION, "") ?: ""

	val readerTranslationPaddleClsModelSha256: String
		get() = prefs.getString(KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_SHA256, "") ?: ""

	var readerTranslationBubbleYoloUrl: String
		get() = prefs.getString(KEY_READER_TRANSLATION_BUBBLE_YOLO_URL, "") ?: ""
		set(value) = prefs.edit { putString(KEY_READER_TRANSLATION_BUBBLE_YOLO_URL, value) }

	var readerTranslationOnnxModelId: String
		get() = ""
		set(value) = prefs.edit { putString(KEY_READER_TRANSLATION_ONNX_MODEL_ID, value) }

	var readerTranslationBubbleDetectorModelId: String
		get() = prefs.getString(KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID, "AUTO") ?: "AUTO"
		set(value) = prefs.edit { putString(KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID, value) }

	fun getReaderTranslationBubbleDetectorNmsKey(modelId: String): String {
		return "reader_translation_bubble_detector_nms_${modelId.replace(Regex("[^a-zA-Z0-9]"), "_")}"
	}

	fun getBubbleDetectorNms(modelId: String, defaultIsDetr: Boolean): Float {
		val key = getReaderTranslationBubbleDetectorNmsKey(modelId)
		val defaultVal = if (defaultIsDetr) 85 else 45
		return prefs.getSafeInt(key, defaultVal) / 100f
	}

	fun setBubbleDetectorNms(modelId: String, value: Float) {
		val key = getReaderTranslationBubbleDetectorNmsKey(modelId)
		prefs.edit { putInt(key, (value * 100).toInt()) }
	}

	var readerThreads: Int
		get() = prefs.getSafeInt(KEY_READER_THREADS, 3)
		set(value) = prefs.edit { putInt(KEY_READER_THREADS, value.coerceIn(1, 10)) }

	var readerPrefetchLimit: Int
		get() = prefs.getSafeInt(KEY_READER_PREFETCH_LIMIT, 6)
		set(value) = prefs.edit { putInt(KEY_READER_PREFETCH_LIMIT, value.coerceIn(1, 20)) }

	var screenshotsPolicy: ScreenshotsPolicy
		get() = prefs.getEnumValue(KEY_SCREENSHOTS_POLICY, ScreenshotsPolicy.ALLOW)
		set(value) = prefs.edit { putEnumValue(KEY_SCREENSHOTS_POLICY, value) }

	var isAdBlockEnabled: Boolean
		get() = prefs.getBoolean(KEY_ADBLOCK, false)
		set(value) = prefs.edit { putBoolean(KEY_ADBLOCK, value) }

	var userSpecifiedContentDirectories: Set<File>
		get() {
			val set = prefs.getStringSet(KEY_LOCAL_MANGA_DIRS, emptySet()).orEmpty()
			return set.mapNotNullToSet { File(it).takeIfReadable() }
		}
		set(value) {
			val set = value.mapToSet { it.absolutePath }
			prefs.edit { putStringSet(KEY_LOCAL_MANGA_DIRS, set) }
		}

	var mangaStorageDir: File?
		get() = prefs.getString(KEY_LOCAL_STORAGE, null)?.let {
			File(it)
		}?.takeIf { it.exists() && it in userSpecifiedContentDirectories }
		set(value) = prefs.edit {
			if (value == null) {
				remove(KEY_LOCAL_STORAGE)
			} else {
				val userDirs = userSpecifiedContentDirectories
				if (value !in userDirs) {
					userSpecifiedContentDirectories = userDirs + value
				}
				putString(KEY_LOCAL_STORAGE, value.path)
			}
		}

	var novelStorageDir: File?
		get() = prefs.getString(KEY_LOCAL_NOVEL_STORAGE, null)?.let {
			File(it)
		}?.takeIf { it.exists() }
		set(value) = prefs.edit {
			if (value == null) {
				remove(KEY_LOCAL_NOVEL_STORAGE)
			} else {
				putString(KEY_LOCAL_NOVEL_STORAGE, value.path)
			}
		}

	var videoStorageDir: File?
		get() = prefs.getString(KEY_LOCAL_VIDEO_STORAGE, null)?.let {
			File(it)
		}?.takeIf { it.exists() }
		set(value) = prefs.edit {
			if (value == null) {
				remove(KEY_LOCAL_VIDEO_STORAGE)
			} else {
				putString(KEY_LOCAL_VIDEO_STORAGE, value.path)
			}
		}

	var allowDownloadOnMeteredNetwork: TriStateOption
		get() = prefs.getEnumValue(KEY_DOWNLOADS_METERED_NETWORK, TriStateOption.ASK)
		set(value) = prefs.edit { putEnumValue(KEY_DOWNLOADS_METERED_NETWORK, value) }

	var preferredDownloadFormat: DownloadFormat
		get() = prefs.getEnumValue(KEY_DOWNLOADS_FORMAT, DownloadFormat.AUTOMATIC)
		set(value) = prefs.edit { putEnumValue(KEY_DOWNLOADS_FORMAT, value) }

	var isDownloadAlignedWithReader: Boolean
		get() = prefs.getBoolean(KEY_DOWNLOADS_ALIGN_READER, false)
		set(value) = prefs.edit { putBoolean(KEY_DOWNLOADS_ALIGN_READER, value) }

	var isDownloadAutoRetryOnNetworkError: Boolean
		get() = prefs.getBoolean(KEY_DOWNLOADS_AUTO_RETRY, false)
		set(value) = prefs.edit { putBoolean(KEY_DOWNLOADS_AUTO_RETRY, value) }

	var downloadThreads: Int
		get() = prefs.getSafeInt(KEY_DOWNLOADS_THREADS, readerThreads).coerceIn(1, 10)
		set(value) = prefs.edit { putInt(KEY_DOWNLOADS_THREADS, value.coerceIn(1, 10)) }

	var downloadMaxActiveSeries: Int
		get() = prefs.getSafeInt(KEY_DOWNLOADS_MAX_ACTIVE_SERIES, 5).coerceIn(1, UNLIMITED_SERIES)
		set(value) = prefs.edit { putInt(KEY_DOWNLOADS_MAX_ACTIVE_SERIES, value.coerceIn(1, UNLIMITED_SERIES)) }

	var downloadRequestDelayMs: Int
		get() = prefs.getSafeInt(KEY_DOWNLOADS_REQUEST_DELAY, DOWNLOADS_REQUEST_DELAY_DEFAULT).coerceIn(0, 5000)
		set(value) = prefs.edit { putInt(KEY_DOWNLOADS_REQUEST_DELAY, value.coerceIn(0, 5000)) }

	var downloadRetryCount: Int
		get() = prefs.getSafeInt(KEY_DOWNLOADS_RETRY_COUNT, DOWNLOADS_RETRY_COUNT_DEFAULT).coerceIn(1, 10)
		set(value) = prefs.edit { putInt(KEY_DOWNLOADS_RETRY_COUNT, value.coerceIn(1, 10)) }

	var downloadRetryDelayMs: Int
		get() = prefs.getSafeInt(KEY_DOWNLOADS_RETRY_DELAY, DOWNLOADS_RETRY_DELAY_DEFAULT).coerceIn(500, 10_000)
		set(value) = prefs.edit { putInt(KEY_DOWNLOADS_RETRY_DELAY, value.coerceIn(500, 10_000)) }

	var downloadChapterDelay: Int
		get() = prefs.getSafeInt(KEY_DOWNLOADS_CHAPTER_DELAY, 0).coerceIn(0, 10)
		set(value) = prefs.edit { putInt(KEY_DOWNLOADS_CHAPTER_DELAY, value.coerceIn(0, 10)) }

	var isSuggestionsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS, false)
		set(value) = prefs.edit { putBoolean(KEY_SUGGESTIONS, value) }

	var isBrowseTrackingRecommendationsEnabled: Boolean
		get() = prefs.getBoolean(KEY_BROWSE_TRACKING_RECOMMENDATIONS, true)
		set(value) = prefs.edit { putBoolean(KEY_BROWSE_TRACKING_RECOMMENDATIONS, value) }

	var isBrowseMoreTrackingRecommendationsEnabled: Boolean
		get() = prefs.getBoolean(KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS, false)
		set(value) = prefs.edit { putBoolean(KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS, value) }

	val isSuggestionsWiFiOnly: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_WIFI_ONLY, false)

	var isSuggestionsExcludeNsfw: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_EXCLUDE_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_SUGGESTIONS_EXCLUDE_NSFW, value) }

	val isSuggestionsIncludeDisabledSources: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_DISABLED_SOURCES, false)

	val isSuggestionsNotificationAvailable: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_NOTIFICATIONS, false)

	val suggestionsTagsBlacklist: Set<String>
		get() {
			val string = prefs.getString(KEY_SUGGESTIONS_EXCLUDE_TAGS, null)?.trimEnd(' ', ',')
			if (string.isNullOrEmpty()) {
				return emptySet()
			}
			return string.split(',').mapToSet { it.trim() }
		}

	val suggestionsTagsWhitelist: Set<String>
		get() {
			val string = prefs.getString(KEY_SUGGESTIONS_PREFERRED_TAGS, null)?.trimEnd(' ', ',')
			if (string.isNullOrEmpty()) {
				return emptySet()
			}
			return string.split(',').mapToSet { it.trim() }
		}

	val isReaderBarEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_BAR, true)

	val isReaderBarTransparent: Boolean
		get() = prefs.getBoolean(KEY_READER_BAR_TRANSPARENT, true)

	val isReaderChapterToastEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_CHAPTER_TOAST, true)

	var isReaderSuperResolutionEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_SUPER_RESOLUTION_ENABLED, false)
		set(value) = prefs.edit().putBoolean(KEY_READER_SUPER_RESOLUTION_ENABLED, value).apply()

	var readerImageScalingQuality: ReaderImageScalingQuality
		get() = prefs.getEnumValue(KEY_READER_IMAGE_SCALING_QUALITY, ReaderImageScalingQuality.DEFAULT)
		set(value) = prefs.edit { putString(KEY_READER_IMAGE_SCALING_QUALITY, value.name) }

	val readerSuperResolutionEngine: String
		get() = prefs.getString(KEY_READER_SUPER_RESOLUTION_ENGINE, "ANIME4K") ?: "ANIME4K"

	val readerSuperResolutionAnime4kMode: String
		get() = prefs.getString(KEY_READER_SUPER_RESOLUTION_ANIME4K_MODE, "ANIME4K_A") ?: "ANIME4K_A"

	val readerSuperResolutionModel: String
		get() = prefs.getString(KEY_READER_SUPER_RESOLUTION_MODEL, "SE") ?: "SE"

	val readerSuperResolutionNoiseLevel: Int
		get() = prefs.getString(KEY_READER_SUPER_RESOLUTION_NOISE_LEVEL, "-1")?.toIntOrNull() ?: -1

	val readerSuperResolutionCacheLimitMb: Int
		get() = prefs.getString(KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT, "512")?.toIntOrNull() ?: 512

	val isReaderKeepScreenOn: Boolean
		get() = prefs.getBoolean(KEY_READER_SCREEN_ON, true)

	var readerColorFilter: ReaderColorFilter?
		get() = runCatching {
			ReaderColorFilter(
				brightness = prefs.getSafeFloat(KEY_CF_BRIGHTNESS, ReaderColorFilter.EMPTY.brightness),
				contrast = prefs.getSafeFloat(KEY_CF_CONTRAST, ReaderColorFilter.EMPTY.contrast),
				isInverted = prefs.getBoolean(KEY_CF_INVERTED, ReaderColorFilter.EMPTY.isInverted),
				isGrayscale = prefs.getBoolean(KEY_CF_GRAYSCALE, ReaderColorFilter.EMPTY.isGrayscale),
				isBookBackground = prefs.getBoolean(KEY_CF_BOOK, ReaderColorFilter.EMPTY.isBookBackground),
			).takeUnless { it.isEmpty }
		}.getOrNull()
		set(value) {
			prefs.edit {
				if (value != null) {
					putFloat(KEY_CF_BRIGHTNESS, value.brightness)
					putFloat(KEY_CF_CONTRAST, value.contrast)
					putBoolean(KEY_CF_INVERTED, value.isInverted)
					putBoolean(KEY_CF_GRAYSCALE, value.isGrayscale)
					putBoolean(KEY_CF_BOOK, value.isBookBackground)
				} else {
					remove(KEY_CF_BRIGHTNESS)
					remove(KEY_CF_CONTRAST)
					remove(KEY_CF_INVERTED)
					remove(KEY_CF_GRAYSCALE)
					remove(KEY_CF_BOOK)
				}
			}
		}

	var imagesProxy: Int
		get() {
			val raw = prefs.getString(KEY_IMAGES_PROXY, null)?.toIntOrNull()
			return raw ?: if (prefs.getBoolean(KEY_IMAGES_PROXY_OLD, false)) 0 else -1
		}
		set(value) = prefs.edit { putString(KEY_IMAGES_PROXY, value.toString()) }

	var dnsOverHttps: DoHProvider
		get() = prefs.getEnumValue(KEY_DOH, DoHProvider.NONE)
		set(value) = prefs.edit { putString(KEY_DOH, value.name) }

	var dohCustomUrl: String?
		get() = prefs.getString(KEY_DOH_CUSTOM_URL, null)?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_DOH_CUSTOM_URL, value?.nullIfEmpty()) }

	var dohCustomIps: String?
		get() = prefs.getString(KEY_DOH_CUSTOM_IPS, null)?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_DOH_CUSTOM_IPS, value?.nullIfEmpty()) }

	var isSSLBypassEnabled: Boolean
		get() = prefs.getBoolean(KEY_SSL_BYPASS, false)
		set(value) = prefs.edit { putBoolean(KEY_SSL_BYPASS, value) }

	val proxyType: Proxy.Type
		get() {
			val raw = prefs.getString(KEY_PROXY_TYPE, null) ?: return Proxy.Type.DIRECT
			return enumValues<Proxy.Type>().find { it.name == raw } ?: Proxy.Type.DIRECT
		}

	val proxyAddress: String?
		get() = prefs.getString(KEY_PROXY_ADDRESS, null)

	val proxyPort: Int
		get() = prefs.getString(KEY_PROXY_PORT, null)?.toIntOrNull() ?: 0

	val proxyLogin: String?
		get() = prefs.getString(KEY_PROXY_LOGIN, null)?.nullIfEmpty()

	val proxyPassword: String?
		get() = prefs.getString(KEY_PROXY_PASSWORD, null)?.nullIfEmpty()

	var localListOrder: SortOrder
		get() = prefs.getEnumValue(KEY_LOCAL_LIST_ORDER, SortOrder.NEWEST)
		set(value) = prefs.edit { putEnumValue(KEY_LOCAL_LIST_ORDER, value) }

	var historySortOrder: ListSortOrder
		get() = prefs.getEnumValue(KEY_HISTORY_ORDER, ListSortOrder.LAST_READ)
		set(value) = prefs.edit { putEnumValue(KEY_HISTORY_ORDER, value) }

	var allFavoritesSortOrder: ListSortOrder
		get() = prefs.getEnumValue(KEY_FAVORITES_ORDER, ListSortOrder.NEWEST)
		set(value) = prefs.edit { putEnumValue(KEY_FAVORITES_ORDER, value) }

	var isRelatedContentEnabled: Boolean
		get() = prefs.getBoolean(KEY_RELATED_MANGA, true)
		set(value) = prefs.edit { putBoolean(KEY_RELATED_MANGA, value) }

	val isWebtoonZoomEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_ZOOM, true)

	var isWebtoonGapsEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_GAPS, false)
		set(value) = prefs.edit { putBoolean(KEY_WEBTOON_GAPS, value) }

	var isWebtoonPullGestureEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_PULL_GESTURE, false)
		set(value) = prefs.edit { putBoolean(KEY_WEBTOON_PULL_GESTURE, value) }


	@get:FloatRange(from = 0.0, to = 0.5)
	val defaultWebtoonZoomOut: Float
		get() = prefs.getSafeInt(KEY_WEBTOON_ZOOM_OUT, 0).coerceIn(0, 50) / 100f

	@get:FloatRange(from = 0.0, to = 1.0)
	var readerAutoscrollSpeed: Float
		get() = prefs.getSafeFloat(KEY_READER_AUTOSCROLL_SPEED, 0f)
		set(@FloatRange(from = 0.0, to = 1.0) value) = prefs.edit {
			putFloat(
				KEY_READER_AUTOSCROLL_SPEED,
				value,
			)
		}

	var isReaderAutoscrollFabVisible: Boolean
		get() = prefs.getBoolean(KEY_READER_AUTOSCROLL_FAB, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_AUTOSCROLL_FAB, value) }

	val isPagesPreloadEnabled: Boolean
		get() {
			if (isBackgroundNetworkRestricted()) {
				return false
			}
			val policy = NetworkPolicy.from(
				prefs.getString(KEY_PAGES_PRELOAD, null),
				NetworkPolicy.NON_METERED,
			)
			return policy.isNetworkAllowed(connectivityManager)
		}

	var pagesPreloadPolicy: NetworkPolicy
		get() = NetworkPolicy.from(prefs.getString(KEY_PAGES_PRELOAD, null), NetworkPolicy.NON_METERED)
		set(value) = prefs.edit {
			putString(KEY_PAGES_PRELOAD, when (value) {
				NetworkPolicy.ALWAYS -> "1"
				NetworkPolicy.NON_METERED -> "2"
				NetworkPolicy.NEVER -> "0"
			})
		}

	val is32BitColorsEnabled: Boolean
		get() = prefs.getBoolean(KEY_32BIT_COLOR, false)

	val isDiscordRpcEnabled: Boolean
		get() = prefs.getBoolean(KEY_DISCORD_RPC, false)

	val isDiscordRpcSkipNsfw: Boolean
		get() = prefs.getBoolean(KEY_DISCORD_RPC_SKIP_NSFW, false)

	var discordToken: String?
		get() = prefs.getString(KEY_DISCORD_TOKEN, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_DISCORD_TOKEN, value?.nullIfEmpty()) }

	var discordRefreshToken: String?
		get() = prefs.getString(KEY_DISCORD_REFRESH_TOKEN, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_DISCORD_REFRESH_TOKEN, value?.nullIfEmpty()) }

	var discordCodeVerifier: String?
		get() = prefs.getString(KEY_DISCORD_CODE_VERIFIER, null)
		set(value) = prefs.edit {
			if (value == null) {
				remove(KEY_DISCORD_CODE_VERIFIER)
			} else {
				putString(KEY_DISCORD_CODE_VERIFIER, value)
			}
		}

	val isPeriodicalBackupEnabled: Boolean
		get() = isBackupWebDavUploadEnabled

	var periodicalBackupFrequency: Float
		get() = prefs.getString(KEY_BACKUP_PERIODICAL_FREQUENCY, null)?.toFloatOrNull() ?: 7f
		set(value) = prefs.edit { putString(KEY_BACKUP_PERIODICAL_FREQUENCY, value.toString()) }

	val periodicalBackupFrequencyMillis: Long
		get() = (TimeUnit.DAYS.toMillis(1) * periodicalBackupFrequency).toLong()

	var isPeriodicalBackupTrimEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_PERIODICAL_TRIM, true)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_PERIODICAL_TRIM, value) }

	var periodicalBackupCount: Int
		get() = prefs.getSafeInt(KEY_BACKUP_PERIODICAL_COUNT, 10)
		set(value) = prefs.edit { putInt(KEY_BACKUP_PERIODICAL_COUNT, value) }

	val periodicalBackupMaxCount: Int
		get() = if (isPeriodicalBackupTrimEnabled) {
			periodicalBackupCount
		} else {
			Int.MAX_VALUE
		}

	val periodicalBackupRemoteMaxCount: Int
		get() = periodicalBackupCount.coerceAtLeast(1)

	var periodicalBackupDirectory: Uri?
		get() = prefs.getString(KEY_BACKUP_PERIODICAL_OUTPUT, null)?.toUriOrNull()
		set(value) = prefs.edit { putString(KEY_BACKUP_PERIODICAL_OUTPUT, value?.toString()) }

	val isBackupTelegramUploadEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_TG_ENABLED, false)

	val backupTelegramChatId: String?
		get() = prefs.getString(KEY_BACKUP_TG_CHAT, null)?.nullIfEmpty()

	// WebDAV backup settings
	var isBackupWebDavUploadEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_WEBDAV_ENABLED, false)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_WEBDAV_ENABLED, value) }

	// 是否在上传到 WebDAV 后保留本地副本
	var isBackupWebDavKeepLocalCopyEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY, true)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY, value) }

	var backupWebDavServerUrl: String?
		get() = prefs.getString(KEY_BACKUP_WEBDAV_URL, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_BACKUP_WEBDAV_URL, value?.trim()?.nullIfEmpty()) }

	var backupWebDavUsername: String?
		get() = prefs.getString(KEY_BACKUP_WEBDAV_USERNAME, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_BACKUP_WEBDAV_USERNAME, value?.trim()?.nullIfEmpty()) }

	var backupWebDavPassword: String?
		get() = prefs.getString(KEY_BACKUP_WEBDAV_PASSWORD, null)?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_BACKUP_WEBDAV_PASSWORD, value?.nullIfEmpty()) }

	var backupWebDavRemotePath: String?
		get() = prefs.getString(KEY_BACKUP_WEBDAV_PATH, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_BACKUP_WEBDAV_PATH, value?.trim()?.nullIfEmpty()) }

	// 是否启用数据自动同步（监听数据变更并自动上传至 WebDAV）
	var isBackupWebDavAutoSyncEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_WEBDAV_AUTO_SYNC, false)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_WEBDAV_AUTO_SYNC, value) }

	// 数据版本号（用于版本化命名与兼容性判断）
	var backupWebDavDataVersion: Int
		get() = prefs.getSafeInt(KEY_BACKUP_WEBDAV_DATA_VERSION, 1)
		set(value) = prefs.edit { putInt(KEY_BACKUP_WEBDAV_DATA_VERSION, value) }

	val backupDeviceId: String
		get() = prefs.getString(KEY_BACKUP_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
			prefs.edit { putString(KEY_BACKUP_DEVICE_ID, it) }
		}

	var isBackupWebDavAutoRestoreEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_WEBDAV_AUTO_RESTORE, false)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_WEBDAV_AUTO_RESTORE, value) }

	var backupWebDavLastRestoreTime: Long
		get() = prefs.getSafeLong(KEY_BACKUP_WEBDAV_LAST_RESTORE_TIME, 0L)
		set(value) = prefs.edit { putLong(KEY_BACKUP_WEBDAV_LAST_RESTORE_TIME, value) }

	var backupWebDavLastUploadTime: Long
		get() = prefs.getSafeLong(KEY_BACKUP_WEBDAV_LAST_UPLOAD_TIME, 0L)
		set(value) = prefs.edit { putLong(KEY_BACKUP_WEBDAV_LAST_UPLOAD_TIME, value) }

    // 自动恢复最近一次“检查”的时间（不一定发生了恢复，仅记录检查节流）
    var backupWebDavLastAutoRestoreCheckTime: Long
        get() = prefs.getSafeLong(KEY_BACKUP_WEBDAV_LAST_AUTO_RESTORE_CHECK_TIME, 0L)
        set(value) = prefs.edit { putLong(KEY_BACKUP_WEBDAV_LAST_AUTO_RESTORE_CHECK_TIME, value) }

	// 最近一次 WebDAV 上传类型："auto"（自动）或 "manual"（手动）
	var backupWebDavLastUploadKind: String?
		get() = prefs.getString(KEY_BACKUP_WEBDAV_LAST_UPLOAD_KIND, null)
		set(value) = prefs.edit { putString(KEY_BACKUP_WEBDAV_LAST_UPLOAD_KIND, value) }

	var backupWebDavLastManualRestoreTime: Long
		get() = prefs.getSafeLong(KEY_BACKUP_WEBDAV_LAST_MANUAL_RESTORE_TIME, 0L)
		set(value) = prefs.edit { putLong(KEY_BACKUP_WEBDAV_LAST_MANUAL_RESTORE_TIME, value) }

	var backupWebDavWriterGeneration: Int
		get() = prefs.getSafeInt(KEY_BACKUP_WEBDAV_WRITER_GENERATION, 2).coerceAtLeast(1)
		set(value) = prefs.edit { putInt(KEY_BACKUP_WEBDAV_WRITER_GENERATION, value.coerceAtLeast(1)) }

	var hasCompletedBackupWebDavV2Migration: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_WEBDAV_V2_MIGRATED, false)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_WEBDAV_V2_MIGRATED, value) }

	var backupWebDavLastSeenLegacyCreatedAt: Long
		get() = prefs.getSafeLong(KEY_BACKUP_WEBDAV_LAST_SEEN_LEGACY_CREATED_AT, 0L)
		set(value) = prefs.edit { putLong(KEY_BACKUP_WEBDAV_LAST_SEEN_LEGACY_CREATED_AT, value) }

	var isBackupWebDavAutoUploadBlockedByLegacyRestore: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE, false)
		set(value) = prefs.edit { putBoolean(KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE, value) }

	var backupWebDavLastImportedSemanticSchemaVersion: Int
		get() = prefs.getSafeInt(KEY_BACKUP_WEBDAV_LAST_IMPORTED_SEMANTIC_SCHEMA_VERSION, 1).coerceAtLeast(1)
		set(value) = prefs.edit {
			putInt(KEY_BACKUP_WEBDAV_LAST_IMPORTED_SEMANTIC_SCHEMA_VERSION, value.coerceAtLeast(1))
		}

	var backupWebDavLastAuthoritativeSemanticSchemaVersion: Int
		get() = prefs.getSafeInt(KEY_BACKUP_WEBDAV_LAST_AUTHORITATIVE_SEMANTIC_SCHEMA_VERSION, 1).coerceAtLeast(1)
		set(value) = prefs.edit {
			putInt(KEY_BACKUP_WEBDAV_LAST_AUTHORITATIVE_SEMANTIC_SCHEMA_VERSION, value.coerceAtLeast(1))
		}

	var isWorkMigrationSyncWriteBlocked: Boolean
		get() = prefs.getBoolean(KEY_WORK_MIGRATION_SYNC_WRITE_BLOCKED, false)
		set(value) = prefs.edit { putBoolean(KEY_WORK_MIGRATION_SYNC_WRITE_BLOCKED, value) }

	var requiresWorkMigrationNormalization: Boolean
		get() = prefs.getBoolean(KEY_WORK_MIGRATION_REQUIRES_NORMALIZATION, false)
		set(value) = prefs.edit { putBoolean(KEY_WORK_MIGRATION_REQUIRES_NORMALIZATION, value) }

	var isReadingTimeEstimationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READING_TIME, true)
		set(value) = prefs.edit { putBoolean(KEY_READING_TIME, value) }

	var isPagesSavingAskEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_SAVE_ASK, true)
		set(value) = prefs.edit { putBoolean(KEY_PAGES_SAVE_ASK, value) }

	var isStatsEnabled: Boolean
		get() = prefs.getBoolean(KEY_STATS_ENABLED, false)
		set(value) = prefs.edit { putBoolean(KEY_STATS_ENABLED, value) }

	val isAutoLocalChaptersCleanupEnabled: Boolean
		get() = prefs.getBoolean(KEY_CHAPTERS_CLEAR_AUTO, false)

	fun isPagesCropEnabled(mode: ReaderMode): Boolean {
		val rawValue = prefs.getStringSet(KEY_READER_CROP, emptySet())
		if (rawValue.isNullOrEmpty()) {
			return false
		}
		val needle = if (mode == ReaderMode.WEBTOON) READER_CROP_WEBTOON else READER_CROP_PAGED
		return needle.toString() in rawValue
	}

	fun isTipEnabled(tip: String): Boolean {
		return prefs.getStringSet(KEY_TIPS_CLOSED, emptySet())?.contains(tip) != true
	}

	fun closeTip(tip: String) {
		val closedTips = prefs.getStringSet(KEY_TIPS_CLOSED, emptySet()).orEmpty()
		if (tip in closedTips) {
			return
		}
		prefs.edit { putStringSet(KEY_TIPS_CLOSED, closedTips + tip) }
	}

	fun isIncognitoModeEnabled(isNsfw: Boolean): Boolean {
		return isIncognitoModeEnabled || (isNsfw && incognitoModeForNsfw == TriStateOption.ENABLED)
	}

	fun getPagesSaveDir(context: Context): DocumentFile? =
		prefs.getString(KEY_PAGES_SAVE_DIR, null)?.toUriOrNull()?.let {
			DocumentFile.fromTreeUri(context, it)?.takeIf { it.canWrite() }
		}

	fun setPagesSaveDir(uri: Uri?) {
		prefs.edit { putString(KEY_PAGES_SAVE_DIR, uri?.toString()) }
	}

	fun getContentListBadges(): Int {
		val raw = sanitizeBadgeValues(prefs.getStringSet(KEY_MANGA_LIST_BADGES, null))
		var result = 0
		for (item in raw) {
			result = result or item.toIntOrNull().orZero()
		}
		return result
	}

	var mangaListBadges: Set<String>
		get() = sanitizeBadgeValues(prefs.getStringSet(KEY_MANGA_LIST_BADGES, null))
		set(value) = prefs.edit { putStringSet(KEY_MANGA_LIST_BADGES, sanitizeBadgeValues(value)) }

	fun subscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	fun observeChanges() = prefs.observeChanges()

	fun observe(vararg keys: String): Flow<String?> = prefs.observeChanges()
		.filter { key -> key == null || key in keys }
		.onStart { emit(null) }
		.flowOn(Dispatchers.IO)

	fun getAllValues(): Map<String, *> = prefs.all

	fun reconcileAfterAppUpgrade(currentVersion: Int) {
		val previousVersion = prefs.getSafeInt(KEY_APP_VERSION, 0)
		if (previousVersion == currentVersion) {
			return
		}
		if (previousVersion > 0 || prefs.all.isNotEmpty()) {
			migrateLegacyUiPreferences()
		}
		prefs.edit { putInt(KEY_APP_VERSION, currentVersion) }
	}

	fun upsertAll(m: Map<String, *>) {
		prefs.edit {
			clear()
			putAll(m)
		}
		migrateLegacyUiPreferences()
	}

	private fun migrateLegacyUiPreferences() {
		val sanitizedSearchSuggestionTypes = searchSuggestionTypes
		val sanitizedBadges = mangaListBadges
		val sanitizedSelectedGroupTab = BrowseGroupTab.fromId(getSelectedGroupTab() ?: BrowseGroupTab.All.id).id
		val sanitizedSelectedSourceTags = SourceTag
			.sanitizeQuickFilterSelection(SourceTag.fromIds(getSelectedSourceTags()))
			.mapToSet { it.id }
		prefs.edit {
			putInt(KEY_NAV_HEIGHT, navHeight)
			putInt(KEY_NAV_FLOATING_HEIGHT, navFloatingHeight)
			putInt(KEY_GRID_SIZE, gridSize)
			putInt(KEY_GRID_SIZE_PAGES, gridSizePages)
			putFloat(KEY_PAGE_THUMBNAIL_ASPECT_RATIO, pageThumbnailAspectRatio)
			putInt(KEY_PANORAMA_BLUR, panoramaCoverBlur)
			putInt(KEY_PANORAMA_TRANSITION_INTENSITY, panoramaTransitionIntensity)
			putInt(KEY_PANORAMA_ANIMATION_SPEED, panoramaAnimationSpeed)
			putInt(KEY_PANORAMA_EXTRA_HEIGHT, panoramaCoverExtraHeight)
			putInt(KEY_PANORAMA_BOTTOM_GRADIENT_ALPHA, panoramaBottomGradientAlpha)
			putInt(KEY_BROWSE_PANORAMA_BOTTOM_GRADIENT_ALPHA, browsePanoramaBottomGradientAlpha)
			putInt(KEY_BROWSE_PANORAMA_BLEND_HEIGHT, browsePanoramaBlendHeight)
			putBoolean(KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT, isDetailsPanoramaLimitedToInfoCardMidpoint)
			putBoolean(KEY_DETAILS_PANORAMA_SCROLL_LINKED, isDetailsPanoramaScrollLinkedEnabled)
			putString(KEY_POPUP_RADIUS, popupRadius.toString())
			putInt(KEY_GLASS_IMMERSIVE_STRENGTH, glassImmersiveStrengthPercent)
			putBoolean(KEY_GLASS_EFFECT_ENABLED, isGlassEffectEnabled)
			putBoolean(KEY_REDUCED_VISUAL_EFFECTS, isReducedVisualEffectsEnabled)
			putStringSet(KEY_SEARCH_SUGGESTION_TYPES, sanitizedSearchSuggestionTypes.mapToSet { it.name })
			putStringSet(KEY_MANGA_LIST_BADGES, sanitizedBadges)
			putString(KEY_SELECTED_GROUP_TAB, sanitizedSelectedGroupTab)
			putString(KEY_SELECTED_SOURCE_TAGS, sanitizedSelectedSourceTags.joinToString(","))
			putLong(KEY_BACKUP_WEBDAV_LAST_RESTORE_TIME, backupWebDavLastRestoreTime)
			putLong(KEY_BACKUP_WEBDAV_LAST_UPLOAD_TIME, backupWebDavLastUploadTime)
			putLong(KEY_BACKUP_WEBDAV_LAST_AUTO_RESTORE_CHECK_TIME, backupWebDavLastAutoRestoreCheckTime)
			putLong(KEY_BACKUP_WEBDAV_LAST_MANUAL_RESTORE_TIME, backupWebDavLastManualRestoreTime)
			putInt(KEY_BACKUP_WEBDAV_WRITER_GENERATION, backupWebDavWriterGeneration)
			putBoolean(KEY_BACKUP_WEBDAV_V2_MIGRATED, hasCompletedBackupWebDavV2Migration)
			putLong(KEY_BACKUP_WEBDAV_LAST_SEEN_LEGACY_CREATED_AT, backupWebDavLastSeenLegacyCreatedAt)
			putBoolean(
				KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE,
				isBackupWebDavAutoUploadBlockedByLegacyRestore,
			)
		}
	}

	private fun sanitizeBadgeValues(values: Set<String>?): Set<String> {
		if (values == null) return mangaListBadgesDefault
		val sanitized = values.filterTo(LinkedHashSet(values.size)) { it in mangaListBadgesDefault }
		return when {
			sanitized.isNotEmpty() -> sanitized
			values.isEmpty() -> emptySet()
			else -> mangaListBadgesDefault
		}
	}

	private fun Int?.orZero(): Int = this ?: 0

	private fun String.toUriOrNull(): Uri? = if (isBlank()) null else Uri.parse(this)

	private fun File.takeIfReadable(): File? = takeIf { canRead() }

	private fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, defaultValue: E): E {
		val raw = getString(key, null) ?: return defaultValue
		return defaultValue.javaClass.enumConstants?.firstOrNull { it.name == raw } ?: defaultValue
	}

	private fun <E : Enum<E>> SharedPreferences.Editor.putEnumValue(key: String, value: E?) {
		putString(key, value?.name)
	}

	private fun SharedPreferences.observeChanges(): Flow<String?> = callbackFlow {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			trySend(key)
		}
		registerOnSharedPreferenceChangeListener(listener)
		awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
	}

	private fun SharedPreferences.Editor.putAll(values: Map<String, *>) {
		values.forEach { (key, value) ->
			when (value) {
				is Boolean -> putBoolean(key, value)
				is Int -> putLong(key, value.toLong()) // JSON can't distinguish Int/Long; store as Long for safety
				is Long -> putLong(key, value)
				is Float -> putFloat(key, value)
				is String -> putString(key, value)
				is Set<*> -> {
					@Suppress("UNCHECKED_CAST")
					putStringSet(key, value as? Set<String>)
				}
			}
		}
	}
	
	/**
	 * Get the selected browse group tab ID
	 */
	fun getSelectedGroupTab(): String? {
		return prefs.getString(KEY_SELECTED_GROUP_TAB, null)
	}
	
	/**
	 * Set the selected browse group tab ID
	 */
	fun setSelectedGroupTab(tabId: String) {
		prefs.edit { putString(KEY_SELECTED_GROUP_TAB, tabId) }
	}
	
	/**
	 * Get the selected source filter ID
	 */
	fun getSelectedSourceFilter(): String? {
		return prefs.getString(KEY_SELECTED_SOURCE_FILTER, null)
	}
	
	/**
	 * Set the selected source filter ID
	 */
	fun setSelectedSourceFilter(filterId: String) {
		prefs.edit { putString(KEY_SELECTED_SOURCE_FILTER, filterId) }
	}

	/**
	 * Get the selected source tags (comma-separated) for browse page
	 */
	fun getSelectedSourceTags(): Set<String> {
		val raw = prefs.getString(KEY_SELECTED_SOURCE_TAGS, null) ?: return emptySet()
		return raw.split(",").mapNotNull { it.trim().takeIf { part -> part.isNotEmpty() } }.toSet()
	}

	/**
	 * Set the selected source tags for browse page
	 */
	fun setSelectedSourceTags(tags: Set<String>) {
		val value = tags.joinToString(separator = ",")
		prefs.edit { putString(KEY_SELECTED_SOURCE_TAGS, value) }
	}
	
	/**
	 * Get the selected adult filter ID for browse page
	 */
	fun getSelectedAdultFilter(): String? {
		return prefs.getString(KEY_SELECTED_ADULT_FILTER, null)
	}
	
	/**
	 * Set the selected adult filter ID for browse page
	 */
	fun setSelectedAdultFilter(filterId: String) {
		prefs.edit { putString(KEY_SELECTED_ADULT_FILTER, filterId) }
	}

	private fun clearDeprecatedAllSourcesEnabledFlag() {
		if (prefs.contains(KEY_SOURCES_ENABLED_ALL)) {
			prefs.edit { remove(KEY_SOURCES_ENABLED_ALL) }
		}
	}

	private fun isBackgroundNetworkRestricted(): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
		} else {
			false
		}
	}

	companion object {
		private val CORNER_RADIUS_ALLOWED_VALUES = setOf(-1, 12, 16, 20, 24)


		const val KEY_SHOW_LANGUAGE_PRESET_FILTER = "show_language_preset_filter"
		const val KEY_HIDDEN_LANGUAGE_PRESET = "hidden_language_preset"
		const val KEY_SHOW_CONTENT_TYPE_FILTER = "show_content_type_filter"
		const val KEY_HIDDEN_CONTENT_TYPE = "hidden_content_type"
		const val KEY_SHOW_SOURCE_TAG_FILTER = "show_source_tag_filter"
		const val KEY_HIDDEN_SOURCE_TAG = "hidden_source_tag"
		const val KEY_ACTIVE_SOURCE_PRESET_ID = "active_source_preset_id"
		const val KEY_SOURCES_GROUPED_BY_LANGUAGE = "sources_grouped_by_language"
		const val KEY_EXPLORE_HIDE_EMPTY_SOURCES = "explore_hide_empty_sources"

		const val TRACK_HISTORY = "history"
		const val TRACK_FAVOURITES = "favourites"

		const val KEY_ADBLOCK = "adblock"
		const val KEY_LIST_MODE = "list_mode_2"
		const val KEY_LIST_MODE_BROWSE = "list_mode_browse"
		const val KEY_LIST_MODE_HOME = "list_mode_home"
		const val KEY_LIST_MODE_HISTORY = "list_mode_history"
		const val KEY_LIST_MODE_FAVORITES = "list_mode_favorites"
		const val KEY_LIST_MODE_SUGGESTIONS = "list_mode_suggestions"
		const val KEY_THEME = "theme"
		const val KEY_COLOR_THEME = "color_theme"
		const val KEY_THEME_AMOLED = "amoled_theme"
		const val KEY_BACKGROUND_STYLE = "background_style"
		const val KEY_MATERIAL_EXPRESSIVE_COMPONENTS = "material_expressive_components"
		const val KEY_INTERFACE_STYLE = "interface_style"
		const val KEY_APP_FONT_PRESET = "app_font_preset"
		const val KEY_EXPRESSIVE_APP_FONT_PRESET = "expressive_app_font_preset"
		const val KEY_TABLET_UI_MODE = "tablet_ui_mode"
		const val KEY_OFFLINE_DISABLED = "no_offline"
		const val KEY_PAGES_CACHE_CLEAR = "pages_cache_clear"
		const val KEY_NOVEL_CACHE_CLEAR = "novel_cache_clear"
		const val KEY_VIDEO_CACHE_CLEAR = "video_cache_clear"
		const val KEY_VIDEO_PROXY_CACHE_CLEAR = "video_proxy_cache_clear"
		const val KEY_VIDEO_DANMAKU_CACHE_CLEAR = "video_danmaku_cache_clear"
		const val KEY_HTTP_CACHE_CLEAR = "http_cache_clear"
		const val KEY_FAVICONS_CACHE_CLEAR = "favicons_cache_clear"
		const val KEY_TTS_CACHE_CLEAR = "tts_cache_clear"
		const val KEY_SR_CACHE_CLEAR = "sr_cache_clear"
		const val KEY_COOKIES_CLEAR = "cookies_clear"
		const val KEY_CHAPTERS_CLEAR = "chapters_clear"
		const val KEY_CHAPTERS_CLEAR_AUTO = "chapters_clear_auto"
		const val KEY_THUMBS_CACHE_CLEAR = "thumbs_cache_clear"
		const val KEY_LOCAL_MANGA_CLEAR = "local_manga_clear"
		const val KEY_LOCAL_NOVELS_CLEAR = "local_novels_clear"
		const val KEY_LOCAL_VIDEOS_CLEAR = "local_videos_clear"
		const val KEY_SEARCH_HISTORY_CLEAR = "search_history_clear"
		const val KEY_UPDATES_FEED_CLEAR = "updates_feed_clear"
		const val KEY_GRID_SIZE = "grid_size"
		const val KEY_GRID_SIZE_PAGES = "grid_size_pages"
		const val KEY_PAGE_THUMBNAIL_ASPECT_RATIO = "page_thumbnail_aspect_ratio"
		const val KEY_PAGE_THUMBNAILS_FIT_PREVIEW = "page_thumbnails_fit_preview"
		const val KEY_RAIL_ANIMATION_INTENSITY = "rail_animation_intensity"
		const val KEY_VERTICAL_LIST_RAIL_ANIMATION = "vertical_list_rail_animation"
		const val KEY_REMOTE_SOURCES = "remote_sources"
		const val KEY_LOCAL_STORAGE = "local_storage"
		const val KEY_LOCAL_NOVEL_STORAGE = "local_novel_storage"
		const val KEY_LOCAL_VIDEO_STORAGE = "local_video_storage"
		const val KEY_READER_DOUBLE_PAGES = "reader_double_pages"
		const val KEY_READER_DOUBLE_PAGES_SENSITIVITY = "reader_double_pages_sensitivity_2"
		const val KEY_READER_DOUBLE_FOLDABLE = "reader_double_foldable"
		const val KEY_READER_DOUBLE_COVER_PAGE = "reader_double_cover_page"
		const val KEY_READER_SPLIT_PAGES = "reader_split_pages"
		const val KEY_READER_ZOOM_BUTTONS = "reader_zoom_buttons"
		const val KEY_READER_CONTROL_LABELS = "reader_control_labels"
		const val KEY_READER_CONTROL_LTR = "reader_taps_ltr"
		const val KEY_READER_NAVIGATION_INVERTED = "reader_navigation_inverted"
		const val KEY_READER_FULLSCREEN = "reader_fullscreen"
		const val KEY_READER_VOLUME_BUTTONS = "reader_volume_buttons"
		const val KEY_READER_ORIENTATION = "reader_orientation"
		const val KEY_TRACKER_ENABLED = "tracker_enabled"
		const val KEY_TRACKER_WIFI_ONLY = "tracker_wifi"
		const val KEY_TRACKER_FREQUENCY = "tracker_freq"
		const val KEY_TRACK_SOURCES = "track_sources"
		const val KEY_TRACK_CATEGORIES = "track_categories"
		const val KEY_TRACK_WARNING = "track_warning"
		const val KEY_TRACKER_NOTIFICATIONS = "tracker_notifications"
		const val KEY_PREFERRED_TRACKING_SITE = "preferred_tracking_site"
		const val KEY_TRACKER_NO_NSFW = "tracker_no_nsfw"
		const val KEY_TRACKER_DOWNLOAD = "tracker_download"
		const val KEY_NOTIFICATIONS_SETTINGS = "notifications_settings"
		const val KEY_NOTIFICATIONS_SOUND = "notifications_sound"
		const val KEY_NOTIFICATIONS_VIBRATE = "notifications_vibrate"
		const val KEY_NOTIFICATIONS_LIGHT = "notifications_light"
		const val KEY_NOTIFICATIONS_INFO = "tracker_notifications_info"
		const val KEY_READER_ANIMATION = "reader_animation2"
		const val KEY_READER_CONTROLS = "reader_controls"
		const val KEY_READER_MODE = "reader_mode"
		const val KEY_READER_MODE_DETECT = "reader_mode_detect"
		const val KEY_READER_CROP = "reader_crop"
		const val KEY_APP_PASSWORD = "app_password"
		const val KEY_APP_PASSWORD_NUMERIC = "app_password_num"
		const val KEY_PROTECT_APP = "protect_app"
		const val KEY_PROTECT_APP_BIOMETRIC = "protect_app_bio"
		const val KEY_ZOOM_MODE = "zoom_mode"
		const val KEY_BACKUP = "backup"
		const val KEY_RESTORE = "restore"
		const val KEY_BACKUP_PERIODICAL_ENABLED = "backup_periodic"
		const val KEY_BACKUP_PERIODICAL_FREQUENCY = "backup_periodic_freq"
		const val KEY_BACKUP_PERIODICAL_TRIM = "backup_periodic_trim"
		const val KEY_BACKUP_PERIODICAL_COUNT = "backup_periodic_count"
		const val KEY_BACKUP_PERIODICAL_OUTPUT = "backup_periodic_output"
		const val KEY_BACKUP_PERIODICAL_LAST = "backup_periodic_last"
		const val KEY_HISTORY_GROUPING = "history_grouping"
		const val KEY_UPDATED_GROUPING = "updated_grouping"
		const val KEY_PROGRESS_INDICATORS = "progress_indicators"
		const val KEY_REVERSE_CHAPTERS = "reverse_chapters"
		const val KEY_GRID_VIEW_CHAPTERS = "grid_view_chapters"
		const val KEY_HIDE_READ_CHAPTERS = "hide_read_chapters"
		const val KEY_MERGE_REPEATED_CHAPTERS = "merge_repeated_chapters"
		const val KEY_INCOGNITO_NSFW = "incognito_nsfw"
		const val KEY_PAGES_NUMBERS = "pages_numbers"
		const val KEY_READER_TRANSLATION_ENABLED = "reader_translation_enabled"
		const val KEY_READER_TRANSLATION_SHOW_TRANSLATED = "reader_translation_show_translated"
		const val KEY_READER_TRANSLATION_DEBUG_LOGS = "reader_translation_debug_logs"
		const val KEY_READER_TRANSLATION_QUALITY_FILTER_ENABLED = "reader_translation_quality_filter_enabled"
		const val KEY_READER_TRANSLATION_SOURCE_LANG = "reader_translation_source_lang"
		const val KEY_READER_TRANSLATION_TARGET_LANG = "reader_translation_target_lang"
		const val KEY_READER_TRANSLATION_OCR_ENGINE = "reader_translation_ocr_engine"
		const val KEY_READER_TRANSLATION_MODE = "reader_translation_mode"
		const val KEY_READER_TRANSLATION_PIPELINE_MODE = "reader_translation_pipeline_mode"
		const val KEY_READER_TRANSLATION_API_ENDPOINT = "reader_translation_api_endpoint"
		const val KEY_READER_TRANSLATION_API_KEY = "reader_translation_api_key"
		const val KEY_READER_TRANSLATION_API_MODEL = "reader_translation_api_model"
		const val KEY_READER_TRANSLATION_API_PROVIDER_PRESET = "reader_translation_api_provider_preset"
		const val KEY_READER_TRANSLATION_API_CUSTOM_HEADERS = "reader_translation_api_custom_headers"
		const val KEY_READER_TRANSLATION_API_FETCH_MODELS = "reader_translation_api_fetch_models"

		const val KEY_READER_E2E_API_ENDPOINT = "reader_e2e_api_endpoint"
		const val KEY_READER_E2E_API_KEY = "reader_e2e_api_key"
		const val KEY_READER_E2E_API_MODEL = "reader_e2e_api_model"
		const val KEY_READER_E2E_API_PROVIDER_PRESET = "reader_e2e_api_provider_preset"
		const val KEY_READER_E2E_API_CUSTOM_HEADERS = "reader_e2e_api_custom_headers"
		const val KEY_READER_E2E_API_FETCH_MODELS = "reader_e2e_api_fetch_models"
		const val KEY_READER_E2E_API_CONCURRENCY = "reader_e2e_api_concurrency"
		const val KEY_READER_TRANSLATION_OCR_PIPELINE_STRATEGY = "reader_translation_ocr_pipeline_strategy"
		const val KEY_READER_TRANSLATION_BUBBLE_GROUPING_TUNING = "reader_translation_bubble_grouping_tuning"
		const val KEY_READER_TRANSLATION_BUBBLE_DETECTOR_ENABLED = "reader_translation_bubble_detector_enabled"
		const val KEY_READER_TRANSLATION_BUBBLE_GROUPING_ENABLED = "reader_translation_bubble_grouping_enabled"
		const val KEY_READER_TRANSLATION_OVERLAY_COMPACTNESS = "reader_translation_overlay_compactness"
		const val KEY_READER_TRANSLATION_RENDER_STYLE = "reader_translation_render_style"
		const val KEY_READER_TRANSLATION_PADDLE_MODEL_PATH = "reader_translation_paddle_model_path"
		const val KEY_READER_TRANSLATION_PADDLE_OCR_ONLY = "reader_translation_paddle_ocr_only"
		const val KEY_READER_TRANSLATION_OCR_MODE = "reader_translation_ocr_mode"
		const val KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID = "reader_translation_paddle_official_model_id"
		const val KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID = "reader_translation_paddle_det_model_id"
		const val DEFAULT_READER_TRANSLATION_PADDLE_DET_MODEL_ID = "manga_default_det_20241225_onnx"
		const val DEFAULT_READER_TRANSLATION_OCR_DETECTION_MAX_SIDE = 1536
		const val DEFAULT_READER_TRANSLATION_OCR_DETECTION_THRESHOLD = 0.4f
		const val DEFAULT_READER_TRANSLATION_OCR_MIN_BOX_SIZE = 6
		const val DEFAULT_READER_TRANSLATION_OCR_RECOGNITION_THRESHOLD = 0.1f
		const val DEFAULT_READER_TRANSLATION_OCR_RECOGNITION_MAX_WIDTH = 320
		const val DEFAULT_READER_TRANSLATION_OCR_RECOGNITION_BATCH_SIZE = 16
		const val KEY_READER_TRANSLATION_PADDLE_MODEL_URL = "reader_translation_paddle_model_url"
		const val KEY_READER_TRANSLATION_PADDLE_MODEL_VERSION = "reader_translation_paddle_model_version"
		const val KEY_READER_TRANSLATION_PADDLE_MODEL_SHA256 = "reader_translation_paddle_model_sha256"
		const val KEY_READER_TRANSLATION_PADDLE_DET_MODEL_URL = "reader_translation_paddle_det_model_url"
		const val KEY_READER_TRANSLATION_PADDLE_DET_MODEL_VERSION = "reader_translation_paddle_det_model_version"
		const val KEY_READER_TRANSLATION_PADDLE_DET_MODEL_SHA256 = "reader_translation_paddle_det_model_sha256"
		const val KEY_READER_TRANSLATION_PADDLE_REC_MODEL_URL = "reader_translation_paddle_rec_model_url"
		const val KEY_READER_TRANSLATION_PADDLE_REC_MODEL_VERSION = "reader_translation_paddle_rec_model_version"
		const val KEY_READER_TRANSLATION_PADDLE_REC_MODEL_SHA256 = "reader_translation_paddle_rec_model_sha256"
		const val KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_URL = "reader_translation_paddle_cls_model_url"
		const val KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_VERSION = "reader_translation_paddle_cls_model_version"
		const val KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_SHA256 = "reader_translation_paddle_cls_model_sha256"
		const val KEY_READER_TRANSLATION_PADDLE_DOWNLOAD_NOW = "reader_translation_paddle_download_now"
		const val KEY_READER_TRANSLATION_REC_DOWNLOAD_NOW = "reader_translation_rec_download_now"
		const val KEY_READER_TRANSLATION_ONNX_MODEL_ID = "reader_translation_onnx_model_id"
		const val KEY_READER_TRANSLATION_BUBBLE_DETECTOR_MODEL_ID = "reader_translation_bubble_detector_model_id"
		const val KEY_READER_TRANSLATION_BUBBLE_YOLO_URL = "reader_translation_bubble_yolo_url"
		const val KEY_SCREENSHOTS_POLICY = "screenshots_policy"
		const val KEY_READER_THREADS = "reader_threads"
		const val KEY_READER_PREFETCH_LIMIT = "reader_prefetch_limit"
		const val KEY_PAGES_PRELOAD = "pages_preload"
		const val KEY_SUGGESTIONS = "suggestions"
		const val KEY_BROWSE_TRACKING_RECOMMENDATIONS = "browse_tracking_recommendations"
		const val KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS = "browse_more_tracking_recommendations"
		const val KEY_SUGGESTIONS_WIFI_ONLY = "suggestions_wifi"
		const val KEY_SUGGESTIONS_EXCLUDE_NSFW = "suggestions_exclude_nsfw"
		const val KEY_SUGGESTIONS_EXCLUDE_TAGS = "suggestions_exclude_tags"
		const val KEY_SUGGESTIONS_PREFERRED_TAGS = "suggestions_preferred_tags"
		const val KEY_SUGGESTIONS_DISABLED_SOURCES = "suggestions_disabled_sources"
		const val KEY_SUGGESTIONS_NOTIFICATIONS = "suggestions_notifications"
		const val KEY_SHIKIMORI = "shikimori"
		const val KEY_ANILIST = "anilist"
		const val KEY_MAL = "mal"
		const val KEY_KITSU = "kitsu"
		const val KEY_BANGUMI = "bangumi"
		const val KEY_MANGAUPDATES = "mangaupdates"
		const val KEY_TRACKING_METADATA_SOURCE_STRATEGY = "tracking_metadata_source_strategy"
		const val KEY_DOWNLOADS_METERED_NETWORK = "downloads_metered_network"
		const val KEY_DOWNLOADS_FORMAT = "downloads_format"
		const val KEY_DOWNLOADS_ALIGN_READER = "downloads_align_reader"
		const val KEY_DOWNLOADS_AUTO_RETRY = "downloads_auto_retry"
		const val KEY_DOWNLOADS_THREADS = "downloads_threads"
		const val KEY_DOWNLOADS_MAX_ACTIVE_SERIES = "downloads_max_active_series"
		const val UNLIMITED_SERIES = 11
		const val KEY_DOWNLOADS_REQUEST_DELAY = "downloads_request_delay"
		const val KEY_DOWNLOADS_RETRY_COUNT = "downloads_retry_count"
		const val KEY_DOWNLOADS_RETRY_DELAY = "downloads_retry_delay"
		const val KEY_DOWNLOADS_CHAPTER_DELAY = "downloads_chapter_delay"
		const val KEY_ALL_FAVOURITES_VISIBLE = "all_favourites_visible"
		const val KEY_DOH = "doh"
		const val KEY_DOH_CUSTOM_URL = "doh_custom_url"
		const val KEY_DOH_CUSTOM_IPS = "doh_custom_ips"
		const val KEY_EXIT_CONFIRM = "exit_confirm"
		const val KEY_INCOGNITO_MODE = "incognito"
		const val KEY_READER_MULTITASK = "reader_multitask"
		const val KEY_SYNC = "sync"
		const val KEY_SYNC_SETTINGS = "sync_settings"
		const val KEY_READER_BAR = "reader_bar"
		const val KEY_READER_BAR_TRANSPARENT = "reader_bar_transparent"
		const val KEY_READER_CHAPTER_TOAST = "reader_chapter_toast"
		const val KEY_READER_SUPER_RESOLUTION_ENABLED = "reader_super_resolution_enabled"
		const val KEY_READER_SUPER_RESOLUTION_ENGINE = "reader_super_resolution_engine"
		const val KEY_READER_SUPER_RESOLUTION_ANIME4K_MODE = "reader_super_resolution_anime4k_mode"
		const val KEY_READER_SUPER_RESOLUTION_MODEL = "reader_super_resolution_model"
		const val KEY_READER_SUPER_RESOLUTION_NOISE_LEVEL = "reader_super_resolution_noise_level"
		const val KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT = "reader_super_resolution_cache_limit"
		const val KEY_READER_BACKGROUND = "reader_background"
		const val KEY_VIDEO_DECODER_MODE = "video_decoder_mode"
		const val KEY_VIDEO_RENDERER_MODE = "video_renderer_mode"
		const val KEY_VIDEO_BACKGROUND = "video_background"
		const val KEY_VIDEO_PREFERRED_QUALITY = "video_preferred_quality"
		const val KEY_VIDEO_SUPER_RES_MODE = "video_super_resolution_mode"
		const val KEY_VIDEO_SUPER_RES_SHADER = "video_super_resolution_shader"
		const val KEY_VIDEO_SUPER_RES_QUALITY_SHADER = "video_super_resolution_quality_shader"
		const val KEY_VIDEO_SUPER_RES_BALANCED_SHADER = "video_super_resolution_balanced_shader"
		const val KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER = "video_super_resolution_performance_shader"
		const val KEY_VIDEO_SUPER_RES_CUSTOM_SHADERS = "video_super_resolution_custom_shaders"
		const val KEY_VIDEO_DANMAKU_ENABLED = "video_danmaku_enabled"
		const val KEY_VIDEO_DANMAKU_SIZE = "video_danmaku_size"
		const val KEY_VIDEO_DANMAKU_SPEED = "video_danmaku_speed"
		const val KEY_VIDEO_DANMAKU_OPACITY = "video_danmaku_opacity"
		const val KEY_VIDEO_DANMAKU_STROKE = "video_danmaku_stroke"
		const val KEY_VIDEO_DANMAKU_SHOW_SCROLL = "video_danmaku_show_scroll"
		const val KEY_VIDEO_DANMAKU_SHOW_TOP = "video_danmaku_show_top"
		const val KEY_VIDEO_DANMAKU_SHOW_BOTTOM = "video_danmaku_show_bottom"
		const val KEY_VIDEO_DANMAKU_MAX_SCROLL_LINES = "video_danmaku_max_scroll_lines"
		const val KEY_VIDEO_DANMAKU_MAX_TOP_LINES = "video_danmaku_max_top_lines"
		const val KEY_VIDEO_DANMAKU_MAX_BOTTOM_LINES = "video_danmaku_max_bottom_lines"
		const val KEY_VIDEO_DANMAKU_MAX_SCREEN_NUM = "video_danmaku_max_screen_num"
		const val KEY_VIDEO_DANMAKU_SOURCE_DANDAN = "video_danmaku_source_dandan"
		const val KEY_VIDEO_DANMAKU_SOURCE_BILIBILI = "video_danmaku_source_bilibili"
		const val KEY_VIDEO_DANMAKU_SOURCE_QQ = "video_danmaku_source_qq"
		const val KEY_VIDEO_DANMAKU_CACHE_MB = "video_danmaku_cache_mb"
		const val KEY_VIDEO_PLAYBACK_SPEED = "video_playback_speed"
		const val KEY_VIDEO_DEFAULT_SPEED = "video_default_speed"
		const val KEY_VIDEO_SEEK_FORWARD_MS = "video_seek_forward_ms"
		const val KEY_VIDEO_SEEK_BACKWARD_MS = "video_seek_backward_ms"

		const val KEY_VIDEO_SUBTITLE_FONT_SIZE = "video_subtitle_font_size"
		const val KEY_VIDEO_SUBTITLE_BOLD = "video_subtitle_bold"
		const val KEY_VIDEO_SUBTITLE_ITALIC = "video_subtitle_italic"
		const val KEY_VIDEO_SUBTITLE_TEXT_COLOR = "video_subtitle_text_color"
		const val KEY_VIDEO_SUBTITLE_BORDER_COLOR = "video_subtitle_border_color"
		const val KEY_VIDEO_SUBTITLE_BORDER_SIZE = "video_subtitle_border_size"
		const val KEY_VIDEO_SUBTITLE_BG_COLOR = "video_subtitle_bg_color"
		const val KEY_VIDEO_SUBTITLE_ALIGN_X = "video_subtitle_align_x"
		const val KEY_VIDEO_SUBTITLE_POSITION = "video_subtitle_position"
		const val KEY_VIDEO_VOLUME_BOOST = "video_volume_boost"
		const val KEY_VIDEO_AUTO_NEXT = "video_auto_next"
		const val KEY_VIDEO_LANDSCAPE_SENSOR = "video_landscape_sensor"
		const val KEY_VIDEO_CACHE_MB = "video_cache_mb"
		const val KEY_VIDEO_PROXY_CACHE_MB = "video_proxy_cache_mb"
		const val KEY_THUMBS_CACHE_MB = "thumbs_cache_mb"
		const val KEY_FAVICON_CACHE_MB = "favicon_cache_mb"
		const val KEY_PAGES_CACHE_MB = "pages_cache_mb"
		const val KEY_NOVEL_CACHE_MB = "novel_cache_mb"
		const val KEY_HTTP_CACHE_MB_LIMIT = "http_cache_mb_limit"
		const val KEY_TTS_CACHE_MB = "tts_cache_mb"
		const val KEY_VIDEO_ASPECT_RATIO = "video_aspect_ratio"
		const val KEY_VIDEO_DOUBLE_TAP_SEEK_ENABLED = "video_double_tap_seek_enabled"
		const val KEY_VIDEO_CONTROLS_ALPHA = "video_controls_alpha"
		const val KEY_VIDEO_GRADIENT_ALPHA = "video_gradient_alpha"
		const val KEY_READER_SCREEN_ON = "reader_screen_on"
		const val KEY_SHORTCUTS = "dynamic_shortcuts"
		const val KEY_READER_TAP_ACTIONS = "reader_tap_actions"
		const val KEY_READER_OPTIMIZE = "reader_optimize"
		const val KEY_READER_REDUCE_PRELOAD = "reader_reduce_offscreen_quality"
		const val KEY_LOCAL_LIST_ORDER = "local_order"
		const val KEY_HISTORY_ORDER = "history_order"
		const val KEY_FAVORITES_ORDER = "fav_order"
		const val KEY_WEBTOON_GAPS = "webtoon_gaps"
		const val KEY_WEBTOON_ZOOM = "webtoon_zoom"
		const val KEY_WEBTOON_ZOOM_OUT = "webtoon_zoom_out"
		private const val DOWNLOADS_REQUEST_DELAY_DEFAULT = 1600
		private const val DOWNLOADS_RETRY_COUNT_DEFAULT = 5
		private const val DOWNLOADS_RETRY_DELAY_DEFAULT = 2000
		const val KEY_WEBTOON_PULL_GESTURE = "webtoon_pull_gesture"
		const val KEY_PREFETCH_CONTENT = "prefetch_content"
		const val KEY_APP_LOCALE = "app_locale"
		const val KEY_CONTENT_LANGUAGES = "content_languages"
		const val KEY_EXTENSION_LANGUAGES = "extension_languages"
		const val KEY_LOCAL_APK_HOT_RELOAD = "local_apk_hot_reload"
		const val KEY_GITHUB_MIRROR = "github_mirror"
		const val KEY_SHOW_EXTRA_INFO_ON_CARDS = "show_extra_info_on_cards"
		const val KEY_HUGGINGFACE_MIRROR = "huggingface_mirror"
		const val KEY_BANGUMI_MIRROR = "bangumi_mirror"
		const val KEY_BANGUMI_MIRROR_CUSTOM_BASE = "bangumi_mirror_custom_base"
		const val KEY_LNREADER_REPOS = "lnreader_repository_urls"
		const val KEY_SOURCES_GRID = "sources_grid"
		const val KEY_SHOW_SOURCE_ON_CARDS = "show_source_on_cards"
		const val KEY_SHARED_ELEMENT_TRANSITIONS = "shared_element_transitions"
		const val KEY_UPDATES_UNSTABLE = "updates_unstable"
		const val KEY_TIPS_CLOSED = "tips_closed"
		const val KEY_SSL_BYPASS = "ssl_bypass"
		const val KEY_READER_AUTOSCROLL_SPEED = "as_speed"
		const val KEY_READER_AUTOSCROLL_FAB = "as_fab"
		const val KEY_READER_AUTOSCROLL_PAUSE_ON_UI = "as_pause_ui"
		const val KEY_MIRROR_SWITCHING = "mirror_switching"
		const val KEY_PROXY = "proxy"
		const val KEY_PROXY_TYPE = "proxy_type_2"
		const val KEY_PROXY_ADDRESS = "proxy_address"
		const val KEY_PROXY_PORT = "proxy_port"
		const val KEY_PROXY_AUTH = "proxy_auth"
		const val KEY_PROXY_LOGIN = "proxy_login"
		const val KEY_PROXY_PASSWORD = "proxy_password"
		const val KEY_IMAGES_PROXY = "images_proxy_2"
		const val KEY_LOCAL_MANGA_DIRS = "local_manga_dirs"
		const val KEY_HISTORY_EXCLUDE_NSFW = "history_exclude_nsfw"
		const val KEY_FAVOURITES_EXCLUDE_NSFW = "favourites_exclude_nsfw"
		const val KEY_FEED_EXCLUDE_NSFW = "feed_exclude_nsfw"
		const val KEY_DISABLE_NSFW = "no_nsfw"
		const val KEY_GLOBAL_TAG_BLACKLIST = "global_tag_blacklist"
		const val KEY_RELATED_MANGA = "related_manga"
		const val KEY_NAV_MAIN = "nav_main"
		const val KEY_NAV_LABELS = "nav_labels"
		const val KEY_NAV_PINNED = "nav_pinned"
		const val KEY_NAV_FLOATING = "nav_floating"
		const val KEY_NAV_FLOATING_ADAPTIVE_WIDTH = "nav_floating_adaptive_width"
		const val KEY_NAV_EXPRESSIVE_PILL = "nav_expressive_pill"
		const val KEY_NAV_HEIGHT = "nav_height"
		const val KEY_NAV_FLOATING_HEIGHT = "nav_floating_height"
		const val KEY_MAIN_FAB = "main_fab"

		const val KEY_LOADING_CIRCLE_STYLE = "loading_circle_style"
		const val KEY_POPUP_RADIUS = "popup_radius"
		const val KEY_GLASS_IMMERSIVE_STRENGTH = "glass_immersive_strength"
		const val KEY_GLASS_EFFECT_ENABLED = "glass_effect_enabled"
		const val KEY_REDUCED_VISUAL_EFFECTS = "reduced_visual_effects"
		const val KEY_DETAILS_PANORAMA_SCROLL_LINKED = "details_panorama_scroll_linked"
		const val KEY_32BIT_COLOR = "enhanced_colors"
		const val KEY_SOURCES_ORDER = "sources_sort_order"
		const val KEY_SOURCES_CATALOG = "sources_catalog"
		const val KEY_CF_BRIGHTNESS = "cf_brightness"
		const val KEY_CF_CONTRAST = "cf_contrast"
		const val KEY_CF_INVERTED = "cf_inverted"
		const val KEY_CF_GRAYSCALE = "cf_grayscale"
		const val KEY_CF_BOOK = "cf_book"
		const val KEY_READER_IMAGE_SCALING_QUALITY = "reader_image_scaling_quality"
		const val KEY_PAGES_TAB = "pages_tab"
		const val KEY_DETAILS_TRANSLATE_BUTTON = "details_translate_button"
		const val KEY_MODERN_DETAILS_DOCK = "modern_details_dock"
		const val KEY_DETAILS_TAB = "details_tab"
		const val KEY_DETAILS_LAST_TAB = "details_last_tab"
		const val KEY_READING_TIME = "reading_time"
		const val KEY_PAGES_SAVE_DIR = "pages_dir"
		const val KEY_PAGES_SAVE_ASK = "pages_dir_ask"
		const val KEY_STATS_ENABLED = "stats_on"
		const val KEY_FEED_HEADER = "feed_header"
		const val KEY_SHOW_ALL_UPDATES = "show_all_updates"
		const val KEY_FEED_LIMIT = "feed_limit"
		const val KEY_FEED_LAST_OPEN_TIME = "feed_last_open_time"
		const val KEY_SEARCH_SUGGESTION_TYPES = "search_suggest_types"
		const val KEY_SOURCES_VERSION = "sources_version"
		const val KEY_SOURCES_ENABLED_ALL = "sources_enabled_all"
		const val KEY_EXTENSIONS_GRID = "extensions_grid"
		const val KEY_SHOW_BROKEN_SOURCES = "show_broken_sources"
		const val KEY_JAR_PRIORITY_ORDER = "jar_priority_order"
		const val KEY_EXTENSIONS = "extensions"
		const val KEY_JSON_SOURCES = "json_sources"
		const val KEY_MIHON_EXTENSIONS = "mihon_extensions"
		const val KEY_ANIYOMI_EXTENSIONS = "aniyomi_extensions"
		const val KEY_QUICK_FILTER = "quick_filter"
		const val KEY_COLLAPSE_DESCRIPTION = "description_collapse"
		const val KEY_PANORAMA_ENABLED = "panorama_enabled"
		const val KEY_PANORAMA_BLUR = "panorama_blur"
		const val KEY_PANORAMA_TRANSITION_INTENSITY = "panorama_transition_intensity"
		const val KEY_PANORAMA_ANIMATION_ENABLED = "panorama_animation_enabled"
		const val KEY_PANORAMA_ANIMATION_SPEED = "panorama_animation_speed"
		const val KEY_PANORAMA_EXTRA_HEIGHT = "panorama_extra_height"
		const val KEY_PANORAMA_BOTTOM_GRADIENT_ALPHA = "panorama_bottom_gradient_alpha"
		const val KEY_BROWSE_PANORAMA_BOTTOM_GRADIENT_ALPHA = "browse_panorama_bottom_gradient_alpha"
		const val KEY_BROWSE_PANORAMA_BLEND_HEIGHT = "browse_panorama_blend_height"
		const val KEY_PANORAMA_DOWNSAMPLE = "panorama_downsample"
		const val KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT = "details_panorama_limit_to_info_card_midpoint"
	
		const val KEY_BACKUP_TG_ENABLED = "backup_periodic_tg_enabled"
		const val KEY_BACKUP_TG_CHAT = "backup_periodic_tg_chat_id"
		// WebDAV backup keys
		const val KEY_BACKUP_WEBDAV = "backup_periodic_webdav"
		const val KEY_BACKUP_WEBDAV_ENABLED = "backup_periodic_webdav_enabled"
		const val KEY_BACKUP_WEBDAV_URL = "backup_periodic_webdav_server_url"
		const val KEY_BACKUP_WEBDAV_USERNAME = "backup_periodic_webdav_username"
		const val KEY_BACKUP_WEBDAV_PASSWORD = "backup_periodic_webdav_password"
		const val KEY_BACKUP_WEBDAV_PATH = "backup_periodic_webdav_remote_path"
		const val KEY_BACKUP_WEBDAV_KEEP_LOCAL_COPY = "backup_periodic_webdav_keep_local_copy"
		const val KEY_BACKUP_WEBDAV_TEST = "backup_periodic_webdav_test"
		const val KEY_BACKUP_WEBDAV_UPLOAD_NOW = "backup_periodic_webdav_upload_now"
		const val KEY_BACKUP_WEBDAV_RESTORE_NOW = "backup_periodic_webdav_restore_now"
		const val KEY_BACKUP_WEBDAV_AUTO_RESTORE = "backup_periodic_webdav_auto_restore"
		const val KEY_BACKUP_WEBDAV_LAST_RESTORE_TIME = "backup_periodic_webdav_last_restore_time"
		const val KEY_BACKUP_WEBDAV_LAST_UPLOAD_TIME = "backup_periodic_webdav_last_upload_time"
		const val KEY_BACKUP_WEBDAV_LAST_AUTO_RESTORE_CHECK_TIME = "backup_periodic_webdav_last_auto_restore_check_time"
		const val KEY_BACKUP_WEBDAV_LAST_UPLOAD_KIND = "backup_periodic_webdav_last_upload_kind"
		const val KEY_BACKUP_WEBDAV_LAST_MANUAL_RESTORE_TIME = "backup_periodic_webdav_last_manual_restore_time"
		const val KEY_BACKUP_WEBDAV_WRITER_GENERATION = "backup_periodic_webdav_writer_generation"
		const val KEY_BACKUP_WEBDAV_V2_MIGRATED = "backup_periodic_webdav_v2_migrated"
		const val KEY_BACKUP_WEBDAV_LAST_SEEN_LEGACY_CREATED_AT = "backup_periodic_webdav_last_seen_legacy_created_at"
		const val KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE =
			"backup_periodic_webdav_block_auto_upload_after_legacy_restore"
		const val KEY_BACKUP_WEBDAV_LAST_IMPORTED_SEMANTIC_SCHEMA_VERSION =
			"backup_periodic_webdav_last_imported_semantic_schema_version"
		const val KEY_BACKUP_WEBDAV_LAST_AUTHORITATIVE_SEMANTIC_SCHEMA_VERSION =
			"backup_periodic_webdav_last_authoritative_semantic_schema_version"
		const val KEY_WORK_MIGRATION_SYNC_WRITE_BLOCKED =
			"work_migration_sync_write_blocked"
		const val KEY_WORK_MIGRATION_REQUIRES_NORMALIZATION =
			"work_migration_requires_normalization"
		const val KEY_BACKUP_WEBDAV_LAST_ACTIONS = "backup_periodic_webdav_last_actions"

		// WebDAV 自动同步与数据版本
		const val KEY_BACKUP_WEBDAV_AUTO_SYNC = "backup_periodic_webdav_auto_sync"
		const val KEY_BACKUP_WEBDAV_DATA_VERSION = "backup_periodic_webdav_data_version"
		const val KEY_BACKUP_DEVICE_ID = "backup_device_id"

		const val KEY_BACKUP_WEBDAV_POLICY_NOTE = "backup_periodic_webdav_policy_note"
		const val KEY_MANGA_LIST_BADGES = "manga_list_badges"
		const val KEY_BADGES_TOP_LEFT = "badges_top_left"
		const val KEY_BADGES_TOP_RIGHT = "badges_top_right"
		const val KEY_BADGES_BOTTOM_LEFT = "badges_bottom_left"
		const val KEY_BADGES_BOTTOM_RIGHT = "badges_bottom_right"
		const val KEY_TAGS_WARNINGS = "tags_warnings"
		const val KEY_DISCORD_RPC = "discord_rpc"
		const val KEY_DISCORD_RPC_SKIP_NSFW = "discord_rpc_skip_nsfw"
		const val KEY_DISCORD_TOKEN = "discord_token"
		const val KEY_DISCORD_REFRESH_TOKEN = "discord_refresh_token"
		const val KEY_DISCORD_CODE_VERIFIER = "discord_code_verifier"
		const val KEY_SELECTED_GROUP_TAB = "selected_group_tab"
		const val KEY_SELECTED_SOURCE_FILTER = "selected_source_filter"
		const val KEY_SELECTED_SOURCE_TAGS = "selected_source_tags"
		const val KEY_SELECTED_ADULT_FILTER = "selected_adult_filter"
		const val KEY_ENTITY_GRAPH_MIGRATED = "entity_graph_migrated"
		const val KEY_LEGACY_FAVOURITE_PROJECTION_MIGRATION_COMPLETED =
			"legacy_favourite_projection_migration_completed"

		// keys for non-persistent preferences
		const val KEY_APP_VERSION = "app_version"
		const val KEY_IGNORE_DOZE = "ignore_dose"
		const val KEY_TRACKER_DEBUG = "tracker_debug"
		const val KEY_LINK_WEBLATE = "about_app_translation"
		const val KEY_LINK_DISCORD = "about_discord"
		const val KEY_LINK_GITHUB = "about_github"
		const val KEY_LINK_DONATE = "about_donate"
		const val KEY_LINK_MANUAL = "about_help"
		const val KEY_PROXY_TEST = "proxy_test"
		const val KEY_OPEN_BROWSER = "open_browser"
		const val KEY_HANDLE_LINKS = "handle_links"
		const val KEY_BACKUP_TG = "backup_periodic_tg"
		const val KEY_BACKUP_TG_OPEN = "backup_periodic_tg_open"
		const val KEY_BACKUP_TG_TEST = "backup_periodic_tg_test"
		const val KEY_CLEAR_MANGA_DATA = "manga_data_clear"
		const val KEY_STORAGE_USAGE = "storage_usage"
		const val KEY_WEBVIEW_CLEAR = "webview_clear"
		private const val DEFAULT_JAR_PRIORITY_ORDER = "kototoro-parsers,kotatsu-parsers-redo,kotatsu-parsers"

		// old keys are for migration only
		private const val KEY_IMAGES_PROXY_OLD = "images_proxy"

		// values
		private const val READER_CROP_PAGED = 1
		private const val READER_CROP_WEBTOON = 2
		
		const val KEY_FILTER_PILL_DEFAULT = "filter_pill_default"
		const val KEY_FILTER_PILL_LEFT = "filter_pill_left"
		const val KEY_FILTER_PILL_RIGHT = "filter_pill_right"
		const val KEY_ACTIVE_SPACE = "entity_space_active"
		const val KEY_ENTITY_SPACE_ENABLED = "entity_space_enabled"
		const val KEY_SPACE_SWITCHER_ENABLED = "entity_space_switcher_enabled"
		const val KEY_SPACE_PERSISTENT_NAVIGATION_ENABLED = "entity_space_persistent_navigation_enabled"
		const val KEY_SPACE_IMMERSIVE_SWITCH_ENABLED = "entity_space_immersive_switch_enabled"
		const val KEY_SPACE_ROUTE_PREFERENCES_ENABLED = "entity_space_route_preferences_enabled"
		const val KEY_SPACE_SWITCHER_POSITION = "entity_space_switcher_position"
	}

	// ==================== Video Intro/Outro Skip ====================

	private val skipPrefs by lazy {
		context.getSharedPreferences("video_skip_times", Context.MODE_PRIVATE)
	}

	fun getIntroEndMs(mangaId: Long): Long = skipPrefs.getLong("intro_end_$mangaId", 0L)

	fun setIntroEndMs(mangaId: Long, ms: Long) {
		skipPrefs.edit { putLong("intro_end_$mangaId", ms) }
	}

	fun clearIntroEndMs(mangaId: Long) {
		skipPrefs.edit { remove("intro_end_$mangaId") }
	}

	fun getOutroStartMs(mangaId: Long): Long = skipPrefs.getLong("outro_start_$mangaId", 0L)

	fun setOutroStartMs(mangaId: Long, ms: Long) {
		skipPrefs.edit { putLong("outro_start_$mangaId", ms) }
	}

	fun clearOutroStartMs(mangaId: Long) {
		skipPrefs.edit { remove("outro_start_$mangaId") }
	}

	// ==================== Filter Pill Settings ====================

	var filterPillDefaultType: org.skepsun.kototoro.parsers.model.ContentType
		get() {
			val name = prefs.getString(KEY_FILTER_PILL_DEFAULT, org.skepsun.kototoro.parsers.model.ContentType.MANGA.name) ?: org.skepsun.kototoro.parsers.model.ContentType.MANGA.name
			return try { enumValueOf(name) } catch (e: Exception) { org.skepsun.kototoro.parsers.model.ContentType.MANGA }
		}
		set(value) = prefs.edit { putString(KEY_FILTER_PILL_DEFAULT, value.name) }

	var filterPillSwipeLeftType: org.skepsun.kototoro.parsers.model.ContentType
		get() {
			val name = prefs.getString(KEY_FILTER_PILL_LEFT, org.skepsun.kototoro.parsers.model.ContentType.VIDEO.name) ?: org.skepsun.kototoro.parsers.model.ContentType.VIDEO.name
			return try { enumValueOf(name) } catch (e: Exception) { org.skepsun.kototoro.parsers.model.ContentType.VIDEO }
		}
		set(value) = prefs.edit { putString(KEY_FILTER_PILL_LEFT, value.name) }

	var filterPillSwipeRightType: org.skepsun.kototoro.parsers.model.ContentType
		get() {
			val name = prefs.getString(KEY_FILTER_PILL_RIGHT, org.skepsun.kototoro.parsers.model.ContentType.NOVEL.name) ?: org.skepsun.kototoro.parsers.model.ContentType.NOVEL.name
			return try { enumValueOf(name) } catch (e: Exception) { org.skepsun.kototoro.parsers.model.ContentType.NOVEL }
		}
		set(value) = prefs.edit { putString(KEY_FILTER_PILL_RIGHT, value.name) }

	var activeSpaceId: String
		get() = prefs.getString(KEY_ACTIVE_SPACE, "builtin:manga") ?: "builtin:manga"
		set(value) = prefs.edit { putString(KEY_ACTIVE_SPACE, value) }

	var isEntitySpaceEnabled: Boolean
		get() = prefs.getBoolean(KEY_ENTITY_SPACE_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_ENTITY_SPACE_ENABLED, value) }

	var isSpaceSwitcherEnabled: Boolean
		get() = prefs.getBoolean(KEY_SPACE_SWITCHER_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_SPACE_SWITCHER_ENABLED, value) }

	var isSpacePersistentNavigationEnabled: Boolean
		get() = prefs.getBoolean(KEY_SPACE_PERSISTENT_NAVIGATION_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_SPACE_PERSISTENT_NAVIGATION_ENABLED, value) }

	var isSpaceImmersiveSwitchEnabled: Boolean
		get() = prefs.getBoolean(KEY_SPACE_IMMERSIVE_SWITCH_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_SPACE_IMMERSIVE_SWITCH_ENABLED, value) }

	var isSpaceRoutePreferencesEnabled: Boolean
		get() = prefs.getBoolean(KEY_SPACE_ROUTE_PREFERENCES_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_SPACE_ROUTE_PREFERENCES_ENABLED, value) }

	var spaceSwitcherPosition: SpaceSwitcherPosition
		get() = prefs.getString(KEY_SPACE_SWITCHER_POSITION, null)
			?.let { value -> SpaceSwitcherPosition.entries.firstOrNull { it.name == value } }
			?: SpaceSwitcherPosition.TOP_RIGHT
		set(value) = prefs.edit { putString(KEY_SPACE_SWITCHER_POSITION, value.name) }

}
