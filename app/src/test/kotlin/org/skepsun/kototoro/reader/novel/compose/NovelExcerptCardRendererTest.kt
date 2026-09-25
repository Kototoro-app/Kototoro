package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

class NovelExcerptCardRendererTest {

    @Test
    fun `date helper formats chinese year month day correctly`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 7)
        }
        val formatted = NovelExcerptDateHelper.formatChineseDate(cal.timeInMillis)
        assertEquals("二〇二六年 · 九月 · 七日", formatted)
    }

    @Test
    fun `date helper calculates 24 solar terms accurately`() {
        // September 7 is White Dew (白露)
        val termSep7 = NovelExcerptDateHelper.getSolarTerm(Calendar.SEPTEMBER, 7)
        assertEquals("白露", termSep7)

        // September 23 is Autumn Equinox (秋分)
        val termSep23 = NovelExcerptDateHelper.getSolarTerm(Calendar.SEPTEMBER, 23)
        assertEquals("秋分", termSep23)
    }

    @Test
    fun `splitTitleForVertical splits long titles into max 6 character columns`() {
        val shortTitle = NovelExcerptDateHelper.splitTitleForVertical("三体")
        assertEquals(listOf("三体"), shortTitle)

        val sixCharTitle = NovelExcerptDateHelper.splitTitleForVertical("平凡的世界")
        assertEquals(listOf("平凡的世界"), sixCharTitle)

        val kantTitle = NovelExcerptDateHelper.splitTitleForVertical("康德三大批判合集")
        assertEquals(listOf("康德三大批判", "合集"), kantTitle)

        val titleWithBookMarks = NovelExcerptDateHelper.splitTitleForVertical("《百年孤独》")
        assertEquals(listOf("百年孤独"), titleWithBookMarks)
    }

    @Test
    fun `computeLayout computes valid layouts for all six templates`() {
        val data = NovelExcerptData(
            selectedText = "借助于外感官",
            bookTitle = "康德三大批判合集",
            chapterTitle = "第一章 论空间",
            author = "康德",
            userNickname = "书友",
            note = "时空的感性纯形式分析",
        )

        NovelExcerptTemplate.entries.forEach { template ->
            val config = NovelExcerptConfiguration(template = template)
            val layout = NovelExcerptCardRenderer.computeLayout(
                context = null,
                data = data,
                configuration = config,
            )

            assertEquals(1080, layout.width)
            assertTrue(layout.height >= 1280, "Height for $template should be >= 1280, was ${layout.height}")
            assertEquals("康德三大批判合集", layout.bookTitle)
            assertEquals("第一章 论空间", layout.chapterTitle)
            assertEquals("康德", layout.author)
            assertEquals("书友", layout.userNickname)
            assertTrue(layout.textLines.isNotEmpty())
            assertTrue(layout.noteLines.isNotEmpty())
        }
    }

    @Test
    fun `calendar template uses centered quote start and distinct height bounds`() {
        val data = NovelExcerptData(
            selectedText = "自由是道德律的存在的理由。",
            bookTitle = "实践理性批判",
            chapterTitle = "序言",
            author = "康德",
        )
        val config = NovelExcerptConfiguration(template = NovelExcerptTemplate.CALENDAR)
        val layout = NovelExcerptCardRenderer.computeLayout(null, data, config)

        assertEquals(450f, layout.quoteStartY)
        assertTrue(layout.height >= 1380)
    }

    @Test
    fun `computeLayout with image and text computes valid layout and places imageRect correctly`() {
        val mockBitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true) {
            io.mockk.every { width } returns 1600
            io.mockk.every { height } returns 900
        }
        val data = NovelExcerptData(
            selectedText = "人类的悲欢并不相通",
            bookTitle = "鲁迅文集",
            chapterTitle = "第一章",
            author = "鲁迅",
            imageBitmap = mockBitmap,
        )
        NovelExcerptTemplate.entries.forEach { template ->
            val config = NovelExcerptConfiguration(template = template)
            val layout = NovelExcerptCardRenderer.computeLayout(null, data, config)

            org.junit.jupiter.api.Assertions.assertNotNull(layout.imageRect)
            assertEquals(1080, layout.width)
            assertTrue(layout.height >= 1280)
            assertTrue(layout.textLines.isNotEmpty())
        }
    }

    @Test
    fun `computeLayout with image only and empty selectedText computes valid layout`() {
        val mockBitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true) {
            io.mockk.every { width } returns 800
            io.mockk.every { height } returns 1200
        }
        val data = NovelExcerptData(
            selectedText = "",
            bookTitle = "海贼王",
            chapterTitle = "第1话",
            author = "尾田荣一郎",
            imageBitmap = mockBitmap,
            note = "草帽一伙出航",
        )
        val config = NovelExcerptConfiguration(template = NovelExcerptTemplate.CLASSIC)
        val layout = NovelExcerptCardRenderer.computeLayout(null, data, config)

        org.junit.jupiter.api.Assertions.assertNotNull(layout.imageRect)
        assertTrue(layout.textLines.isEmpty())
        assertTrue(layout.noteLines.isNotEmpty())
        assertTrue(layout.height >= 1280)
    }

    @Test
    fun `calculateTotalPages with image adjusts linesPerPage to 6`() {
        val dataNoImage = NovelExcerptData(
            selectedText = "1\n2\n3\n4\n5\n6\n7\n8",
            bookTitle = "测试",
            chapterTitle = "第1章",
        )
        val pagesNoImage = NovelExcerptCardRenderer.calculateTotalPages(null, dataNoImage, org.skepsun.kototoro.reader.novel.NovelReaderFont.SYSTEM_SERIF)
        assertEquals(1, pagesNoImage) // 8 lines < 12 lines per page

        val dataWithImage = dataNoImage.copy(imageUri = "file:///test.jpg")
        val pagesWithImage = NovelExcerptCardRenderer.calculateTotalPages(null, dataWithImage, org.skepsun.kototoro.reader.novel.NovelReaderFont.SYSTEM_SERIF)
        assertEquals(2, pagesWithImage) // 8 lines with 6 per page = 2 pages
    }

    @Test
    fun `computeLayout respects non-Chinese locale for date weekday and book title`() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            val cal = Calendar.getInstance().apply {
                set(2026, Calendar.SEPTEMBER, 23, 12, 0, 0)
            }
            val data = NovelExcerptData(
                selectedText = "Test excerpt text",
                bookTitle = "Pride and Prejudice",
                chapterTitle = "Chapter 1",
                createdAtMillis = cal.timeInMillis,
            )
            val layout = NovelExcerptCardRenderer.computeLayout(
                context = null,
                data = data,
                configuration = NovelExcerptConfiguration(template = NovelExcerptTemplate.CALENDAR),
            )
            assertEquals("Wednesday", layout.weekday)
            assertEquals("", layout.solarTerm)
            assertEquals("Pride and Prejudice", layout.formattedBookTitle)
            assertTrue(layout.dateMonthYear.contains("SEPTEMBER"))
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `computeLayout respects Chinese locale for date weekday solar term and book title`() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
            val cal = Calendar.getInstance().apply {
                set(2026, Calendar.SEPTEMBER, 23, 12, 0, 0)
            }
            val data = NovelExcerptData(
                selectedText = "测试摘录内容",
                bookTitle = "红楼梦",
                chapterTitle = "第一回",
                createdAtMillis = cal.timeInMillis,
            )
            val layout = NovelExcerptCardRenderer.computeLayout(
                context = null,
                data = data,
                configuration = NovelExcerptConfiguration(template = NovelExcerptTemplate.CALENDAR),
            )
            assertEquals("星期三", layout.weekday)
            assertEquals("秋分", layout.solarTerm)
            assertEquals("《红楼梦》", layout.formattedBookTitle)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }
}

