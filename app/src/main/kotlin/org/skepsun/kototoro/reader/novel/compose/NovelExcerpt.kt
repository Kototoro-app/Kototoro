package org.skepsun.kototoro.reader.novel.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.OnlineFontLoader
import org.skepsun.kototoro.reader.novel.NovelReaderFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

enum class NovelExcerptTemplate(@StringRes val displayNameRes: Int) {
    CALENDAR(R.string.novel_excerpt_template_calendar),
    CLASSIC(R.string.novel_excerpt_template_classic),
    INK_WHITE(R.string.novel_excerpt_template_ink_white),
    SHADOW(R.string.novel_excerpt_template_shadow),
    MANUSCRIPT(R.string.novel_excerpt_template_manuscript),
    JINSHU(R.string.novel_excerpt_template_jinshu),
}

private data class NovelExcerptTemplateStyle(
    val accent: Color,
    val label: String,
    val quoteStart: String = "",
    val quoteEnd: String = "",
)

val NovelExcerptTemplate.defaultBackgroundColor: Color
    get() = when (this) {
        NovelExcerptTemplate.CALENDAR -> Color(0xFFFFFFFF)
        NovelExcerptTemplate.CLASSIC -> Color(0xFFFCFCFC)
        NovelExcerptTemplate.INK_WHITE -> Color(0xFFF7F4EC)
        NovelExcerptTemplate.SHADOW -> Color(0xFFFFFFFF)
        NovelExcerptTemplate.MANUSCRIPT -> Color(0xFFF5F0E8)
        NovelExcerptTemplate.JINSHU -> Color(0xFFF0F2F7)
    }

private val NovelExcerptTemplate.style: NovelExcerptTemplateStyle
    get() = when (this) {
        NovelExcerptTemplate.CALENDAR -> NovelExcerptTemplateStyle(
            accent = Color(0xFFC4574A),
            label = "CALENDAR",
        )
        NovelExcerptTemplate.CLASSIC -> NovelExcerptTemplateStyle(
            accent = Color(0xFF986D3F),
            label = "CLASSIC",
        )
        NovelExcerptTemplate.INK_WHITE -> NovelExcerptTemplateStyle(
            accent = Color(0xFF4D5963),
            label = "INK / WHITE",
        )
        NovelExcerptTemplate.SHADOW -> NovelExcerptTemplateStyle(
            accent = Color(0xFF5A8EAF),
            label = "SHADOW",
        )
        NovelExcerptTemplate.MANUSCRIPT -> NovelExcerptTemplateStyle(
            accent = Color(0xFFB07C45),
            label = "MANUSCRIPT",
        )
        NovelExcerptTemplate.JINSHU -> NovelExcerptTemplateStyle(
            accent = Color(0xFF7A5B87),
            label = "JINSHU",
        )
    }

enum class NovelExcerptBackground(@StringRes val displayNameRes: Int, val color: Color?) {
    AUTO(R.string.novel_excerpt_background_auto, null),
    WHITE(R.string.novel_excerpt_background_white, Color(0xFFFFFFFF)),
    PAPER(R.string.novel_excerpt_background_paper, Color(0xFFF5EFE4)),
    MIST(R.string.novel_excerpt_background_mist, Color(0xFFE8E9E6)),
    TEA(R.string.novel_excerpt_background_tea, Color(0xFFE7D2B5)),
    INK(R.string.novel_excerpt_background_ink, Color(0xFF202124)),
    DUSK(R.string.novel_excerpt_background_dusk, Color(0xFF29384B)),
}

data class NovelExcerptConfiguration(
    val template: NovelExcerptTemplate = NovelExcerptTemplate.CALENDAR,
    val font: NovelReaderFont = NovelReaderFont.SYSTEM_SERIF,
    val background: NovelExcerptBackground = NovelExcerptBackground.AUTO,
    val pageIndex: Int = 0,
)

data class NovelExcerptData(
    val selectedText: String,
    val bookTitle: String,
    val chapterTitle: String,
    val author: String = "",
    // Blank means "use the localized default reader name" (R.string.novel_excerpt_default_nickname)
    val userNickname: String = "",
    val note: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val imageBitmap: Bitmap? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelExcerptSheet(
    data: NovelExcerptData,
    onDismiss: () -> Unit,
    onSave: ((NovelExcerptData, NovelExcerptConfiguration) -> Unit)? = null,
    onShare: ((NovelExcerptData, NovelExcerptConfiguration) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val defaultNickname = stringResource(R.string.novel_excerpt_default_nickname)
    var configuration by remember { mutableStateOf(NovelExcerptConfiguration()) }
    var userNickname by remember(data.userNickname) { mutableStateOf(data.userNickname) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var optionsExpanded by remember { mutableStateOf(false) }

    var loadedBitmap by remember(data.imageBitmap, data.imageUri) {
        mutableStateOf(data.imageBitmap)
    }

    LaunchedEffect(data.imageUri, data.imageBitmap) {
        if (loadedBitmap == null && !data.imageUri.isNullOrBlank()) {
            val bmp = NovelExcerptHelper.loadExcerptBitmap(context, data.imageUri)
            if (bmp != null) {
                loadedBitmap = bmp
            }
        }
    }

    val activeData = remember(data, userNickname, defaultNickname, loadedBitmap) {
        data.copy(
            userNickname = userNickname.ifBlank { defaultNickname },
            imageBitmap = loadedBitmap ?: data.imageBitmap,
        )
    }

    val pageCount = remember(activeData, configuration.font) {
        NovelExcerptCardRenderer.calculateTotalPages(context, activeData, configuration.font)
    }

    LaunchedEffect(pageCount) {
        if (currentPageIndex >= pageCount) {
            currentPageIndex = (pageCount - 1).coerceAtLeast(0)
        }
    }

    val activeConfig = configuration.copy(pageIndex = currentPageIndex)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.novel_excerpt_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (pageCount > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = { currentPageIndex = (currentPageIndex - 1).coerceAtLeast(0) },
                            enabled = currentPageIndex > 0,
                        ) {
                            Text(stringResource(R.string.prev_page))
                        }
                        Text(
                            text = "${currentPageIndex + 1} / $pageCount",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(
                            onClick = { currentPageIndex = (currentPageIndex + 1).coerceAtMost(pageCount - 1) },
                            enabled = currentPageIndex < pageCount - 1,
                        ) {
                            Text(stringResource(R.string.next_page))
                        }
                    }
                }
            }
            NovelExcerptCanvasPreview(
                data = activeData,
                configuration = activeConfig,
                pageIndex = currentPageIndex,
                pageCount = pageCount,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { optionsExpanded = !optionsExpanded },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (optionsExpanded) {
                                R.string.novel_excerpt_hide_options
                            } else {
                                R.string.novel_excerpt_toggle_options
                            },
                        ),
                    )
                }
                Button(
                    onClick = {
                        if (onSave != null) {
                            onSave(activeData, activeConfig)
                        } else {
                            coroutineScope.launch {
                                NovelExcerptHelper.saveExcerptToGallery(context, activeData, activeConfig)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.novel_excerpt_save_to_gallery))
                }
                TextButton(
                    onClick = {
                        if (onShare != null) {
                            onShare(activeData, activeConfig)
                        } else {
                            coroutineScope.launch {
                                NovelExcerptHelper.shareExcerpt(context, activeData, activeConfig)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.share))
                }
            }
            if (optionsExpanded) {
                NovelExcerptOptions(
                    configuration = configuration,
                    onConfigurationChanged = { configuration = it },
                    userNickname = userNickname,
                    onUserNicknameChanged = { userNickname = it },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NovelExcerptOptions(
    configuration: NovelExcerptConfiguration,
    onConfigurationChanged: (NovelExcerptConfiguration) -> Unit,
    userNickname: String,
    onUserNicknameChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.novel_excerpt_user_nickname), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = userNickname,
                onValueChange = onUserNicknameChanged,
                placeholder = {
                    Text(stringResource(R.string.novel_excerpt_default_nickname), fontSize = 12.sp)
                },
                singleLine = true,
                modifier = Modifier.width(160.dp),
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        NovelExcerptOptionRow(
            title = stringResource(R.string.novel_excerpt_template_label),
            values = NovelExcerptTemplate.entries,
            selected = configuration.template,
            labelRes = { it.displayNameRes },
            onSelected = { onConfigurationChanged(configuration.copy(template = it)) },
        )
        NovelReaderFontOptionRow(
            selected = configuration.font,
            onSelected = { onConfigurationChanged(configuration.copy(font = it)) },
        )
        NovelExcerptOptionRow(
            title = stringResource(R.string.novel_excerpt_background_label),
            values = NovelExcerptBackground.entries,
            selected = configuration.background,
            labelRes = { it.displayNameRes },
            onSelected = { onConfigurationChanged(configuration.copy(background = it)) },
        )
    }
}

@Composable
private fun <T> NovelExcerptOptionRow(
    title: String,
    values: List<T>,
    selected: T,
    @StringRes labelRes: (T) -> Int,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { value ->
                Surface(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onSelected(value) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected == value) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = if (selected == value) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = stringResource(labelRes(value)),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelExcerptCanvasPreview(
    data: NovelExcerptData,
    configuration: NovelExcerptConfiguration,
    pageIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val layout = remember(data, configuration, pageIndex, pageCount) {
        NovelExcerptCardRenderer.computeLayout(context, data, configuration, pageIndex, pageCount)
    }
    Box(
        modifier = modifier
            .aspectRatio(layout.width.toFloat() / layout.height.toFloat())
            .clip(RoundedCornerShape(16.dp)),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { composeCanvas ->
                val nativeCanvas = composeCanvas.nativeCanvas
                val scale = size.width / layout.width.toFloat()
                nativeCanvas.save()
                nativeCanvas.scale(scale, scale)
                NovelExcerptCardRenderer.drawToCanvas(nativeCanvas, layout, configuration)
                nativeCanvas.restore()
            }
        }
    }
}

internal data class ExcerptCardLayout(
    val width: Int = 1080,
    val height: Int,
    val margin: Float = 96f,
    val contentWidth: Float = 888f,
    val backgroundColor: Int,
    val foregroundColor: Int,
    val accentColor: Int,
    val template: NovelExcerptTemplate,
    val typeface: Typeface?,
    val boldTypeface: Typeface?,
    val dateDay: String,
    val dateMonthYear: String,
    val weekday: String,
    val dateFullSlash: String,
    val dateLine: String,
    val verticalUserLine: String,
    val dateChinese: String,
    val solarTerm: String,
    val quoteStart: String,
    val quoteEnd: String,
    val textLines: List<String>,
    val noteLines: List<String>,
    val bookTitle: String,
    val chapterTitle: String,
    val author: String,
    val userNickname: String,
    val pageIndex: Int,
    val pageCount: Int,
    val quoteStartY: Float,
    val lineHeight: Float = 74f,
    val noteStartY: Float,
    val noteLineHeight: Float = 44f,
    val footerTop: Float,
    val imageBitmap: Bitmap? = null,
    val imageRect: RectF? = null,
    val formattedBookTitle: String = bookTitle,
)

internal object NovelExcerptDateHelper {
    private val SOLAR_TERMS = arrayOf(
        Pair("小寒", "大寒"),
        Pair("立春", "雨水"),
        Pair("惊蛰", "春分"),
        Pair("清明", "谷雨"),
        Pair("立夏", "小满"),
        Pair("芒种", "夏至"),
        Pair("小暑", "大暑"),
        Pair("立秋", "处暑"),
        Pair("白露", "秋分"),
        Pair("寒露", "霜降"),
        Pair("立冬", "小雪"),
        Pair("大雪", "冬至"),
    )

    private val SOLAR_TERM_DAYS = arrayOf(
        Pair(5, 20),
        Pair(4, 19),
        Pair(5, 20),
        Pair(4, 20),
        Pair(5, 21),
        Pair(5, 21),
        Pair(7, 22),
        Pair(7, 23),
        Pair(7, 23),
        Pair(8, 23),
        Pair(7, 22),
        Pair(7, 22),
    )

    fun getSolarTerm(month0: Int, day: Int): String {
        val safeMonth = month0.coerceIn(0, 11)
        val days = SOLAR_TERM_DAYS[safeMonth]
        val terms = SOLAR_TERMS[safeMonth]
        return if (day >= days.second) terms.second else terms.first
    }

    private val CHINESE_DIGITS = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    fun toChineseYear(year: Int): String {
        return year.toString().map { CHINESE_DIGITS.getOrElse(it - '0') { "" } }.joinToString("") + "年"
    }

    private val CHINESE_MONTHS = arrayOf(
        "一月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "十一月", "十二月",
    )

    fun toChineseMonth(month0: Int): String {
        return CHINESE_MONTHS.getOrElse(month0.coerceIn(0, 11)) { "一月" }
    }

    fun toChineseDay(day: Int): String {
        return when {
            day <= 10 -> if (day == 10) "十日" else "${CHINESE_DIGITS[day]}日"
            day < 20 -> "十${CHINESE_DIGITS[day % 10]}日"
            day == 20 -> "二十日"
            day < 30 -> "二十${CHINESE_DIGITS[day % 10]}日"
            day == 30 -> "三十日"
            else -> "三十${CHINESE_DIGITS[day % 10]}日"
        }
    }

    fun formatChineseDate(timeMillis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val y = toChineseYear(cal.get(java.util.Calendar.YEAR))
        val m = toChineseMonth(cal.get(java.util.Calendar.MONTH))
        val d = toChineseDay(cal.get(java.util.Calendar.DAY_OF_MONTH))
        return "$y · $m · $d"
    }

    fun splitTitleForVertical(title: String, maxPerColumn: Int = 6): List<String> {
        val clean = title.trim().removePrefix("《").removeSuffix("》")
        if (clean.length <= maxPerColumn) return listOf(clean)
        return clean.chunked(maxPerColumn)
    }
}

internal object NovelExcerptCardRenderer {

    fun calculateTotalPages(
        context: Context?,
        data: NovelExcerptData,
        font: NovelReaderFont,
    ): Int {
        if (data.selectedText.isBlank()) {
            return 1
        }
        val tf = resolveTypeface(context, font)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = 48f
        }
        val lines = wrapLines(data.selectedText, paint, 888f)
        val hasImage = data.imageBitmap != null || !data.imageUri.isNullOrBlank()
        val linesPerPage = if (hasImage) 6 else 12
        return maxOf(1, (lines.size + linesPerPage - 1) / linesPerPage)
    }

    fun computeLayout(
        context: Context?,
        data: NovelExcerptData,
        configuration: NovelExcerptConfiguration,
        pageIndex: Int = 0,
        pageCount: Int = 1,
    ): ExcerptCardLayout {
        val width = 1080
        val margin = 96f
        val contentWidth = width - margin * 2
        val activeBg = configuration.background.color ?: configuration.template.defaultBackgroundColor
        val backgroundInt = activeBg.toArgb()
        val foregroundInt = if (activeBg.luminance() < 0.35f) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.rgb(38, 35, 31)
        }
        val templateStyle = configuration.template.style
        val accentInt = templateStyle.accent.toArgb()
        val tf = resolveTypeface(context, configuration.font)
        val boldTf = tf?.let { runCatching { Typeface.create(it, Typeface.BOLD) }.getOrNull() }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = 48f
        }

        val initialContentTop = when (configuration.template) {
            NovelExcerptTemplate.CALENDAR -> 450f
            NovelExcerptTemplate.CLASSIC -> 270f
            NovelExcerptTemplate.INK_WHITE -> 630f
            NovelExcerptTemplate.SHADOW -> 600f
            NovelExcerptTemplate.MANUSCRIPT -> 270f
            NovelExcerptTemplate.JINSHU -> 540f
        }

        val img = data.imageBitmap
        val (imgW, imgH) = if (img != null && img.width > 0 && img.height > 0) {
            val aspect = img.width.toFloat() / img.height.toFloat()
            val maxAllowedHeight = when (configuration.template) {
                NovelExcerptTemplate.SHADOW, NovelExcerptTemplate.INK_WHITE -> 500f
                else -> 600f
            }
            val targetW = contentWidth
            val targetH = targetW / aspect
            if (targetH > maxAllowedHeight) {
                val clampedH = maxAllowedHeight
                val clampedW = clampedH * aspect
                clampedW to clampedH
            } else {
                targetW to targetH
            }
        } else {
            0f to 0f
        }

        val imageRect = if (img != null && imgW > 0 && imgH > 0) {
            val imgLeft = margin + (contentWidth - imgW) / 2f
            val imgTop = initialContentTop
            RectF(imgLeft, imgTop, imgLeft + imgW, imgTop + imgH)
        } else null

        val allTextLines = if (data.selectedText.isBlank()) emptyList() else wrapLines(data.selectedText, paint, contentWidth)
        val hasImage = imageRect != null || !data.imageUri.isNullOrBlank()
        val linesPerPage = if (hasImage) 6 else 12
        val totalPages = maxOf(pageCount, maxOf(1, (allTextLines.size + linesPerPage - 1) / linesPerPage))
        val safePageIndex = pageIndex.coerceIn(0, totalPages - 1)
        val pageTextLines = if (allTextLines.isEmpty()) {
            emptyList()
        } else {
            allTextLines.drop(safePageIndex * linesPerPage).take(linesPerPage)
        }

        val noteLines = if (safePageIndex == totalPages - 1 && !data.note.isNullOrBlank()) {
            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = 30f
            }
            val noteText = context?.getString(R.string.novel_excerpt_note_with_idea, data.note) ?: data.note
            wrapLines(noteText, notePaint, contentWidth).take(4)
        } else emptyList()

        val lineHeight = if (configuration.template == NovelExcerptTemplate.CALENDAR) 76f else 74f
        val textHeight = if (pageTextLines.isEmpty()) 0f else pageTextLines.size * lineHeight

        val quoteStartY = if (imageRect != null) {
            imageRect.bottom + (if (pageTextLines.isEmpty()) 0f else 60f)
        } else {
            initialContentTop
        }

        val noteStartY = if (pageTextLines.isEmpty()) {
            (imageRect?.bottom ?: initialContentTop) + (if (imageRect != null) 36f else 0f)
        } else {
            quoteStartY + textHeight + 32f
        }
        val noteLineHeight = 44f
        val noteHeight = if (noteLines.isEmpty()) 0f else (40f + noteLines.size * noteLineHeight)

        val footerTop = noteStartY + noteHeight + when (configuration.template) {
            NovelExcerptTemplate.CALENDAR -> 70f
            NovelExcerptTemplate.CLASSIC -> 60f
            NovelExcerptTemplate.INK_WHITE -> 50f
            NovelExcerptTemplate.SHADOW -> 50f
            NovelExcerptTemplate.MANUSCRIPT -> 60f
            NovelExcerptTemplate.JINSHU -> 50f
        }

        val minHeight = when (configuration.template) {
            NovelExcerptTemplate.CALENDAR -> 1380
            NovelExcerptTemplate.CLASSIC -> 1280
            NovelExcerptTemplate.INK_WHITE -> 1380
            NovelExcerptTemplate.SHADOW -> 1380
            NovelExcerptTemplate.MANUSCRIPT -> 1300
            NovelExcerptTemplate.JINSHU -> 1360
        }
        val bottomPadding = when (configuration.template) {
            NovelExcerptTemplate.CALENDAR -> 190f
            NovelExcerptTemplate.CLASSIC -> 200f
            NovelExcerptTemplate.INK_WHITE -> 160f
            NovelExcerptTemplate.SHADOW -> 160f
            NovelExcerptTemplate.MANUSCRIPT -> 220f
            NovelExcerptTemplate.JINSHU -> 180f
        }
        val calcHeight = (footerTop + bottomPadding).toInt()
        val height = maxOf(minHeight, calcHeight)

        val locale = context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
        val isZh = locale.language.equals("zh", ignoreCase = true)

        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = data.createdAtMillis }
        val dateDay = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString()
        val dateMonthYear = SimpleDateFormat("MMMM yyyy", locale).format(Date(data.createdAtMillis)).uppercase(locale)
        val weekday = SimpleDateFormat("EEEE", locale).format(Date(data.createdAtMillis))
        val dateFullSlash = "${calendar.get(java.util.Calendar.YEAR)}/${calendar.get(java.util.Calendar.MONTH) + 1}/${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
        val dateChinese = if (isZh) {
            NovelExcerptDateHelper.formatChineseDate(data.createdAtMillis)
        } else {
            SimpleDateFormat("yyyy · MM · dd", locale).format(Date(data.createdAtMillis))
        }
        val solarTerm = if (isZh) {
            NovelExcerptDateHelper.getSolarTerm(
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
            )
        } else {
            ""
        }
        val userNickname = data.userNickname.ifBlank {
            context?.getString(R.string.novel_excerpt_default_nickname).orEmpty()
        }
        val dateLine = context?.getString(R.string.novel_excerpt_label_excerpted_on, dateFullSlash)
            ?: dateFullSlash
        val verticalUserLine = context?.getString(
            R.string.novel_excerpt_label_excerpted_on_vertical,
            userNickname,
        ) ?: userNickname
        val formattedBookTitle = context?.getString(R.string.book_notes_overview_book_title, data.bookTitle)
            ?: if (isZh) "《${data.bookTitle}》" else data.bookTitle

        return ExcerptCardLayout(
            width = width,
            height = height,
            margin = margin,
            contentWidth = contentWidth,
            backgroundColor = backgroundInt,
            foregroundColor = foregroundInt,
            accentColor = accentInt,
            template = configuration.template,
            typeface = tf,
            boldTypeface = boldTf,
            dateDay = dateDay,
            dateMonthYear = dateMonthYear,
            weekday = weekday,
            dateFullSlash = dateFullSlash,
            dateLine = dateLine,
            verticalUserLine = verticalUserLine,
            dateChinese = dateChinese,
            solarTerm = solarTerm,
            quoteStart = templateStyle.quoteStart,
            quoteEnd = templateStyle.quoteEnd,
            textLines = pageTextLines,
            noteLines = noteLines,
            bookTitle = data.bookTitle,
            chapterTitle = data.chapterTitle,
            author = data.author,
            userNickname = userNickname,
            pageIndex = safePageIndex,
            pageCount = totalPages,
            quoteStartY = quoteStartY,
            lineHeight = lineHeight,
            noteStartY = noteStartY,
            noteLineHeight = noteLineHeight,
            footerTop = footerTop,
            imageBitmap = data.imageBitmap,
            imageRect = imageRect,
            formattedBookTitle = formattedBookTitle,
        )
    }

    fun render(
        data: NovelExcerptData,
        configuration: NovelExcerptConfiguration,
        pageIndex: Int = configuration.pageIndex,
        context: Context? = null,
    ): Bitmap {
        val layout = computeLayout(context, data, configuration, pageIndex)
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawToCanvas(canvas, layout, configuration)
        return bitmap
    }

    fun drawToCanvas(
        canvas: Canvas,
        layout: ExcerptCardLayout,
        configuration: NovelExcerptConfiguration,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(layout.backgroundColor)

        drawTemplateHeaderAndDecorations(canvas, layout, paint)

        // Draw image if present
        if (layout.imageBitmap != null && layout.imageRect != null) {
            val imgBitmap = layout.imageBitmap
            val rect = layout.imageRect
            val cornerRadius = 20f

            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val srcRect = android.graphics.Rect(0, 0, imgBitmap.width, imgBitmap.height)
            canvas.drawBitmap(imgBitmap, srcRect, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = if (layout.foregroundColor == android.graphics.Color.WHITE) {
                    android.graphics.Color.argb(45, 255, 255, 255)
                } else {
                    android.graphics.Color.argb(35, 0, 0, 0)
                }
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        }

        // Draw selected text lines if present
        if (layout.textLines.isNotEmpty()) {
            paint.color = layout.foregroundColor
            paint.textSize = 48f
            paint.typeface = layout.typeface

            if (layout.template == NovelExcerptTemplate.CALENDAR) {
                // Centered quote lines
                var currentY = layout.quoteStartY
                layout.textLines.forEach { line ->
                    if (line.isNotEmpty()) {
                        val textWidth = paint.measureText(line)
                        val textX = (layout.width - textWidth) / 2f
                        canvas.drawText(line, textX, currentY, paint)
                    }
                    currentY += layout.lineHeight
                }
            } else {
                // Left-aligned quote lines
                val textStartX = layout.margin
                var currentY = layout.quoteStartY
                layout.textLines.forEach { line ->
                    if (line.isNotEmpty()) {
                        canvas.drawText(line, textStartX, currentY, paint)
                    }
                    currentY += layout.lineHeight
                }
            }
        }

        // Draw note if present
        if (layout.noteLines.isNotEmpty()) {
            val noteBoxTop = layout.noteStartY
            val noteBoxBottom = noteBoxTop + layout.noteLines.size * layout.noteLineHeight + 20f
            paint.color = layout.accentColor
            paint.alpha = 20
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                RectF(layout.margin, noteBoxTop - 30f, layout.margin + layout.contentWidth, noteBoxBottom),
                12f,
                12f,
                paint,
            )
            paint.alpha = 255
            paint.color = layout.foregroundColor
            paint.textSize = 30f
            paint.typeface = layout.typeface
            var noteY = noteBoxTop + 14f
            layout.noteLines.forEach { line ->
                canvas.drawText(line, layout.margin + 20f, noteY, paint)
                noteY += layout.noteLineHeight
            }
        }

        drawTemplateFooter(canvas, layout, paint)
    }

    private fun drawVerticalColumn(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        paint: Paint,
        charSpacing: Float = 6f,
    ): Float {
        var y = startY
        text.forEach { ch ->
            val s = ch.toString()
            val tw = paint.measureText(s)
            canvas.drawText(s, x - tw / 2f, y + paint.textSize * 0.88f, paint)
            y += paint.textSize + charSpacing
        }
        return y
    }

    private fun drawAvatarBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        nickname: String,
        paint: Paint,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(255, 237, 226)
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = android.graphics.Color.rgb(240, 205, 185)
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(180, 110, 70)
        paint.textSize = radius * 0.95f
        paint.typeface = Typeface.DEFAULT_BOLD
        val initial = nickname.trim().firstOrNull()?.toString() ?: ""
        val tw = paint.measureText(initial)
        canvas.drawText(initial, cx - tw / 2f, cy + radius * 0.35f, paint)
    }

    private fun drawTranquilSeascape(
        canvas: Canvas,
        width: Float,
        bannerHeight: Float,
    ) {
        val horizonY = bannerHeight * 0.42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky gradient
        val skyShader = android.graphics.LinearGradient(
            0f, 0f, 0f, horizonY,
            android.graphics.Color.rgb(150, 182, 206),
            android.graphics.Color.rgb(188, 209, 224),
            android.graphics.Shader.TileMode.CLAMP,
        )
        paint.shader = skyShader
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width, horizonY, paint)

        // Sea gradient
        val seaShader = android.graphics.LinearGradient(
            0f, horizonY, 0f, bannerHeight,
            intArrayOf(
                android.graphics.Color.rgb(90, 142, 175),
                android.graphics.Color.rgb(55, 112, 148),
                android.graphics.Color.rgb(28, 78, 112),
            ),
            floatArrayOf(0f, 0.4f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        paint.shader = seaShader
        canvas.drawRect(0f, horizonY, width, bannerHeight, paint)
        paint.shader = null

        // Horizon line
        paint.color = android.graphics.Color.argb(70, 255, 255, 255)
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(0f, horizonY, width, horizonY, paint)

        // Water ripples
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.argb(28, 220, 240, 255)
        }
        var wy = horizonY + 28f
        var step = 22f
        var sw = 1.2f
        while (wy < bannerHeight) {
            wavePaint.strokeWidth = sw
            canvas.drawLine(0f, wy, width, wy, wavePaint)
            wy += step
            step += 8f
            sw += 0.4f
        }
    }

    private fun drawTemplateHeaderAndDecorations(
        canvas: Canvas,
        layout: ExcerptCardLayout,
        paint: Paint,
    ) {
        val margin = layout.margin
        val width = layout.width.toFloat()
        val height = layout.height.toFloat()

        when (layout.template) {
            NovelExcerptTemplate.CALENDAR -> {
                // Day number centered
                paint.color = layout.foregroundColor
                paint.textSize = 150f
                paint.typeface = layout.boldTypeface
                val dayWidth = paint.measureText(layout.dateDay)
                canvas.drawText(layout.dateDay, (width - dayWidth) / 2f, 200f, paint)

                // Month & Year centered (tracked)
                paint.textSize = 34f
                paint.typeface = layout.boldTypeface
                paint.letterSpacing = 0.12f
                val myWidth = paint.measureText(layout.dateMonthYear)
                canvas.drawText(layout.dateMonthYear, (width - myWidth) / 2f, 265f, paint)
                paint.letterSpacing = 0f

                // Weekday & Solar Term centered
                paint.textSize = 26f
                paint.typeface = layout.typeface
                paint.color = android.graphics.Color.rgb(136, 136, 136)
                val termStr = if (layout.solarTerm.isNotBlank()) {
                    "${layout.weekday} · ${layout.solarTerm}"
                } else {
                    layout.weekday.uppercase()
                }
                val termWidth = paint.measureText(termStr)
                canvas.drawText(termStr, (width - termWidth) / 2f, 315f, paint)

                // Short centered hairline separator
                paint.color = android.graphics.Color.rgb(220, 220, 220)
                paint.strokeWidth = 2f
                val cx = width / 2f
                canvas.drawLine(cx - 60f, 360f, cx + 60f, 360f, paint)
            }
            NovelExcerptTemplate.CLASSIC -> {
                // Circular avatar at top left
                drawAvatarBadge(canvas, margin + 42f, 80f + 42f, 42f, layout.userNickname, paint)

                // Nickname
                paint.textSize = 30f
                paint.typeface = layout.boldTypeface
                paint.color = layout.foregroundColor
                canvas.drawText(layout.userNickname, margin + 104f, 114f, paint)

                // Date line
                paint.textSize = 24f
                paint.typeface = layout.typeface
                paint.color = android.graphics.Color.rgb(153, 153, 153)
                canvas.drawText(layout.dateLine, margin + 104f, 154f, paint)
            }
            NovelExcerptTemplate.INK_WHITE -> {
                // Top dark bar
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.rgb(58, 51, 44)
                canvas.drawRect(margin - 16f, 44f, width - margin + 16f, 68f, paint)

                // Left vertical title columns
                val cols = NovelExcerptDateHelper.splitTitleForVertical(layout.bookTitle, 6)
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layout.foregroundColor
                    textSize = 62f
                    typeface = layout.boldTypeface
                }
                val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layout.foregroundColor
                    textSize = 32f
                    typeface = layout.typeface
                }
                val startY = 120f
                drawVerticalColumn(canvas, cols[0], margin + 28f, startY, titlePaint, 8f)
                if (cols.size > 1) {
                    drawVerticalColumn(canvas, cols[1], margin + 112f, startY, titlePaint, 8f)
                }
                if (layout.author.isNotBlank()) {
                    val authorX = if (cols.size > 1) margin + 192f else margin + 112f
                    drawVerticalColumn(canvas, layout.author, authorX, startY, authorPaint, 6f)
                }

                // Right vertical frame box
                val boxRight = width - margin
                val boxWidth = 140f
                val boxTop = 120f
                val boxBottom = 520f
                val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(208, 201, 186)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                canvas.drawRect(RectF(boxRight - boxWidth, boxTop, boxRight, boxBottom), boxBorderPaint)

                // Inside vertical box
                val boxTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(120, 115, 105)
                    textSize = 24f
                    typeface = layout.typeface
                }
                drawVerticalColumn(canvas, layout.dateChinese, boxRight - 44f, boxTop + 24f, boxTextPaint, 4f)
                drawVerticalColumn(canvas, layout.verticalUserLine, boxRight - 98f, boxTop + 24f, boxTextPaint, 4f)

                // Separator line below vertical header
                paint.color = android.graphics.Color.rgb(226, 221, 210)
                paint.strokeWidth = 1.5f
                canvas.drawLine(margin, 560f, width - margin, 560f, paint)
            }
            NovelExcerptTemplate.SHADOW -> {
                // Top photographic seascape banner
                drawTranquilSeascape(canvas, width, 520f)

                // Lower white card
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.WHITE
                canvas.drawRect(0f, 520f, width, height, paint)

                // Vertical white typography on seascape
                val cols = NovelExcerptDateHelper.splitTitleForVertical(layout.bookTitle, 6)
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 62f
                    typeface = layout.boldTypeface
                }
                val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 32f
                    typeface = layout.typeface
                }
                val startY = 90f
                drawVerticalColumn(canvas, cols[0], margin + 28f, startY, titlePaint, 8f)
                if (cols.size > 1) {
                    drawVerticalColumn(canvas, cols[1], margin + 112f, startY, titlePaint, 8f)
                }
                if (layout.author.isNotBlank()) {
                    val authorX = if (cols.size > 1) margin + 192f else margin + 112f
                    drawVerticalColumn(canvas, layout.author, authorX, startY, authorPaint, 6f)
                }
            }
            NovelExcerptTemplate.MANUSCRIPT -> {
                // Outer inset frame
                val frameInset = 48f
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(214, 204, 188)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                canvas.drawRect(RectF(frameInset, frameInset, width - frameInset, height - frameInset), borderPaint)

                // Avatar at top left inside frame
                drawAvatarBadge(canvas, margin + 42f, 90f + 42f, 42f, layout.userNickname, paint)

                // Nickname
                paint.textSize = 30f
                paint.typeface = layout.boldTypeface
                paint.color = layout.foregroundColor
                canvas.drawText(layout.userNickname, margin + 104f, 124f, paint)

                // Date line
                paint.textSize = 24f
                paint.typeface = layout.typeface
                paint.color = android.graphics.Color.rgb(153, 153, 153)
                canvas.drawText(layout.dateLine, margin + 104f, 164f, paint)
            }
            NovelExcerptTemplate.JINSHU -> {
                // Vertical typography directly on card
                val cols = NovelExcerptDateHelper.splitTitleForVertical(layout.bookTitle, 6)
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layout.foregroundColor
                    textSize = 62f
                    typeface = layout.boldTypeface
                }
                val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(102, 102, 102)
                    textSize = 32f
                    typeface = layout.typeface
                }
                val startY = 90f
                drawVerticalColumn(canvas, cols[0], margin + 28f, startY, titlePaint, 8f)
                if (cols.size > 1) {
                    drawVerticalColumn(canvas, cols[1], margin + 112f, startY, titlePaint, 8f)
                }
                if (layout.author.isNotBlank()) {
                    val authorX = if (cols.size > 1) margin + 192f else margin + 112f
                    drawVerticalColumn(canvas, layout.author, authorX, startY, authorPaint, 6f)
                }
            }
        }
    }

    private fun drawTemplateFooter(
        canvas: Canvas,
        layout: ExcerptCardLayout,
        paint: Paint,
    ) {
        val margin = layout.margin
        val width = layout.width.toFloat()
        val footerTop = layout.footerTop

        when (layout.template) {
            NovelExcerptTemplate.CALENDAR -> {
                // Book title centered
                paint.color = layout.foregroundColor
                paint.textSize = 32f
                paint.typeface = layout.boldTypeface
                val bookStr = layout.formattedBookTitle
                val bw = paint.measureText(bookStr)
                canvas.drawText(bookStr, (width - bw) / 2f, footerTop + 40f, paint)

                // Author centered
                if (layout.author.isNotBlank()) {
                    paint.textSize = 26f
                    paint.typeface = layout.typeface
                    paint.color = android.graphics.Color.rgb(119, 119, 119)
                    val aw = paint.measureText(layout.author)
                    canvas.drawText(layout.author, (width - aw) / 2f, footerTop + 85f, paint)
                }

                // Branding centered at bottom
                val brand = "Kototoro"
                paint.textSize = 22f
                paint.color = android.graphics.Color.rgb(170, 170, 170)
                paint.typeface = layout.typeface
                val brandW = paint.measureText(brand)
                canvas.drawText(brand, (width - brandW) / 2f, layout.height - 50f, paint)
            }
            NovelExcerptTemplate.CLASSIC -> {
                // / Book · Chapter
                paint.color = android.graphics.Color.rgb(102, 102, 102)
                paint.textSize = 28f
                paint.typeface = layout.typeface
                val pathStr = buildString {
                    append("/ ")
                    append(layout.bookTitle)
                    if (layout.chapterTitle.isNotBlank()) {
                        append(" · ")
                        append(layout.chapterTitle)
                    }
                }
                canvas.drawText(pathStr, margin, footerTop + 40f, paint)

                // Author
                if (layout.author.isNotBlank()) {
                    canvas.drawText(layout.author, margin, footerTop + 85f, paint)
                }

                // Branding left-aligned
                val brand = "Kototoro"
                paint.textSize = 22f
                paint.color = android.graphics.Color.rgb(153, 153, 153)
                canvas.drawText(brand, margin, layout.height - 50f, paint)
            }
            NovelExcerptTemplate.INK_WHITE,
            NovelExcerptTemplate.SHADOW -> {
                // / Chapter (or / Book)
                paint.color = android.graphics.Color.rgb(140, 133, 123)
                paint.textSize = 28f
                paint.typeface = layout.typeface
                val displayTitle = if (layout.chapterTitle.isNotBlank()) layout.chapterTitle else layout.bookTitle
                canvas.drawText("/ $displayTitle", margin, footerTop + 40f, paint)
            }
            NovelExcerptTemplate.MANUSCRIPT -> {
                // Book · Chapter (no slash)
                paint.color = android.graphics.Color.rgb(102, 102, 102)
                paint.textSize = 28f
                paint.typeface = layout.typeface
                val titleStr = buildString {
                    append(layout.bookTitle)
                    if (layout.chapterTitle.isNotBlank()) {
                        append(" · ")
                        append(layout.chapterTitle)
                    }
                }
                canvas.drawText(titleStr, margin, footerTop + 40f, paint)

                // Author
                if (layout.author.isNotBlank()) {
                    canvas.drawText(layout.author, margin, footerTop + 85f, paint)
                }

                // Branding left-aligned
                val brand = "Kototoro"
                paint.textSize = 22f
                paint.color = android.graphics.Color.rgb(153, 153, 153)
                canvas.drawText(brand, margin, layout.height - 70f, paint)
            }
            NovelExcerptTemplate.JINSHU -> {
                // / Chapter
                paint.color = android.graphics.Color.rgb(119, 119, 119)
                paint.textSize = 28f
                paint.typeface = layout.typeface
                val displayTitle = if (layout.chapterTitle.isNotBlank()) layout.chapterTitle else layout.bookTitle
                canvas.drawText("/ $displayTitle", margin, footerTop + 40f, paint)

                // Fine hairline separator
                paint.color = android.graphics.Color.rgb(220, 224, 234)
                paint.strokeWidth = 2f
                canvas.drawLine(margin, footerTop + 80f, width - margin, footerTop + 80f, paint)
            }
        }

        // Page indicator (if multi-page)
        if (layout.pageCount > 1) {
            paint.textSize = 20f
            paint.color = android.graphics.Color.rgb(160, 160, 160)
            paint.typeface = layout.typeface
            val pageStr = "— ${layout.pageIndex + 1} / ${layout.pageCount} —"
            val pageStrWidth = paint.measureText(pageStr)
            val py = if (layout.template == NovelExcerptTemplate.CALENDAR) layout.height - 85f else layout.height - 50f
            canvas.drawText(pageStr, (width - pageStrWidth) / 2f, py, paint)
        }
    }

    private fun resolveTypeface(context: Context?, font: NovelReaderFont): Typeface? {
        val onlinePreset = font.onlinePreset
        if (context != null && onlinePreset != null) {
            val cached = OnlineFontLoader.getCachedTypeface(context, onlinePreset)
            if (cached != null) return cached
        }
        val familyName = when (font) {
            NovelReaderFont.SOURCE_HAN_SERIF,
            NovelReaderFont.SYSTEM_SERIF -> "serif"
            NovelReaderFont.NOTO_SANS,
            NovelReaderFont.SYSTEM_SANS -> "sans-serif"
            NovelReaderFont.LXGW_WENKAI,
            NovelReaderFont.SYSTEM_CURSIVE -> "cursive"
            NovelReaderFont.SYSTEM_MONOSPACE -> "monospace"
        }
        return runCatching { Typeface.create(familyName, Typeface.NORMAL) }.getOrNull()
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> = buildList {
        text.replace("\r\n", "\n").split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                add("")
                return@forEach
            }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                add(remaining.take(count))
                remaining = remaining.drop(count)
            }
        }
    }
}

private fun Color.luminance(): Float {
    fun linear(value: Float): Float = if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
