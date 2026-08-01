package org.skepsun.kototoro.reader.novel

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.reader.translate.data.ReaderTranslationTextCache
import org.skepsun.kototoro.reader.translate.domain.OnnxReaderTranslationEngine
import org.skepsun.kototoro.reader.translate.domain.ReaderTranslationCoordinator
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小说翻译总协调器。
 *
 * 职责：
 * - 将章节文本按段落切分
 * - 分批调用 ReaderTranslationCoordinator（复用漫画翻译层）
 * - 每批完成后通过 Flow emit 部分翻译结果，实现进度式渲染
 * - 复用 ReaderTranslationTextCache 避免重复翻译
 */
@Singleton
class NovelTranslationProcessor @Inject constructor(
    private val settings: AppSettings,
    private val textCache: ReaderTranslationTextCache,
    @ContentHttpClient private val okHttpClient: OkHttpClient,
    private val onnxTranslationEngine: OnnxReaderTranslationEngine,
) {

    private val translationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ReaderTranslationCoordinator(
            settings = settings,
            textCache = textCache,
            onnxTranslationEngine = onnxTranslationEngine,
            okHttpClient = okHttpClient,
            jsonMediaType = JSON_MEDIA_TYPE,
            defaultOpenAiModel = DEFAULT_OPENAI_MODEL,
            openAiTranslationSystemPrompt = OPENAI_NOVEL_SYSTEM_PROMPT,
            maxOpenAiBatchSize = MAX_BATCH_SIZE,
            thinkTagRegex = THINK_TAG_REGEX,
            buildTextCacheKey = ::buildTextCacheKey,
            sanitizeTranslation = ::sanitizeTranslation,
            isAcceptableTranslation = ::isAcceptableTranslation,
            log = { msg -> if (settings.isReaderTranslationDebugLogsEnabled) Log.d(LOG_TAG, msg()) },
            oneLine = ::oneLine,
        )
    }

    /**
     * 清除翻译缓存。
     */
    fun clearCache() {
        textCache.clear()
    }

    /**
     * 翻译一章内容，返回进度 Flow。
     * 每完成一批段落翻译，emit 一次 NovelChapterTranslation（isComplete=false）。
     * 全部完成后 emit 最终结果（isComplete=true）。
     */
    fun translateChapterFlow(
        chapterIndex: Int,
        content: String,
        sourceLang: String,
        targetLang: String,
        displayMode: NovelTranslationDisplayMode,
    ): Flow<NovelChapterTranslation> = flow {
        Log.d(LOG_TAG, "translateChapterFlow start: chapter=$chapterIndex, contentLength=${content.length}, source=$sourceLang, target=$targetLang")
        val paragraphs = NovelParagraphSplitter.split(content)
        Log.d(LOG_TAG, "Split into ${paragraphs.size} paragraphs")
        if (paragraphs.isEmpty()) return@flow

        val textParagraphs = paragraphs.filter {
            it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank()
        }
        Log.d(LOG_TAG, "Filtered to ${textParagraphs.size} text paragraphs")

        val accumulated = mutableMapOf<Int, String>()

        // 按批次翻译，每批最多 MAX_BATCH_SIZE 个段落
        val batches = textParagraphs.chunked(MAX_BATCH_SIZE)
        Log.d(LOG_TAG, "Split into ${batches.size} batches")
        for (batch in batches) {
            val texts = batch.map { it.originalText }
            Log.d(LOG_TAG, "Translating batch: ${texts.size} texts")
            val results = runCatching {
                translationCoordinator.translateBlocksCached(texts, sourceLang, targetLang)
            }.onFailure {
                // 只记录非取消异常
                if (it !is kotlinx.coroutines.CancellationException) {
                    it.printStackTraceDebug()
                    Log.e(LOG_TAG, "translateChapterFlow batch failed: ${it.message}")
                }
            }.getOrDefault(emptyMap())
            Log.d(LOG_TAG, "Batch translation complete: ${results.size} results")

            for (para in batch) {
                val translated = results[para.originalText].orEmpty()
                if (translated.isNotBlank()) {
                    accumulated[para.index] = translated
                }
            }
            Log.d(LOG_TAG, "Accumulated translations: ${accumulated.size}")

            emit(
                NovelChapterTranslation(
                    chapterIndex = chapterIndex,
                    paragraphs = paragraphs,
                    translations = accumulated.toMap(),
                    displayMode = displayMode,
                    isComplete = false,
                )
            )
        }

        // emit 最终完成状态
        emit(
            NovelChapterTranslation(
                chapterIndex = chapterIndex,
                paragraphs = paragraphs,
                translations = accumulated.toMap(),
                displayMode = displayMode,
                isComplete = true,
            )
        )
    }

    private fun buildTextCacheKey(text: String, sourceLang: String, targetLang: String): String {
        val raw = listOf(
            NOVEL_CACHE_VERSION,
            text,
            sourceLang,
            targetLang,
            settings.readerTranslationMode.name,
            settings.readerTranslationOnnxModelId,
			settings.readerTranslationApiProviderPreset,
            settings.readerTranslationApiEndpoint,
            settings.readerTranslationApiModel,
        ).joinToString("|")
        return "novel_translate_${raw.sha256()}"
    }

    private fun isAcceptableTranslation(
        sourceText: String,
        translatedText: String,
        sourceLang: String,
        targetLang: String,
    ): Boolean {
        if (translatedText.isBlank()) return false
        if (translatedText == "..." || translatedText == "…") return false
        // 小说翻译：不使用漫画的 OCR 噪声过滤，直接接受
        return true
    }

    private fun oneLine(text: String, limit: Int = 140): String {
        if (text.isBlank()) return ""
        return text.replace('\n', ' ').replace('\r', ' ').trim().let {
            if (it.length <= limit) it else it.take(limit) + "..."
        }
    }

    /**
     * 简单清洗：去除 think 标签、markdown 代码块前后缀、多余引号等。
     * 小说翻译不需要漫画的 OCR 噪声处理，保持简洁。
     */
    private fun sanitizeTranslation(text: String): String {
        if (text.isBlank()) return ""
        val stripped = THINK_TAG_REGEX.replace(text, "")
            .replace(Regex("(?is)<think>.*$"), "")
            .trim()
        if (stripped.isBlank()) return ""
        // 去除 markdown 代码块
        val noCodeBlock = if (stripped.startsWith("```")) {
            stripped.lines().drop(1).dropLastWhile { it.trim().startsWith("```") }
                .joinToString("\n").trim()
                .ifBlank { stripped }
        } else {
            stripped
        }
        return noCodeBlock
            .removeSurrounding("**")
            .removeSurrounding("\"")
            .trim()
            .takeUnless { it == "..." || it == "…" }
            .orEmpty()
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val THINK_TAG_REGEX = Regex("(?is)<think>.*?</think>")
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val MAX_BATCH_SIZE = 20
        const val NOVEL_CACHE_VERSION = "novel-translate-v1"
        const val LOG_TAG = "NovelTranslation"
        const val OPENAI_NOVEL_SYSTEM_PROMPT = """
You translate novel text.
Output only the translation.
Preserve line breaks and paragraph structure.
Do not explain or add notes.
"""
    }
}
