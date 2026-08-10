package org.skepsun.kototoro.reader.novel

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan

object NovelTypography {

    private val titleLinePattern = Regex("""^[《【].+[》】]$|^第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇话集].*$""")
    private val westernSpacingPattern = Regex("""([\p{IsHan}])([A-Za-z0-9@#&])|([A-Za-z0-9@#&])([\p{IsHan}])""")

    fun prepareContentText(
        text: String,
        settings: NovelReaderSettings,
        textPaint: TextPaint,
    ): String {
        val normalized = normalizeParagraphs(text)
        val spaced = if (settings.paragraphSpacingLines == 0) normalized else applyParagraphSpacing(normalized, settings)
        val indented = applyParagraphIndent(spaced, settings)
        return optimizeChineseTypography(indented)
    }

    fun applyBilingualSpannable(
        processedText: String,
        translation: NovelChapterTranslation?,
        secondaryColor: Int,
    ): CharSequence {
        if (translation == null ||
            translation.displayMode != NovelTranslationDisplayMode.BILINGUAL ||
            translation.translations.isEmpty()
        ) {
            return styleChapterTitles(processedText, secondaryColor)
        }

        val ssb = SpannableStringBuilder(processedText)
        val smallSize = 0.8f

        for (para in translation.paragraphs) {
            if (para.type != NovelParagraphType.TEXT) continue
            val translated = translation.translations[para.index] ?: continue
            if (translated.isBlank()) continue

            val originalInText = para.originalText
            var searchFrom = 0
            while (searchFrom < ssb.length) {
                val idx = ssb.indexOf(originalInText, searchFrom)
                if (idx < 0) break
                val expectedAfter = idx + originalInText.length
                if (expectedAfter < ssb.length && ssb[expectedAfter] == '\n') {
                    ssb.setSpan(
                        ForegroundColorSpan(secondaryColor),
                        idx,
                        expectedAfter,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    ssb.setSpan(
                        RelativeSizeSpan(smallSize),
                        idx,
                        expectedAfter,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                searchFrom = expectedAfter + 1
                break
            }
        }
        return styleChapterTitles(ssb, secondaryColor)
    }

    fun styleChapterTitles(
        text: CharSequence,
        secondaryColor: Int,
    ): CharSequence {
        val source = if (text is SpannableStringBuilder) text else SpannableStringBuilder(text)
        var cursor = 0
        val raw = source.toString()
        raw.split('\n').forEach { line ->
            val start = cursor
            val end = cursor + line.length
            if (isChapterTitleLine(line)) {
                source.setSpan(
                    RelativeSizeSpan(1.18f),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                source.setSpan(
                    ForegroundColorSpan(secondaryColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                source.setSpan(
                    LeadingMarginSpan.Standard(0, 0),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            cursor = end + 1
        }
        return source
    }

    private fun normalizeParagraphs(text: String): String {
        return text.replace("\r\n", "\n")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n[ \t]+"""), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("""(?m)^([《【].+[》】]|第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇话集].*)$"""), "\n$1\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun applyParagraphSpacing(
        text: String,
        settings: NovelReaderSettings,
    ): String {
        val extraLines = settings.paragraphSpacingLines
        if (extraLines == 0) return text
        val spacer = "\n".repeat(extraLines)
        return text.split(Regex("\\n+")).joinToString(separator = "\n$spacer")
    }

    private fun applyParagraphIndent(
        text: String,
        settings: NovelReaderSettings,
    ): String {
        if (!settings.enableParagraphIndent) return text
        val indent = "　　"
        return text.split('\n').joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                isChapterTitleLine(line.trim()) -> line.trim()
                line.startsWith(indent) -> line
                else -> indent + line.trimStart()
            }
        }
    }

    private fun optimizeChineseTypography(text: String): String {
        return text.lines().joinToString("\n") { line ->
            if (line.isBlank()) {
                line
            } else {
                line.replace(Regex("[ \t]+"), " ")
                    .replace(westernSpacingPattern) { match ->
                        val left = match.groupValues[1].ifBlank { match.groupValues[3] }
                        val right = match.groupValues[2].ifBlank { match.groupValues[4] }
                        "$left $right"
                    }
                    .replace("…… ……", "…………")
                    .replace("。。", "。")
            }
        }
    }

    internal fun isChapterTitleLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.length > 40) return false
        return titleLinePattern.matches(trimmed)
    }
}
