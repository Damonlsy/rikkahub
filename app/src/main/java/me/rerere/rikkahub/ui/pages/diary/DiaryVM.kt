package me.rerere.rikkahub.ui.pages.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.DiaryDAO
import me.rerere.rikkahub.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.data.db.entity.DiaryEntity
import me.rerere.rikkahub.service.DiaryService

const val DIARY_AUTHOR_USER = "user"
const val DIARY_AUTHOR_AI = "ai"

data class DiaryUiState(
    val loading: Boolean = true,
    val diaries: List<DiaryEntity> = emptyList(),
)

class DiaryVM(
    private val dao: DiaryDAO,
    private val service: DiaryService,
    private val context: Context,
) : ViewModel() {

    private val loading = MutableStateFlow(true)
    private val aiBusy = MutableStateFlow(false)

    // 日记本自定义背景：存在独立文件里，不动用户的设置数据
    private val backgroundFile = File(context.filesDir, "diary_background.txt")
    private val _diaryBackground = MutableStateFlow(readBackground())
    val diaryBackground: StateFlow<String?> = _diaryBackground.asStateFlow()

    private fun readBackground(): String? = runCatching {
        if (backgroundFile.exists()) {
            backgroundFile.readText().trim().ifBlank { null }
        } else {
            null
        }
    }.getOrNull()

    fun setDiaryBackground(uri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (uri.isNullOrBlank()) {
                    if (backgroundFile.exists()) backgroundFile.delete()
                } else {
                    backgroundFile.writeText(uri)
                }
            }
            _diaryBackground.value = readBackground()
        }
    }

    val uiState: StateFlow<DiaryUiState> =
        combine(dao.listDiaries(), loading) { list, isLoading ->
            DiaryUiState(loading = isLoading, diaries = list)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DiaryUiState(),
        )

    val aiWorking: StateFlow<Boolean> = aiBusy.asStateFlow()

    init {
        // Keep the skeleton visible for a beat on first open so the loading
        // state reads as intentional rather than a flash.
        viewModelScope.launch {
            delay(700)
            loading.value = false
        }
    }

    fun diaryFlow(id: String): Flow<DiaryEntity?> = dao.getDiaryFlow(id)

    fun comments(diaryId: String): Flow<List<DiaryCommentEntity>> = dao.listComments(diaryId)

    fun addDiary(title: String, content: String, author: String) {
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { insertDiary(title, content, author) }
    }

    fun aiWriteDiary() {
        if (aiBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            aiBusy.value = true
            val result = service.writeDiary()
            if (result != null) insertDiary(result.first, result.second, DIARY_AUTHOR_AI)
            aiBusy.value = false
        }
    }

    fun deleteDiary(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteCommentsOfDiary(id)
            dao.deleteDiary(id)
        }
    }

    fun setEntryBackground(id: String, uri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateDiaryBackground(id, uri, System.currentTimeMillis())
        }
    }

    fun addComment(diaryId: String, content: String, author: String, parentId: String? = null) {
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { insertComment(diaryId, content, author, parentId) }
    }

    fun toggleLike(comment: DiaryCommentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (comment.liked) dao.unlikeComment(comment.id) else dao.likeComment(comment.id)
        }
    }

    fun aiComment(diaryId: String, title: String, content: String) {
        if (aiBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            aiBusy.value = true
            val existing = dao.listComments(diaryId).first().map { it.content }
            val text = service.comment(title, content, existing)
            if (!text.isNullOrBlank()) insertComment(diaryId, text, DIARY_AUTHOR_AI)
            aiBusy.value = false
        }
    }

    fun deleteComment(id: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteComment(id) }
    }

    private suspend fun insertDiary(title: String, content: String, author: String) {
        val now = System.currentTimeMillis()
        dao.upsertDiary(
            DiaryEntity(
                id = Uuid.random().toString(),
                title = title.trim().ifBlank { "无标题" },
                content = content.trim(),
                author = author,
                date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private suspend fun insertComment(
        diaryId: String,
        content: String,
        author: String,
        parentId: String? = null,
    ) {
        dao.upsertComment(
            DiaryCommentEntity(
                id = Uuid.random().toString(),
                diaryId = diaryId,
                content = content.trim(),
                author = author,
                createdAt = System.currentTimeMillis(),
                parentId = parentId,
            )
        )
    }
}
