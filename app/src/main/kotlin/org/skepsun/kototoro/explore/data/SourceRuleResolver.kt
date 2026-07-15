package org.skepsun.kototoro.explore.data

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.parsers.model.ContentType

data class SourceRule(
    val languages: Set<String> = emptySet(),
    val contentTypes: Set<ContentType> = emptySet(),
    val sourceTypes: Set<SourceType> = emptySet(),
)

@Singleton
class SourceRuleResolver @Inject constructor(
    private val sourcesRepository: ContentSourcesRepository,
    private val sourceTypeIdentifier: SourceTypeIdentifier,
) {

    fun observeResolvedSourceNames(rule: SourceRule): Flow<Set<String>> = sourcesRepository
        .observeEnabledSources()
        .map { resolveSourceNames(rule, it) }
        .distinctUntilChanged()

    fun resolveCurrentSourceNames(rule: SourceRule): Set<String> {
        return resolveSourceNames(rule, sourcesRepository.observeEnabledSources().value)
    }

    fun resolveSourceNames(rule: SourceRule, sources: List<ContentSourceInfo>): Set<String> {
        val languages = rule.languages.normalizeLanguages()
        return sources.asSequence()
            .filter { source ->
                languages.isEmpty() || source.getLocale()?.language?.lowercase(Locale.ROOT) in languages
            }
            .filter { source ->
                rule.contentTypes.isEmpty() || source.getContentType() in rule.contentTypes
            }
            .filter { source ->
                rule.sourceTypes.isEmpty() || sourceTypeIdentifier.getSourceType(source.name) in rule.sourceTypes
            }
            .mapTo(LinkedHashSet()) { it.name }
    }
}

private fun Set<String>.normalizeLanguages(): Set<String> = mapNotNullTo(LinkedHashSet()) {
    it.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
}
