package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "diary_comments",
    indices = [
        Index(value = ["diary_id"]),
        Index(value = ["created_at"]),
    ]
)
data class DiaryCommentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("diary_id")
    val diaryId: String,
    @ColumnInfo("content")
    val content: String,
    /** "user" or "ai" */
    @ColumnInfo("author")
    val author: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** 回复的父评论 id，null 表示顶级评论 */
    @ColumnInfo("parent_id")
    val parentId: String? = null,
    @ColumnInfo("likes", defaultValue = "0")
    val likes: Int = 0,
    @ColumnInfo("liked", defaultValue = "0")
    val liked: Boolean = false,
)
