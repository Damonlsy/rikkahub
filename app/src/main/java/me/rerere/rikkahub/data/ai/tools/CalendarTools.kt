package me.rerere.rikkahub.data.ai.tools

import java.time.LocalDate
import java.time.format.DateTimeParseException
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
import me.rerere.rikkahub.data.db.dao.AnniversaryDAO
import me.rerere.rikkahub.data.db.dao.PeriodDAO
import me.rerere.rikkahub.data.db.entity.AnniversaryEntity
import me.rerere.rikkahub.data.db.entity.PeriodRecordEntity

private const val DATE_FORMAT_HINT = "日期格式 yyyy-MM-dd，例如 2026-10-02"

/**
 * 日历工具：用户已授权 AI 自主添加/删除纪念日与经期记录，与日记本页面用的是同一份数据。
 */
fun buildCalendarTools(
    periodDao: PeriodDAO,
    anniversaryDao: AnniversaryDAO,
): List<Tool> = listOf(
    Tool(
        name = "calendar_tool",
        description = """
            日历工具：管理纪念日和经期记录，写进用户日记本页面的日历里，用户能直接看到。
            用 `action` 选择操作：
            - list_anniversaries：列出全部纪念日（id、标题、日期、是否每年重复、emoji）。
            - add_anniversary：添加纪念日，需要 title 和 date（$DATE_FORMAT_HINT）；repeat_yearly 默认 true（每年重复）；emoji 可选。
            - delete_anniversary：删除纪念日，需要 id。
            - list_periods：列出全部经期记录（日期、流量 flow、备注）。flow：1=轻 2=中 3=重。
            - add_period：记录经期，需要 date（$DATE_FORMAT_HINT），flow 可选（默认 2=中），note 可选；同一天重复记录会覆盖旧值。
            - delete_period：删除某天的经期记录，需要 id。
            用户授权你自主添加纪念日和经期，不需要再问一遍；日期拿不准时先用系统给的今天日期推算。
            今天是 ${LocalDate.now()}。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("list_anniversaries")
                                add("add_anniversary")
                                add("delete_anniversary")
                                add("list_periods")
                                add("add_period")
                                add("delete_period")
                            }
                        )
                        put("description", "要执行的操作")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "记录 id（delete_anniversary / delete_period 时必填，来自 list 结果）")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "纪念日标题（add_anniversary 时必填）")
                    })
                    put("date", buildJsonObject {
                        put("type", "string")
                        put("description", "日期，$DATE_FORMAT_HINT（add_anniversary / add_period 时必填）")
                    })
                    put("repeat_yearly", buildJsonObject {
                        put("type", "boolean")
                        put("description", "是否每年重复（add_anniversary 时可选，默认 true）")
                    })
                    put("emoji", buildJsonObject {
                        put("type", "string")
                        put("description", "纪念日 emoji 标记（add_anniversary 时可选）")
                    })
                    put("flow", buildJsonObject {
                        put("type", "integer")
                        put("description", "经期流量：1=轻 2=中 3=重（add_period 时可选，默认 2）")
                    })
                    put("note", buildJsonObject {
                        put("type", "string")
                        put("description", "经期备注（add_period 时可选）")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            fun requireDate(): String {
                val raw = params["date"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("date is required")
                return try {
                    LocalDate.parse(raw).toString()
                } catch (e: DateTimeParseException) {
                    error("date 格式错误：$raw，需要 $DATE_FORMAT_HINT")
                }
            }
            val payload = when (action) {
                "list_anniversaries" -> {
                    val anniversaries = anniversaryDao.getAll()
                    buildJsonArray {
                        anniversaries.forEach { a ->
                            add(buildJsonObject {
                                put("id", a.id)
                                put("title", a.title)
                                put("date", a.date)
                                put("repeat_yearly", a.repeatYearly)
                                put("emoji", a.emoji)
                            })
                        }
                    }
                }

                "add_anniversary" -> {
                    val title = params["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                        ?: error("title is required")
                    val repeatYearly = params["repeat_yearly"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: true
                    val emoji = params["emoji"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val entity = AnniversaryEntity(
                        title = title,
                        date = requireDate(),
                        repeatYearly = repeatYearly,
                        emoji = emoji,
                        createdAt = System.currentTimeMillis(),
                    )
                    val id = anniversaryDao.upsert(entity)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                        put("title", entity.title)
                        put("date", entity.date)
                    }
                }

                "delete_anniversary" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: error("id is required")
                    anniversaryDao.delete(id)
                    buildJsonObject { put("success", true) }
                }

                "list_periods" -> {
                    val periods = periodDao.getAll()
                    buildJsonArray {
                        periods.forEach { p ->
                            add(buildJsonObject {
                                put("id", p.id)
                                put("date", p.date)
                                put("flow", p.flow)
                                put("note", p.note)
                            })
                        }
                    }
                }

                "add_period" -> {
                    val flow = params["flow"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2
                    if (flow !in 1..3) error("flow 必须是 1/2/3（1=轻 2=中 3=重）")
                    val note = params["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val date = requireDate()
                    val existing = periodDao.getByDate(date)
                    val entity = PeriodRecordEntity(
                        id = existing?.id ?: 0,
                        date = date,
                        flow = flow,
                        note = note.trim(),
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                    val id = periodDao.upsert(entity)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                        put("date", entity.date)
                        put("flow", entity.flow)
                    }
                }

                "delete_period" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: error("id is required")
                    periodDao.delete(id)
                    buildJsonObject { put("success", true) }
                }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
