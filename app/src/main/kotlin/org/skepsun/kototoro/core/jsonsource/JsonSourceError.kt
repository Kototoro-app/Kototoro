package org.skepsun.kototoro.core.jsonsource

/**
 * Sealed class representing all possible errors that can occur during JSON source operations.
 *
 * This hierarchy provides type-safe error handling for:
 * - JSON parsing and validation
 * - Network operations
 * - Database operations
 * - Rule syntax validation
 * - Source identifier conflicts
 */
sealed class JsonSourceError : Exception() {

    /**
     * Error indicating invalid JSON format during parsing.
     *
     * @property position The character position where the error occurred (if available)
     * @property message Detailed error message describing the parsing issue
     */
    data class InvalidJsonFormat(
        val position: Int,
        override val message: String,
    ) : JsonSourceError() {
        override fun toString(): String = "InvalidJsonFormat(position=$position, message='$message')"
    }

    /**
     * Error indicating a required field is missing from the JSON configuration.
     *
     * @property fieldName The name of the missing required field
     */
    data class MissingRequiredField(
        val fieldName: String,
    ) : JsonSourceError() {
        override val message: String
            get() = "Required field '$fieldName' is missing"

        override fun toString(): String = "MissingRequiredField(fieldName='$fieldName')"
    }

    /**
     * Error indicating invalid rule syntax in a JSON source configuration.
     *
     * @property rule The rule string that failed to parse
     * @property reason Explanation of why the rule is invalid
     */
    data class InvalidRuleSyntax(
        val rule: String,
        val reason: String,
    ) : JsonSourceError() {
        override val message: String
            get() = "Invalid rule syntax: $reason (rule: '$rule')"

        override fun toString(): String = "InvalidRuleSyntax(rule='$rule', reason='$reason')"
    }

    /**
     * Error indicating a network operation failure.
     *
     * @property url The URL that failed to be accessed
     * @property cause The underlying exception that caused the network error
     */
    data class NetworkError(
        val url: String,
        override val cause: Throwable,
    ) : JsonSourceError() {
        override val message: String
            get() = "Network error accessing '$url': ${cause.message}"

        override fun toString(): String = "NetworkError(url='$url', cause=${cause.javaClass.simpleName})"
    }

    /**
     * Error indicating a database operation failure.
     *
     * @property operation Description of the database operation that failed
     * @property cause The underlying exception that caused the database error
     */
    data class DatabaseError(
        val operation: String,
        override val cause: Throwable,
    ) : JsonSourceError() {
        override val message: String
            get() = "Database error during '$operation': ${cause.message}"

        override fun toString(): String = "DatabaseError(operation='$operation', cause=${cause.javaClass.simpleName})"
    }

    /**
     * Error indicating a source identifier conflict (duplicate ID).
     *
     * @property sourceId The conflicting source identifier
     */
    data class DuplicateSourceId(
        val sourceId: String,
    ) : JsonSourceError() {
        override val message: String
            get() = "Source with ID '$sourceId' already exists"

        override fun toString(): String = "DuplicateSourceId(sourceId='$sourceId')"
    }
}
