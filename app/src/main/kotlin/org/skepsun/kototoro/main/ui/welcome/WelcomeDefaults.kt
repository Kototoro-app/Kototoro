package org.skepsun.kototoro.main.ui.welcome

import java.util.Locale
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.util.ext.sortedWithSafe
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

internal object WelcomeDefaults {

    private val defaultRepositoryNames = mapOf(
        UnifiedSourceKind.JAR to setOf("Kototoro Parsers", "Redo Parsers"),
        UnifiedSourceKind.MIHON to setOf("Keiyoushi"),
        UnifiedSourceKind.ANIYOMI to setOf("Yuzono Anime Repo"),
        UnifiedSourceKind.IREADER to setOf("IReader Official"),
        UnifiedSourceKind.LEGADO to setOf("XIU2 Yuedu"),
        UnifiedSourceKind.TVBOX to setOf("Qiqi TVBox"),
        UnifiedSourceKind.LNREADER to setOf("LNReader Official"),
        UnifiedSourceKind.TSUNDOKU to setOf("NovelSourcery (Tsundoku novels)"),
        UnifiedSourceKind.CLOUDSTREAM to setOf("Phisher Repo"),
    )

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
