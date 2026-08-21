package org.skepsun.kototoro.local.epub

/**
 * Sealed class representing different types of EPUB-related errors.
 *
 * This provides type-safe error handling with user-friendly messages
 * for different error scenarios.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5
 */
sealed class EpubError(
    val message: String,
    val userMessage: String,
    val cause: Throwable? = null
) {

    /**
     * EPUB parsing errors (Requirement 10.1, 10.2)
     */
    sealed class ParseError(message: String, userMessage: String, cause: Throwable? = null) :
        EpubError(message, userMessage, cause) {

        class InvalidFormat(cause: Throwable? = null) : ParseError(
            message = "Invalid EPUB format",
            userMessage = "EPUB 文件格式无效，无法解析",
            cause = cause
        )

        class CorruptedFile(fileName: String, cause: Throwable? = null) : ParseError(
            message = "EPUB file is corrupted: $fileName",
            userMessage = "EPUB 文件已损坏，无法读取：$fileName",
            cause = cause
        )

        class MissingComponents(component: String, cause: Throwable? = null) : ParseError(
            message = "Missing required EPUB component: $component",
            userMessage = "EPUB 文件缺少必要组件：$component",
            cause = cause
        )

        class UnsupportedVersion(version: String, cause: Throwable? = null) : ParseError(
            message = "Unsupported EPUB version: $version",
            userMessage = "不支持的 EPUB 版本：$version",
            cause = cause
        )

        class MalformedHtml(chapterIndex: Int, cause: Throwable? = null) : ParseError(
            message = "Malformed HTML in chapter $chapterIndex",
            userMessage = "第 $chapterIndex 章的 HTML 格式错误",
            cause = cause
        )
    }

    /**
     * File system errors (Requirement 10.4)
     */
    sealed class FileSystemError(message: String, userMessage: String, cause: Throwable? = null) :
        EpubError(message, userMessage, cause) {

        class FileNotFound(fileName: String, cause: Throwable? = null) : FileSystemError(
            message = "EPUB file not found: $fileName",
            userMessage = "未找到 EPUB 文件，可能已被删除：$fileName",
            cause = cause
        )

        class InsufficientStorage(requiredBytes: Long, availableBytes: Long) : FileSystemError(
            message = "Insufficient storage: required $requiredBytes bytes, available $availableBytes bytes",
            userMessage = "存储空间不足。需要：${formatBytes(requiredBytes)}，可用：${formatBytes(availableBytes)}"
        )

        class PermissionDenied(path: String, cause: Throwable? = null) : FileSystemError(
            message = "Permission denied: $path",
            userMessage = "没有访问权限：$path",
            cause = cause
        )

        class ReadError(fileName: String, cause: Throwable? = null) : FileSystemError(
            message = "Failed to read EPUB file: $fileName",
            userMessage = "读取 EPUB 文件失败：$fileName",
            cause = cause
        )

        class WriteError(fileName: String, cause: Throwable? = null) : FileSystemError(
            message = "Failed to write EPUB file: $fileName",
            userMessage = "写入 EPUB 文件失败：$fileName",
            cause = cause
        )

        companion object {
            private fun formatBytes(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                    else -> "${bytes / (1024 * 1024 * 1024)} GB"
                }
            }
        }
    }

    /**
     * Chapter loading errors (Requirement 10.3)
     */
    sealed class ChapterLoadError(message: String, userMessage: String, cause: Throwable? = null) :
        EpubError(message, userMessage, cause) {

        class InvalidChapterId(chapterId: Long) : ChapterLoadError(
            message = "Invalid chapter ID: $chapterId",
            userMessage = "无效的章节 ID：$chapterId"
        )

        class ChapterNotFound(chapterId: Long) : ChapterLoadError(
            message = "Chapter not found: $chapterId",
            userMessage = "章节不存在：$chapterId"
        )

        class IndexOutOfBounds(index: Int, totalChapters: Int) : ChapterLoadError(
            message = "Chapter index $index out of bounds (total: $totalChapters)",
            userMessage = "章节索引超出范围：$index（共 $totalChapters 章）"
        )

        class InvalidUrl(url: String) : ChapterLoadError(
            message = "Invalid chapter URL format: $url",
            userMessage = "章节 URL 格式无效：$url"
        )

        class LoadFailed(chapterId: Long, cause: Throwable? = null) : ChapterLoadError(
            message = "Failed to load chapter: $chapterId",
            userMessage = "加载章节失败：$chapterId",
            cause = cause
        )
    }

    /**
     * Database errors
     */
    sealed class DatabaseError(message: String, userMessage: String, cause: Throwable? = null) :
        EpubError(message, userMessage, cause) {

        class MappingInsertFailed(chapterId: Long, cause: Throwable? = null) : DatabaseError(
            message = "Failed to insert chapter mapping: $chapterId",
            userMessage = "Failed to save chapter mapping: $chapterId",
            cause = cause
        )

        class QueryFailed(query: String, cause: Throwable? = null) : DatabaseError(
            message = "Database query failed: $query",
            userMessage = "Database query failed",
            cause = cause
        )

        class MigrationFailed(fromVersion: Int, toVersion: Int, cause: Throwable? = null) : DatabaseError(
            message = "Database migration failed: $fromVersion -> $toVersion",
            userMessage = "Database upgrade failed: version $fromVersion -> $toVersion",
            cause = cause
        )
    }

    /**
     * Generic error for unexpected situations
     */
    class UnexpectedError(message: String, cause: Throwable? = null) : EpubError(
        message = message,
        userMessage = "发生未知错误：$message",
        cause = cause
    )
}

/**
 * Extension function to convert exceptions to EpubError
 */
fun Throwable.toEpubError(): EpubError {
    return when (this) {
        is java.io.FileNotFoundException -> EpubError.FileSystemError.FileNotFound(
            fileName = message ?: "unknown",
            cause = this
        )
        is java.io.IOException -> EpubError.FileSystemError.ReadError(
            fileName = message ?: "unknown",
            cause = this
        )
        is SecurityException -> EpubError.FileSystemError.PermissionDenied(
            path = message ?: "unknown",
            cause = this
        )
        else -> EpubError.UnexpectedError(
            message = message ?: "Unknown error",
            cause = this
        )
    }
}
