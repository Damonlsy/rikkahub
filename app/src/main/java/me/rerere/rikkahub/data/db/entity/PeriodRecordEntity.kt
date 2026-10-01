package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 经期记录：一天一条（同一日期重复记录会覆盖）。
 */
@Entity(tableName = "period_records")
data class PeriodRecordEntity(
    @PrimaryKey(true) val id: Long = 0,
    /** yyyy-MM-dd */
    val date: String,
    /** 流量：1=轻 2=中 3=重 */
    val flow: Int = 2,
    val note: String = "",
    val createdAt: Long,
)

/**
 * 纪念日：年重复（生日/纪念日）或固定一次性日期。
 */
@Entity(tableName = "anniversaries")
data class AnniversaryEntity(
    @PrimaryKey(true) val id: Long = 0,
    val title: String,
    /** yyyy-MM-dd（repeatYearly 时通常指第一次发生的那天） */
    val date: String,
    /** true = 每年重复 */
    val repeatYearly: Boolean = true,
    /** 可选 emoji 标记 */
    val emoji: String = "",
    val createdAt: Long,
)
