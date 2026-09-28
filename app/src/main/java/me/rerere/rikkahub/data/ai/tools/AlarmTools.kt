package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 闹钟工具：调用手机系统时钟 App 设置闹钟（不是 App 内部提醒）。
 *
 * 用 [AlarmClock.ACTION_SET_ALARM] + `EXTRA_SKIP_UI` 直接落到系统时钟里。
 */
fun buildAlarmTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "alarm_tool",
        description = """
            给用户的手机设置闹钟（会跳转到手机自带的「时钟」App 的设闹钟界面，由用户确认）。
            需要 hour（0-23）和 minute（0-59），以及 label（闹钟备注，必填）。
            label 的内容由你自己决定——写一句贴合这次提醒的话，比如“该起床啦”“开会”“喝药”等，不要空着。
            可选 days：重复的星期，1=周一 … 7=周日，例如 [1,2,3,4,5] 表示工作日。不传 days 就是一次性闹钟。
            当前时间是 ${java.time.LocalDateTime.now()}。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("hour", buildJsonObject {
                        put("type", "integer")
                        put("description", "小时，0-23")
                    })
                    put("minute", buildJsonObject {
                        put("type", "integer")
                        put("description", "分钟，0-59")
                    })
                    put("label", buildJsonObject {
                        put("type", "string")
                        put("description", "闹钟备注（必填，内容由你决定），例如“该起床啦”")
                    })
                    put("days", buildJsonObject {
                        put("type", "array")
                        put("description", "重复星期，1=周一 … 7=周日；不传为一次性")
                        put("items", buildJsonObject { put("type", "integer") })
                    })
                },
                required = listOf("hour", "minute", "label")
            )
        },
        execute = {
            val params = it.jsonObject
            val hour = params["hour"]?.jsonPrimitive?.intOrNull ?: error("hour is required")
            val minute = params["minute"]?.jsonPrimitive?.intOrNull ?: error("minute is required")
            require(hour in 0..23) { "hour must be 0-23" }
            require(minute in 0..59) { "minute must be 0-59" }
            val label = params["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("label is required")
            val days = params["days"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?.filter { it in 1..7 }
                ?: emptyList()

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (days.isNotEmpty()) {
                    putExtra(
                        AlarmClock.EXTRA_DAYS,
                        ArrayList(days.map { d -> if (d == 7) Calendar.SUNDAY else d + 1 }),
                    )
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i("AlarmTool", "setAlarm intent=$intent hour=$hour minute=$minute label=$label days=$days")
            try {
                context.startActivity(intent)
                Log.i("AlarmTool", "startActivity OK")
            } catch (e: Exception) {
                Log.e("AlarmTool", "startActivity failed", e)
                error("启动系统时钟失败：${e.message}")
            }

            val payload = buildJsonObject {
                put("success", true)
                put("time", "%02d:%02d".format(hour, minute))
                put("label", label)
                if (days.isNotEmpty()) {
                    put("days", buildJsonArray { days.forEach { add(it) } })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
