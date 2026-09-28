package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次「上下文压缩」的结果。
 *
 * 压缩不再改写 conversation.messageNodes，聊天记录原样保留；
 * 这里只记录「摘要 + 摘要覆盖到哪条消息」，生成时由
 * CompressedContextTransformer 决定哪些消息折叠、哪些照常送模型。
 */
@Serializable
data class CompressedContext(
    /** 折叠掉的历史消息的摘要正文 */
    val summary: String,
    /** 摘要覆盖的最后一条消息 id（字符串形式的 Uuid），生成时把这条及其之前的历史折叠 */
    val boundaryMessageId: String,
    /** 兜底：边界消息找不到（被删除/切分支/已被 contextMessageLimit 截掉）时按条数折叠 */
    val compressedCount: Int,
    val updatedAt: Long = System.currentTimeMillis(),
)

private val Context.compressionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "conversation_compression"
)

/**
 * 上下文压缩状态存储（按会话隔离）
 *
 * 刻意用独立的 DataStore 而不是 Room：不改 schema、不加表。
 */
class ConversationCompressionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun key(conversationId: kotlin.uuid.Uuid) =
        stringPreferencesKey("compression_$conversationId")

    fun flowOf(conversationId: kotlin.uuid.Uuid): Flow<CompressedContext?> =
        context.compressionDataStore.data.map { prefs ->
            prefs[key(conversationId)]?.let { raw ->
                runCatching {
                    json.decodeFromString(CompressedContext.serializer(), raw)
                }.getOrNull()
            }
        }

    suspend fun get(conversationId: kotlin.uuid.Uuid): CompressedContext? =
        flowOf(conversationId).first()

    suspend fun set(conversationId: kotlin.uuid.Uuid, value: CompressedContext) {
        context.compressionDataStore.edit { prefs ->
            prefs[key(conversationId)] =
                json.encodeToString(CompressedContext.serializer(), value)
        }
    }

    suspend fun clear(conversationId: kotlin.uuid.Uuid) {
        context.compressionDataStore.edit { prefs ->
            prefs.remove(key(conversationId))
        }
    }
}
