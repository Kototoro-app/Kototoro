package org.skepsun.kototoro.core.jsonsource

import android.content.Context
import org.skepsun.kototoro.R

/**
 * Utility class for formatting JsonSourceError instances into user-friendly localized messages.
 *
 * This class provides methods to convert technical error objects into human-readable strings
 * that can be displayed to users in the UI. All messages are localized based on the device's
 * language settings.
 */
object JsonSourceErrorFormatter {

    /**
     * Formats a JsonSourceError into a user-friendly localized message.
     *
     * @param context Android context for accessing string resources
     * @param error The error to format
     * @return A localized, user-friendly error message
     */
    fun format(context: Context, error: JsonSourceError): String {
        return when (error) {
            is JsonSourceError.InvalidJsonFormat -> {
                context.getString(
                    R.string.error_invalid_json_format,
                    error.position,
                    error.message
                )
            }

            is JsonSourceError.MissingRequiredField -> {
                context.getString(
                    R.string.error_missing_required_field,
                    error.fieldName
                )
            }

            is JsonSourceError.InvalidRuleSyntax -> {
                context.getString(
                    R.string.error_invalid_rule_syntax,
                    error.reason,
                    error.rule
                )
            }

            is JsonSourceError.NetworkError -> {
                context.getString(
                    R.string.error_network_error,
                    error.url
                )
            }

            is JsonSourceError.DatabaseError -> {
                context.getString(
                    R.string.error_database_error,
                    error.operation
                )
            }

            is JsonSourceError.DuplicateSourceId -> {
                context.getString(
                    R.string.error_duplicate_source_id,
                    error.sourceId
                )
            }
        }
    }

    /**
     * Formats a generic Throwable into a user-friendly message.
     *
     * This method attempts to extract a JsonSourceError from the throwable chain,
     * and if found, formats it appropriately. Otherwise, it returns a generic error message.
     *
     * @param context Android context for accessing string resources
     * @param throwable The throwable to format
     * @return A localized, user-friendly error message
     */
    fun formatThrowable(context: Context, throwable: Throwable): String {
        // Check if the throwable is a JsonSourceError
        val jsonError = findJsonSourceError(throwable)
        if (jsonError != null) {
            return format(context, jsonError)
        }

        // Check for common exception types
        return when (throwable) {
            is kotlinx.serialization.SerializationException -> {
                context.getString(R.string.error_json_parsing_failed)
            }

            is IllegalArgumentException -> {
                // Return the message if it's meaningful, otherwise use generic message
                if (!throwable.message.isNullOrBlank()) {
                    throwable.message!!
                } else {
                    context.getString(R.string.error_source_validation_failed)
                }
            }

            else -> {
                // Generic error message with exception type
                "${context.getString(R.string.error_import_failed)}: ${throwable.message ?: throwable.javaClass.simpleName}"
            }
        }
    }

    /**
     * Searches the throwable chain for a JsonSourceError.
     *
     * @param throwable The throwable to search
     * @return The first JsonSourceError found in the chain, or null if none found
     */
    private fun findJsonSourceError(throwable: Throwable): JsonSourceError? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is JsonSourceError) {
                return current
            }
            current = current.cause
        }
        return null
    }

    /**
     * Formats a list of validation errors into a single message.
     *
     * @param context Android context for accessing string resources
     * @param errors List of error messages
     * @return A formatted string containing all errors
     */
    fun formatValidationErrors(context: Context, errors: List<String>): String {
        return if (errors.isEmpty()) {
            context.getString(R.string.error_source_validation_failed)
        } else if (errors.size == 1) {
            errors[0]
        } else {
            // Format multiple errors as a bulleted list
            errors.joinToString("\n") { "• $it" }
        }
    }
}
