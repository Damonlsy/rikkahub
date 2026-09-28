package me.rerere.rikkahub.data.ai.tools

import java.time.LocalDate
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
import me.rerere.rikkahub.data.db.dao.DiaryDAO
import me.rerere.rikkahub.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.data.db.entity.DiaryEntity

/**
 * 日记本工具：让 AI 能读、写日记并评论。与用户在 App 里用的是同一本日记。
 */
fun buildDiaryTools(dao: DiaryDAO): List<Tool> = listOf(
    Tool(
        name = "diary_tool",
        description = """
            日记本工具：读取和书写日记，也可以给日记写评论。你和用户共用同一本日记，双方都能看到彼此写的内容和评论。
            用 `action` 选择操作：
            - list：列出所有日记（返回 id、标题、日期、作者）。先 list 拿到 id，再做其他操作。
            - read：读取某篇日记的正文和已有评论，需要 id。
            - create：以你自己的身份写一篇新日记，需要 title（标题）和 content（正文）。
            - comment：给某篇日记写评论，需要 id 和 content。
            今天是 ${LocalDate.now()}。
            写日记或评论时用你自己的语气，别写成客服腔。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("list")
                                add("read")
                                add("create")
                                add("comment")
                            }
                        )
                        put("description", "要执行的操作：list / read / create / comment")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "日记的 id（read / comment 时必填）")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "日记标题（create 时必填）")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "日记正文或评论内容（create / comment 时必填）")
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
                    val diaries = dao.getAllDiaries()
                    buildJsonArray {
                        diaries.forEach { d ->
                            add(buildJsonObject {
                                put("id", d.id)
                                put("title", d.title)
                                put("date", d.date)
                                put("author", d.author)
                            })
                        }
                    }
                }

                "read" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val diary = dao.getDiary(id) ?: error("diary not found: $id")
                    val comments = dao.getComments(id)
                    buildJsonObject {
                        put("id", diary.id)
                        put("title", diary.title)
                        put("date", diary.date)
                        put("author", diary.author)
                        put("content", diary.content)
                        put(
                            "comments",
                            buildJsonArray {
                                comments.forEach { c ->
                                    add(buildJsonObject {
                                        put("author", c.author)
                                        put("content", c.content)
                                    })
                                }
                            }
                        )
                    }
                }

                "create" -> {
                    val title = params["title"]?.jsonPrimitive?.contentOrNull ?: error("title is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val now = System.currentTimeMillis()
                    val entity = DiaryEntity(
                        id = Uuid.random().toString(),
                        title = title.trim(),
                        content = content.trim(),
                        author = "ai",
                        date = LocalDate.now().toString(),
                        createdAt = now,
                        updatedAt = now,
                    )
                    dao.upsertDiary(entity)
                    buildJsonObject {
                        put("success", true)
                        put("id", entity.id)
                    }
                }

                "comment" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    dao.getDiary(id) ?: error("diary not found: $id")
                    val entity = DiaryCommentEntity(
                        id = Uuid.random().toString(),
                        diaryId = id,
                        content = content.trim(),
                        author = "ai",
                        createdAt = System.currentTimeMillis(),
                    )
                    dao.upsertComment(entity)
                    buildJsonObject {
                        put("success", true)
                        put("id", entity.id)
                    }
                }

                else -> error("unknown action: $action, must be one of [list, read, create, comment]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
