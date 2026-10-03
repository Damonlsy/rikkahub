package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.ContextMemory
import me.rerere.rikkahub.data.model.ContextMemoryKind

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(content = content, updatedAt = System.currentTimeMillis())
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val memories = memoryDAO.getMemoriesOfAssistant(fromAssistantId)
        if (memories.isEmpty()) return
        memoryDAO.insertMemories(
            memories.map { MemoryEntity(assistantId = toAssistantId, content = it.content) }
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id, System.currentTimeMillis())
    }

    suspend fun getContextMemories(assistantId: String, query: String = ""): List<ContextMemory> {
        val memories = memoryDAO.getContextMemories(assistantId)
            .filter { it.kind.startsWith("context:") }
            .map(::toContextMemory)
        if (query.isBlank()) return memories.take(8)
        val terms = query.lowercase().split(Regex("\\s+|[，。！？,.!?]"))
            .filter { it.length >= 2 }
        return memories
            .map { memory ->
                val matches = terms.count { term -> memory.content.lowercase().contains(term) }
                memory to (matches * 2f + memory.confidence + memory.importance)
            }
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .take(8)
            .map { it.first }
    }

    suspend fun getCompressionRecords(assistantId: String): List<ContextMemory> =
        memoryDAO.getCompressionRecords(assistantId).map(::toContextMemory)

    suspend fun addCompressionRecord(
        assistantId: String,
        summary: String,
        sourceConversationId: String,
    ): ContextMemory {
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(
            assistantId = assistantId,
            content = summary,
            kind = "compression:summary",
            sourceConversationId = sourceConversationId,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = entity.copy(id = memoryDAO.insertMemory(entity).toInt())
        return toContextMemory(inserted)
    }

    suspend fun upsertContextMemory(
        assistantId: String,
        content: String,
        kind: ContextMemoryKind = ContextMemoryKind.OTHER,
        sourceConversationId: String? = null,
        confidence: Float = 0.65f,
        importance: Float = defaultImportance(kind),
    ): ContextMemory {
        val now = System.currentTimeMillis()
        val existing = memoryDAO.getContextMemories(assistantId).firstOrNull {
            it.content.substringBefore(":").trim().equals(content.substringBefore(":").trim(), ignoreCase = true)
        }
        val entity = if (existing == null) {
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                kind = "context:${kind.name.lowercase()}",
                sourceConversationId = sourceConversationId.orEmpty(),
                createdAt = now,
                updatedAt = now,
                confidence = confidence.coerceIn(0f, 1f),
                importance = importance.coerceIn(0f, 1f),
            ).let { inserted ->
                inserted.copy(id = memoryDAO.insertMemory(inserted).toInt())
            }
        } else {
            existing.copy(
                content = content,
                kind = "context:${kind.name.lowercase()}",
                sourceConversationId = sourceConversationId.orEmpty(),
                updatedAt = now,
                deletedAt = 0L,
                confidence = confidence.coerceIn(0f, 1f),
                importance = importance.coerceIn(0f, 1f),
            ).also { updated ->
                memoryDAO.updateMemory(updated)
            }
        }
        return toContextMemory(entity)
    }

    suspend fun restoreMemory(id: Int) = memoryDAO.restoreMemory(id)

    suspend fun updateContextScores(id: Int, confidence: Float, importance: Float) {
        val existing = memoryDAO.getMemoryById(id) ?: return
        memoryDAO.updateMemory(existing.copy(
            confidence = confidence.coerceIn(0f, 1f),
            importance = importance.coerceIn(0f, 1f),
            updatedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun purgeTrash() = memoryDAO.purgeTrash(System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L)

    private fun toContextMemory(entity: MemoryEntity): ContextMemory = ContextMemory(
        id = entity.id,
        assistantId = entity.assistantId,
        content = entity.content,
        kind = if (entity.kind.startsWith("compression:")) {
            ContextMemoryKind.OTHER
        } else {
            entity.kind.substringAfter("context:").uppercase().let {
                runCatching { ContextMemoryKind.valueOf(it) }.getOrDefault(ContextMemoryKind.OTHER)
            }
        },
        sourceConversationId = entity.sourceConversationId.ifBlank { null },
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
        confidence = entity.confidence,
        importance = entity.importance,
    )

    private fun defaultImportance(kind: ContextMemoryKind): Float = when (kind) {
        ContextMemoryKind.PREFERENCE, ContextMemoryKind.PERSON -> 0.85f
        ContextMemoryKind.PROJECT, ContextMemoryKind.DECISION -> 0.8f
        ContextMemoryKind.TODO -> 0.7f
        ContextMemoryKind.OTHER -> 0.5f
    }
}
