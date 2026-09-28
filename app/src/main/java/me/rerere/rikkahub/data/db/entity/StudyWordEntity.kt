package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(tableName = "study_words")
data class StudyWordEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    val deck: String,
    val word: String,
    val phonetic: String,
    val meaning: String,
    val example: String,
    val exampleMeaning: String,
    val box: Int = 0,
    val intervalDays: Int = 0,
    val dueDay: Long = 0,
    val lastReviewedAt: Long = 0,
    val learnedAt: Long = 0,
)
