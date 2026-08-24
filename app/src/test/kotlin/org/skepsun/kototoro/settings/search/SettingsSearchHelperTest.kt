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
		)

		val settings = SettingsSearchHelper(context).inflatePreferences()
			.filter { it.destination == SettingsDestination.AppearanceSettings }

		settings.map { it.key to it.title } shouldContainExactly expectedSettings.map { (key, titleRes) ->
			key to "string-$titleRes"
		}
		settings.map { it.breadcrumbs }.distinct() shouldBe listOf(listOf("string-${R.string.appearance}"))
	}

	@Test
	fun `appearance search results open focused subpages`() {
		val settings = SettingsSearchHelper(context).inflatePreferences().associateBy { it.key }

		settings.getValue("list_mode_2").destination shouldBe SettingsDestination.AppearanceListSettings
		settings.getValue("description_collapse").destination shouldBe
			SettingsDestination.AppearanceDetailsSettings
		settings.getValue("home_hero_mode").destination shouldBe SettingsDestination.AppearanceHomeSettings
		settings.getValue("app_font_preset").destination shouldBe SettingsDestination.AppearanceInterfaceSettings
		settings.getValue("badges_top_left").destination shouldBe SettingsDestination.AppearanceBadgesSettings
		settings.getValue("show_language_preset_filter").destination shouldBe
			SettingsDestination.AppearanceSearchFiltersSettings
	}

	@Test
	fun `glass appearance search index opens glass settings`() {
		val settings = SettingsSearchHelper(context).inflatePreferences()
			.filter { it.destination == SettingsDestination.AppearanceGlassSettings }

		settings.map { it.key to it.title } shouldContainExactly listOf(
			"glass_tuner" to "string-${R.string.appearance_group_glass_tuner}",
			"glass_effect" to "string-${R.string.pref_glass_effect}",
			"glass_immersive_strength" to "string-${R.string.pref_glass_immersive_strength}",
			"reduce_visual_effects" to "string-${R.string.pref_reduce_visual_effects}",
		)
		settings.map { it.breadcrumbs }.distinct() shouldBe listOf(
			listOf(
				"string-${R.string.appearance}",
				"string-${R.string.appearance_group_glass_tuner}",
			),
		)
	}


	@Test
	fun `navigation appearance search index matches visible settings`() {
		val settings = SettingsSearchHelper(context).inflatePreferences()
			.filter { it.destination == SettingsDestination.AppearanceNavigationSettings }

		settings.map { it.key to it.title } shouldContainExactly listOf(
			"nav_main" to "string-${R.string.main_screen_sections}",
			"main_fab" to "string-${R.string.main_screen_fab}",
			"nav_pinned" to "string-${R.string.pin_navigation_ui}",
			"nav_labels" to "string-${R.string.show_labels_in_navbar}",
			"nav_labels_always_visible" to "string-${R.string.pref_nav_labels_always_visible}",
			"nav_floating" to "string-${R.string.pref_nav_floating}",
			"nav_layered_surface" to "string-${R.string.pref_nav_layered_surface}",
			"nav_expressive_pill" to "string-${R.string.pref_nav_expressive_pill}",
			"nav_indicator_full_width" to "string-${R.string.pref_nav_indicator_full_width}",
			"nav_accent_sample_blue" to "string-${R.string.pref_nav_accent_sample_blue}",
			"nav_height" to "string-${R.string.pref_nav_height}",
			"nav_floating_height" to "string-${R.string.pref_nav_floating_height}",
		)
		settings.map { it.breadcrumbs }.distinct() shouldBe listOf(
			listOf(
				"string-${R.string.appearance}",
				"string-${R.string.appearance_navigation_group}",
			),
		)
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
