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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PhoneMemo
import me.rerere.rikkahub.data.repository.AIPhoneRepository

fun buildPhoneTools(
    assistant: Assistant,
    repository: AIPhoneRepository,
): List<Tool> = listOf(
    Tool(
        name = "phone_tool",
        description = """
            这是只属于你的虚拟小手机。你可以自主管理相册、备忘录和你给用户写的备注。
            action：
            - status：查看相册、备忘录数量和用户备注。
            - list_chat_images：列出你和用户历史聊天里的真实图片。想收藏图片时必须先调用它取得 image_id。
            - add_photo：把聊天图片收藏进你的相册，需要 image_id，可选 caption。不得编造 image_id 或传文件路径。
            - remove_photo：删除相册图片，需要 id。
            - list_chats：查看最近聊天列表。
            - read_chat：读取一段聊天，需要 id（来自 list_chats）。
            - list_memos：查看你的备忘录。
            - save_memo：新建或更新备忘录。新建不传 id；更新传已有 id。需要 title/content，可选 background（十六进制颜色）。
            - delete_memo：删除备忘录，需要 id。
            - set_user_remark：修改你私下给用户写的备注，需要 content。
            - set_passcode：修改你的小手机四位数字密码，需要 content。
            当一张聊天图片对你有纪念意义时，你可以自行收藏，不需要再次询问用户；不要批量收藏无关图片。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("status"); add("list_chat_images"); add("add_photo"); add("remove_photo")
                            add("list_chats"); add("read_chat"); add("list_memos"); add("save_memo")
                            add("delete_memo"); add("set_user_remark"); add("set_passcode")
                        })
                    })
                    put("id", buildJsonObject { put("type", "string") })
                    put("image_id", buildJsonObject { put("type", "string") })
                    put("title", buildJsonObject { put("type", "string") })
                    put("content", buildJsonObject { put("type", "string") })
                    put("caption", buildJsonObject { put("type", "string") })
                    put("background", buildJsonObject {
                        put("type", "string")
                        put("description", "备忘录背景色，例如 #FFF4C2")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val current = repository.currentAssistant(assistant.id) ?: error("assistant not found")
            var previewUrls = emptyList<String>()
            val payload = when (action) {
                "status" -> buildJsonObject {
                    put("album_count", current.phoneAlbum.size)
                    put("memo_count", current.phoneMemos.size)
                    put("user_remark", current.phoneUserRemark)
                }
                "list_chat_images" -> {
                    val images = repository.listChatImages(assistant.id)
                    previewUrls = images.filter { it.canAdd }.take(10).map { it.url }
                    buildJsonArray {
                        images.forEach { image ->
                            add(buildJsonObject {
                                put("image_id", image.imageId)
                                put("conversation", image.conversationTitle)
                                put("context", image.messageText)
                                put("can_add", image.canAdd)
                                put("already_in_album", current.phoneAlbum.any { it.sourceImageId == image.imageId })
                            })
                        }
                    }
                }
                "add_photo" -> {
                    val imageId = params["image_id"]?.jsonPrimitive?.contentOrNull ?: error("image_id is required")
                    val caption = params["caption"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    buildJsonObject { put("success", repository.addChatImage(assistant.id, imageId, caption)) }
                }
                "remove_photo" -> {
                    val id = requireUuid(params["id"]?.jsonPrimitive?.contentOrNull)
                    buildJsonObject { put("success", repository.removeAlbumItem(assistant.id, id)) }
                }
                "list_chats" -> buildJsonArray {
                    repository.listChats(assistant.id).forEach { conversation ->
                        add(buildJsonObject {
                            put("id", conversation.id.toString())
                            put("title", conversation.title)
                            put("updated_at", conversation.updateAt.toEpochMilli())
                            put("message_count", conversation.currentMessages.size)
                        })
                    }
                }
                "read_chat" -> {
                    val id = requireUuid(params["id"]?.jsonPrimitive?.contentOrNull)
                    val conversation = repository.readChat(assistant.id, id) ?: error("chat not found")
                    buildJsonObject {
                        put("title", conversation.title)
                        put("messages", buildJsonArray {
                            conversation.currentMessages.takeLast(100).forEach { message ->
                                add(buildJsonObject {
                                    put("role", message.role.name.lowercase())
                                    put("text", message.toText())
                                    put("image_count", message.parts.count { it is UIMessagePart.Image })
                                })
                            }
                        })
                    }
                }
                "list_memos" -> buildJsonArray {
                    current.phoneMemos.forEach { memo ->
                        add(buildJsonObject {
                            put("id", memo.id.toString())
                            put("title", memo.title)
                            put("content", memo.content)
                            put("background", memo.background)
                            put("updated_at", memo.updatedAt)
                        })
                    }
                }
                "save_memo" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random()
                    val existing = current.phoneMemos.firstOrNull { it.id == id }
                    val title = params["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val content = params["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val background = params["background"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) } ?: existing?.background ?: "#FFF4C2"
                    if (title.isBlank() && content.isBlank()) error("title or content is required")
                    val memo = PhoneMemo(
                        id = id,
                        title = title,
                        content = content,
                        background = background,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    buildJsonObject { put("success", repository.saveMemo(assistant.id, memo)); put("id", id.toString()) }
                }
                "delete_memo" -> {
                    val id = requireUuid(params["id"]?.jsonPrimitive?.contentOrNull)
                    buildJsonObject { put("success", repository.deleteMemo(assistant.id, id)) }
                }
                "set_user_remark" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    buildJsonObject { put("success", repository.setUserRemark(assistant.id, content)) }
                }
                "set_passcode" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    buildJsonObject { put("success", repository.setPasscode(assistant.id, content)) }
                }
                else -> error("unknown action: $action")
            }
            buildList {
                add(UIMessagePart.Text(payload.toString()))
                previewUrls.forEach { add(UIMessagePart.Image(it)) }
            }
        },
    )
)

private fun requireUuid(raw: String?): Uuid = raw
    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    ?: error("valid id is required")
