package org.skepsun.kototoro.core.dictionary

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryRuleDao {

    @Query("SELECT * FROM dictionary_rules ORDER BY sortNumber ASC, name ASC")
    suspend fun getAll(): List<DictionaryRule>

    @Query("SELECT * FROM dictionary_rules WHERE enabled = 1 ORDER BY sortNumber ASC, name ASC")
    suspend fun getEnabled(): List<DictionaryRule>

    @Query("SELECT * FROM dictionary_rules ORDER BY sortNumber ASC, name ASC")
    fun observeAll(): Flow<List<DictionaryRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rules: List<DictionaryRule>)

    @Delete
    suspend fun delete(rule: DictionaryRule)

    @Query("DELETE FROM dictionary_rules")
    suspend fun deleteAll()
}
