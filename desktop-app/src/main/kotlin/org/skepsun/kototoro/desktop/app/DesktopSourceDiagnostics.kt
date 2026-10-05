package org.skepsun.kototoro.desktop.app

import kotlinx.serialization.SerializationException
import org.skepsun.kototoro.core.source.SourceRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.util.UUID

/**
 * Bounded local diagnostics. The log file never records request arguments, exception messages, headers or bodies;
 * the exception messages are kept in memory only, so the window can show what actually failed.
 */
internal class DesktopSourceDiagnostics(root: Path) {
    private data class Entry(val description: String, val id: String, val detail: String, val text: String)
    private val entries = linkedMapOf<String, Entry>()
    private val directory = root.resolve("logs")
    private val path = directory.resolve("source-errors.log")
    private var saved = false

    @Synchronized
    fun record(request: SourceRequest, error: Exception) {
        val causes = mutableListOf<Throwable>()
        var cause: Throwable? = error
        while (cause != null && causes.size < 6 && causes.none { it === cause }) {
            causes += cause
            cause = cause.cause
        }
        val description = when {
            causes.any { it is LinkageError || it is ClassNotFoundException } -> "来源运行时不兼容"
            causes.any { it is IOException } -> "来源网络请求失败"
            causes.any { it is SerializationException } -> "来源数据解析失败"
            else -> "来源执行失败"
        }
        val id = UUID.randomUUID().toString().take(8)
        val text = buildString {
            appendLine("time=${Instant.now()} diagnostic=$id")
            appendLine("operation=${request.call.javaClass.simpleName}")
            for (failure in causes) {
                appendLine("cause=${failure.javaClass.name.take(160)}")
                failure.stackTrace.take(6).forEach { frame ->
                    appendLine("  ${frame.className.take(160)}.${frame.methodName.take(80)}:${frame.lineNumber}")
                }
            }
        }
        entries[request.requestId] = Entry(description, id, detail(causes), text.take(4096))
        while (entries.size > 16) entries.remove(entries.keys.first())
        saved = runCatching {
            Files.createDirectories(directory)
            require(Files.isDirectory(directory, NOFOLLOW_LINKS))
            Files.newBufferedWriter(path, Charsets.UTF_8, CREATE, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use {
                for (entry in entries.values) { it.write(entry.text); it.newLine() }
            }
        }.isSuccess
    }

    /** The error as the user sees it: category, what the source reported, then where the stack traces are. */
    @Synchronized
    fun message(requestId: String?): String? = entries[requestId]?.let { entry ->
        "${entry.description}：${entry.detail}\n（诊断 ${entry.id}" + if (saved) "，日志：$path）" else "，诊断日志写入失败）"
    }

    /** Distinct messages along the cause chain, outermost first; wrappers without a message are skipped. */
    private fun detail(causes: List<Throwable>): String {
        val messages = causes.mapNotNull { failure ->
            failure.message?.trim()?.takeIf { it.isNotEmpty() }?.let { "${failure.javaClass.simpleName}: ${it.take(300)}" }
        }.distinctBy { it.substringAfter(": ") }.take(3)
        return messages.ifEmpty { listOf(causes.last().javaClass.simpleName) }.joinToString("\n← ")
    }
}
