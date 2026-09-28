package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moments",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["author"]),
    ]
)
data class MomentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("content")
    val content: String,
    /** 图片 URI 列表的 JSON 数组字符串，可为 "[]" */
    @ColumnInfo("images")
    val images: String = "[]",
    /** "user" 或 "ai" */
    @ColumnInfo("author")
    val author: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
