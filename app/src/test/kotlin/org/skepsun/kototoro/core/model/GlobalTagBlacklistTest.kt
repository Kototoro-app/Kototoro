package org.skepsun.kototoro.core.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag

class GlobalTagBlacklistTest : StringSpec({

	"matching ignores surrounding whitespace and letter case" {
		val blacklist = GlobalTagBlacklist(setOf("  Dark Fantasy "))

		(contentWithTags("dark fantasy") in blacklist) shouldBe true
	}

	"matching is exact instead of fuzzy" {
		val blacklist = GlobalTagBlacklist(setOf("act"))

		(contentWithTags("action") in blacklist) shouldBe false
	}

	"stable taxonomy ID matches localized and aliased source tags" {
		val blacklist = GlobalTagBlacklist(setOf("setting.other-world"))

		(contentWithTags("Isekai") in blacklist) shouldBe true
		(contentWithTags("异世界") in blacklist) shouldBe true
	}

	"raw selection remains an exact fallback" {
		val blacklist = GlobalTagBlacklist(setOf(GlobalTagBlacklist.rawTagKey("Custom Trope")))

		(contentWithTags("custom trope") in blacklist) shouldBe true
		(contentWithTags("custom tropes") in blacklist) shouldBe false
	}

	"filter removes content containing any blacklisted tag" {
		val allowed = contentWithTags("comedy", id = 1L)
		val blocked = contentWithTags("gore", id = 2L)

		GlobalTagBlacklist(setOf("gore"))
			.filter(listOf(allowed, blocked))
			.shouldContainExactly(allowed)
	}
})

private fun contentWithTags(vararg tags: String, id: Long = 1L) = Content(
	id = id,
	title = "Content $id",
	altTitles = emptySet(),
	url = "/$id",
	publicUrl = "https://example.com/$id",
	rating = 0f,
	contentRating = null,
	coverUrl = null,
	tags = tags.map { title ->
		ContentTag(title = title, key = title, source = TestContentSource)
	}.toSet(),
	state = null,
	authors = emptySet(),
	source = TestContentSource,
)
