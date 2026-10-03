package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PeriodRecordEntity

@Dao
interface PeriodDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PeriodRecordEntity): Long

    @Query("SELECT * FROM period_records ORDER BY date ASC")
    fun listAll(): Flow<List<PeriodRecordEntity>>

    @Query("SELECT * FROM period_records ORDER BY date ASC")
    suspend fun getAll(): List<PeriodRecordEntity>

    @Query("SELECT * FROM period_records WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): PeriodRecordEntity?

    @Query("DELETE FROM period_records WHERE id = :id")
    suspend fun delete(id: Long)
}
