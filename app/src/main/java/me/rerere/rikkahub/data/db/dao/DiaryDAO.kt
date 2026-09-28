package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.data.db.entity.DiaryEntity

@Dao
interface DiaryDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiary(diary: DiaryEntity)

    @Query("SELECT * FROM diaries ORDER BY date DESC, created_at DESC")
    fun listDiaries(): Flow<List<DiaryEntity>>

    @Query("SELECT * FROM diaries ORDER BY date DESC, created_at DESC")
    suspend fun getAllDiaries(): List<DiaryEntity>

    @Query("SELECT * FROM diaries WHERE id = :id LIMIT 1")
    suspend fun getDiary(id: String): DiaryEntity?

    @Query("SELECT * FROM diaries WHERE id = :id LIMIT 1")
    fun getDiaryFlow(id: String): Flow<DiaryEntity?>

    @Query("DELETE FROM diaries WHERE id = :id")
    suspend fun deleteDiary(id: String)

    @Query("UPDATE diaries SET background = :background, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDiaryBackground(id: String, background: String?, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComment(comment: DiaryCommentEntity)

    @Query("SELECT * FROM diary_comments WHERE diary_id = :diaryId ORDER BY created_at ASC")
    fun listComments(diaryId: String): Flow<List<DiaryCommentEntity>>

    @Query("SELECT * FROM diary_comments WHERE diary_id = :diaryId ORDER BY created_at ASC")
    suspend fun getComments(diaryId: String): List<DiaryCommentEntity>

    @Query("DELETE FROM diary_comments WHERE id = :id")
    suspend fun deleteComment(id: String)

    @Query("UPDATE diary_comments SET likes = likes + 1, liked = 1 WHERE id = :id")
    suspend fun likeComment(id: String)

    @Query("UPDATE diary_comments SET likes = CASE WHEN likes > 0 THEN likes - 1 ELSE 0 END, liked = 0 WHERE id = :id")
    suspend fun unlikeComment(id: String)

    @Query("DELETE FROM diary_comments WHERE diary_id = :diaryId")
    suspend fun deleteCommentsOfDiary(diaryId: String)
}
