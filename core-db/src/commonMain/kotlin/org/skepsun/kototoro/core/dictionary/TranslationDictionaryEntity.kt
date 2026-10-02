package org.skepsun.kototoro.core.dictionary

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_dictionaries")
data class TranslationDictionaryEntity(
    @PrimaryKey
    val bookKey: String,
    val pairsJson: String,
    val updatedAt: Long,
)
