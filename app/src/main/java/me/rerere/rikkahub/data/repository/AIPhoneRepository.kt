package me.rerere.rikkahub.data.repository

import android.net.Uri
import androidx.core.net.toUri
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.PhoneAlbumItem
import me.rerere.rikkahub.data.model.PhoneMemo

data class ChatImageRef(
    val imageId: String,
    val url: String,
    val conversationTitle: String,
    val messageText: String,
    val canAdd: Boolean,
)

class AIPhoneRepository(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) {
    private val mutex = Mutex()

    fun assistantFlow(assistantId: Uuid): Flow<Assistant?> =
        settingsStore.settingsFlow.map { it.getAssistantById(assistantId) }

    fun currentAssistant(assistantId: Uuid): Assistant? =
        settingsStore.settingsFlow.value.getAssistantById(assistantId)

    suspend fun listChatImages(assistantId: Uuid, limit: Int = 20): List<ChatImageRef> {
        val result = mutableListOf<ChatImageRef>()
        conversationRepository.getRecentConversations(assistantId, 50).forEach { conversation ->
            conversation.currentMessages.forEach { message ->
                message.parts.forEachIndexed { partIndex, part ->
                    if (part is UIMessagePart.Image && result.size < limit) {
                        val scheme = part.url.toUri().scheme
                        result += ChatImageRef(
                            imageId = "${conversation.id}|${message.id}|$partIndex",
                            url = part.url,
                            conversationTitle = conversation.title,
                            messageText = message.toText().take(160),
                            canAdd = scheme == "file" || scheme == "content",
                        )
                    }
                }
            }
        }
        return result
    }

    suspend fun listChats(assistantId: Uuid, limit: Int = 20): List<Conversation> =
        conversationRepository.getRecentConversations(assistantId, limit)

    suspend fun readChat(assistantId: Uuid, conversationId: Uuid): Conversation? =
        conversationRepository.getConversationById(conversationId)
            ?.takeIf { it.assistantId == assistantId }

    suspend fun addChatImage(assistantId: Uuid, imageId: String, caption: String): Boolean = mutex.withLock {
        val source = resolveImage(assistantId, imageId) ?: return@withLock false
        if (source.url.toUri().scheme !in setOf("file", "content")) return@withLock false
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId) ?: return@withLock false
        if (assistant.phoneAlbum.any { it.sourceImageId == imageId }) return@withLock true
        val copy = filesManager.copyChatFile(source.url.toUri()) ?: return@withLock false
        val item = PhoneAlbumItem(uri = copy.toString(), sourceImageId = imageId, caption = caption.trim())
        val removed = (assistant.phoneAlbum + item).dropLast(200)
        val updated = runCatching {
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map {
                    if (it.id == assistantId) it.copy(phoneAlbum = (it.phoneAlbum + item).takeLast(200)) else it
                })
            }
        }.isSuccess
        if (!updated) filesManager.deleteChatFiles(listOf(copy))
        if (updated && removed.isNotEmpty()) {
            filesManager.deleteChatFiles(removed.map { it.uri.toUri() })
        }
        updated
    }

    suspend fun removeAlbumItem(assistantId: Uuid, itemId: Uuid): Boolean = mutex.withLock {
        val item = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?.phoneAlbum?.firstOrNull { it.id == itemId } ?: return@withLock false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id == assistantId) it.copy(phoneAlbum = it.phoneAlbum.filterNot { photo -> photo.id == itemId }) else it
            })
        }
        filesManager.deleteChatFiles(listOf(item.uri.toUri()))
        true
    }

    suspend fun saveMemo(assistantId: Uuid, memo: PhoneMemo): Boolean = mutex.withLock {
        if (settingsStore.settingsFlow.value.getAssistantById(assistantId) == null) return@withLock false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.id == assistantId) {
                    val exists = assistant.phoneMemos.any { it.id == memo.id }
                    assistant.copy(phoneMemos = if (exists) {
                        assistant.phoneMemos.map { if (it.id == memo.id) memo else it }
                    } else {
                        (assistant.phoneMemos + memo).takeLast(200)
                    })
                } else assistant
            })
        }
        true
    }

    suspend fun deleteMemo(assistantId: Uuid, memoId: Uuid): Boolean = mutex.withLock {
        if (settingsStore.settingsFlow.value.getAssistantById(assistantId) == null) return@withLock false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id == assistantId) it.copy(phoneMemos = it.phoneMemos.filterNot { memo -> memo.id == memoId }) else it
            })
        }
        true
    }

    suspend fun setUserRemark(assistantId: Uuid, remark: String): Boolean = mutex.withLock {
        if (settingsStore.settingsFlow.value.getAssistantById(assistantId) == null) return@withLock false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id == assistantId) it.copy(phoneUserRemark = remark.trim().take(500)) else it
            })
        }
        true
    }

    suspend fun setPasscode(assistantId: Uuid, passcode: String): Boolean = mutex.withLock {
        if (!passcode.matches(Regex("^[0-9]{4}$"))) return@withLock false
        if (settingsStore.settingsFlow.value.getAssistantById(assistantId) == null) return@withLock false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id == assistantId) it.copy(phonePasscode = passcode) else it
            })
        }
        true
    }

    suspend fun setWallpaper(assistantId: Uuid, lockScreen: Boolean, source: Uri?): Boolean = mutex.withLock {
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId) ?: return@withLock false
        val oldWallpaper = if (lockScreen) assistant.phoneLockWallpaper else assistant.phoneHomeWallpaper
        val copied = source?.let { filesManager.copyChatFile(it) ?: return@withLock false }
        val updated = runCatching {
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map {
                    if (it.id != assistantId) it
                    else if (lockScreen) it.copy(phoneLockWallpaper = copied?.toString())
                    else it.copy(phoneHomeWallpaper = copied?.toString())
                })
            }
        }.isSuccess
        if (!updated) copied?.let { filesManager.deleteChatFiles(listOf(it)) }
        if (updated) oldWallpaper?.let { filesManager.deleteChatFiles(listOf(it.toUri())) }
        updated
    }

    private suspend fun resolveImage(assistantId: Uuid, imageId: String): UIMessagePart.Image? {
        val parts = imageId.split('|')
        if (parts.size != 3) return null
        val conversationId = runCatching { Uuid.parse(parts[0]) }.getOrNull() ?: return null
        val messageId = runCatching { Uuid.parse(parts[1]) }.getOrNull() ?: return null
        val partIndex = parts[2].toIntOrNull() ?: return null
        val conversation = conversationRepository.getConversationById(conversationId) ?: return null
        if (conversation.assistantId != assistantId) return null
        val message = conversation.currentMessages.firstOrNull { it.id == messageId } ?: return null
        return message.parts.getOrNull(partIndex) as? UIMessagePart.Image
    }
}
