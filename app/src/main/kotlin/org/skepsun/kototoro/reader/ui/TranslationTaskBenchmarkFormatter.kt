package org.skepsun.kototoro.reader.ui

import android.content.Context
import org.skepsun.kototoro.R
import kotlin.math.ceil

internal class TranslationTaskBenchmarkFormatter(private val context: Context) {

    fun formatPageDetail(log: String): String {
        if (log.isBlank()) return ""
        var sourceLanguage = "?"
        var targetLanguage = "?"
        var configuredOcr = "?"
        var failCode: String? = null
        var failedReason: String? = null
        val metrics = linkedMapOf<String, String>()
        val ocrAttempts = linkedMapOf<String, Int>()
        val timeline = mutableListOf<String>()
        log.lineSequence().forEach { line ->
            metric(line)?.let { (key, value) -> metrics[key] = value }
            if (line.contains("process start ")) {
                Regex("""sourceLang=([^\s]+)""").find(line)?.groupValues?.getOrNull(1)?.let { sourceLanguage = it }
                Regex("""targetLang=([^\s]+)""").find(line)?.groupValues?.getOrNull(1)?.let { targetLanguage = it }
                Regex("""ocr=([^\s]+)""").find(line)?.groupValues?.getOrNull(1)?.let { configuredOcr = it }
                timeline += context.getString(R.string.reader_translation_task_diag_start)
            }
            if (line.contains("process failed:")) {
                failedReason = line.substringAfter("process failed:").trim()
                timeline += context.getString(R.string.reader_translation_task_diag_failed)
            }
            Regex("""fail_code=([A-Z_]+)""").find(line)?.groupValues?.getOrNull(1)?.let { failCode = it }
            Regex("""ocr engine=([A-Z_]+) blocks=(\d+)""").find(line)?.let { match ->
                val engine = match.groupValues[1]
                val count = match.groupValues[2].toIntOrNull() ?: 0
                ocrAttempts[engine] = count
                timeline += "OCR[$engine]=$count"
            }
            Regex("""translate local requested size=(\d+)""").find(line)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()?.let { timeline += context.getString(R.string.reader_translation_task_diag_local_req, it) }
            Regex("""translate local batch done translated=(\d+)/(\d+)""").find(line)?.let { match ->
                timeline += context.getString(
                    R.string.reader_translation_task_diag_local_done,
                    match.groupValues[1].toIntOrNull() ?: 0,
                    match.groupValues[2].toIntOrNull() ?: 0,
                )
            }
            Regex("""render done translatedBubbles=(\d+)""").find(line)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()?.let { timeline += context.getString(R.string.reader_translation_task_diag_render_done, it) }
        }
        return buildString {
            appendLine(context.getString(R.string.reader_translation_task_diag_panel))
            appendLine(context.getString(R.string.reader_translation_task_diag_lang, sourceLanguage, targetLanguage))
            appendLine(context.getString(R.string.reader_translation_task_diag_ocr_param, configuredOcr))
            if (ocrAttempts.isNotEmpty()) {
                appendLine(
                    context.getString(
                        R.string.reader_translation_task_diag_ocr_attempt,
                        ocrAttempts.entries.joinToString { "${it.key}:${it.value}" },
                    ),
                )
            }
            metricSummary(metrics).forEach(::appendLine)
            failCode?.let { appendLine(context.getString(R.string.reader_translation_task_diag_fail_code, it)) }
            failedReason?.let { appendLine(context.getString(R.string.reader_translation_task_diag_fail_reason, it)) }
            if (timeline.isNotEmpty()) {
                appendLine(context.getString(R.string.reader_translation_task_diag_timeline, timeline.joinToString(" -> ")))
            }
        }.trim()
    }

    fun format(snapshots: List<ReaderViewModel.TranslationPageTaskSnapshot>): String {
        val samples = snapshots.mapNotNull(::parse)
        if (samples.isEmpty()) return ""
        return buildList {
            add(context.getString(R.string.reader_translation_task_bench_title))
            add(context.getString(R.string.reader_translation_task_bench_sampled, samples.size, snapshots.size))
            percentileLine(context.getString(R.string.reader_translation_task_bench_dist_total), samples.mapNotNull { it.totalMs })?.let(::add)
            percentileLine(context.getString(R.string.reader_translation_task_bench_dist_ocr), samples.mapNotNull { it.ocrMs })?.let(::add)
            percentileLine(context.getString(R.string.reader_translation_task_bench_dist_trans), samples.mapNotNull { it.translationMs })?.let(::add)
            percentileLine(context.getString(R.string.reader_translation_task_bench_dist_render), samples.mapNotNull { it.renderMs })?.let(::add)
            rateLine(context.getString(R.string.reader_translation_task_bench_dist_ocr_cache), samples.mapNotNull { it.ocrCacheHit })?.let(::add)
            rateLine(context.getString(R.string.reader_translation_task_bench_dist_render_cache), samples.mapNotNull { it.renderCacheHit })?.let(::add)
            distributionLine(context.getString(R.string.reader_translation_task_bench_dist_pipe), samples.mapNotNull { it.pipeline })?.let(::add)
            distributionLine(context.getString(R.string.reader_translation_task_bench_dist_pipe_fb), samples.mapNotNull { it.fallbackReason })?.let(::add)
            percentileLine(
                context.getString(R.string.reader_translation_task_bench_dist_roi_box),
                samples.mapNotNull { it.detectedBoxes?.toLong() },
            )?.let(::add)
            distributionLine(context.getString(R.string.reader_translation_task_bench_dist_ocr_sel), samples.mapNotNull { it.engine })?.let(::add)
            distributionLine(context.getString(R.string.reader_translation_task_bench_dist_fail_code), samples.mapNotNull { it.failCode })?.let(::add)
        }.joinToString("\n")
    }

    private fun parse(snapshot: ReaderViewModel.TranslationPageTaskSnapshot): Sample? {
        val metrics = snapshot.log.lineSequence().mapNotNull(::metric).toMap()
        if (metrics.isEmpty() && snapshot.failCode == null) return null
        return Sample(
            totalMs = metrics["process.total_ms"]?.toLongOrNull(),
            ocrMs = metrics["ocr.total_ms"]?.toLongOrNull(),
            translationMs = metrics["translation.total_ms"]?.toLongOrNull(),
            renderMs = metrics["render.total_ms"]?.toLongOrNull(),
            ocrCacheHit = metrics["ocr.cache_hit"]?.toIntOrNull()?.let { it == 1 },
            renderCacheHit = metrics["render_cache.hit"]?.toIntOrNull()?.let { it == 1 },
            pipeline = metrics["ocr.pipeline.strategy"]?.takeIf(String::isNotBlank),
            fallbackReason = metrics["ocr.pipeline.fallback_reason"]?.takeIf { it.isNotBlank() && it != "none" },
            detectedBoxes = metrics["ocr.pipeline.roi_first_detected_boxes"]?.toIntOrNull(),
            engine = metrics["ocr.selected_engine"]?.takeIf(String::isNotBlank),
            failCode = snapshot.failCode,
        )
    }

    private fun metric(line: String): Pair<String, String>? {
        if (!line.startsWith("metric.")) return null
        val separator = line.indexOf('=')
        if (separator <= 7 || separator == line.lastIndex) return null
        return line.substring(7, separator) to line.substring(separator + 1).trim()
    }

    private fun metricSummary(metrics: Map<String, String>): List<String> = buildList {
        metrics["process.total_ms"]?.let { add(context.getString(R.string.reader_translation_task_stat_total, it)) }
        metrics["ocr.total_ms"]?.let { add(context.getString(R.string.reader_translation_task_stat_ocr, it)) }
        metrics["translation.total_ms"]?.let { add(context.getString(R.string.reader_translation_task_stat_trans, it)) }
        metrics["render.total_ms"]?.let { add(context.getString(R.string.reader_translation_task_stat_render, it)) }
        metrics["ocr.pipeline.strategy"]?.let { add(context.getString(R.string.reader_translation_task_stat_pipe_strat, it)) }
        metrics["ocr.selected_engine"]?.let { add(context.getString(R.string.reader_translation_task_stat_ocr_eng, it)) }
        metrics["ocr.blocks"]?.let { add(context.getString(R.string.reader_translation_task_stat_ocr_block, it)) }
        metrics["translation.bubbles"]?.let { add(context.getString(R.string.reader_translation_task_stat_bubble, it)) }
        metrics["render.translated_bubbles"]?.let { add(context.getString(R.string.reader_translation_task_stat_render_bubble, it)) }
    }

    private fun percentileLine(label: String, values: List<Long>): String? {
        if (values.isEmpty()) return null
        return "$label p50/p95: ${percentile(values, 0.5)}ms / ${percentile(values, 0.95)}ms"
    }

    private fun rateLine(label: String, values: List<Boolean>): String? {
        if (values.isEmpty()) return null
        val hits = values.count { it }
        return "$label: $hits/${values.size} (${hits * 100 / values.size}%)"
    }

    private fun distributionLine(label: String, values: List<String>): String? {
        if (values.isEmpty()) return null
        val distribution = values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(" / ") { "${it.key}:${it.value}" }
        return "$label: $distribution"
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        val sorted = values.sorted()
        val index = (ceil(fraction.coerceIn(0.0, 1.0) * sorted.size) - 1).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class Sample(
        val totalMs: Long?,
        val ocrMs: Long?,
        val translationMs: Long?,
        val renderMs: Long?,
        val ocrCacheHit: Boolean?,
        val renderCacheHit: Boolean?,
        val pipeline: String?,
        val fallbackReason: String?,
        val detectedBoxes: Int?,
        val engine: String?,
        val failCode: String?,
    )
}
