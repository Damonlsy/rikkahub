package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.LedgerEntity

@Dao
interface LedgerDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LedgerEntity)

    @Query("SELECT * FROM ledger_entries ORDER BY created_at DESC")
    fun listAll(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledger_entries ORDER BY created_at DESC")
    suspend fun getAll(): List<LedgerEntity>

    @Query("SELECT * FROM ledger_entries WHERE wallet = :wallet ORDER BY created_at DESC")
    fun listByWallet(wallet: String): Flow<List<LedgerEntity>>

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM ledger_entries WHERE wallet = :wallet")
    suspend fun deleteByWallet(wallet: String)
}
