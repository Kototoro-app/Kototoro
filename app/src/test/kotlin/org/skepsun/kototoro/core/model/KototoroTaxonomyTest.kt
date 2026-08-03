package org.skepsun.kototoro.core.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.Locale

class KototoroTaxonomyTest : StringSpec({

	"v0.1 contains unique permanent IDs" {
		KototoroTaxonomy.tags.size shouldBe 334
		KototoroTaxonomy.tags.map(TaxonomyTag::id).distinct().size shouldBe KototoroTaxonomy.tags.size
	}

	"one source alias may resolve to multiple standard tags" {
		KototoroTaxonomy.resolve("Reincarnated in Another World")
			.map(TaxonomyTag::id)
			.shouldContainExactlyInAnyOrder(
				"narrative.reincarnation",
				"setting.other-world",
			)
	}

	"source tags do not use fuzzy matching" {
		KototoroTaxonomy.resolve("act") shouldBe emptySet()
	}

	"Futon ecchi tag is a visible standard alias" {
		val ecchi = KototoroTaxonomy.find("genre.ecchi")

		ecchi?.chineseLabel shouldBe "轻度情色"
		ecchi?.aliases?.contains("ecc") shouldBe true
		KototoroTaxonomy.knownSourceTags.contains("ecchi") shouldBe true
	}

	"Futon multilingual aliases resolve across sources" {
		KototoroTaxonomy.resolve("reencarnacao").single().id shouldBe "narrative.reincarnation"
		KototoroTaxonomy.resolve("artes marciais").single().id shouldBe "genre.martial-arts"
		KototoroTaxonomy.resolve("zumbi").single().id shouldBe "element.zombies"
	}

	"abbreviations and community synonyms share one permanent ID" {
		listOf("ecc", "ecchi").forEach { alias ->
			KototoroTaxonomy.resolve(alias).single().id shouldBe "genre.ecchi"
		}
		listOf("BL", "boylove", "boys love", "yaoi", "soft yaoi", "shounen ai").forEach { alias ->
			KototoroTaxonomy.resolve(alias).single().id shouldBe "relationship.boys-love"
		}
		listOf("GL", "girl love", "girls love", "yuri", "soft yuri", "shoujo ai").forEach { alias ->
			KototoroTaxonomy.resolve(alias).single().id shouldBe "relationship.girls-love"
		}
	}

	"standard tags expose simplified and traditional Chinese variants" {
		val scienceFiction = KototoroTaxonomy.find("genre.science-fiction")!!

		scienceFiction.displayName(Locale.SIMPLIFIED_CHINESE) shouldBe "科幻"
		scienceFiction.displayName(Locale.TRADITIONAL_CHINESE) shouldBe "科幻"
		KototoroTaxonomy.resolve("輕度情色").single().id shouldBe "genre.ecchi"
		KototoroTaxonomy.search(KototoroTaxonomy.find("theme.revenge")!!, "復仇") shouldBe true
	}

	"semantic Futon tags reuse localized standard tags" {
		KototoroTaxonomy.resolve("gender bender").single().id shouldBe "narrative.gender-swap"
		KototoroTaxonomy.resolve("heartwarming").single().id shouldBe "tone.wholesome"
		KototoroTaxonomy.resolve("wuxia")
			.map(TaxonomyTag::id)
			.shouldContainExactlyInAnyOrder("genre.martial-arts", "setting.martial-arts-world")
	}
})
