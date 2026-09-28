package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.applock.AppLockStore

/**
 * 受保护白名单：这些应用**永远不允许被锁**（防止把关键 App 锁死）。
 */
internal val APP_LOCK_PROTECTED = listOf(
    "微信",
    "学习通",
    "支付宝",
    "完美校园",
    "胖乖生活",
    "到梦空间",
    "百度网盘",
)

/** 已知应用的包名（优先用这个，避免同名误匹配） */
private val KNOWN_PACKAGES = mapOf(
    "微信" to "com.tencent.mm",
    "学习通" to "com.chaoxing.mobile",
    "支付宝" to "com.eg.android.AlipayGphone",
    "完美校园" to "com.newcapec.mobile.ncp",
    "百度网盘" to "com.baidu.netdisk",
)

internal fun isProtectedName(name: String): Boolean =
    APP_LOCK_PROTECTED.any { it == name.trim() }

internal fun isProtectedPackage(pkg: String): Boolean =
    KNOWN_PACKAGES.values.any { it == pkg }

internal fun resolvePackage(context: Context, name: String): String? {
    val key = name.trim()
    KNOWN_PACKAGES[key]?.let { return it }
    val pm = context.packageManager
    var fuzzy: String? = null
    for (app in pm.getInstalledApplications(0)) {
        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue
        if (label == key) return app.packageName
        if (fuzzy == null && label.contains(key)) fuzzy = app.packageName
    }
    return fuzzy
}

fun buildAppLockTools(context: Context, store: AppLockStore, assistantName: String): List<Tool> = listOf(
    Tool(
        name = "app_lock_tool",
        description = """
            管理手机应用锁：被锁定的应用一打开就会显示拦截页（灰色图标 + 被谁锁定 + 备注）。你可以锁定、也可以解锁。
            用 `action` 选择操作：
            - lock：锁定应用，需要 apps（应用名数组，例如 ["抖音","哔哩哔哩"]）和 note（锁定备注，必填）
            - unlock：解锁应用，需要 apps
            - list：查看当前已锁定的应用
            - protected：查看受保护白名单（这些应用**永远不允许锁**）
            注意：
            1. 白名单里的应用是受保护的，锁不了（调用 lock 会被拒绝）。白名单：${APP_LOCK_PROTECTED.joinToString("、")}。
            2. lock 必须写 note——写一句给用户看的备注，说明为什么锁它，比如“先把作业写完”“该睡觉了”。内容由你决定，不要空着。
            应用名用中文名即可（会自动匹配已安装的应用）。锁定是否生效取决于用户有没有开启无障碍服务。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("lock")
                                add("unlock")
                                add("list")
                                add("protected")
                            }
                        )
                        put("description", "要执行的操作：lock / unlock / list / protected")
                    })
                    put("apps", buildJsonObject {
                        put("type", "array")
                        put("description", "应用名数组，例如 [\"抖音\",\"哔哩哔哩\"]")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("note", buildJsonObject {
                        put("type", "string")
                        put("description", "锁定备注（lock 时必填），内容由你决定，例如“先写作业”")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val apps = params["apps"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val note = params["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            val payload = when (action) {
                "protected" -> buildJsonObject {
                    put("protected", buildJsonArray { APP_LOCK_PROTECTED.forEach { add(it) } })
                }

                "list" -> {
                    val locked = store.lockedPackages()
                    val pm = context.packageManager
                    buildJsonObject {
                        put("locked", buildJsonArray {
                            locked.forEach { pkg ->
                                val label = runCatching {
                                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                                }.getOrNull() ?: pkg
                                add(buildJsonObject {
                                    put("name", label)
                                    put("package", pkg)
                                })
                            }
                        })
                    }
                }

                "lock", "unlock" -> {
                    require(apps.isNotEmpty()) { "apps is required" }
                    if (action == "lock") require(note.isNotBlank()) { "note is required for lock" }
                    val results = buildJsonArray {
                        apps.forEach { name ->
                            if (isProtectedName(name)) {
                                add(buildJsonObject {
                                    put("app", name)
                                    put("ok", false)
                                    put("reason", "在受保护白名单里，不能锁")
                                })
                                return@forEach
                            }
                            val pkg = resolvePackage(context, name)
                            if (pkg == null) {
                                add(buildJsonObject {
                                    put("app", name)
                                    put("ok", false)
                                    put("reason", "未安装或找不到")
                                })
                                return@forEach
                            }
                            if (action == "lock" && isProtectedPackage(pkg)) {
                                add(buildJsonObject {
                                    put("app", name)
                                    put("ok", false)
                                    put("reason", "在受保护白名单里，不能锁")
                                })
                                return@forEach
                            }
                            if (action == "lock") store.lock(pkg, note, assistantName) else store.unlock(pkg)
                            add(buildJsonObject {
                                put("app", name)
                                put("package", pkg)
                                put("ok", true)
                            })
                        }
                    }
                    buildJsonObject { put("results", results) }
                }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
