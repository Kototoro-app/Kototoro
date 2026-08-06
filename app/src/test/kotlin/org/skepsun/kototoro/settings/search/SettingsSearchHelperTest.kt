package org.skepsun.kototoro.settings.search

import android.content.Context
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.settings.SettingsDestination

class SettingsSearchHelperTest {
	private val context = mockk<Context> {
		every { getString(any()) } answers { "string-${firstArg<Int>()}" }
	}

	@Test
	fun `appearance search index matches visible settings`() {
		val expectedSettings = listOf(
			"interface_style" to R.string.interface_style,
			"color_theme" to R.string.color_theme,
			"theme" to R.string.appearance_mode,
			"background_style" to R.string.background_style,
			"amoled_theme" to R.string.black_dark_theme,
			"app_font_preset" to R.string.pref_app_font_preset,
			"tablet_ui_mode" to R.string.tablet_ui_mode,
			"app_locale" to R.string.language,
			"loading_circle_style" to R.string.pref_loading_circle_style,
			"popup_radius" to R.string.pref_popup_radius,
			"list_mode_2" to R.string.list_mode,
			"grid_size" to R.string.grid_size,
			"rail_animation_intensity" to R.string.pref_rail_animation_intensity,
			"quick_filter" to R.string.show_quick_filters,
			"progress_indicators" to R.string.show_reading_indicators,
			"badges_top_left" to R.string.badge_top_left,
			"badges_top_right" to R.string.badge_top_right,
			"badges_bottom_left" to R.string.badge_bottom_left,
			"badges_bottom_right" to R.string.badge_bottom_right,
			"description_collapse" to R.string.collapse_long_description,
			"panorama_enabled" to R.string.pref_panorama_cover,
			"pages_tab" to R.string.show_pages_thumbs,
			"details_translate_button" to R.string.details_translate_button_visible,
			"modern_details_dock" to R.string.modern_details_dock,
			"details_tab" to R.string.default_tab,
			"search_suggest_types" to R.string.search_suggestions,
			"nav_main" to R.string.main_screen_sections,
			"shared_element_transitions" to R.string.shared_element_transitions,
			"show_language_preset_filter" to R.string.show_language_preset_filter,
			"hidden_language_preset" to R.string.fixed_language_preset,
			"show_content_type_filter" to R.string.show_content_type_filter,
			"hidden_content_type" to R.string.fixed_content_type,
			"show_source_tag_filter" to R.string.show_source_tag_filter,
			"hidden_source_tag" to R.string.fixed_source_tag,
			"main_fab" to R.string.main_screen_fab,
			"nav_pinned" to R.string.pin_navigation_ui,
			"nav_labels" to R.string.show_labels_in_navbar,
			"nav_floating" to R.string.pref_nav_floating,
			"nav_expressive_pill" to R.string.pref_nav_expressive_pill,
			"nav_height" to R.string.pref_nav_height,
			"nav_floating_height" to R.string.pref_nav_floating_height,
			"exit_confirm" to R.string.exit_confirmation,
			"dynamic_shortcuts" to R.string.history_shortcuts,
			"protect_app" to R.string.protect_application,
			"screenshots_policy" to R.string.screenshots_policy,
		)

		val settings = SettingsSearchHelper(context).inflatePreferences()
			.filter { it.destination == SettingsDestination.AppearanceSettings }

		settings.map { it.key to it.title } shouldContainExactly expectedSettings.map { (key, titleRes) ->
			key to "string-$titleRes"
		}
		settings.map { it.breadcrumbs }.distinct() shouldBe listOf(listOf("string-${R.string.appearance}"))
	}

	@Test
	fun `panorama search index matches visible settings`() {
		val settings = SettingsSearchHelper(context).inflatePreferences()
			.filter { it.destination == SettingsDestination.PanoramaSettings }

		settings.map { it.key to it.title } shouldContainExactly listOf(
			"panorama_layout_mode" to "string-${R.string.panorama_settings_layout_mode}",
			"panorama_style" to "string-${R.string.panorama_settings_style}",
			"details_panorama_scroll_linked" to "string-${R.string.pref_details_panorama_scroll_linked}",
			"panorama_animation_enabled" to "string-${R.string.pref_panorama_animation}",
			"panorama_blur" to "string-${R.string.pref_panorama_blur}",
			"panorama_top_opacity" to "string-${R.string.pref_panorama_top_opacity}",
			"panorama_transition_intensity" to "string-${R.string.pref_panorama_transition_intensity}",
		)
		settings.map { it.breadcrumbs }.distinct() shouldBe listOf(
			listOf(
				"string-${R.string.appearance}",
				"string-${R.string.panorama_settings_title}",
			),
		)
	}

	@Test
	fun `space settings are included in search index`() {
		val settings = SettingsSearchHelper(context).inflatePreferences()
			.filter { it.destination == SettingsDestination.SpacesSettings }

		settings.map { it.key to it.title } shouldContainExactly listOf(
			"spaces" to "string-${R.string.spaces}",
			"entity_space_enabled" to "string-${R.string.spaces_enabled}",
			"entity_space_switcher_position" to "string-${R.string.space_switcher_position}",
			"add_custom_space" to "string-${R.string.add_custom_space}",
		)
		settings.map { it.breadcrumbs } shouldContainExactly listOf(
			listOf("string-${R.string.users}"),
			listOf("string-${R.string.users}", "string-${R.string.spaces}"),
			listOf("string-${R.string.users}", "string-${R.string.spaces}"),
			listOf("string-${R.string.users}", "string-${R.string.spaces}"),
		)
	}
}
