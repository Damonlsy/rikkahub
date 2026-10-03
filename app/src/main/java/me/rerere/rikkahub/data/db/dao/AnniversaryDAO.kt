package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AnniversaryEntity

@Dao
interface AnniversaryDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnniversaryEntity): Long

    @Query("SELECT * FROM anniversaries ORDER BY date ASC")
    fun listAll(): Flow<List<AnniversaryEntity>>

    @Query("SELECT * FROM anniversaries ORDER BY date ASC")
    suspend fun getAll(): List<AnniversaryEntity>

    @Query("DELETE FROM anniversaries WHERE id = :id")
    suspend fun delete(id: Long)
}
