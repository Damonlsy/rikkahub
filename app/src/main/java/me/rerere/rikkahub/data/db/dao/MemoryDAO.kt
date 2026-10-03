package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND deleted_at = 0")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND deleted_at = 0")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND deleted_at > 0 ORDER BY deleted_at DESC")
    fun getTrashOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND kind LIKE 'context:%' AND deleted_at = 0 ORDER BY updated_at DESC")
    suspend fun getContextMemories(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND kind LIKE 'compression:%' AND deleted_at = 0 ORDER BY created_at DESC")
    suspend fun getCompressionRecords(assistantId: String): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Insert
    suspend fun insertMemories(memories: List<MemoryEntity>)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("UPDATE memoryentity SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun deleteMemory(id: Int, deletedAt: Long)

    @Query("UPDATE memoryentity SET deleted_at = 0 WHERE id = :id")
    suspend fun restoreMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE deleted_at > 0 AND deleted_at < :cutoff")
    suspend fun purgeTrash(cutoff: Long)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
