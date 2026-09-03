package org.skepsun.kototoro.main.ui.welcome

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

class WelcomeDefaultsTest {

    @Test
    fun `no repository is selected by default`() {
        val selected = WelcomeDefaults.defaultRepositories(UnifiedRecommendedRepositories.all)

        selected.shouldBeEmpty()
    }

    @Test
    fun `selected repository kinds are empty when nothing is selected`() {
        val selectedRepositories = WelcomeDefaults.defaultRepositories(UnifiedRecommendedRepositories.all)
        val visuallySelectedKinds = WelcomeDefaults.selectedRepositoryKinds(selectedRepositories)
        val configuredKinds = selectedRepositories.mapTo(LinkedHashSet()) { it.kind }

        visuallySelectedKinds shouldBe configuredKinds
        visuallySelectedKinds shouldBe emptySet<UnifiedSourceKind>()
    }

    @Test
    fun `wizard language catalog comes from configured repository metadata`() {
        val locales = WelcomeDefaults.availableLocales(
            repositoryLanguages = setOf("en", "ja", "zh"),
        )
        val languages = locales.map { it.language }.toSet()

        locales.first() shouldBe Locale.ROOT
        languages shouldContain "en"
        languages shouldContain "ja"
        languages shouldContain "zh"
        languages shouldHaveSize 4
    }

    @Test
    fun `wizard language catalog retains discovered source locales`() {
        val discovered = Locale.forLanguageTag("fil")

        WelcomeDefaults.availableLocales(discovered = listOf(discovered)).map { it.language } shouldContain "fil"
    }

    @Test
    fun `wizard language catalog does not invent ISO languages without repository metadata`() {
        WelcomeDefaults.availableLocales() shouldBe listOf(Locale.ROOT)
    }
}
