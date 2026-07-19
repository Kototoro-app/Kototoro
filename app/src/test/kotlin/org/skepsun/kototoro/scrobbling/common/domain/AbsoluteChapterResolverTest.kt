package org.skepsun.kototoro.scrobbling.common.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.ContentChapter

class AbsoluteChapterResolverTest : StringSpec({

	fun createChapter(
		id: Long,
		number: Float,
		title: String?,
		branch: String? = "default"
	): ContentChapter {
		return ContentChapter(
			id = id,
			title = title,
			number = number,
			volume = 0,
			url = "http://example.com/chapter/$id",
			scanlator = null,
			uploadDate = 0L,
			branch = branch,
			source = TestContentSource
		)
	}

	"should resolve target chapter number directly when sequential and no resets" {
		val ch1 = createChapter(1L, 1f, "Chapter 1")
		val ch2 = createChapter(2L, 2f, "Chapter 2")
		val ch3 = createChapter(3L, 3f, "Chapter 3")
		val chapters = listOf(ch1, ch2, ch3)

		resolveAbsoluteChapterNumber(chapters, ch1) shouldBe 1
		resolveAbsoluteChapterNumber(chapters, ch2) shouldBe 2
		resolveAbsoluteChapterNumber(chapters, ch3) shouldBe 3
	}

	"should resolve target chapter number when list is in reverse order (newest first)" {
		val ch1 = createChapter(1L, 1f, "Chapter 1")
		val ch2 = createChapter(2L, 2f, "Chapter 2")
		val ch3 = createChapter(3L, 3f, "Chapter 3")
		val chapters = listOf(ch3, ch2, ch1)

		resolveAbsoluteChapterNumber(chapters, ch1) shouldBe 1
		resolveAbsoluteChapterNumber(chapters, ch2) shouldBe 2
		resolveAbsoluteChapterNumber(chapters, ch3) shouldBe 3
	}

	"should compute correct offset on numeric reset (Season 1 ends, Season 2 starts at 1)" {
		val s1c1 = createChapter(1L, 1f, "Chapter 1")
		val s1c2 = createChapter(2L, 2f, "Chapter 2") // Season 1 ends at 2
		val s2c1 = createChapter(3L, 1f, "Chapter 1") // Season 2 starts at 1
		val s2c2 = createChapter(4L, 2f, "Chapter 2")
		val chapters = listOf(s1c1, s1c2, s2c1, s2c2)

		resolveAbsoluteChapterNumber(chapters, s1c1) shouldBe 1
		resolveAbsoluteChapterNumber(chapters, s1c2) shouldBe 2
		resolveAbsoluteChapterNumber(chapters, s2c1) shouldBe 3
		resolveAbsoluteChapterNumber(chapters, s2c2) shouldBe 4
	}

	"should compute correct offset on explicit season title change" {
		val s1c1 = createChapter(1L, 1f, "S1 - Chapter 1")
		val s1c2 = createChapter(2L, 82f, "S1 - Chapter 82")
		val s2c1 = createChapter(3L, 1f, "S2 - Chapter 1")
		val s2c2 = createChapter(4L, 2f, "S2 - Chapter 2")
		val chapters = listOf(s1c1, s1c2, s2c1, s2c2)

		resolveAbsoluteChapterNumber(chapters, s1c1) shouldBe 1
		resolveAbsoluteChapterNumber(chapters, s1c2) shouldBe 82
		resolveAbsoluteChapterNumber(chapters, s2c1) shouldBe 83
		resolveAbsoluteChapterNumber(chapters, s2c2) shouldBe 84
	}

	"should not trigger false reset on out-of-order bonus chapter" {
		val ch1 = createChapter(1L, 1f, "Chapter 1")
		val ch2 = createChapter(2L, 2f, "Chapter 2")
		val chBonus = createChapter(3L, 1.5f, "Chapter 1.5") // Jumps back from 2 to 1.5, but next is 3
		val ch3 = createChapter(4L, 3f, "Chapter 3")
		val chapters = listOf(ch1, ch2, chBonus, ch3)

		resolveAbsoluteChapterNumber(chapters, ch1) shouldBe 1
		resolveAbsoluteChapterNumber(chapters, ch2) shouldBe 2
		resolveAbsoluteChapterNumber(chapters, chBonus) shouldBe 1 // toInt() of 1.5f is 1
		resolveAbsoluteChapterNumber(chapters, ch3) shouldBe 3
	}

	"should resolve using simple index fallback if chapter numbers are 0 or missing" {
		val ch1 = createChapter(1L, 0f, "Intro")
		val ch2 = createChapter(2L, 0f, "Middle")
		val ch3 = createChapter(3L, 0f, "Outro")
		val chapters = listOf(ch1, ch2, ch3)

		resolveAbsoluteChapterNumber(chapters, ch1) shouldBe 1
		resolveAbsoluteChapterNumber(chapters, ch2) shouldBe 2
		resolveAbsoluteChapterNumber(chapters, ch3) shouldBe 3
	}
})
