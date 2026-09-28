package me.rerere.rikkahub

import me.rerere.rikkahub.data.db.entity.StudyWordEntity
import me.rerere.rikkahub.data.repository.StudyScheduling
import org.junit.Assert.assertEquals
import org.junit.Test

class StudySchedulingTest {

    private fun newWord() = StudyWordEntity(
        deck = "high_school",
        word = "abandon",
        phonetic = "/əˈbændən/",
        meaning = "v. 放弃",
        example = "He abandoned the plan.",
        exampleMeaning = "他放弃了计划。",
    )

    @Test
    fun `new word graded known advances to box 1 due tomorrow`() {
        val now = 1_000_000L
        val today = 100L
        val result = StudyScheduling.grade(newWord(), known = true, now = now, today = today)
        assertEquals(1, result.box)
        assertEquals(1, result.intervalDays)
        assertEquals(today + 1, result.dueDay)
        assertEquals(now, result.learnedAt)
        assertEquals(now, result.lastReviewedAt)
    }

    @Test
    fun `known word advances box and interval`() {
        val word = newWord().copy(box = 1, intervalDays = 1, learnedAt = 1L, dueDay = 100L)
        val result = StudyScheduling.grade(word, known = true, now = 2_000L, today = 100L)
        assertEquals(2, result.box)
        assertEquals(3, result.intervalDays)
        assertEquals(103L, result.dueDay)
        assertEquals(1L, result.learnedAt)
    }

    @Test
    fun `forgotten word resets to box 0 due tomorrow`() {
        val word = newWord().copy(box = 3, intervalDays = 7, learnedAt = 1L, dueDay = 100L)
        val result = StudyScheduling.grade(word, known = false, now = 2_000L, today = 100L)
        assertEquals(0, result.box)
        assertEquals(0, result.intervalDays)
        assertEquals(100L, result.dueDay)
    }

    @Test
    fun `box never exceeds max interval`() {
        val word = newWord().copy(box = 5, intervalDays = 30, learnedAt = 1L, dueDay = 100L)
        val result = StudyScheduling.grade(word, known = true, now = 2_000L, today = 100L)
        assertEquals(5, result.box)
        assertEquals(30, result.intervalDays)
        assertEquals(130L, result.dueDay)
    }
}
