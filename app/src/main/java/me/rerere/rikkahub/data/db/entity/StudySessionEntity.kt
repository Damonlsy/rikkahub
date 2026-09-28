package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    val deck: String,
    val startedAt: Long,
    val durationMs: Long,
    val reviewedCount: Int,
    val learnedCount: Int,
)
