package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.dao.StudyDAO
import me.rerere.rikkahub.data.db.entity.StudyWordEntity
import java.time.LocalDate
import java.time.ZoneId

object StudyDecks {
    const val HIGH_SCHOOL = "high_school"
    const val CET46 = "cet46"

    val ALL = listOf(HIGH_SCHOOL, CET46)

    fun displayName(deck: String): String = when (deck) {
        HIGH_SCHOOL -> "高中必备词汇"
        CET46 -> "大学四六级必备词汇"
        else -> deck
    }
}

object StudyScheduling {
    val INTERVALS = listOf(0, 1, 3, 7, 14, 30)

    fun grade(word: StudyWordEntity, known: Boolean, now: Long, today: Long): StudyWordEntity {
        val box = if (known) (word.box + 1).coerceAtMost(INTERVALS.lastIndex) else 0
        return word.copy(
            box = box,
            intervalDays = INTERVALS[box],
            dueDay = today + INTERVALS[box],
            lastReviewedAt = now,
            learnedAt = if (word.learnedAt == 0L) now else word.learnedAt,
        )
    }
}

data class DeckSummary(
    val deck: String,
    val total: Int,
    val learned: Int,
    val mastered: Int,
    val due: Int,
    val newCount: Int,
    val todayNew: Int,
    val todayReviewed: Int,
    val todayStudyMs: Long,
)

fun currentEpochDay(): Long = LocalDate.now().toEpochDay()

fun todayStartMillis(): Long =
    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

class StudyRepository(
    private val context: Context,
    private val dao: StudyDAO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureDataLoaded() {
        for (deck in StudyDecks.ALL) {
            if (dao.countByDeck(deck) == 0) {
                dao.insertWords(loadAsset(deck))
            }
        }
    }

    suspend fun summaries(): List<DeckSummary> {
        return StudyDecks.ALL.map { deck ->
            summary(dao.wordsByDeck(deck), deck)
        }
    }

    private suspend fun summary(words: List<StudyWordEntity>, deck: String): DeckSummary {
        val today = currentEpochDay()
        val startOfDay = todayStartMillis()
        return DeckSummary(
            deck = deck,
            total = words.size,
            learned = words.count { it.learnedAt > 0 },
            mastered = words.count { it.box >= 4 },
            due = words.count { it.learnedAt > 0 && it.dueDay <= today },
            newCount = words.count { it.learnedAt == 0L },
            todayNew = words.count { it.learnedAt >= startOfDay },
            todayReviewed = words.count { it.lastReviewedAt >= startOfDay },
            todayStudyMs = dao.studyTimeToday(deck, startOfDay),
        )
    }

    suspend fun queue(deckId: String): List<StudyWordEntity> {
        val today = currentEpochDay()
        return dao.newWords(deckId) + dao.dueWords(deckId, today)
    }

    suspend fun grade(wordId: String, known: Boolean) {
        val word = dao.wordById(wordId) ?: return
        dao.updateWord(StudyScheduling.grade(word, known, System.currentTimeMillis(), currentEpochDay()))
    }

    suspend fun saveSession(deck: String, durationMs: Long, reviewed: Int, learned: Int) {
        if (durationMs < 1000L || reviewed <= 0) return
        dao.insertSession(
            me.rerere.rikkahub.data.db.entity.StudySessionEntity(
                deck = deck,
                startedAt = System.currentTimeMillis(),
                durationMs = durationMs,
                reviewedCount = reviewed,
                learnedCount = learned,
            )
        )
    }

    suspend fun statusJson(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        val startOfDay = todayStartMillis()
        for (deck in StudyDecks.ALL) {
            val words = dao.wordsByDeck(deck)
            put(deck, buildJsonObject {
                put("deckName", StudyDecks.displayName(deck))
                put("totalWords", words.size)
                put("learnedWords", words.count { it.learnedAt > 0 })
                put("masteredWords", words.count { it.box >= 4 })
                put("todayNewWords", words.count { it.learnedAt >= startOfDay })
                put("todayReviewedWords", words.count { it.lastReviewedAt >= startOfDay })
                put("todayStudySeconds", dao.studyTimeToday(deck, startOfDay) / 1000)
            })
        }
    }

    private fun loadAsset(deck: String): List<StudyWordEntity> {
        val text = context.assets.open("study/$deck.json").bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        val words = root["words"]?.jsonArray ?: error("study/$deck.json missing words array")
        return words.map { el ->
            val o = el.jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            StudyWordEntity(
                deck = deck,
                word = str("word"),
                phonetic = str("phonetic"),
                meaning = str("meaning"),
                example = str("example"),
                exampleMeaning = str("exampleMeaning"),
            )
        }
    }
}
