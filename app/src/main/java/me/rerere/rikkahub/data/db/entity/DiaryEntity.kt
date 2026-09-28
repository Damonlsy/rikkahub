package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "diaries",
    indices = [
        Index(value = ["date"]),
        Index(value = ["created_at"]),
    ]
)
data class DiaryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("content")
    val content: String,
    /** "user" or "ai" */
    @ColumnInfo("author")
    val author: String,
    /** yyyy-MM-dd */
    @ColumnInfo("date")
    val date: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    /** 该篇日记的自定义背景图（URI），null 表示跟随日记本背景 */
    @ColumnInfo("background")
    val background: String? = null,
)
