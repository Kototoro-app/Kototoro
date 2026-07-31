package org.skepsun.kototoro.settings.search

import android.content.Context
import dagger.Reusable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.settings.SettingsDestination
import javax.inject.Inject

@Reusable
class SettingsSearchHelper @Inject constructor(
    @LocalizedAppContext private val context: Context,
) {

    fun inflatePreferences(): List<SettingsItem> {
		val result = ArrayList<SettingsItem>()
		
		val appearanceBreadcrumbs = listOf(context.getString(R.string.appearance))
		val appearanceKeys = listOf(
			"color_theme" to R.string.color_theme,
			"theme" to R.string.theme,
			"amoled_theme" to R.string.black_dark_theme,
			"reduced_visual_effects" to R.string.pref_reduce_visual_effects,
			"glass_material_preset" to R.string.pref_blur_mode,
			"glass_blur_strength" to R.string.pref_glass_blur_strength,
			"glass_noise_strength" to R.string.pref_glass_noise_strength,
			"glass_immersive_strength" to R.string.pref_glass_immersive_strength,
			"tablet_ui_mode" to R.string.tablet_ui_mode,
			"app_locale" to R.string.language,
			"loading_circle_style" to R.string.pref_loading_circle_style,
			"popup_radius" to R.string.pref_popup_radius,
			"list_mode_2" to R.string.list_mode,
			"grid_size" to R.string.grid_size,
			"quick_filter" to R.string.show_quick_filters,
			"progress_indicators" to R.string.show_reading_indicators,
			"manga_list_badges" to R.string.badges_in_lists,
			"description_collapse" to R.string.collapse_long_description,
			"panorama_enabled" to R.string.pref_panorama_cover,
			"panorama_blur" to R.string.pref_panorama_blur,
			"panorama_transition_intensity" to R.string.pref_panorama_transition_intensity,
			"panorama_extra_height" to R.string.pref_panorama_extra_height,
			"panorama_bottom_gradient_alpha" to R.string.pref_panorama_gradient_alpha,
			"panorama_downsample" to R.string.pref_panorama_downsample,
			"details_panorama_scroll_linked" to R.string.pref_details_panorama_scroll_linked,
			"details_panorama_limit_to_info_card_midpoint" to R.string.pref_details_panorama_limit_to_info_card_midpoint,
			
			"pages_tab" to R.string.show_pages_thumbs,
			"details_translate_button" to R.string.details_translate_button_visible,
			"details_tab" to R.string.default_tab,
			"search_suggest_types" to R.string.search_suggestions,
			"nav_main" to R.string.main_screen_sections,
			"shared_element_transitions" to R.string.shared_element_transitions,
			"nav_pinned" to R.string.pin_navigation_ui,
			"nav_labels" to R.string.show_labels_in_navbar,
			"rail_animation_intensity" to R.string.pref_rail_animation_intensity,
			"nav_floating" to R.string.pref_nav_floating,
			"nav_height" to R.string.pref_nav_height,
			"nav_floating_height" to R.string.pref_nav_floating_height,
			"exit_confirm" to R.string.exit_confirmation,
			"dynamic_shortcuts" to R.string.history_shortcuts,
			"protect_app" to R.string.protect_application,
			"screenshots_policy" to R.string.screenshots_policy
		)
		appearanceKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = appearanceBreadcrumbs,
					destination = SettingsDestination.AppearanceSettings,
				),
			)
		}

		val playbackBreadcrumbs = listOf(context.getString(R.string.playback_settings))
		val playbackKeys = listOf(
			"video_decoder_mode" to R.string.video_decoder_mode,
			"video_renderer_mode" to R.string.video_renderer_mode,
			"video_background" to R.string.video_background,
			"video_mpv_conf_trigger" to R.string.video_mpv_conf,
			"playback_ai_video_settings_entry" to R.string.ai_settings,
			"video_controls_alpha" to R.string.video_controls_alpha,
			"video_gradient_alpha" to R.string.video_gradient_alpha
		)
		playbackKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = playbackBreadcrumbs,
					destination = SettingsDestination.PlaybackSettings,
				),
			)
		}

		val aiBreadcrumbs = listOf(context.getString(R.string.ai_settings))
		val aiKeys = listOf(
			"ai_models" to R.string.reader_translation_manage_ocr_models,
			"ai_api" to R.string.ai_api_settings,
			"ai_translation" to R.string.translation_settings,
			"ai_image" to R.string.ai_image_enhancement_settings,
			"ai_tts" to R.string.tts_settings_title,
			"ai_video" to R.string.ai_video_enhancement_settings,
		)
		aiKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = emptyList(),
					destination = SettingsDestination.AISettings,
				),
			)
		}

		val aiImageKeys = listOf(
			"reader_super_resolution_enabled" to R.string.reader_super_resolution,
			"reader_super_resolution_engine" to R.string.reader_super_resolution_engine,
			"reader_super_resolution_model" to R.string.reader_super_resolution_model,
		)
		aiImageKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = aiBreadcrumbs,
					destination = SettingsDestination.AiImageEnhancementSettings,
				),
			)
		}
		val aiVideoKeys = listOf(
			"video_super_resolution_mode" to R.string.video_super_resolution_mode,
			"video_super_resolution_quality_shader" to R.string.video_super_resolution_submode_quality,
			"video_super_resolution_balanced_shader" to R.string.video_super_resolution_submode_balanced,
			"video_super_resolution_performance_shader" to R.string.video_super_resolution_submode_performance,
			"video_super_resolution_advanced_settings_button" to R.string.video_super_resolution_advanced_settings
		)
		aiVideoKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = listOf(context.getString(R.string.ai_settings)),
					destination = SettingsDestination.AiVideoEnhancementSettings,
				),
			)
		}
		val ttsBreadcrumbs = listOf(context.getString(R.string.ai_settings), context.getString(R.string.tts_settings_title))
		val ttsKeys = listOf(
			"tts_enabled" to R.string.tts_enable,
			"tts_engine_type" to R.string.tts_engine_type,
			"tts_test" to R.string.tts_test,
			"tts_system_voice" to R.string.tts_system_voice,
			"tts_legado_voice" to R.string.tts_legado_voice,
			"tts_import_legado_clipboard" to R.string.tts_legado_import_clipboard,
			"tts_import_legado_url" to R.string.tts_legado_import_url,
			"tts_manage_legado" to R.string.tts_legado_manage_sources,
		)
		ttsKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = ttsBreadcrumbs,
					destination = SettingsDestination.TtsSettings,
				),
			)
		}
		val ocrModelsBreadcrumbs = listOf(context.getString(R.string.ai_settings), context.getString(R.string.reader_translation_ocr_models_title))
		val ocrModelsKeys = listOf(
			"reader_translation_onnx_models" to R.string.reader_translation_onnx_models_title,
			"reader_translation_ocr_detector_models" to R.string.reader_translation_ocr_detector_models_title,
			"reader_translation_ocr_recognizer_models" to R.string.reader_translation_ocr_recognizer_models_title,
			"reader_translation_onnx_bubble_detector_models" to R.string.reader_translation_onnx_bubble_detector_models_title,
			"reader_translation_onnx_super_resolution_models" to R.string.reader_translation_onnx_super_resolution_models_title,
		)
		ocrModelsKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = ocrModelsBreadcrumbs,
					destination = SettingsDestination.OcrModelsSettings,
				),
			)
		}

		val sourcesBreadcrumbs = listOf(context.getString(R.string.remote_sources))
		val sourcesKeys = listOf(
			"sources_sort_order" to R.string.sort_order,
			"show_source_on_cards" to R.string.show_source_on_cards,
			"sources_grid" to R.string.show_in_grid_view,
			"sources_grouped_by_language" to R.string.group_sources_by_language,
			"setup_wizard" to R.string.setup_wizard,
			"remote_sources" to R.string.manage_sources,
			"json_sources" to R.string.json_sources_directory,
			"extensions" to R.string.extensions,
			"jar_priority_order" to R.string.jar_priority_order_title,
			"show_broken_sources" to R.string.show_broken_sources,
			"no_nsfw" to R.string.disable_nsfw,
			"history_exclude_nsfw" to R.string.disable_history_nsfw,
			"favourites_exclude_nsfw" to R.string.disable_favourites_nsfw,
			"feed_exclude_nsfw" to R.string.disable_feed_nsfw,
			"tracker_no_nsfw" to R.string.disable_updates_nsfw,
			"suggestions_exclude_nsfw" to R.string.disable_suggestions_nsfw,
			"incognito_nsfw" to R.string.incognito_for_nsfw,
			"tags_warnings" to R.string.tags_warnings,
			"mirror_switching" to R.string.mirror_switching,
			"handle_links" to R.string.handle_links
		)
		sourcesKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = sourcesBreadcrumbs,
					destination = SettingsDestination.SourcesSettings,
				),
			)
		}

		val readerBreadcrumbs = listOf(context.getString(R.string.reader_settings))
		val readerKeys = listOf(
			"reader_mode" to R.string.default_mode,
			"reader_mode_detect" to R.string.detect_reader_mode,
			"zoom_mode" to R.string.scale_mode,
			"reader_zoom_buttons" to R.string.reader_zoom_buttons,
			"webtoon_zoom" to R.string.webtoon_zoom,
			"webtoon_zoom_out" to R.string.default_webtoon_zoom_out,
			"webtoon_gaps" to R.string.webtoon_gaps,
			"reader_tap_actions" to R.string.reader_actions,
			"reader_ai_settings_entry" to R.string.ai_settings,
			"reader_taps_ltr" to R.string.reader_control_ltr,
			"reader_volume_buttons" to R.string.switch_pages_volume_buttons,
			"reader_navigation_inverted" to R.string.reader_navigation_inverted,
			"reader_animation2" to R.string.pages_animation,
			"webtoon_pull_gesture" to R.string.enable_pull_gesture_title,
			"enhanced_colors" to R.string.enhanced_colors,
			"reader_optimize" to R.string.reader_optimize,
			"reader_reduce_page_preloading" to R.string.reader_reduce_page_preloading,
			"reader_crop" to R.string.crop_pages,
			"reader_fullscreen" to R.string.fullscreen_mode,
			"reader_orientation" to R.string.screen_orientation,
			"reader_screen_on" to R.string.keep_screen_on,
			"reader_multitask" to R.string.reader_multitask,
			"reader_bar" to R.string.reader_info_bar,
			"reader_bar_transparent" to R.string.reader_info_bar_transparent,
			"reader_chapter_toast" to R.string.reader_chapter_toast,
			"reader_background" to R.string.background,
			"pages_numbers" to R.string.show_pages_numbers,
			"pages_preload" to R.string.preload_pages,
			"reader_threads" to R.string.download_threads,
			"reader_prefetch_limit" to R.string.prefetch_limit
		)
		readerKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = readerBreadcrumbs,
					destination = SettingsDestination.ReaderSettings,
				),
			)
		}
		val translationBreadcrumbs = listOf(context.getString(R.string.ai_settings), context.getString(R.string.translation_settings))
		val translationKeys = listOf(
			"reader_translation_source_lang" to R.string.reader_translation_source_lang,
			"reader_translation_target_lang" to R.string.reader_translation_target_lang,
			"reader_translation_paddle_det_model_id" to R.string.reader_translation_ocr_det_model_selection,
			"reader_translation_paddle_official_model_id" to R.string.reader_translation_ocr_recognizer_model_selection,
		)
		translationKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = translationBreadcrumbs,
					destination = SettingsDestination.TranslationSettings,
				),
			)
		}
		val translationApiBreadcrumbs = translationBreadcrumbs + context.getString(R.string.ai_api_settings)
		val translationApiKeys = listOf(
			"reader_translation_api_provider_preset" to R.string.reader_translation_api_provider_preset,
			"reader_translation_api_endpoint" to R.string.reader_translation_api_endpoint,
			"reader_translation_api_key" to R.string.reader_translation_api_key,
			"reader_translation_api_model" to R.string.reader_translation_api_model,
			"reader_translation_api_fetch_models" to R.string.reader_translation_api_models_fetch,
		)
		translationApiKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = translationApiBreadcrumbs,
					destination = SettingsDestination.TranslationApiSettings,
				),
			)
		}
		val networkStorageBreadcrumbs = listOf(context.getString(R.string.network))
		val networkStorageKeys = listOf(
			"prefetch_content" to R.string.prefetch_content,
			"pages_preload" to R.string.preload_pages,
			"proxy" to R.string.proxy,
			"doh" to R.string.dns_over_https,
			"doh_custom_url" to R.string.pref_doh_custom_url,
			"doh_custom_ips" to R.string.pref_doh_custom_ips,
			"images_proxy_2" to R.string.images_proxy_title,
			"github_mirror" to R.string.pref_github_mirror,
			"huggingface_mirror" to R.string.pref_huggingface_mirror,
			"ssl_bypass" to R.string.ignore_ssl_errors,
			"no_offline" to R.string.disable_connectivity_check,
			"adblock" to R.string.adblock
		)
		networkStorageKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = networkStorageBreadcrumbs,
					destination = SettingsDestination.StorageAndNetworkSettings,
				),
			)
		}

		val backupsBreadcrumbs = listOf(context.getString(R.string.users), context.getString(R.string.backup_restore))
		val backupsKeys = listOf(
			"backup_periodic_webdav" to R.string.webdav_integration,
			"backup_periodic_webdav_enabled" to R.string.sync_webdav_enable,
			"backup_periodic_output" to R.string.backups_output_directory,
			"backup_periodic_freq" to R.string.backup_frequency,
			"backup_periodic_trim" to R.string.delete_old_backups,
			"backup_periodic_count" to R.string.max_backups_count,
			"backup_periodic_webdav_server_url" to R.string.webdav_server_url,
			"backup_periodic_webdav_username" to R.string.webdav_username,
			"backup_periodic_webdav_password" to R.string.webdav_password,
			"backup_periodic_webdav_remote_path" to R.string.webdav_remote_path,
			"backup_periodic_webdav_test" to R.string.test_connection,
			"backup_periodic_webdav_upload_now" to R.string.webdav_upload_now,
			"backup_periodic_webdav_restore_now" to R.string.webdav_restore_now,
			"backup_periodic_webdav_auto_restore" to R.string.webdav_auto_restore,
			"backup_periodic_webdav_keep_local_copy" to R.string.webdav_keep_local_copy,
			"backup" to R.string.create_backup,
			"restore" to R.string.restore_backup
		)
		backupsKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = backupsBreadcrumbs,
					destination = SettingsDestination.BackupsSettings,
				),
			)
		}

		val syncBreadcrumbs = listOf(context.getString(R.string.users), context.getString(R.string.sync))
		val syncKeys = listOf(
			"sync_settings" to R.string.sync_settings,
			"sync_server_address" to R.string.server_address,
		)
		syncKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = syncBreadcrumbs,
					destination = SettingsDestination.SyncSettings,
				),
			)
		}

		val cacheLimitsBreadcrumbs = listOf(
			context.getString(R.string.storage_and_network),
			context.getString(R.string.cache_limits),
		)
		val cacheLimitsKeys = listOf(
			"video_cache_mb" to R.string.video_playback_cache_limit,
			"video_proxy_cache_mb" to R.string.video_proxy_cache_limit,
			"video_danmaku_cache_mb" to R.string.danmaku_cache_limit,
			"thumbs_cache_mb" to R.string.thumbnails_cache_limit,
			"favicon_cache_mb" to R.string.favicons_cache_limit,
			"pages_cache_mb" to R.string.pages_cache_limit,
			"novel_cache_mb" to R.string.novel_cache_limit,
			"http_cache_mb_limit" to R.string.network_cache_limit,
			"tts_cache_mb" to R.string.tts_audio_cache_limit,
			"reader_super_resolution_cache_limit" to R.string.reader_super_resolution_cache_limit,
		)
		cacheLimitsKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = cacheLimitsBreadcrumbs,
					destination = SettingsDestination.CacheLimitsSettings,
				),
			)
		}

		val dataCleanupBreadcrumbs = listOf(
			context.getString(R.string.storage_and_network),
			context.getString(R.string.data_removal),
		)
		val dataCleanupKeys = listOf(
			"local_manga_clear" to R.string.clear_local_manga_storage,
			"local_novels_clear" to R.string.clear_local_novel_storage,
			"local_videos_clear" to R.string.clear_local_video_storage,
			"search_history_clear" to R.string.clear_search_history,
			"updates_feed_clear" to R.string.clear_updates_feed,
			"thumbs_cache_clear" to R.string.clear_thumbs_cache,
			"favicons_cache_clear" to R.string.clear_favicons_cache,
			"pages_cache_clear" to R.string.clear_pages_cache,
			"novel_cache_clear" to R.string.clear_novel_cache,
			"video_cache_clear" to R.string.clear_video_cache,
			"video_proxy_cache_clear" to R.string.clear_video_proxy_cache,
			"video_danmaku_cache_clear" to R.string.clear_danmaku_cache,
			"tts_cache_clear" to R.string.clear_tts_audio_cache,
			"sr_cache_clear" to R.string.reader_super_resolution_clear_cache,
			"http_cache_clear" to R.string.clear_network_cache,
			"manga_data_clear" to R.string.clear_database,
			"cookies_clear" to R.string.clear_cookies,
			"webview_clear" to R.string.clear_browser_data,
			"chapters_clear" to R.string.delete_read_chapters,
			"chapters_clear_auto" to R.string.delete_read_chapters_auto
		)
		dataCleanupKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = dataCleanupBreadcrumbs,
					destination = SettingsDestination.DataCleanupSettings,
				),
			)
		}
		val downloadsBreadcrumbs = listOf(context.getString(R.string.downloads))
		val downloadsKeys = listOf(
			"downloads_format" to R.string.preferred_download_format,
			"downloads_align_reader" to R.string.download_align_reader,
			"downloads_auto_retry" to R.string.download_auto_retry,
			"downloads_threads" to R.string.download_threads,
			"downloads_max_active_series" to R.string.download_max_active_series,
			"downloads_request_delay" to R.string.download_request_delay,
			"downloads_retry_count" to R.string.download_retry_count,
			"downloads_retry_delay" to R.string.download_retry_delay,
			"downloads_metered_network" to R.string.download_over_cellular,
			"pages_dir_ask" to R.string.ask_for_dest_dir_every_time
		)
		downloadsKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = downloadsBreadcrumbs,
					destination = SettingsDestination.DownloadsSettings,
				),
			)
		}

		val trackerBreadcrumbs = listOf(context.getString(R.string.check_for_new_chapters))
		val trackerKeys = listOf(
			"tracker_enabled" to R.string.check_new_chapters_title,
			"tracker_wifi" to R.string.only_using_wifi,
			"tracker_freq" to R.string.frequency_of_check,
			"track_sources" to R.string.track_sources,
			"track_categories" to R.string.favourites_categories,
			"notifications_settings" to R.string.notifications_settings,
			"tracker_download" to R.string.download_new_chapters,
			"tracker_debug" to R.string.tracker_debug_info,
			"ignore_dose" to R.string.disable_battery_optimization
		)
		trackerKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = trackerBreadcrumbs,
					destination = SettingsDestination.TrackerSettings,
				),
			)
		}

		val servicesBreadcrumbs = listOf(context.getString(R.string.services))
		val servicesKeys = listOf(
			"suggestions" to R.string.suggestions,
			"related_manga" to R.string.related_manga,
			"stats_on" to R.string.reading_stats,
			"reading_time" to R.string.reading_time_estimation,
			"anilist" to R.string.anilist,
			"kitsu" to R.string.kitsu,
			"mal" to R.string.mal,
			"shikimori" to R.string.shikimori,
			"bangumi" to R.string.bangumi,
			"mangaupdates" to R.string.mangaupdates,
			"discord_rpc" to R.string.discord_rpc
		)
		servicesKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = servicesBreadcrumbs,
					destination = SettingsDestination.ServicesSettings,
				),
			)
		}

		val aboutKeys = listOf(
			"app_version" to R.string.check_for_updates,
			"updates_unstable" to R.string.allow_unstable_updates,
			"changelog" to R.string.changelog,
			"about_help" to R.string.user_manual,
			"about_github" to R.string.source_code,
			"about_donate" to R.string.about_donate,
			"about_app_translation" to R.string.about_app_translation_summary,
			"about_discord" to R.string.about_discord,
			"crash_logs" to R.string.crash_logs
		)
		aboutKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = emptyList(),
					destination = SettingsDestination.AboutSettings,
				),
			)
		}

		val proxyBreadcrumbs = listOf(context.getString(R.string.storage_and_network))
		val proxyKeys = listOf(
			"proxy_type_2" to R.string.type,
			"proxy_address" to R.string.address,
			"proxy_port" to R.string.port,
			"proxy_auth" to R.string.authorization_optional,
			"proxy_login" to R.string.username,
			"proxy_password" to R.string.password,
			"proxy_test" to R.string.test_connection
		)
		proxyKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = proxyBreadcrumbs,
					destination = SettingsDestination.ProxySettings,
				),
			)
		}

		val suggestionsBreadcrumbs = listOf(context.getString(R.string.services))
		val suggestionsKeys = listOf(
			"suggestions" to R.string.suggestions_enable,
			"suggestions_wifi" to R.string.only_using_wifi,
			"suggestions_disabled_sources" to R.string.include_disabled_sources,
			"suggestions_notifications" to R.string.notifications_enable,
			"suggestions_exclude_tags" to R.string.suggestions_excluded_genres,
			"suggestions_preferred_tags" to R.string.suggestions_preferred_genres,
			"track_warning" to R.string.suggestions_info
		)
		suggestionsKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = suggestionsBreadcrumbs,
					destination = SettingsDestination.SuggestionsSettings,
				),
			)
		}

		val discordBreadcrumbs = listOf(context.getString(R.string.services))
		val discordKeys = listOf(
			"discord_rpc" to R.string.discord_rpc,
			"discord_token" to R.string.discord_token,
			"discord_logout" to R.string.logout,
			"discord_rpc_skip_nsfw" to R.string.disable_nsfw
		)
		discordKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = discordBreadcrumbs,
					destination = SettingsDestination.DiscordSettings,
				),
			)
		}

		val notificationBreadcrumbs = listOf(context.getString(R.string.notifications_settings))
		val notificationKeys = listOf(
			"notifications" to R.string.notifications,
			"reader_chapter_toast" to R.string.reader_chapter_toast
		)
		notificationKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = notificationBreadcrumbs,
					destination = SettingsDestination.NotificationSettings,
				),
			)
		}

		val extensionBreadcrumbs = listOf(context.getString(R.string.extensions))
		val extensionKeys = listOf(
			"extensions_update_all" to R.string.update_all_extensions,
			"extensions_manage" to R.string.manage_extension_repositories
		)
		extensionKeys.forEach { (key, titleRes) ->
			result.add(
				SettingsItem(
					key = key,
					title = context.getString(titleRes),
					breadcrumbs = extensionBreadcrumbs,
					destination = SettingsDestination.UnifiedSources(),
				),
			)
		}

		return result
    }
}
