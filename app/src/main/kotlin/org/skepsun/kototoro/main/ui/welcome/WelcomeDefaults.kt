package org.skepsun.kototoro.main.ui.welcome

import java.util.Locale
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.util.ext.sortedWithSafe
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

internal object WelcomeDefaults {

    /**
     * No repository is auto-selected by default. The wizard no longer imports repositories, and
     * Kototoro never curates a default set of third-party repositories (see
     * [UnifiedRecommendedRepositories]); users add their own sources explicitly.
     */
    private val defaultRepositoryNames: Map<UnifiedSourceKind, Set<String>> = emptyMap()

    fun defaultRepositories(
        recommendations: List<UnifiedRecommendedRepository>,
    ): List<UnifiedRecommendedRepository> = recommendations.filter { repository ->
        repository.name in defaultRepositoryNames[repository.kind].orEmpty()
    }

    /** Repository kinds whose entries will be submitted by the configuration action. */
    fun selectedRepositoryKinds(
        selectedRepositories: Collection<UnifiedRecommendedRepository>,
    ): Set<UnifiedSourceKind> = selectedRepositories.mapTo(LinkedHashSet()) { repository ->
        repository.kind
    }

    /** Builds the language catalog from configured repository metadata and already known sources. */
    fun availableLocales(
        repositoryLanguages: Collection<String> = emptyList(),
        discovered: Collection<Locale> = emptyList(),
    ): List<Locale> {
        val byLanguage = LinkedHashMap<String, Locale>()
        repositoryLanguages.asSequence()
            .filter(String::isNotBlank)
            .forEach { language ->
                val locale = Locale.forLanguageTag(language)
                if (locale.language.isNotBlank()) {
                    byLanguage.putIfAbsent(locale.language, locale)
                }
            }
        discovered.asSequence()
            .filter { locale -> locale != Locale.ROOT && locale.language.isNotBlank() }
            .forEach { locale -> byLanguage.putIfAbsent(locale.language, locale) }
        return listOf(Locale.ROOT) + byLanguage.values.sortedWithSafe(LocaleComparator())
    }
}
