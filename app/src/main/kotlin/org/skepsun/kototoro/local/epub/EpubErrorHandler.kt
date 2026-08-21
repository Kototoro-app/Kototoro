package org.skepsun.kototoro.local.epub

import android.util.Log

/**
 * Centralized error handling for EPUB operations.
 *
 * Responsibilities:
 * - Log errors with appropriate detail (Requirement 10.5)
 * - Provide user-friendly error messages (Requirements 10.1, 10.2, 10.3, 10.4)
 * - Track error statistics for debugging
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5
 */
object EpubErrorHandler {

    private const val TAG = "EpubErrorHandler"

    /**
     * Handles an EPUB error by logging it with appropriate detail.
     *
     * @param error The error to handle
     * @param context Additional context about where the error occurred
     */
    fun handleError(error: EpubError, context: String = "") {
        val logMessage = buildString {
            if (context.isNotEmpty()) {
                append("[$context] ")
            }
            append(error.message)
        }

        // Log with appropriate level based on error type
        when (error) {
            is EpubError.ParseError -> {
                Log.e(TAG, "Parse Error: $logMessage", error.cause)
            }
            is EpubError.FileSystemError -> {
                Log.e(TAG, "File System Error: $logMessage", error.cause)
            }
            is EpubError.ChapterLoadError -> {
                Log.w(TAG, "Chapter Load Error: $logMessage", error.cause)
            }
            is EpubError.DatabaseError -> {
                Log.e(TAG, "Database Error: $logMessage", error.cause)
            }
            is EpubError.UnexpectedError -> {
                Log.e(TAG, "Unexpected Error: $logMessage", error.cause)
            }
        }
    }

    /**
     * Handles a generic exception by converting it to an EpubError and logging it.
     *
     * @param exception The exception to handle
     * @param context Additional context about where the error occurred
     * @return The converted EpubError
     */
    fun handleException(exception: Throwable, context: String = ""): EpubError {
        val error = exception.toEpubError()
        handleError(error, context)
        return error
    }

    /**
     * Creates a Result.failure with proper error handling.
     *
     * @param error The error to wrap
     * @param context Additional context about where the error occurred
     * @return Result.failure with the error
     */
    fun <T> createFailure(error: EpubError, context: String = ""): Result<T> {
        handleError(error, context)
        return Result.failure(Exception(error.userMessage, error.cause))
    }

    /**
     * Wraps a block of code with error handling.
     *
     * @param context Context about the operation
     * @param block The code to execute
     * @return Result containing the value or error
     */
    inline fun <T> withErrorHandling(
        context: String,
        block: () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Exception) {
            val error = handleException(e, context)
            Result.failure(Exception(error.userMessage, e))
        }
    }

    /**
     * Wraps a suspend block of code with error handling.
     *
     * @param context Context about the operation
     * @param block The code to execute
     * @return Result containing the value or error
     */
    suspend inline fun <T> withErrorHandlingSuspend(
        context: String,
        crossinline block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Exception) {
            val error = handleException(e, context)
            Result.failure(Exception(error.userMessage, e))
        }
    }
}
