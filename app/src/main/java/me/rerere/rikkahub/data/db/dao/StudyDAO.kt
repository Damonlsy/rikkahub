package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.StudySessionEntity
import me.rerere.rikkahub.data.db.entity.StudyWordEntity

@Dao
interface StudyDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWords(words: List<StudyWordEntity>)

    @Update
    suspend fun updateWord(word: StudyWordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StudySessionEntity)

    @Query("SELECT COUNT(*) FROM study_words WHERE deck = :deck")
    suspend fun countByDeck(deck: String): Int

    @Query("SELECT * FROM study_words WHERE deck = :deck")
    suspend fun wordsByDeck(deck: String): List<StudyWordEntity>

    @Query("SELECT * FROM study_words WHERE deck = :deck AND learnedAt = 0 ORDER BY word ASC")
    suspend fun newWords(deck: String): List<StudyWordEntity>

    @Query("SELECT * FROM study_words WHERE deck = :deck AND learnedAt > 0 AND dueDay <= :today ORDER BY dueDay ASC, word ASC")
    suspend fun dueWords(deck: String, today: Long): List<StudyWordEntity>

    @Query("SELECT * FROM study_words WHERE id = :id")
    suspend fun wordById(id: String): StudyWordEntity?

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM study_sessions WHERE deck = :deck AND startedAt >= :startOfDay")
    suspend fun studyTimeToday(deck: String, startOfDay: Long): Long
}
