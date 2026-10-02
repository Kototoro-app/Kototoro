package org.skepsun.kototoro.core.replace

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Global text replacement rule, compatible with Legado replace rule JSON format.
 */
@Serializable
@Entity(
    tableName = "replace_rules",
    indices = [Index(value = ["sortOrder"])],
)
data class ReplaceRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val name: String = "",
    val group: String? = null,
    @ColumnInfo(defaultValue = "")
    val pattern: String = "",
    @ColumnInfo(defaultValue = "")
    val replacement: String = "",
    /** Legado's optional comma-separated source/book scope. */
    @ColumnInfo(name = "scope")
    @SerialName("scope")
    val scopeFilter: String? = null,
    @ColumnInfo(defaultValue = "0")
    val scopeTitle: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val scopeContent: Boolean = true,
    val excludeScope: String? = null,
    @ColumnInfo(defaultValue = "1")
    val isEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val isRegex: Boolean = true,
    @ColumnInfo(defaultValue = "3000")
    val timeoutMillisecond: Long = 3000L,
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    @SerialName("order")
    val order: Int = 0,
) {
    val scope: Scope
        get() = when {
            scopeTitle && scopeContent -> Scope.BOTH
            scopeTitle -> Scope.TITLE
            else -> Scope.CONTENT
        }

    fun appliesTo(target: Scope, scopeValues: Collection<String> = emptyList()): Boolean {
        val enabledForTarget = when (target) {
            Scope.TITLE -> scopeTitle
            Scope.CONTENT -> scopeContent
            Scope.BOTH -> scopeTitle && scopeContent
        }
        if (!enabledForTarget) return false
        if (!matchesIncludedScope(scopeFilter, scopeValues)) return false
        return !matchesExcludedScope(excludeScope, scopeValues)
    }

    /** Pre-compiled regex, lazy to fail only on first use. */
    val regex: Regex? by lazy {
        if (!isRegex || pattern.isBlank()) return@lazy null
        runCatching { Regex(pattern) }.getOrNull()
    }

    fun isValid(): Boolean {
        if (pattern.isBlank()) return false
        if (isRegex) {
            val r = regex ?: return false
            if (pattern.endsWith('|') && !pattern.endsWith("\\|")) return false
        }
        return true
    }

    fun apply(text: String): String {
        if (!isEnabled || pattern.isBlank()) return text
        return try {
            if (isRegex) {
                val r = regex ?: return text
                r.replace(text, replacement)
            } else {
                text.replace(pattern, replacement)
            }
        } catch (_: Exception) {
            text
        }
    }

    enum class Scope { TITLE, CONTENT, BOTH }

    private fun matchesIncludedScope(filter: String?, values: Collection<String>): Boolean {
        if (filter.isNullOrBlank()) return true
        return matchesAnyScopeValue(filter, values)
    }

    private fun matchesExcludedScope(filter: String?, values: Collection<String>): Boolean {
        if (filter.isNullOrBlank()) return false
        return matchesAnyScopeValue(filter, values)
    }

    private fun matchesAnyScopeValue(filter: String, values: Collection<String>): Boolean {
        val scopeValues = filter
            .split(',', '\n', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return values.any { value ->
            value.isNotBlank() && scopeValues.any { scopeValue ->
                // Match Legado's `scope LIKE '%' || name || '%'` behavior for import compatibility.
                scopeValue.contains(value.trim(), ignoreCase = true)
            }
        }
    }
}
