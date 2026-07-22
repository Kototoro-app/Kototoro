package org.skepsun.kototoro.scrobbling.mangaupdates.data

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.json.JSONObject

class MangaUpdatesRatingTest : StringSpec({

	"converts local normalized rating to MangaUpdates score" {
		toMangaUpdatesRating(0.8f) shouldBe 8
		toMangaUpdatesRating(1f) shouldBe 10
		toMangaUpdatesRating(0f) shouldBe 0
	}

	"normalizes remote rating from the ten point scale" {
		normalizeMangaUpdatesRating(8.0) shouldBe 0.8f
		normalizeMangaUpdatesRating(0.0) shouldBe 0f
		normalizeMangaUpdatesRating(10.0) shouldBe 1f
	}

	"preserves an absent remote rating instead of treating it as a score" {
		parseMangaUpdatesUserRating(JSONObject("{\"rating\":8}")) shouldBe 0.8f
		parseMangaUpdatesUserRating(JSONObject()) shouldBe null
		parseMangaUpdatesUserRating(JSONObject("{\"rating\":null}")) shouldBe null
		parseMangaUpdatesUserRating(JSONObject("{\"rating\":0}")) shouldBe 0f
	}
})
