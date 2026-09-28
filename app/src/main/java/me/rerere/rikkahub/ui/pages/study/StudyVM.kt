package me.rerere.rikkahub.ui.pages.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.StudyWordEntity
import me.rerere.rikkahub.data.repository.DeckSummary
import me.rerere.rikkahub.data.repository.StudyRepository

class StudyVM(
    private val repository: StudyRepository,
) : ViewModel() {
    private val _decks = MutableStateFlow<List<DeckSummary>>(emptyList())
    val decks: StateFlow<List<DeckSummary>> = _decks.asStateFlow()

    private val _queue = MutableStateFlow<List<StudyWordEntity>>(emptyList())
    val queue: StateFlow<List<StudyWordEntity>> = _queue.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var loaded = false

    init {
        viewModelScope.launch {
            repository.ensureDataLoaded()
            loaded = true
            refreshSummaries()
        }
    }

    fun openDeck(deckId: String) {
        viewModelScope.launch {
            _queue.value = repository.queue(deckId)
        }
    }

    fun grade(deckId: String, wordId: String, known: Boolean) {
        viewModelScope.launch {
            repository.grade(wordId, known)
            _queue.value = repository.queue(deckId)
        }
    }

    fun saveSession(deckId: String, durationMs: Long, reviewed: Int, learned: Int) {
        viewModelScope.launch {
            repository.saveSession(deckId, durationMs, reviewed, learned)
            refreshSummaries()
        }
    }

    fun refreshSummaries() {
        viewModelScope.launch {
            _decks.value = repository.summaries()
            if (_decks.value.isNotEmpty()) _loading.value = false
        }
    }
}
