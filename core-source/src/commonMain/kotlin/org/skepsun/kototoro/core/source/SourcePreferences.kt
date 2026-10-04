package org.skepsun.kototoro.core.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** SharedPreferences types stay distinct; in particular an Int must not silently become a Long. */
@Serializable
sealed interface SourcePreferenceValue {
    @Serializable @SerialName("string")
    data class Text(val value: String) : SourcePreferenceValue

    @Serializable @SerialName("boolean")
    data class Toggle(val value: Boolean) : SourcePreferenceValue

    @Serializable @SerialName("int")
    data class Integer(val value: Int) : SourcePreferenceValue

    @Serializable @SerialName("long")
    data class LongInteger(@Serializable(with = SourceLongSerializer::class) val value: Long) : SourcePreferenceValue

    /** Raw IEEE bits preserve negative zero, infinity and NaN without nonstandard JSON numbers. */
    @Serializable @SerialName("float")
    data class FloatBits(val bits: Int) : SourcePreferenceValue

    @Serializable @SerialName("stringSet")
    data class TextSet(val values: Set<String>) : SourcePreferenceValue
}

/** Clear happens before the final per-key changes, regardless of editor call order; null removes a key. */
data class SourcePreferenceEdit(
    val clear: Boolean = false,
    val changes: Map<String, SourcePreferenceValue?> = emptyMap(),
)

data class SourcePreferenceChange(val keys: Set<String>, val cleared: Boolean)

fun interface SourcePreferenceListener {
    fun onChanged(change: SourcePreferenceChange)
}

interface SourcePreferences {
    /** Detached snapshot: changing a returned collection must never mutate the store. */
    fun snapshot(): Map<String, SourcePreferenceValue>

    /** Publishes memory and notifies listeners even if persistence fails; false reports a disk failure. */
    fun edit(edit: SourcePreferenceEdit): Boolean
    fun addListener(listener: SourcePreferenceListener)
    fun removeListener(listener: SourcePreferenceListener)
}

/** The compatibility Application must delegate getSharedPreferences here before constructing extensions. */
interface SourcePreferenceStore {
    fun open(namespace: String): SourcePreferences
}
