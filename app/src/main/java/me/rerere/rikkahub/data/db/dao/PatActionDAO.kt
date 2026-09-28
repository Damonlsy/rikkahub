package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PatActionEntity

@Dao
interface PatActionDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(action: PatActionEntity)

    @Query("SELECT * FROM pat_actions ORDER BY created_at ASC")
    fun listActions(): Flow<List<PatActionEntity>>

    @Query("SELECT * FROM pat_actions ORDER BY created_at ASC")
    suspend fun getAll(): List<PatActionEntity>

    @Query("DELETE FROM pat_actions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM pat_actions")
    suspend fun count(): Int
}
