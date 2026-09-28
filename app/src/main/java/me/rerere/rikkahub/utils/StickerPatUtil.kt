package me.rerere.rikkahub.utils

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.PatActionDAO
import me.rerere.rikkahub.data.db.entity.PatActionEntity

const val STICKER_OWNER_USER = "user"
const val STICKER_OWNER_AI = "ai"

const val PAT_AUTHOR_USER = "user"
const val PAT_AUTHOR_AI = "ai"

/**
 * 表情包消息标记：消息里会带一个隐藏的 `#sticker:<id>` 文本，
 * UI 不渲染它，但会发给模型，这样 AI 能用 id 把用户发的表情包收进自己的表情包库。
 */
const val STICKER_MARKER_PREFIX = "#sticker:"

fun buildStickerParts(stickerId: String, uri: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(STICKER_MARKER_PREFIX + stickerId),
    UIMessagePart.Image(uri),
)

fun stickerMarkerId(parts: List<UIMessagePart>): String? = parts
    .filterIsInstance<UIMessagePart.Text>()
    .firstOrNull { it.text.startsWith(STICKER_MARKER_PREFIX) }
    ?.text
    ?.removePrefix(STICKER_MARKER_PREFIX)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun isStickerMessage(parts: List<UIMessagePart>): Boolean = stickerMarkerId(parts) != null

// ---- 拍一拍 ----

private const val PAT_META_KEY = "pat"

/** 拍一拍的三个槽位：方式 + 动作 + 部位。用户和 AI 各有一套独立的词库。 */
const val PAT_SLOT_MANNER = "manner"
const val PAT_SLOT_ACTION = "action"
const val PAT_SLOT_PART = "part"

val PAT_SLOT_ALL = listOf(PAT_SLOT_MANNER, PAT_SLOT_ACTION, PAT_SLOT_PART)

val PAT_SLOT_LABELS = mapOf(
    PAT_SLOT_MANNER to "方式",
    PAT_SLOT_ACTION to "动作",
    PAT_SLOT_PART to "部位",
)

/**
 * 拍一拍消息：一段纯文本 + metadata 标记。
 * metadata 只用于 UI 识别（渲染成居中的系统行），不会发给模型，
 * 模型看到的就是 [text] 本身。
 */
fun buildPatParts(text: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        text = text,
        metadata = buildJsonObject { put(PAT_META_KEY, true) },
    )
)

fun UIMessage.isPatMessage(): Boolean = parts.any { part ->
    part is UIMessagePart.Text && (part.metadata?.get(PAT_META_KEY) as? JsonPrimitive)?.booleanOrNull == true
}

/**
 * 用户发起的拍一拍：「我 + 方式 + 动作 + 对方 + 部位」。
 * 用「我/你」人称，展示和模型读到的是同一句话且都不会指错人。
 *
 * 三个词都可以缺：哪个槽位的词库空了，这句话里就直接省掉那一段，
 * 例如词库里只有方式和动作时，出来的是「我用手轻轻捏了捏AI」。
 */
fun buildUserPatText(
    manner: String,
    action: String,
    part: String,
    aiName: String,
    patAi: Boolean,
): String {
    val target = if (patAi) aiName.ifBlank { "AI" } else "自己"
    return "我$manner$action$target$part"
}

/**
 * AI 通过 pat_tool 发起的拍一拍：「AI + 方式 + 动作 + 对方 + 部位」。
 * AI 用自己的第一人称，所以拍用户是「你」。
 */
fun buildAiPatText(
    manner: String,
    action: String,
    part: String,
    aiName: String,
    patUser: Boolean,
): String {
    val target = if (patUser) "你" else "自己"
    return "${aiName.ifBlank { "AI" }}$manner$action$target$part"
}

/**
 * 从指定词库里随机挑一个词，词库空了就返回空串（这句话里省掉这一段）。
 *
 * 不报错、不兜底，也绝不越界去拿对方的词库。
 */
fun pickPatWord(words: List<PatActionEntity>, owner: String, slot: String): String = words
    .filter { it.slot == slot && it.author == owner && it.text.isNotBlank() }
    .map { it.text }
    .randomOrNull()
    .orEmpty()

fun pickPatWords(
    words: List<PatActionEntity>,
    owner: String,
): Triple<String, String, String> = Triple(
    pickPatWord(words, owner, PAT_SLOT_MANNER),
    pickPatWord(words, owner, PAT_SLOT_ACTION),
    pickPatWord(words, owner, PAT_SLOT_PART),
)

/**
 * 给拍一拍菜单生成几条预览（每次打开菜单都会重新随机），让用户点之前就知道会发什么。
 *
 * @param owner 用谁的词库（发起者自己的）
 * @param patAi true = 目标是 AI，false = 目标是自己
 */
fun buildPatTextSamples(
    words: List<PatActionEntity>,
    owner: String,
    aiName: String,
    patAi: Boolean,
    count: Int = 5,
): List<String> {
    val mine = words.filter { it.author == owner && it.text.isNotBlank() }
    if (mine.isEmpty()) return emptyList()
    return (1..count).map {
        val (manner, action, part) = pickPatWords(words, owner)
        buildUserPatText(manner, action, part, aiName, patAi)
    }
}

object PatDefaults {
    /**
     * 内置词：**只在第一次安装时塞进词库**，以及设置页给个例子用。
     * 发送时不会拿它兜底——词库空了就是发不出拍一拍，得自己去加。
     */
    val WORDS: Map<String, List<String>> = mapOf(
        PAT_SLOT_MANNER to listOf("轻轻地", "悄悄地", "狠狠地", "静静地", "猛地"),
        PAT_SLOT_ACTION to listOf("拍了拍", "捏了捏", "揉了揉", "敲了敲", "摸了摸", "亲了亲"),
        PAT_SLOT_PART to listOf("脑袋", "肩膀", "后背", "脸蛋", "手臂", "屁股"),
    )

    private const val PREFS = "pat_defaults"
    private const val KEY_SEEDED_SLOTS = "seeded_slots"

    /**
     * 第一次运行时给用户和 AI 两套词库各补一遍默认词；之后用户删了就不再回来。
     * 顺带把老版本（只有动作短语、没有槽位）的记录归位成「动作」。
     */
    suspend fun ensureSeeded(context: Context, dao: PatActionDAO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        dao.getAll()
            .filter { it.slot !in PAT_SLOT_ALL }
            .forEach { legacy -> dao.upsert(legacy.copy(slot = PAT_SLOT_ACTION)) }

        if (prefs.getBoolean(KEY_SEEDED_SLOTS, false)) return

        val existing = dao.getAll()
        val now = System.currentTimeMillis()
        listOf(PAT_AUTHOR_USER, PAT_AUTHOR_AI).forEach { owner ->
            PAT_SLOT_ALL.forEach { slot ->
                if (existing.none { it.author == owner && it.slot == slot }) {
                    WORDS.getValue(slot).forEachIndexed { index, word ->
                        dao.upsert(
                            PatActionEntity(
                                id = "${owner}_${slot}_$index",
                                text = word,
                                author = owner,
                                slot = slot,
                                createdAt = now + index,
                            )
                        )
                    }
                }
            }
        }
        prefs.edit().putBoolean(KEY_SEEDED_SLOTS, true).apply()
    }
}
