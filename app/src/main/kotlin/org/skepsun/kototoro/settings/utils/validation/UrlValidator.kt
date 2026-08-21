package org.skepsun.kototoro.settings.utils.validation

import android.webkit.URLUtil
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.EditTextValidator

class UrlValidator : EditTextValidator() {

    override fun validate(text: String): ValidationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Success
        }
        return if (!isValidUrl(trimmed)) {
            ValidationResult.Failed(context.getString(R.string.invalid_server_address_message))
        } else {
            ValidationResult.Success
        }
    }

    private fun isValidUrl(str: String): Boolean {
        return URLUtil.isValidUrl(str) || DomainValidator.isValidDomain(str)
    }
}
