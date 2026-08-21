package org.skepsun.kototoro.core.jsonsource

/**
 * Result of a validation operation
 *
 * @property isValid Whether the validation passed
 * @property errors List of error messages if validation failed
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
) {
    companion object {
        /**
         * Create a successful validation result
         */
        fun success(): ValidationResult = ValidationResult(isValid = true, errors = emptyList())

        /**
         * Create a failed validation result with a single error
         */
        fun failure(error: String): ValidationResult = ValidationResult(isValid = false, errors = listOf(error))

        /**
         * Create a failed validation result with multiple errors (varargs)
         */
        fun failure(vararg errors: String): ValidationResult = ValidationResult(isValid = false, errors = errors.toList())

        /**
         * Create a failed validation result with multiple errors (list)
         */
        fun failure(errors: List<String>): ValidationResult = ValidationResult(isValid = false, errors = errors)
    }
}
