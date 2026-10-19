package org.skepsun.kototoro.reader.novel

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.getSafeFloat
import kotlin.math.roundToInt

/**
 * 小说阅读器设置
 */
data class NovelReaderSettings(
    val fontSizeSp: Float = NovelReaderLimits.DEFAULT_FONT_SIZE,
    val font: NovelReaderFont = NovelReaderFont.SYSTEM_SERIF,
    val lineSpacing: Float = NovelReaderLimits.DEFAULT_LINE_SPACING,
    val paragraphSpacing: Float = 0f,
    val marginHorizontal: Int = NovelReaderLimits.DEFAULT_MARGIN,
    val marginVertical: Int = NovelReaderLimits.DEFAULT_MARGIN,
    val themePreset: NovelReaderThemePreset = NovelReaderThemePreset.PAPER,
    val readingMode: ReadingMode = ReadingMode.PAGED,
    val pageTurnAnimation: NovelPageTurnAnimation = NovelPageTurnAnimation.SLIDE,
    val textDirection: TextDirection = TextDirection.AUTO,
    val enableDualPage: Boolean = true,
    val enableFullscreen: Boolean = false,
    val showReadingStatus: Boolean = true,  // 显示阅读状态（之前是 showFooter）
    val chapterTitleAtBottom: Boolean = false,
    val isReadingStatusTransparent: Boolean = true,
    val enableParagraphIndent: Boolean = true, // 段首缩进两个全角空格
    /** Apply an EPUB's own headings, alignment and emphasis (its style sheets) to the text. */
    val useBookStyles: Boolean = true,
    /** Screen brightness override; null keeps the system brightness. */
    val screenBrightness: Float? = null,
    val isTranslationEnabled: Boolean = false,
    val translationDisplayMode: NovelTranslationDisplayMode = NovelTranslationDisplayMode.TRANSLATION_ONLY,
) {

    val paragraphSpacingLines: Int
        get() = paragraphSpacing.roundToInt().coerceIn(
            PARAGRAPH_SPACING_RANGE.start.roundToInt(),
            PARAGRAPH_SPACING_RANGE.endInclusive.roundToInt(),
        )

    fun normalized(): NovelReaderSettings {
        return copy(
            fontSizeSp = snapFloat(fontSizeSp, FONT_SIZE_RANGE.start, FONT_SIZE_RANGE.endInclusive, FONT_SIZE_STEP),
            lineSpacing = snapFloat(
                lineSpacing,
                LINE_SPACING_RANGE.start,
                LINE_SPACING_RANGE.endInclusive,
                LINE_SPACING_STEP,
            ),
            paragraphSpacing = snapFloat(
                paragraphSpacing,
                PARAGRAPH_SPACING_RANGE.start,
                PARAGRAPH_SPACING_RANGE.endInclusive,
                PARAGRAPH_SPACING_STEP,
            ),
            marginHorizontal = snapInt(
                marginHorizontal,
                MARGIN_RANGE.first,
                MARGIN_RANGE.last,
                MARGIN_STEP,
            ),
            marginVertical = snapInt(
                marginVertical,
                MARGIN_RANGE.first,
                MARGIN_RANGE.last,
                MARGIN_STEP,
            ),
            screenBrightness = screenBrightness?.coerceIn(SCREEN_BRIGHTNESS_RANGE),
        )
    }

    fun save(context: Context) {
        val normalized = normalized()
        getPrefs(context).edit {
            putFloat(KEY_FONT_SIZE, normalized.fontSizeSp)
            putString(KEY_FONT, normalized.font.name)
            putFloat(KEY_LINE_SPACING, normalized.lineSpacing)
            putInt(KEY_PARAGRAPH_SPACING_LINES, normalized.paragraphSpacing.roundToInt())
            remove(KEY_PARAGRAPH_SPACING)
            putInt(KEY_MARGIN_HORIZONTAL, normalized.marginHorizontal)
            putInt(KEY_MARGIN_VERTICAL, normalized.marginVertical)
            putString(KEY_THEME_PRESET, normalized.themePreset.name)
            putString(KEY_READING_MODE, normalized.readingMode.name)
            putString(KEY_PAGE_TURN_ANIMATION, normalized.pageTurnAnimation.name)
            remove(KEY_LEGACY_TEXT_DIRECTION)
            putString(KEY_TEXT_DIRECTION, normalized.textDirection.name)
            putBoolean(KEY_DUAL_PAGE, normalized.enableDualPage)
            putBoolean(KEY_FULLSCREEN, normalized.enableFullscreen)
            putBoolean(KEY_SHOW_READING_STATUS, normalized.showReadingStatus)
            putBoolean(KEY_CHAPTER_TITLE_AT_BOTTOM, normalized.chapterTitleAtBottom)
            putBoolean(KEY_READING_STATUS_TRANSPARENT, normalized.isReadingStatusTransparent)
            putBoolean(KEY_PARAGRAPH_INDENT, normalized.enableParagraphIndent)
            putBoolean(KEY_BOOK_STYLES, normalized.useBookStyles)
            if (normalized.screenBrightness == null) {
                remove(KEY_SCREEN_BRIGHTNESS)
            } else {
                putFloat(KEY_SCREEN_BRIGHTNESS, normalized.screenBrightness)
            }
            remove(KEY_TRANSLATION_ENABLED)
            putString(KEY_TRANSLATION_DISPLAY_MODE, normalized.translationDisplayMode.name)
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM, normalized.chapterTitleAtBottom)
        }
    }

    companion object {
        const val FONT_SIZE_STEP = NovelReaderLimits.FONT_SIZE_STEP
        const val LINE_SPACING_STEP = NovelReaderLimits.LINE_SPACING_STEP
        const val PARAGRAPH_SPACING_STEP = NovelReaderLimits.PARAGRAPH_SPACING_STEP
        const val MARGIN_STEP = NovelReaderLimits.MARGIN_STEP
        val FONT_SIZE_RANGE = NovelReaderLimits.FONT_SIZE_RANGE
        val LINE_SPACING_RANGE = NovelReaderLimits.LINE_SPACING_RANGE
        val PARAGRAPH_SPACING_RANGE = NovelReaderLimits.PARAGRAPH_SPACING_RANGE
        val MARGIN_RANGE = NovelReaderLimits.MARGIN_RANGE
        val SCREEN_BRIGHTNESS_RANGE = 0.1f..1f

        private const val PREF_NAME = "novel_reader_settings"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT = "font"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_PARAGRAPH_SPACING = "paragraph_spacing"
        private const val KEY_PARAGRAPH_SPACING_LINES = "paragraph_spacing_lines"
        private const val KEY_MARGIN_HORIZONTAL = "margin_horizontal"
        private const val KEY_MARGIN_VERTICAL = "margin_vertical"
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_PAGE_TURN_ANIMATION = "page_turn_animation"
        // The legacy key was written as LTR on every save before the direction was selectable, so it
        // does not record a user choice and is ignored in favour of the AUTO default.
        private const val KEY_LEGACY_TEXT_DIRECTION = "text_direction"
        private const val KEY_TEXT_DIRECTION = "text_direction_mode"
        private const val KEY_DUAL_PAGE = "dual_page"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_SHOW_READING_STATUS = "show_reading_status"
        private const val KEY_CHAPTER_TITLE_AT_BOTTOM = "chapter_title_at_bottom"
        private const val KEY_READING_STATUS_TRANSPARENT = "reading_status_transparent"
        private const val KEY_PARAGRAPH_INDENT = "paragraph_indent"
        private const val KEY_BOOK_STYLES = "book_styles"
        private const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
        private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
        private const val KEY_TRANSLATION_DISPLAY_MODE = "translation_display_mode"

        fun load(context: Context): NovelReaderSettings {
            val prefs = getPrefs(context)
            val commonPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val commonChapterTitleAtBottom = commonPrefs
                .takeIf { it.contains(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM) }
                ?.getBoolean(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM, false)
            val legacyChapterTitleAtBottom = prefs.getBoolean(KEY_CHAPTER_TITLE_AT_BOTTOM, false)
            return NovelReaderSettings(
                fontSizeSp = prefs.getSafeFloat(KEY_FONT_SIZE, 17f),
                font = runCatching {
                    NovelReaderFont.valueOf(
                        prefs.getString(KEY_FONT, null) ?: NovelReaderFont.SYSTEM_SERIF.name
                    )
                }.getOrDefault(NovelReaderFont.SYSTEM_SERIF),
                lineSpacing = prefs.getSafeFloat(KEY_LINE_SPACING, 1.6f),
                paragraphSpacing = if (prefs.contains(KEY_PARAGRAPH_SPACING_LINES)) {
                    prefs.getInt(KEY_PARAGRAPH_SPACING_LINES, 0).toFloat()
                } else {
                    migrateLegacyParagraphSpacing(prefs.getSafeFloat(KEY_PARAGRAPH_SPACING, 0f))
                },
                marginHorizontal = prefs.getInt(KEY_MARGIN_HORIZONTAL, 36),
                marginVertical = prefs.getInt(KEY_MARGIN_VERTICAL, 36),
                themePreset = runCatching {
                    NovelReaderThemePreset.valueOf(
                        prefs.getString(KEY_THEME_PRESET, null) ?: NovelReaderThemePreset.PAPER.name
                    )
                }.getOrDefault(NovelReaderThemePreset.PAPER),
                readingMode = runCatching {
                    ReadingMode.valueOf(prefs.getString(KEY_READING_MODE, null) ?: ReadingMode.PAGED.name)
                }.getOrDefault(ReadingMode.PAGED),
                pageTurnAnimation = runCatching {
                    NovelPageTurnAnimation.valueOf(
                        prefs.getString(KEY_PAGE_TURN_ANIMATION, null) ?: NovelPageTurnAnimation.SLIDE.name
                    )
                }.getOrDefault(NovelPageTurnAnimation.SLIDE),
                textDirection = runCatching {
                    TextDirection.valueOf(prefs.getString(KEY_TEXT_DIRECTION, null) ?: TextDirection.AUTO.name)
                }.getOrDefault(TextDirection.AUTO),
                enableDualPage = prefs.getBoolean(KEY_DUAL_PAGE, true),
                enableFullscreen = prefs.getBoolean(KEY_FULLSCREEN, false),
                showReadingStatus = prefs.getBoolean(KEY_SHOW_READING_STATUS, true),
                chapterTitleAtBottom = resolveNovelChapterTitleAtBottom(
                    commonValue = commonChapterTitleAtBottom,
                    legacyValue = legacyChapterTitleAtBottom,
                ),
                isReadingStatusTransparent = prefs.getBoolean(KEY_READING_STATUS_TRANSPARENT, true),
                enableParagraphIndent = prefs.getBoolean(KEY_PARAGRAPH_INDENT, true),
                useBookStyles = prefs.getBoolean(KEY_BOOK_STYLES, true),
                screenBrightness = prefs.getSafeFloat(KEY_SCREEN_BRIGHTNESS, -1f)
                    .takeIf { it >= 0f },
                isTranslationEnabled = false,
                translationDisplayMode = runCatching {
                    NovelTranslationDisplayMode.valueOf(
                        prefs.getString(KEY_TRANSLATION_DISPLAY_MODE, null)
                            ?: NovelTranslationDisplayMode.TRANSLATION_ONLY.name
                    )
                }.getOrDefault(NovelTranslationDisplayMode.TRANSLATION_ONLY),
            ).normalized()
        }

        private fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }

        private fun snapFloat(value: Float, min: Float, max: Float, step: Float): Float {
            val clamped = value.coerceIn(min, max)
            val snappedSteps = ((clamped - min) / step).toInt()
            val remainder = (clamped - min) - (snappedSteps * step)
            val rounded = if (remainder >= step / 2f) snappedSteps + 1 else snappedSteps
            val snapped = min + rounded * step
            return snapped.coerceIn(min, max)
        }

        private fun snapInt(value: Int, min: Int, max: Int, step: Int): Int {
            val clamped = value.coerceIn(min, max)
            val snappedSteps = (clamped - min) / step
            val remainder = (clamped - min) % step
            val rounded = if (remainder >= step / 2f) snappedSteps + 1 else snappedSteps
            return (min + rounded * step).coerceIn(min, max)
        }

        private fun migrateLegacyParagraphSpacing(spacingDp: Float): Float {
            if (spacingDp <= 0f) return 0f
            return (spacingDp / 8f).roundToInt().coerceIn(1, 3).toFloat()
        }
    }
}

internal fun resolveNovelChapterTitleAtBottom(
    commonValue: Boolean?,
    legacyValue: Boolean,
): Boolean = commonValue ?: legacyValue

enum class NovelReaderFont(@StringRes val displayNameRes: Int) {
    SOURCE_HAN_SERIF(R.string.novel_reader_font_source_han_serif),
    LXGW_WENKAI(R.string.novel_reader_font_lxgw_wenkai),
    NOTO_SANS(R.string.novel_reader_font_noto_sans),
    SYSTEM_SERIF(R.string.novel_reader_font_system_serif),
    SYSTEM_SANS(R.string.novel_reader_font_system_sans),
    SYSTEM_CURSIVE(R.string.novel_reader_font_system_cursive),
    SYSTEM_MONOSPACE(R.string.novel_reader_font_system_monospace),
}

data class NovelReaderPalette(
    val backgroundColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val chromeBackgroundColor: Int,
    val chromeTextColor: Int,
    val highlightColor: Int,
    val placeholderColor: Int,
    val placeholderTextColor: Int,
    val isDark: Boolean,
)

fun novelReaderPalette(
    preset: NovelReaderThemePreset,
    isDarkTheme: Boolean,
): NovelReaderPalette {
    val colors = novelReaderColors(preset, isDarkTheme)
    return NovelReaderPalette(
        backgroundColor = colors.background.toInt(),
        textColor = colors.text.toInt(),
        secondaryTextColor = colors.secondaryText.toInt(),
        chromeBackgroundColor = colors.chromeBackground.toInt(),
        chromeTextColor = colors.chromeText.toInt(),
        highlightColor = colors.highlight.toInt(),
        placeholderColor = colors.placeholder.toInt(),
        placeholderTextColor = colors.placeholderText.toInt(),
        isDark = colors.isDark,
    )
}
