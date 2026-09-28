package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MomentCommentEntity
import me.rerere.rikkahub.data.db.entity.MomentEntity
import me.rerere.rikkahub.data.db.entity.MomentFavoriteEntity
import me.rerere.rikkahub.data.db.entity.MomentLikeEntity

@Dao
interface MomentDAO {
    // ---- 动态 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoment(moment: MomentEntity)

    @Query("SELECT * FROM moments ORDER BY created_at DESC")
    fun listMoments(): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments ORDER BY created_at DESC")
    suspend fun getAllMoments(): List<MomentEntity>

    @Query("SELECT * FROM moments WHERE id = :id LIMIT 1")
    suspend fun getMoment(id: String): MomentEntity?

    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun deleteMoment(id: String)

    // ---- 点赞 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addLike(like: MomentLikeEntity)

    @Query("DELETE FROM moment_likes WHERE moment_id = :momentId AND author = :author")
    suspend fun removeLike(momentId: String, author: String)

    @Query("SELECT * FROM moment_likes")
    fun listLikes(): Flow<List<MomentLikeEntity>>

    @Query("SELECT * FROM moment_likes")
    suspend fun getLikes(): List<MomentLikeEntity>

    @Query("DELETE FROM moment_likes WHERE moment_id = :momentId")
    suspend fun deleteLikesOfMoment(momentId: String)

    // ---- 评论 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addComment(comment: MomentCommentEntity)

    @Query("DELETE FROM moment_comments WHERE id = :id")
    suspend fun deleteComment(id: String)

    @Query("SELECT * FROM moment_comments ORDER BY created_at ASC")
    fun listComments(): Flow<List<MomentCommentEntity>>

    @Query("SELECT * FROM moment_comments ORDER BY created_at ASC")
    suspend fun getComments(): List<MomentCommentEntity>

    @Query("DELETE FROM moment_comments WHERE moment_id = :momentId")
    suspend fun deleteCommentsOfMoment(momentId: String)

    // ---- 收藏 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: MomentFavoriteEntity)

    @Query("DELETE FROM moment_favorites WHERE moment_id = :momentId AND author = :author")
    suspend fun removeFavorite(momentId: String, author: String)

    @Query("SELECT * FROM moment_favorites")
    fun listFavorites(): Flow<List<MomentFavoriteEntity>>

    @Query("SELECT * FROM moment_favorites")
    suspend fun getFavorites(): List<MomentFavoriteEntity>

    @Query("DELETE FROM moment_favorites WHERE moment_id = :momentId")
    suspend fun deleteFavoritesOfMoment(momentId: String)
}
