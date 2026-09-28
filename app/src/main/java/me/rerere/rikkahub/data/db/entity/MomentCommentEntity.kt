package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moment_comments",
    indices = [
        Index(value = ["moment_id"]),
        Index(value = ["created_at"]),
    ]
)
data class MomentCommentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("moment_id")
    val momentId: String,
    @ColumnInfo("content")
    val content: String,
    /** "user" 或 "ai" */
    @ColumnInfo("author")
    val author: String,
    /** 回复的评论作者（"user" 或 "ai"），null 表示顶级评论 */
    @ColumnInfo("reply_to")
    val replyTo: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
