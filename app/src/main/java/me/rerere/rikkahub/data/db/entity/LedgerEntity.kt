package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ledger_entries",
    indices = [
        Index(value = ["wallet"]),
        Index(value = ["created_at"]),
    ]
)
data class LedgerEntity(
    @PrimaryKey
    val id: String,
    /** "user" 或 "ai" */
    @ColumnInfo("wallet")
    val wallet: String,
    /** "income" 或 "expense" */
    @ColumnInfo("kind")
    val kind: String,
    /** 金额（单位：分，恒为正） */
    @ColumnInfo("amount")
    val amount: Long,
    @ColumnInfo("note")
    val note: String = "",
    /** 记录人："ai" 或 "user"；老数据为空串，显示时按 wallet 兜底推断 */
    @ColumnInfo("recorder", defaultValue = "")
    val recorder: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
)
