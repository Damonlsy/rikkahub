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
import me.rerere.rikkahub.data.repository.AvatarPairRepository

fun buildAvatarPairTools(
    assistantId: Uuid,
    repository: AvatarPairRepository,
    supportsVision: Boolean,
): List<Tool> = listOf(
    Tool(
        name = "avatar_pair_tool",
        description = """
            成对头像库工具。每组头像由用户明确指定一张属于你、一张属于用户。
            - list：查看可选头像对，返回 pair_id 和名称。
            - inspect/view：查看指定头像对，需要 pair_id。视觉模型会收到明确标注的 AI 头像和用户头像；请先 list，再按需查看一对，不要假装看过未查看的图片。
            - rename：给已添加的头像对命名或改名，需要 pair_id 和 name。
            - change_self：请求把你自己的头像换成指定头像对里的 AI 头像，需要 pair_id；用户批准后才会生效。
            你只能更换自己的头像，不能借此修改用户头像，也不能传入任意图片路径。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list"); add("inspect"); add("view"); add("rename"); add("change_self")
                        })
                    })
                    put("pair_id", buildJsonObject {
                        put("type", "string")
                        put("description", "inspect/view/change_self/rename 时必填，来自 list 结果")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "rename 时的新名称")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { args ->
            args.jsonObject["action"]?.jsonPrimitive?.contentOrNull !in setOf("list", "inspect", "view")
        },
        execute = { args ->
            val params = args.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            when (action) {
                "list" -> buildJsonArray {
                    repository.getPairs(assistantId).forEach { pair ->
                        add(buildJsonObject {
                            put("pair_id", pair.id.toString())
                            put("name", pair.name)
                        })
                    }
                }.let { listOf(UIMessagePart.Text(it.toString())) }
                "inspect", "view" -> {
                    val pairId = params.requiredPairId()
                    if (!supportsVision) {
                        val pair = repository.getPair(assistantId, pairId)
                            ?: error("avatar pair not found")
                        listOf(UIMessagePart.Text(
                            "无法查看头像图片：当前模型不支持视觉/图片输入。pair_id=${pair.id}, name=${pair.name}"
                        ))
                    } else {
                        val pair = repository.copyPairForInspection(assistantId, pairId)
                            ?: error("avatar pair not found or image copy failed")
                        listOf(
                            UIMessagePart.Text("头像对：pair_id=${pair.id}, name=${pair.name}"),
                            UIMessagePart.Text("AI 头像（属于助手，也就是你；你只能更换这张）"),
                            UIMessagePart.Image(pair.aiAvatar.url),
                            UIMessagePart.Text("用户头像（属于用户；你无权更换这张）"),
                            UIMessagePart.Image(pair.userAvatar.url),
                        )
                    }
                }
                "change_self" -> {
                    val pairId = params.requiredPairId()
                    if (!repository.applyAiAvatar(assistantId, pairId)) {
                        error("avatar pair not found or image copy failed")
                    }
                    buildJsonObject { put("success", true); put("pair_id", pairId.toString()) }
                        .let { listOf(UIMessagePart.Text(it.toString())) }
                }
                "rename" -> {
                    val pairId = params.requiredPairId()
                    val name = params["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
                    buildJsonObject { put("success", repository.renamePair(assistantId, pairId, name)) }
                        .let { listOf(UIMessagePart.Text(it.toString())) }
                }
                else -> error("unknown action: $action")
            }
        },
    )
)

private fun kotlinx.serialization.json.JsonObject.requiredPairId(): Uuid =
    this["pair_id"]?.jsonPrimitive?.contentOrNull
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: error("valid pair_id is required")
