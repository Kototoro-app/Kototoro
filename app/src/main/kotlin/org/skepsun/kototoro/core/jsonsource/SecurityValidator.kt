package org.skepsun.kototoro.core.jsonsource

import java.net.URI
import java.net.URISyntaxException

/**
 * Security validator for JSON source configurations
 * Validates URLs, regex patterns, and input data to prevent security vulnerabilities
 */
object SecurityValidator {

    /**
     * Validates a URL for security concerns
     *
     * @param url The URL to validate
     * @return ValidationResult indicating if the URL is safe
     *
     * Validates: Requirements 13.1, 13.2
     */
    fun validateUrl(url: String): ValidationResult {
        if (url.isBlank()) {
            return ValidationResult(
                isValid = false,
                errors = listOf("URL cannot be empty")
            )
        }

        return try {
            val uri = URI(url)

            // Check protocol - only allow http and https
            val scheme = uri.scheme?.lowercase()
            if (scheme !in listOf("http", "https")) {
                return ValidationResult(
                    isValid = false,
                    errors = listOf("Invalid protocol: only HTTP and HTTPS are allowed")
                )
            }

            // Check host validity
            val host = uri.host
            if (host.isNullOrBlank()) {
                return ValidationResult(
                    isValid = false,
                    errors = listOf("Invalid URL: missing or invalid hostname")
                )
            }

            // Prevent access to local addresses
            if (isLocalAddress(host)) {
                return ValidationResult(
                    isValid = false,
                    errors = listOf("Access to local addresses is not allowed")
                )
            }

            ValidationResult(isValid = true, errors = emptyList())

        } catch (e: URISyntaxException) {
            ValidationResult(
                isValid = false,
                errors = listOf("Invalid URL format: ${e.message}")
            )
        } catch (e: Exception) {
            ValidationResult(
                isValid = false,
                errors = listOf("URL validation error: ${e.message}")
            )
        }
    }

    /**
     * Checks if a hostname is a local address
     *
     * @param host The hostname to check
     * @return true if the host is a local address
     */
    private fun isLocalAddress(host: String): Boolean {
        val lowerHost = host.lowercase()

        // Check for localhost variants
        if (lowerHost in listOf("localhost", "127.0.0.1", "0.0.0.0", "::1")) {
            return true
        }

        // Check for private network ranges
        // 192.168.0.0/16
        if (lowerHost.startsWith("192.168.")) {
            return true
        }

        // 10.0.0.0/8
        if (lowerHost.startsWith("10.")) {
            return true
        }

        // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        if (lowerHost.startsWith("172.")) {
            val parts = lowerHost.split(".")
            if (parts.size >= 2) {
                val secondOctet = parts[1].toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Validates a regular expression for security concerns
     *
     * @param pattern The regex pattern to validate
     * @return ValidationResult indicating if the regex is safe
     *
     * Validates: Requirements 13.4
     */
    fun validateRegex(pattern: String): ValidationResult {
        // Limit regex length to prevent excessive memory usage
        if (pattern.length > 500) {
            return ValidationResult(
                isValid = false,
                errors = listOf("Regular expression is too long (max 500 characters)")
            )
        }

        // Check for dangerous patterns that could cause ReDoS attacks
        val dangerousPatterns = listOf(
            "(.*)*" to "Nested quantifiers on wildcard",
            "(a+)+" to "Nested quantifiers",
            "(a|a)*" to "Redundant alternation with quantifier",
            "(a*)*" to "Nested star quantifiers",
            "(a+)*" to "Plus followed by star quantifier",
            "(a*a*)*" to "Multiple nested quantifiers",
            "(.+)*" to "Nested quantifiers on wildcard",
            "(.*)++" to "Possessive nested quantifiers"
        )

        val errors = mutableListOf<String>()
        for ((dangerousPattern, reason) in dangerousPatterns) {
            if (pattern.contains(dangerousPattern)) {
                errors.add("Potentially dangerous pattern detected: $reason")
            }
        }

        // Try to compile the regex to check for syntax errors
        try {
            Regex(pattern)
        } catch (e: Exception) {
            errors.add("Invalid regex syntax: ${e.message}")
        }

        return if (errors.isEmpty()) {
            ValidationResult(isValid = true, errors = emptyList())
        } else {
            ValidationResult(isValid = false, errors = errors)
        }
    }

    /**
     * Sanitizes HTML content to prevent XSS attacks
     *
     * @param input The input string to sanitize
     * @return Sanitized string with HTML entities escaped
     *
     * Validates: Requirements 13.3
     */
    fun sanitizeHtmlInput(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
    }

    /**
     * Validates JSON file size
     *
     * @param sizeInBytes The size of the JSON file in bytes
     * @return ValidationResult indicating if the size is acceptable
     *
     * Validates: Requirements 13.3
     */
    fun validateJsonFileSize(sizeInBytes: Long): ValidationResult {
        // Practically no limit for local imports as requested
        val maxSizeBytes = 100 * 1024 * 1024 // 100MB

        return if (sizeInBytes > maxSizeBytes) {
            ValidationResult(
                isValid = false,
                errors = listOf("JSON file is too large (max 100MB)")
            )
        } else {
            ValidationResult(isValid = true, errors = emptyList())
        }
    }

    /**
     * Validates a field value format
     *
     * @param fieldName The name of the field
     * @param value The value to validate
     * @param maxLength Maximum allowed length
     * @return ValidationResult indicating if the field is valid
     *
     * Validates: Requirements 13.3
     */
    fun validateFieldFormat(
        fieldName: String,
        value: String,
        maxLength: Int = 1000
    ): ValidationResult {
        if (value.isBlank()) {
            return ValidationResult(
                isValid = false,
                errors = listOf("$fieldName cannot be empty")
            )
        }

        if (value.length > maxLength) {
            return ValidationResult(
                isValid = false,
                errors = listOf("$fieldName is too long (max $maxLength characters)")
            )
        }

        return ValidationResult(isValid = true, errors = emptyList())
    }
}
