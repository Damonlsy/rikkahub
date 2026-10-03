package me.rerere.rikkahub.data.repository

import androidx.core.net.toUri
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.AvatarPair

class AvatarPairRepository(
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
) {
    private val operationMutex = Mutex()

    fun assistantFlow(assistantId: Uuid): Flow<Assistant?> =
        settingsStore.settingsFlow.map { it.getAssistantById(assistantId) }

    fun getPairs(assistantId: Uuid): List<AvatarPair> =
        settingsStore.settingsFlow.value.getAssistantById(assistantId)?.avatarPairs.orEmpty()

    fun getPair(assistantId: Uuid, pairId: Uuid): AvatarPair? =
        getPairs(assistantId).firstOrNull { it.id == pairId }

    suspend fun copyPairForInspection(assistantId: Uuid, pairId: Uuid): AvatarPair? =
        operationMutex.withLock {
            val pair = getPair(assistantId, pairId) ?: return@withLock null
            val aiAvatar = copyAvatar(pair.aiAvatar) ?: return@withLock null
            val userAvatar = copyAvatar(pair.userAvatar) ?: run {
                filesManager.deleteChatFiles(listOf(aiAvatar.url.toUri()))
                return@withLock null
            }
            pair.copy(aiAvatar = aiAvatar, userAvatar = userAvatar)
        }

    suspend fun addPair(assistantId: Uuid, pair: AvatarPair): Boolean = operationMutex.withLock {
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?: return@withLock false
        if (assistant.avatarPairs.any { it.id == pair.id }) return@withLock false
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistantId) it.copy(avatarPairs = it.avatarPairs + pair) else it
                }
            )
        }
        true
    }

    suspend fun deletePair(assistantId: Uuid, pairId: Uuid): Boolean = operationMutex.withLock {
        val pair = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?.avatarPairs?.firstOrNull { it.id == pairId }
            ?: return@withLock false
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistantId) {
                        it.copy(avatarPairs = it.avatarPairs.filterNot { candidate -> candidate.id == pairId })
                    } else {
                        it
                    }
                }
            )
        }
        deleteIfUnreferenced(listOf(pair.aiAvatar.url, pair.userAvatar.url))
        true
    }

    suspend fun renamePair(assistantId: Uuid, pairId: Uuid, name: String): Boolean = operationMutex.withLock {
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?: return@withLock false
        if (assistant.avatarPairs.none { it.id == pairId }) return@withLock false
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map {
                    if (it.id == assistantId) {
                        it.copy(avatarPairs = it.avatarPairs.map { pair ->
                            if (pair.id == pairId) pair.copy(name = name.trim().take(80)) else pair
                        })
                    } else it
                }
            )
        }
        true
    }

    suspend fun applyPair(assistantId: Uuid, pairId: Uuid): Boolean = operationMutex.withLock {
        val before = settingsStore.settingsFlow.value
        val assistant = before.getAssistantById(assistantId) ?: return@withLock false
        val pair = assistant.avatarPairs.firstOrNull { it.id == pairId } ?: return@withLock false
        val aiAvatar = copyAvatar(pair.aiAvatar) ?: return@withLock false
        val userAvatar = copyAvatar(pair.userAvatar) ?: run {
            filesManager.deleteChatFiles(listOf(aiAvatar.url.toUri()))
            return@withLock false
        }
        val updated = runCatching {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistantId) it.copy(avatar = aiAvatar, useAssistantAvatar = true) else it
                    },
                    displaySetting = settings.displaySetting.copy(userAvatar = userAvatar),
                )
            }
        }.isSuccess
        if (!updated) {
            filesManager.deleteChatFiles(listOf(aiAvatar.url.toUri(), userAvatar.url.toUri()))
            return@withLock false
        }
        deleteIfUnreferenced(
            listOfNotNull(
                (assistant.avatar as? Avatar.Image)?.url,
                (before.displaySetting.userAvatar as? Avatar.Image)?.url,
            )
        )
        true
    }

    suspend fun applyAiAvatar(assistantId: Uuid, pairId: Uuid): Boolean = operationMutex.withLock {
        val before = settingsStore.settingsFlow.value
        val assistant = before.getAssistantById(assistantId) ?: return@withLock false
        val pair = assistant.avatarPairs.firstOrNull { it.id == pairId } ?: return@withLock false
        val avatar = copyAvatar(pair.aiAvatar) ?: return@withLock false
        val updated = runCatching {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistantId) it.copy(avatar = avatar, useAssistantAvatar = true) else it
                    }
                )
            }
        }.isSuccess
        if (!updated) {
            filesManager.deleteChatFiles(listOf(avatar.url.toUri()))
            return@withLock false
        }
        deleteIfUnreferenced(listOfNotNull((assistant.avatar as? Avatar.Image)?.url))
        true
    }

    private fun copyAvatar(source: Avatar.Image): Avatar.Image? =
        filesManager.copyChatFile(source.url.toUri())?.let { Avatar.Image(it.toString()) }

    private fun deleteIfUnreferenced(urls: List<String>) {
        val referenced = referencedImages(settingsStore.settingsFlow.value)
        val unused = urls.distinct().filterNot { it in referenced }.map { it.toUri() }
        if (unused.isNotEmpty()) filesManager.deleteChatFiles(unused)
    }

    private fun referencedImages(settings: Settings): Set<String> = buildSet {
        (settings.displaySetting.userAvatar as? Avatar.Image)?.let { add(it.url) }
        settings.assistants.forEach { assistant ->
            (assistant.avatar as? Avatar.Image)?.let { add(it.url) }
            assistant.avatarPairs.forEach { pair ->
                add(pair.aiAvatar.url)
                add(pair.userAvatar.url)
            }
        }
    }
}
