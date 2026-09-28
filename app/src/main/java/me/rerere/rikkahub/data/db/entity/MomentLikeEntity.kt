package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** 朋友圈点赞：一个作者对一条动态最多赞一次（复合主键去重）。 */
@Entity(
    tableName = "moment_likes",
    primaryKeys = ["moment_id", "author"],
    indices = [Index(value = ["moment_id"])]
)
data class MomentLikeEntity(
    @ColumnInfo("moment_id")
    val momentId: String,
    /** "user" 或 "ai" */
    @ColumnInfo("author")
    val author: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
