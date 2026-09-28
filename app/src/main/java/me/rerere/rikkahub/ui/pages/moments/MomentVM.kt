package me.rerere.rikkahub.ui.pages.moments

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.MomentDAO
import me.rerere.rikkahub.data.db.entity.MomentCommentEntity
import me.rerere.rikkahub.data.db.entity.MomentEntity
import me.rerere.rikkahub.data.db.entity.MomentFavoriteEntity
import me.rerere.rikkahub.data.db.entity.MomentLikeEntity
import me.rerere.rikkahub.utils.JsonInstant

const val MOMENT_AUTHOR_USER = "user"
const val MOMENT_AUTHOR_AI = "ai"

data class MomentItem(
    val moment: MomentEntity,
    val likes: List<MomentLikeEntity>,
    val comments: List<MomentCommentEntity>,
    val favorites: List<MomentFavoriteEntity>,
)

data class MomentsUiState(
    val loading: Boolean = true,
    val items: List<MomentItem> = emptyList(),
)

class MomentVM(
    private val dao: MomentDAO,
    private val context: Context,
) : ViewModel() {

    private val loading = MutableStateFlow(true)

    // 微信版朋友圈的封面背景（独立 SharedPreferences，不动用户设置数据）
    private val coverPrefs = context.getSharedPreferences("moments", Context.MODE_PRIVATE)
    private val _cover = MutableStateFlow(coverPrefs.getString("cover", null))
    val cover: StateFlow<String?> = _cover.asStateFlow()

    fun setCover(uri: String?) {
        coverPrefs.edit().apply {
            if (uri.isNullOrBlank()) remove("cover") else putString("cover", uri)
        }.apply()
        _cover.value = uri?.takeIf { it.isNotBlank() }
    }

    val uiState: StateFlow<MomentsUiState> =
        combine(
            dao.listMoments(),
            dao.listLikes(),
            dao.listComments(),
            dao.listFavorites(),
        ) { moments, likes, comments, favorites ->
            MomentsUiState(
                loading = false,
                items = moments.map { m ->
                    MomentItem(
                        moment = m,
                        likes = likes.filter { it.momentId == m.id },
                        comments = comments.filter { it.momentId == m.id },
                        favorites = favorites.filter { it.momentId == m.id },
                    )
                },
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MomentsUiState(),
        )

    init {
        viewModelScope.launch {
            delay(500)
            loading.value = false
        }
    }

    fun imagesOf(moment: MomentEntity): List<String> = runCatching {
        JsonInstant.decodeFromString<List<String>>(moment.images)
    }.getOrDefault(emptyList())

    fun addMoment(content: String, images: List<String>, author: String) {
        if (content.isBlank() && images.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertMoment(
                MomentEntity(
                    id = Uuid.random().toString(),
                    content = content.trim(),
                    images = JsonInstant.encodeToString(images),
                    author = author,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun toggleLike(momentId: String, author: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val liked = dao.getLikes().any { it.momentId == momentId && it.author == author }
            if (liked) dao.removeLike(momentId, author)
            else dao.addLike(MomentLikeEntity(momentId, author, System.currentTimeMillis()))
        }
    }

    fun toggleFavorite(momentId: String, author: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val favorited = dao.getFavorites().any { it.momentId == momentId && it.author == author }
            if (favorited) dao.removeFavorite(momentId, author)
            else dao.addFavorite(MomentFavoriteEntity(momentId, author, System.currentTimeMillis()))
        }
    }

    fun addComment(momentId: String, content: String, author: String, replyTo: String? = null) {
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.addComment(
                MomentCommentEntity(
                    id = Uuid.random().toString(),
                    momentId = momentId,
                    content = content.trim(),
                    author = author,
                    replyTo = replyTo,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteComment(id: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteComment(id) }
    }

    fun deleteMoment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteLikesOfMoment(id)
            dao.deleteCommentsOfMoment(id)
            dao.deleteFavoritesOfMoment(id)
            dao.deleteMoment(id)
        }
    }
}
