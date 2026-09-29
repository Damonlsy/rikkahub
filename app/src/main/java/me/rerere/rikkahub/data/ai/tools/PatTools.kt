package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlin.uuid.Uuid
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.PatActionDAO
import me.rerere.rikkahub.data.db.entity.PatActionEntity
import me.rerere.rikkahub.utils.PAT_AUTHOR_AI
import me.rerere.rikkahub.utils.PAT_SLOT_ACTION
import me.rerere.rikkahub.utils.PAT_SLOT_ALL
import me.rerere.rikkahub.utils.PAT_SLOT_MANNER
import me.rerere.rikkahub.utils.PAT_SLOT_PART
import me.rerere.rikkahub.utils.PatDefaults
import me.rerere.rikkahub.utils.buildAiPatText
import me.rerere.rikkahub.utils.buildPatParts
import me.rerere.rikkahub.utils.pickPatWord

/**
 * 拍一拍工具：你和用户都能拍对方、拍自己。
 *
 * 一句话由三个槽位拼成：方式 + 动作 + 部位（比如「轻轻地 + 拍了拍 + 脑袋」）。
 * 你和用户各有一套**独立**的词库，互不干涉。
 *
 * @param send 同表情包，先排队、生成结束后由 ChatService 追加。
 */
fun buildPatTools(
    dao: PatActionDAO,
    context: Context,
    send: (List<UIMessagePart>) -> Unit,
    aiName: String,
): List<Tool> = listOf(
    Tool(
        name = "pat_tool",
        description = """
            拍一拍工具：聊天里双方都能「拍一拍」对方或自己，效果是一行居中的小字，例如「AI轻轻地拍了拍你脑袋」。
            一句话由三个槽位拼成：方式 + 动作 + 部位。
            每次拍之前由**你自己选词**：可以从你的词库里挑（list_words 查看），也可以直接想一个贴合当前语气的词传进去，别什么都不想直接碰运气。
            用 `action` 选择操作（注意：这里是操作名，不是拍一拍的动作词）：
            - list_words：列出词库（id、词、槽位、是谁的）。
            - add_word：往**你的**词库加一个词，需要 slot（manner/action/part）和 word。
            - remove_word：删掉**你自己**加过的词，需要 id；用户加的删不掉。
            - pat：真的拍一下。target 必填（user/self）；manner/verb/part 建议你挑好再传，
              漏传才会退回从你的词库里随机抽；某个槽位空着也没关系，那句话里就省掉这一段。
            什么时候用：用户拍你、气氛到了、想撒娇或逗他的时候，偶尔来一下，别每次都拍。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list_words"); add("add_word"); add("remove_word"); add("pat")
                        })
                        put("description", "操作：list_words / add_word / remove_word / pat")
                    })
                    put("slot", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            PAT_SLOT_ALL.forEach { add(it) }
                        })
                        put("description", "槽位（add_word 必填）：manner=方式 / action=动作 / part=部位")
                    })
                    put("word", buildJsonObject {
                        put("type", "string")
                        put("description", "词（add_word 必填），比如「悄悄地」「弹了个脑瓜崩」「耳朵」")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "词 id（remove_word 时必填）")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("user"); add("self") })
                        put("description", "拍谁：user = 拍用户，self = 拍你自己（pat 时必填）")
                    })
                    put("manner", buildJsonObject {
                        put("type", "string")
                        put("description", "方式（pat 可选），不填随机从词库挑，例如「轻轻地」")
                    })
                    put("verb", buildJsonObject {
                        put("type", "string")
                        put("description", "动作（pat 可选），不填随机从词库挑，例如「拍了拍」")
                    })
                    put("part", buildJsonObject {
                        put("type", "string")
                        put("description", "部位（pat 可选），不填随机从词库挑，例如「脑袋」")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            PatDefaults.ensureSeeded(context, dao)
            val payload = when (action) {
                "list_words" -> {
                    buildJsonArray {
                        dao.getAll().forEach { item ->
                            add(buildJsonObject {
                                put("id", item.id)
                                put("word", item.text)
                                put("slot", item.slot)
                                put("owner", item.author)
                            })
                        }
                    }
                }

                "add_word" -> {
                    val slot = params["slot"]?.jsonPrimitive?.contentOrNull
                        ?: error("slot is required (manner / action / part)")
                    if (slot !in PAT_SLOT_ALL) {
                        error("slot must be one of ${PAT_SLOT_ALL.joinToString()}")
                    }
                    val word = params["word"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { t -> t.isNotBlank() }
                        ?: error("word is required")
                    dao.upsert(
                        PatActionEntity(
                            id = Uuid.random().toString(),
                            text = word,
                            author = PAT_AUTHOR_AI,
                            slot = slot,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    buildJsonObject { put("success", true) }
                }

                "remove_word" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val existing = dao.getAll().find { item -> item.id == id }
                        ?: error("word not found: $id")
                    if (existing.author != PAT_AUTHOR_AI) error("这是用户词库里的词，你删不掉")
                    dao.delete(id)
                    buildJsonObject { put("success", true) }
                }

                "pat" -> {
                    val target = params["target"]?.jsonPrimitive?.contentOrNull
                        ?: error("target is required")
                    if (target != "user" && target != "self") error("target must be user or self")
                    fun override(key: String) = params[key]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { t -> t.isNotBlank() }
                    val all = dao.getAll()
                    fun pick(key: String, slot: String): String =
                        override(key) ?: pickPatWord(all, PAT_AUTHOR_AI, slot)
                    val manner = pick("manner", PAT_SLOT_MANNER)
                    val verb = pick("verb", PAT_SLOT_ACTION)
                    val part = pick("part", PAT_SLOT_PART)
                    val patText = buildAiPatText(
                        manner = manner,
                        action = verb,
                        part = part,
                        aiName = aiName,
                        patUser = target == "user",
                    )
                    send(buildPatParts(patText))
                    buildJsonObject {
                        put("success", true)
                        put("text", patText)
                    }
                }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
