package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(assistants = current.assistants + assistant)
            }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val remaining = current.assistants.filter { it.id != assistant.id }
                current.copy(
                    assistants = remaining,
                    assistantId = if (current.assistantId == assistant.id) {
                        remaining.firstOrNull()?.id ?: current.assistantId
                    } else current.assistantId,
                )
            }
            cleanupAssistantFiles(assistant)
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
            assistant.avatarPairs.forEach { pair ->
                add(pair.aiAvatar.url.toUri())
                add(pair.userAvatar.url.toUri())
            }
            assistant.phoneAlbum.forEach { photo -> add(photo.uri.toUri()) }
            assistant.phoneLockWallpaper?.let { add(it.toUri()) }
            assistant.phoneHomeWallpaper?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant, copyMemories: Boolean = false) {
        viewModelScope.launch {
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
                avatarPairs = emptyList(),
                phoneAlbum = emptyList(),
                phoneMemos = emptyList(),
                phoneUserRemark = "",
                phoneLockWallpaper = null,
                phoneHomeWallpaper = null,
                background = null,
            )
            settingsStore.update { current ->
                current.copy(assistants = current.assistants + copiedAssistant)
            }
            if (copyMemories) {
                memoryRepository.copyMemories(
                    fromAssistantId = assistant.id.toString(),
                    toAssistantId = copiedAssistant.id.toString(),
                )
            }
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
