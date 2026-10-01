package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.rikkahub.data.db.dao.StickerDAO
import me.rerere.rikkahub.data.db.entity.StickerEntity
import me.rerere.rikkahub.utils.STICKER_OWNER_AI
import me.rerere.rikkahub.utils.buildStickerParts

/**
 * 表情包工具：AI 只能从**自己的**表情包库里发表情包，也决定要不要把用户发过的表情包收进自己库里。
 *
 * @param send 生成期间不能直接插消息节点，先排队，等本轮生成结束由 ChatService 追加。
 * @param inventory 当前 AI 库存清单（id｜分类｜内容描述），直接写进工具描述，AI 不用先 list 就知道每张图是什么。
 */
fun buildStickerTools(
    dao: StickerDAO,
    send: (List<UIMessagePart>) -> Unit,
    copyForSend: (String) -> String,
    inventory: String = "",
): List<Tool> = listOf(
    Tool(
        name = "sticker_tool",
        description = """
            表情包工具：你和用户各有各的表情包库，互不相通。用户发出去的表情包默认只在用户库里，不会自动进你的库。
            用 `action` 选择操作：
            - list：列出**你自己**表情包库里的表情包（返回 id、分类、内容描述、备注名）。发表情包前先 list 拿 id。
            - send：发一个表情包，需要 sticker_id（必须是你自己库里的 id）。发出去后会作为一条独立的图片消息出现在聊天里。
            - add：把用户的表情包收藏进你的库，需要 sticker_id。用户发过的表情包消息里会带 `#sticker:<id>`，
              那个 id 就是这里要填的 sticker_id。用户没主动加的时候，你可以自己决定要不要收藏。
            - remove：从你自己的库里删掉一个表情包，需要 sticker_id。
            注意：send 只能发你自己库里的，不能直接发用户库里的；想发用户的东西先用 add 收藏。
            发的时候挑贴合当前气氛的，别硬刷。

            你的表情包库存（id｜分类｜内容描述），按气氛挑：
            ${inventory.ifBlank { "（库是空的，先用 add 收藏，或让用户给你加几张）" }}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list"); add("send"); add("add"); add("remove")
                        })
                        put("description", "操作：list / send / add / remove")
                    })
                    put("sticker_id", buildJsonObject {
                        put("type", "string")
                        put("description", "表情包 id（send / add / remove 时必填；list 时不需要）")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "备注名/关键词（add 时可选，方便你以后认出这张图）")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "list" -> {
                    val mine = dao.getByOwner(STICKER_OWNER_AI)
                    buildJsonArray {
                        mine.forEach { sticker ->
                            add(buildJsonObject {
                                put("id", sticker.id)
                                put("name", sticker.name)
                                put("category", sticker.category)
                                put("description", sticker.description)
                                put("created_at", sticker.createdAt)
                            })
                        }
                    }
                }

                "send" -> {
                    val id = params["sticker_id"]?.jsonPrimitive?.contentOrNull ?: error("sticker_id is required")
                    val sticker = dao.getById(id) ?: error("sticker not found: $id")
                    if (sticker.owner != STICKER_OWNER_AI) {
                        error("这个表情包在用户的库里，先用 add 收藏到你的库再发")
                    }
                    send(buildStickerParts(sticker.id, copyForSend(sticker.uri)))
                    buildJsonObject {
                        put("success", true)
                        put("id", sticker.id)
                        put("note", "表情包已作为独立消息发出（本轮回复结束后出现）")
                    }
                }

                "add" -> {
                    val id = params["sticker_id"]?.jsonPrimitive?.contentOrNull ?: error("sticker_id is required")
                    val source = dao.getById(id) ?: error("sticker not found: $id")
                    if (source.owner == STICKER_OWNER_AI) {
                        buildJsonObject { put("success", true); put("already_in_library", true) }
                    } else if (dao.getByOwner(STICKER_OWNER_AI).any { it.uri == source.uri }) {
                        buildJsonObject { put("success", true); put("already_in_library", true) }
                    } else {
                        val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            .ifEmpty { source.name }
                        dao.upsert(
                            StickerEntity(
                                id = Uuid.random().toString(),
                                uri = source.uri,
                                owner = STICKER_OWNER_AI,
                                name = name,
                                category = source.category,
                                description = source.description,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                        buildJsonObject { put("success", true); put("already_in_library", false) }
                    }
                }

                "remove" -> {
                    val id = params["sticker_id"]?.jsonPrimitive?.contentOrNull ?: error("sticker_id is required")
                    val sticker = dao.getById(id) ?: error("sticker not found: $id")
                    if (sticker.owner != STICKER_OWNER_AI) error("只能删你自己库里的表情包")
                    dao.delete(id)
                    buildJsonObject { put("success", true) }
                }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
