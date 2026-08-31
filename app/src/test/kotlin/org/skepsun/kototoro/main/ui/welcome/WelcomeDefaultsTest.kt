package org.skepsun.kototoro.main.ui.welcome

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

class WelcomeDefaultsTest {

    @Test
    fun `wizard selects the intended repositories for every supported ecosystem`() {
        val selected = WelcomeDefaults.defaultRepositories(UnifiedRecommendedRepositories.all)
        val selectedNames = selected.groupBy({ it.kind }, { it.name })

        selectedNames[UnifiedSourceKind.JAR] shouldBe listOf("Kototoro Parsers", "Redo Parsers")
        selectedNames[UnifiedSourceKind.MIHON] shouldBe listOf("Keiyoushi")
        selectedNames[UnifiedSourceKind.ANIYOMI] shouldBe listOf("Yuzono Anime Repo")
        selectedNames[UnifiedSourceKind.IREADER] shouldBe listOf("IReader Official")
        selectedNames[UnifiedSourceKind.LEGADO] shouldBe listOf("XIU2 Yuedu")
        selectedNames[UnifiedSourceKind.TVBOX] shouldBe listOf("Qiqi TVBox")
        selectedNames[UnifiedSourceKind.LNREADER] shouldBe listOf("LNReader Official")
        selectedNames[UnifiedSourceKind.TSUNDOKU] shouldBe listOf("NovelSourcery (Tsundoku novels)")
        selectedNames[UnifiedSourceKind.CLOUDSTREAM] shouldBe listOf("Phisher Repo")
        selected shouldHaveSize 10
    }

    @Test
    fun `repository kind chips reflect repositories that will actually be configured`() {
        val selectedRepositories = WelcomeDefaults.defaultRepositories(UnifiedRecommendedRepositories.all)
        val visuallySelectedKinds = WelcomeDefaults.selectedRepositoryKinds(selectedRepositories)
        val configuredKinds = selectedRepositories.mapTo(LinkedHashSet()) { it.kind }

        visuallySelectedKinds shouldBe configuredKinds
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
