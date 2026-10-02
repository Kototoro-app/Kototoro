package org.skepsun.kototoro.core.replace

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplaceRuleDao {

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<ReplaceRule>

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<ReplaceRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rules: List<ReplaceRule>)

    @Query("DELETE FROM replace_rules")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(rules: List<ReplaceRule>) {
        deleteAll()
        if (rules.isNotEmpty()) upsert(rules)
    }
}
