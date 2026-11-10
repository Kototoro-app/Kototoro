package org.skepsun.kototoro.core.jsonsource

import org.skepsun.kototoro.core.parser.legado.BoundedRegex
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
     * Checks if a hostname is a local address.
     *
     * IP literals are classified by address (IPv6, IPv4-mapped IPv6, link-local, CGNAT and the shorthand
     * numeric forms such as `2130706433` or `0x7f.1` that resolvers accept). Names are not resolved: a
     * source can already request any URL through its own rules, so DNS checks here would not close that.
     *
     * @param host The hostname to check
     * @return true if the host is a local address
     */
    internal fun isLocalAddress(host: String): Boolean {
        val lowerHost = host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

        if (lowerHost == "localhost" || lowerHost.endsWith(".localhost")) {
            return true
        }

        val address = when {
            lowerHost.contains(':') -> parseIpv6Literal(lowerHost) ?: return true
            NUMERIC_HOST.matches(lowerHost) -> parseIpv4Literal(lowerHost) ?: return false
            else -> return false
        }
        return isLocalAddress(address)
    }

    private fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (address) {
            // 100.64.0.0/10 carrier-grade NAT
            is Inet4Address -> bytes[0].toInt() and 0xFF == 100 && bytes[1].toInt() and 0xC0 == 64
            // fc00::/7 unique local; ::ffff:a.b.c.d is normalized to Inet4Address by the parser
            is Inet6Address -> bytes[0].toInt() and 0xFE == 0xFC
            else -> false
        }
    }

    /** Parses a literal only; [InetAddress.getByName] does no lookup for strings that contain ':'. */
    private fun parseIpv6Literal(host: String): InetAddress? = runCatching {
        InetAddress.getByName(host.substringBefore('%'))
    }.getOrNull()

    /**
     * Parses the inet_aton forms: 1 to 4 parts, each decimal, octal (leading 0) or hex (0x), the last part
     * filling the remaining bytes. Returns null for strings that are not a valid address in that grammar.
     */
    private fun parseIpv4Literal(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size > 4) return null
        val values = parts.map { part ->
            when {
                part.startsWith("0x") -> part.drop(2).ifEmpty { "0" }.toLongOrNull(16)
                part.length > 1 && part.startsWith("0") -> part.drop(1).toLongOrNull(8)
                else -> part.toLongOrNull()
            } ?: return null
        }
        var value = 0L
        for (i in 0 until values.lastIndex) {
            if (values[i] > 0xFF) return null
            value = value or (values[i] shl (8 * (3 - i)))
        }
        val lastBytes = 4 - values.lastIndex
        if (values.last() >= 1L shl (8 * lastBytes)) return null
        value = value or values.last()
        return InetAddress.getByAddress(
            byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte(),
            ),
        )
    }

    private val NUMERIC_HOST = Regex("^[0-9a-fx.]+$")

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

    /** Runs source-supplied regular expressions with the shared execution deadline. */
    fun findBounded(regex: Regex, input: CharSequence, timeoutMs: Long = REGEX_TIMEOUT_MS): MatchResult? =
        BoundedRegex.findBounded(regex, input, timeoutMs)

    fun replaceBounded(
        regex: Regex, input: CharSequence, replacement: String, timeoutMs: Long = REGEX_TIMEOUT_MS,
    ): String? = BoundedRegex.replaceBounded(regex, input, replacement, timeoutMs)

    fun replaceFirstBounded(
        regex: Regex, input: CharSequence, replacement: String, timeoutMs: Long = REGEX_TIMEOUT_MS,
    ): String? = BoundedRegex.replaceFirstBounded(regex, input, replacement, timeoutMs)

    fun regexDeadline(timeoutMs: Long = REGEX_TIMEOUT_MS): Long = BoundedRegex.regexDeadline(timeoutMs)

    fun <T> withRegexDeadline(input: CharSequence, deadline: Long, block: (CharSequence) -> T): T? =
        BoundedRegex.withRegexDeadline(input, deadline, block)

    private const val REGEX_TIMEOUT_MS = 2_000L

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
