package org.skepsun.kototoro.local.epub

import android.content.res.Resources
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

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
    @StringRes val userMessageRes: Int,
    val userMessageArgs: List<Any> = emptyList(),
    val cause: Throwable? = null
) {

    fun userMessage(resources: Resources): String =
        resources.getString(userMessageRes, *userMessageArgs.toTypedArray())

    /**
     * EPUB parsing errors (Requirement 10.1, 10.2)
     */
    sealed class ParseError(
        message: String,
        @StringRes userMessageRes: Int,
        userMessageArgs: List<Any> = emptyList(),
        cause: Throwable? = null,
    ) : EpubError(message, userMessageRes, userMessageArgs, cause) {

        class InvalidFormat(cause: Throwable? = null) : ParseError(
            message = "Invalid EPUB format",
            userMessageRes = R.string.epub_error_invalid_format,
            cause = cause
        )

        class CorruptedFile(fileName: String, cause: Throwable? = null) : ParseError(
            message = "EPUB file is corrupted: $fileName",
            userMessageRes = R.string.epub_error_corrupted_file,
            userMessageArgs = listOf(fileName),
            cause = cause
        )

        class MissingComponents(component: String, cause: Throwable? = null) : ParseError(
            message = "Missing required EPUB component: $component",
            userMessageRes = R.string.epub_error_missing_components,
            userMessageArgs = listOf(component),
            cause = cause
        )

        class UnsupportedVersion(version: String, cause: Throwable? = null) : ParseError(
            message = "Unsupported EPUB version: $version",
            userMessageRes = R.string.epub_error_unsupported_version,
            userMessageArgs = listOf(version),
            cause = cause
        )

        class MalformedHtml(chapterIndex: Int, cause: Throwable? = null) : ParseError(
            message = "Malformed HTML in chapter $chapterIndex",
            userMessageRes = R.string.epub_error_malformed_html,
            userMessageArgs = listOf(chapterIndex),
            cause = cause
        )
    }

    /**
     * File system errors (Requirement 10.4)
     */
    sealed class FileSystemError(
        message: String,
        @StringRes userMessageRes: Int,
        userMessageArgs: List<Any> = emptyList(),
        cause: Throwable? = null,
    ) : EpubError(message, userMessageRes, userMessageArgs, cause) {

        class FileNotFound(fileName: String, cause: Throwable? = null) : FileSystemError(
            message = "EPUB file not found: $fileName",
            userMessageRes = R.string.epub_error_file_not_found,
            userMessageArgs = listOf(fileName),
            cause = cause
        )

        class InsufficientStorage(requiredBytes: Long, availableBytes: Long) : FileSystemError(
            message = "Insufficient storage: required $requiredBytes bytes, available $availableBytes bytes",
            userMessageRes = R.string.epub_error_insufficient_storage,
            userMessageArgs = listOf(formatBytes(requiredBytes), formatBytes(availableBytes))
        )

        class PermissionDenied(path: String, cause: Throwable? = null) : FileSystemError(
            message = "Permission denied: $path",
            userMessageRes = R.string.epub_error_permission_denied,
            userMessageArgs = listOf(path),
            cause = cause
        )

        class ReadError(fileName: String, cause: Throwable? = null) : FileSystemError(
            message = "Failed to read EPUB file: $fileName",
            userMessageRes = R.string.epub_error_read_failed,
            userMessageArgs = listOf(fileName),
            cause = cause
        )

        class WriteError(fileName: String, cause: Throwable? = null) : FileSystemError(
            message = "Failed to write EPUB file: $fileName",
            userMessageRes = R.string.epub_error_write_failed,
            userMessageArgs = listOf(fileName),
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
    sealed class ChapterLoadError(
        message: String,
        @StringRes userMessageRes: Int,
        userMessageArgs: List<Any> = emptyList(),
        cause: Throwable? = null,
    ) : EpubError(message, userMessageRes, userMessageArgs, cause) {

        class InvalidChapterId(chapterId: Long) : ChapterLoadError(
            message = "Invalid chapter ID: $chapterId",
            userMessageRes = R.string.epub_error_invalid_chapter_id,
            userMessageArgs = listOf(chapterId)
        )

        class ChapterNotFound(chapterId: Long) : ChapterLoadError(
            message = "Chapter not found: $chapterId",
            userMessageRes = R.string.epub_error_chapter_not_found,
            userMessageArgs = listOf(chapterId)
        )

        class IndexOutOfBounds(index: Int, totalChapters: Int) : ChapterLoadError(
            message = "Chapter index $index out of bounds (total: $totalChapters)",
            userMessageRes = R.string.epub_error_index_out_of_bounds,
            userMessageArgs = listOf(index, totalChapters)
        )

        class InvalidUrl(url: String) : ChapterLoadError(
            message = "Invalid chapter URL format: $url",
            userMessageRes = R.string.epub_error_invalid_url,
            userMessageArgs = listOf(url)
        )

        class LoadFailed(chapterId: Long, cause: Throwable? = null) : ChapterLoadError(
            message = "Failed to load chapter: $chapterId",
            userMessageRes = R.string.epub_error_load_failed,
            userMessageArgs = listOf(chapterId),
            cause = cause
        )
    }

    /**
     * Database errors
     */
    sealed class DatabaseError(
        message: String,
        @StringRes userMessageRes: Int,
        userMessageArgs: List<Any> = emptyList(),
        cause: Throwable? = null,
    ) : EpubError(message, userMessageRes, userMessageArgs, cause) {

        class MappingInsertFailed(chapterId: Long, cause: Throwable? = null) : DatabaseError(
            message = "Failed to insert chapter mapping: $chapterId",
            userMessageRes = R.string.epub_error_mapping_insert_failed,
            userMessageArgs = listOf(chapterId),
            cause = cause
        )

        class QueryFailed(query: String, cause: Throwable? = null) : DatabaseError(
            message = "Database query failed: $query",
            userMessageRes = R.string.epub_error_query_failed,
            cause = cause
        )

        class MigrationFailed(fromVersion: Int, toVersion: Int, cause: Throwable? = null) : DatabaseError(
            message = "Database migration failed: $fromVersion -> $toVersion",
            userMessageRes = R.string.epub_error_migration_failed,
            userMessageArgs = listOf(fromVersion, toVersion),
            cause = cause
        )
    }

    /**
     * Generic error for unexpected situations
     */
    class UnexpectedError(message: String, cause: Throwable? = null) : EpubError(
        message = message,
        userMessageRes = R.string.epub_error_unexpected,
        userMessageArgs = listOf(message),
        cause = cause
    )
}

/**
 * Carries an [EpubError] through `Result` so the UI can show its localized message.
 */
class EpubException(val error: EpubError) : Exception(error.message, error.cause)

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
