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
 * 已知应用的包名（优先用这个，避免同名误匹配）
 */
private val KNOWN_PACKAGES = mapOf(
    "微信" to "com.tencent.mm",
    "学习通" to "com.chaoxing.mobile",
    "支付宝" to "com.eg.android.AlipayGphone",
    "完美校园" to "com.newcapec.mobile.ncp",
    "百度网盘" to "com.baidu.netdisk",
)

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
            - protected：查看受保护应用（用户设置的、永远不允许锁的清单）
            注意：
            1. 受保护清单由用户在「设置 → 应用锁」里自定义，随时可能变化——锁之前用 protected 查询最新清单，不要凭记忆判断。受保护的应用调用 lock 会被拒绝。
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
                "protected" -> {
                    val pm = context.packageManager
                    buildJsonObject {
                        put("protected", buildJsonArray {
                            store.protectedPackages().forEach { pkg ->
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
                    val pm = context.packageManager
                    val scanned = runCatching { pm.getInstalledApplications(0).size }.getOrDefault(0)
                    val results = buildJsonArray {
                        apps.forEach { name ->
                            val pkg = resolvePackage(context, name)
                            if (pkg == null) {
                                add(buildJsonObject {
                                    put("app", name)
                                    put("ok", false)
                                    put("reason", "未安装或找不到")
                                    put("scannedApps", scanned)
                                })
                                return@forEach
                            }
                            if (action == "lock" && store.isProtected(pkg)) {
                                add(buildJsonObject {
                                    put("app", name)
                                    put("ok", false)
                                    put("reason", "用户把它设为受保护应用，不能锁（用户可在 设置 → 应用锁 里取消保护）")
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
                    buildJsonObject {
                        put("results", results)
                        // 小米/红米等国产 ROM 单独管控「读取应用列表」权限：没授权时几乎扫不到任何应用
                        if (scanned <= 10) {
                            put(
                                "hint",
                                "系统没有授予「读取应用列表」权限，读不到已安装的应用（小米/红米：手机设置 → 应用信息 → Damonlsy → 权限 → 读取应用列表 → 允许；或在 app 的 设置 → 应用锁 里重新申请）",
                            )
                        }
                    }
                }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
