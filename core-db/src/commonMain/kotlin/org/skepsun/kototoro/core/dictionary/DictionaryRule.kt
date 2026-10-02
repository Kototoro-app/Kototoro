package org.skepsun.kototoro.core.dictionary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Legado-compatible online dictionary rule. */
@Serializable
@Entity(tableName = "dictionary_rules")
data class DictionaryRule(
    @PrimaryKey
    val name: String,
    val urlRule: String,
    val showRule: String = "",
    val enabled: Boolean = true,
    @ColumnInfo(name = "sortNumber")
    @SerialName("sortNumber")
    val sortNumber: Int = 0,
)

@Serializable
data class DictPair(
    val original: String,
    val translation: String,
)

@Serializable
data class BookDictionary(
    val bookKey: String,
    val pairs: List<DictPair> = emptyList(),
    val updatedAt: Long = 0L,
)
