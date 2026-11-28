package org.skepsun.kototoro.details.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchFilters

/**
 * Tier 3 of the filter state: the detail page's reading-source scope. These tests pin down that the panel is a plain
 * value that only ever changes locally, which is what keeps switching source on the detail page from rewriting the
 * search page's filters or the global browsing preset.
 */
class ReadingSearchScopeFilterUiStateTest {

    @Test
    fun `a fresh panel restricts nothing and follows the global preset`() {
        val state = ReadingSearchScopeFilterUiState()

        state.sourceTypes shouldBe ALL_SOURCE_TYPES
        state.contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
        state.pinnedOnly shouldBe false
        state.hideEmpty shouldBe false
        state.languagePresetId shouldBe null
        state.appliedFilterCount shouldBe 0
        state.resolveLanguagePresetId(5L) shouldBe 5L
    }

    @Test
    fun `pinning a preset stays local and overrides the global one only for this panel`() {
        val pinned = ReadingSearchScopeFilterUiState().withLanguagePreset(9L)

        pinned.languagePresetId shouldBe 9L
        pinned.resolveLanguagePresetId(5L) shouldBe 9L
        pinned.appliedFilterCount shouldBe 1
    }

    @Test
    fun `choosing All is an explicit choice, not a return to following the global preset`() {
        val all = ReadingSearchScopeFilterUiState().withLanguagePreset(SearchFilters.PRESET_ALL)

        all.languagePresetId shouldBe SearchFilters.PRESET_ALL
        all.resolveLanguagePresetId(5L) shouldBe SearchFilters.PRESET_ALL
    }

    @Test
    fun `following the global preset again clears the local override`() {
        ReadingSearchScopeFilterUiState()
            .withLanguagePreset(9L)
            .withLanguagePreset(SearchFilters.PRESET_FOLLOW_GLOBAL)
            .languagePresetId shouldBe null

        ReadingSearchScopeFilterUiState().withLanguagePreset(9L).withLanguagePreset(null).languagePresetId shouldBe null
    }

    @Test
    fun `toggling every source type off falls back to all instead of an empty scope`() {
        ALL_SOURCE_TYPES.fold(ReadingSearchScopeFilterUiState()) { state, type ->
            state.withSourceTypeToggled(type)
        }.sourceTypes shouldBe ALL_SOURCE_TYPES
    }

    @Test
    fun `toggling one source type off narrows the panel without touching the kind or the preset`() {
        val state = ReadingSearchScopeFilterUiState()
            .withSourceTypeToggled(SourceType.MIHON)

        state.sourceTypes shouldBe (ALL_SOURCE_TYPES - SourceType.MIHON)
        state.contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
        state.languagePresetId shouldBe null
    }

    @Test
    fun `toggling every content kind off falls back to all`() {
        ALL_SEARCH_CONTENT_KINDS.fold(ReadingSearchScopeFilterUiState()) { state, kind ->
            state.withContentKindToggled(kind)
        }.contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
    }

    @Test
    fun `hide empty and pinned only are local flags`() {
        val state = ReadingSearchScopeFilterUiState()
            .withHideEmpty(true)
            .withPinnedOnly(true)

        state.hideEmpty shouldBe true
        state.pinnedOnly shouldBe true
        state.appliedFilterCount shouldBe 2
    }

    @Test
    fun `a work's kind narrows the panel independently of the persisted search kinds`() {
        val state = ReadingSearchScopeFilterUiState().copy(contentKinds = setOf(SearchContentKind.NOVEL))

        state.contentKinds shouldBe setOf(SearchContentKind.NOVEL)
        ReadingSearchScopeFilterUiState().contentKinds shouldBe ALL_SEARCH_CONTENT_KINDS
    }
}
