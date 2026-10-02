package org.skepsun.kototoro.core.dictionary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDictionaryDao {

    @Query("SELECT * FROM translation_dictionaries WHERE bookKey = :bookKey")
    suspend fun get(bookKey: String): TranslationDictionaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dictionary: TranslationDictionaryEntity)

    @Query("DELETE FROM translation_dictionaries WHERE bookKey = :bookKey")
    suspend fun delete(bookKey: String)
}
