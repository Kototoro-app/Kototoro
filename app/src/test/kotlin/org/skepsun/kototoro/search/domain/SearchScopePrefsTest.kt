package org.skepsun.kototoro.search.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.prefs.AppSettings

class SearchScopePrefsTest {

    private val prefs = InMemorySharedPreferences()
    private val scope = SearchScopePrefs(prefs)

    @Test
    fun `nothing stored means defaults that follow the global preset`() {
        scope.isStored shouldBe false
        scope.loadOrNull() shouldBe null

        val filters = scope.load()
        filters.sourceTypes shouldBe ALL_SOURCE_TYPES
        filters.contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
        filters.pinnedOnly shouldBe false
        filters.hideEmpty shouldBe false
        filters.languagePresetId shouldBe SearchFilters.PRESET_FOLLOW_GLOBAL
    }

    @Test
    fun `a saved scope round-trips, including an explicit All preset`() {
        val filters = SearchFilters(
            sourceTypes = setOf(SourceType.NATIVE, SourceType.MIHON),
            contentKinds = setOf(SearchContentKind.NOVEL),
            pinnedOnly = true,
            hideEmpty = true,
            languagePresetId = SearchFilters.PRESET_ALL,
        )
        scope.save(filters)

        scope.isStored shouldBe true
        scope.load() shouldBe filters
    }

    @Test
    fun `a concrete preset id survives a round-trip`() {
        scope.save(SearchFilters(languagePresetId = 42L))

        scope.load().languagePresetId shouldBe 42L
    }

    @Test
    fun `follow-global is the stored default rather than a pinned preset`() {
        scope.save(SearchFilters())

        prefs.getLong(AppSettings.KEY_SEARCH_SCOPE_LANGUAGE_PRESET_ID, Long.MIN_VALUE) shouldBe
            SearchFilters.PRESET_FOLLOW_GLOBAL
        scope.load().languagePresetId shouldBe SearchFilters.PRESET_FOLLOW_GLOBAL
    }

    @Test
    fun `empty sets are stored as the full set so the scope can never be empty`() {
        scope.save(SearchFilters(sourceTypes = emptySet(), contentKinds = emptySet()))

        val stored = prefs.getStringSet(AppSettings.KEY_SEARCH_SCOPE_SOURCE_TYPES, null).orEmpty()
        stored shouldBe ALL_SOURCE_TYPES.mapTo(mutableSetOf()) { it.name }
        scope.load().sourceTypes shouldBe ALL_SOURCE_TYPES
    }

    @Test
    fun `hide empty migrates from the legacy global switch when nothing was stored yet`() {
        prefs.edit().putBoolean(AppSettings.KEY_SEARCH_HIDE_EMPTY_SOURCES, true).commit()

        scope.load().hideEmpty shouldBe true
    }

    @Test
    fun `the stored scope wins over the legacy switch`() {
        prefs.edit().putBoolean(AppSettings.KEY_SEARCH_HIDE_EMPTY_SOURCES, true).commit()
        scope.save(SearchFilters(hideEmpty = false))

        scope.load().hideEmpty shouldBe false
    }

    @Test
    fun `reset restores every default`() {
        scope.save(
            SearchFilters(
                sourceTypes = setOf(SourceType.JSON_LEGADO),
                contentKinds = setOf(SearchContentKind.VIDEO),
                pinnedOnly = true,
                hideEmpty = true,
                languagePresetId = 7L,
            ),
        )
        scope.reset()

        scope.load() shouldBe SearchFilters()
        scope.load().languagePresetId shouldBe SearchFilters.PRESET_FOLLOW_GLOBAL
    }

    @Test
    fun `a language preset stored under another type is read as follow-global`() {
        // Older or foreign writers may have used a string here; a cast failure must not break the search screen.
        prefs.edit().putString(AppSettings.KEY_SEARCH_SCOPE_LANGUAGE_PRESET_ID, "7").commit()

        scope.load().languagePresetId shouldBe SearchFilters.PRESET_FOLLOW_GLOBAL
    }
}

class SearchFiltersTest {

    @Test
    fun `a fresh filter set follows the global preset and means no restriction`() {
        val filters = SearchFilters()

        filters.languagePresetId shouldBe SearchFilters.PRESET_FOLLOW_GLOBAL
        filters.sourceTypes shouldBe ALL_SOURCE_TYPES
        filters.contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
    }

    @Test
    fun `follow-global resolves to whatever the global browsing preset is at call time`() {
        val filters = SearchFilters()

        filters.resolveLanguagePresetId(5L) shouldBe 5L
        filters.resolveLanguagePresetId(SearchFilters.PRESET_ALL) shouldBe SearchFilters.PRESET_ALL
    }

    @Test
    fun `an explicitly pinned preset ignores the global one`() {
        SearchFilters(languagePresetId = 3L).resolveLanguagePresetId(5L) shouldBe 3L
        SearchFilters(languagePresetId = SearchFilters.PRESET_ALL).resolveLanguagePresetId(5L) shouldBe
            SearchFilters.PRESET_ALL
    }

    @Test
    fun `normalizing keeps follow-global and All but folds unknown negative ids`() {
        SearchFilters(languagePresetId = -7L).normalized().languagePresetId shouldBe
            SearchFilters.PRESET_FOLLOW_GLOBAL
        SearchFilters(languagePresetId = SearchFilters.PRESET_ALL).normalized().languagePresetId shouldBe
            SearchFilters.PRESET_ALL
        SearchFilters(languagePresetId = SearchFilters.PRESET_FOLLOW_GLOBAL).normalized().languagePresetId shouldBe
            SearchFilters.PRESET_FOLLOW_GLOBAL
        SearchFilters(languagePresetId = 8L).normalized().languagePresetId shouldBe 8L
    }

    @Test
    fun `normalizing fills an empty selection back to all`() {
        val normalized = SearchFilters(sourceTypes = emptySet(), contentKinds = emptySet()).normalized()

        normalized.sourceTypes shouldBe ALL_SOURCE_TYPES
        normalized.contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
    }

    @Test
    fun `hide empty does not count as a scope change, the preset does`() {
        val base = SearchFilters()

        base.hasSameSearchScope(base.copy(hideEmpty = true)) shouldBe true
        base.hasSameSearchScope(base.copy(languagePresetId = 4L)) shouldBe false
        base.hasSameSearchScope(base.copy(pinnedOnly = true)) shouldBe false
    }
}
