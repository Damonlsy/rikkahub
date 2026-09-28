package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.StickerEntity

@Dao
interface StickerDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sticker: StickerEntity)

    @Query("SELECT * FROM stickers WHERE owner = :owner ORDER BY created_at DESC")
    fun listByOwner(owner: String): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers WHERE owner = :owner ORDER BY created_at DESC")
    suspend fun getByOwner(owner: String): List<StickerEntity>

    @Query("SELECT * FROM stickers ORDER BY created_at DESC")
    suspend fun getAll(): List<StickerEntity>

    @Query("SELECT * FROM stickers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StickerEntity?

    @Query("DELETE FROM stickers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM stickers WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int

    @Query("SELECT * FROM stickers ORDER BY created_at DESC")
    fun listAll(): Flow<List<StickerEntity>>
}
