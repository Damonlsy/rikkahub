package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拍一拍词库。一条 = 一个词。
 *
 * 三个槽位（[slot]）拼成一句拍一拍：
 * 方式 + 动作 + 部位，例如「轻轻地」+「拍了拍」+「脑袋」。
 *
 * 用户和 AI 各有一套独立的词库（[author]），互不干扰。
 */
@Entity(
    tableName = "pat_actions",
    indices = [
        Index(value = ["author"]),
        Index(value = ["created_at"]),
    ]
)
data class PatActionEntity(
    @PrimaryKey
    val id: String,
    /** 词本身，例如：轻轻地 / 拍了拍 / 脑袋 */
    @ColumnInfo("text")
    val text: String,
    /** "user" 或 "ai"：谁的词库 */
    @ColumnInfo("author")
    val author: String,
    /**
     * 槽位：manner(方式) / action(动作) / part(部位)。
     * 旧版本没有这一列，升级上来是空串，由 [me.rerere.rikkahub.utils.PatDefaults] 归位成动作。
     */
    @ColumnInfo(name = "slot", defaultValue = "")
    val slot: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
