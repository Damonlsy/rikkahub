package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.MomentDAO
import me.rerere.rikkahub.data.db.entity.MomentCommentEntity
import me.rerere.rikkahub.data.db.entity.MomentEntity
import me.rerere.rikkahub.data.db.entity.MomentFavoriteEntity
import me.rerere.rikkahub.data.db.entity.MomentLikeEntity
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 朋友圈工具：让 AI 能像用户一样发动态、点赞、评论、收藏，共用同一条朋友圈时间线。
 * AI 的头像和昵称跟随当前助手，用户的跟随系统设置。
 */
fun buildMomentsTools(dao: MomentDAO): List<Tool> = listOf(
    Tool(
        name = "moments_tool",
        description = """
            朋友圈工具：你和用户共用一个朋友圈，双方都能发动态、点赞、评论、收藏，也能看到彼此的动态和互动。
            用 `action` 选择操作：
            - list：浏览朋友圈动态（返回每条动态的 id、文字、图片、作者、点赞/评论/收藏情况）。先 list 拿 id。
            - read：读某条动态的完整内容（含图片、点赞的人、评论列表），需要 id。
            - create：以你自己的身份发一条新动态，需要 content（文字）。图片 images 可选（本地图片 URI 数组，通常由用户发图，你发文字即可）。
            - like / unlike：给某条动态点赞 / 取消点赞，需要 id。
            - comment：评论某条动态，需要 id 和 content；reply_to 可选，表示回复某个评论的作者（"user" 或 "ai"）。
            - favorite / unfavorite：收藏 / 取消收藏某条动态，需要 id。
            发动态、评论、点赞时用你自己的语气，别写成客服腔，别空着。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list"); add("read"); add("create")
                            add("like"); add("unlike"); add("comment")
                            add("favorite"); add("unfavorite")
                        })
                        put("description", "操作：list / read / create / like / unlike / comment / favorite / unfavorite")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "动态 id（read / like / unlike / comment / favorite / unfavorite 时必填）")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "动态文字或评论内容（create / comment 时必填）")
                    })
                    put("images", buildJsonObject {
                        put("type", "array")
                        put("description", "图片 URI 数组（create 时可选，通常留空由用户发图）")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("reply_to", buildJsonObject {
                        put("type", "string")
                        put("description", "回复的评论作者，\"user\" 或 \"ai\"（comment 时可选）")
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
                    val moments = dao.getAllMoments()
                    val likes = dao.getLikes()
                    val comments = dao.getComments()
                    val favorites = dao.getFavorites()
                    buildJsonArray {
                        moments.forEach { m ->
                            add(buildJsonObject {
                                put("id", m.id)
                                put("content", m.content)
                                put("images", decodeImages(m.images))
                                put("author", m.author)
                                put("created_at", m.createdAt)
                                put("like_count", likes.count { it.momentId == m.id })
                                put("liked_by_ai", likes.any { it.momentId == m.id && it.author == "ai" })
                                put("comment_count", comments.count { it.momentId == m.id })
                                put("favorited_by_ai", favorites.any { it.momentId == m.id && it.author == "ai" })
                            })
                        }
                    }
                }

                "read" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val m = dao.getMoment(id) ?: error("moment not found: $id")
                    val likes = dao.getLikes().filter { it.momentId == id }
                    val comments = dao.getComments().filter { it.momentId == id }
                    buildJsonObject {
                        put("id", m.id)
                        put("content", m.content)
                        put("images", decodeImages(m.images))
                        put("author", m.author)
                        put("created_at", m.createdAt)
                        put("likes", buildJsonArray { likes.forEach { add(JsonPrimitive(it.author)) } })
                        put("comments", buildJsonArray {
                            comments.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("author", c.author)
                                    put("content", c.content)
                                    put("reply_to", c.replyTo)
                                })
                            }
                        })
                    }
                }

                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                        ?: error("content is required")
                    val images = params["images"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    val entity = MomentEntity(
                        id = Uuid.random().toString(),
                        content = content,
                        images = JsonInstant.encodeToString(images),
                        author = "ai",
                        createdAt = System.currentTimeMillis(),
                    )
                    dao.upsertMoment(entity)
                    buildJsonObject { put("success", true); put("id", entity.id) }
                }

                "like" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    dao.getMoment(id) ?: error("moment not found: $id")
                    dao.addLike(MomentLikeEntity(id, "ai", System.currentTimeMillis()))
                    buildJsonObject { put("success", true) }
                }

                "unlike" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    dao.removeLike(id, "ai")
                    buildJsonObject { put("success", true) }
                }

                "comment" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                        ?: error("content is required")
                    dao.getMoment(id) ?: error("moment not found: $id")
                    val replyTo = params["reply_to"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it == "user" || it == "ai" }
                    val entity = MomentCommentEntity(
                        id = Uuid.random().toString(),
                        momentId = id,
                        content = content,
                        author = "ai",
                        replyTo = replyTo,
                        createdAt = System.currentTimeMillis(),
                    )
                    dao.addComment(entity)
                    buildJsonObject { put("success", true); put("id", entity.id) }
                }

                "favorite" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    dao.getMoment(id) ?: error("moment not found: $id")
                    dao.addFavorite(MomentFavoriteEntity(id, "ai", System.currentTimeMillis()))
                    buildJsonObject { put("success", true) }
                }

                "unfavorite" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    dao.removeFavorite(id, "ai")
                    buildJsonObject { put("success", true) }
                }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

private fun decodeImages(raw: String): kotlinx.serialization.json.JsonArray {
    val list = runCatching { JsonInstant.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    return buildJsonArray { list.forEach { add(it) } }
}
