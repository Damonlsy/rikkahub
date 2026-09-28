package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表情包库：用户和 AI 各有一份，互相独立。
 * 用户发出去的表情包默认只在用户库里，AI 需要自己（或由用户）收藏才会进 AI 库。
 */
@Entity(
    tableName = "stickers",
    indices = [
        Index(value = ["owner"]),
        Index(value = ["created_at"]),
    ]
)
data class StickerEntity(
    @PrimaryKey
    val id: String,
    /** 本地图片 URI（file://...） */
    @ColumnInfo("uri")
    val uri: String,
    /** "user" 或 "ai" */
    @ColumnInfo("owner")
    val owner: String,
    /** 备注/关键词，给 AI 识别用 */
    @ColumnInfo("name")
    val name: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
)
