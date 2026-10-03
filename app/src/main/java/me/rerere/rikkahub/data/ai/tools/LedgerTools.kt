package me.rerere.rikkahub.data.ai.tools

import kotlin.math.roundToLong
import kotlin.uuid.Uuid
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.LedgerDAO
import me.rerere.rikkahub.data.db.entity.LedgerEntity
import me.rerere.rikkahub.ui.pages.ledger.LEDGER_KIND_EXPENSE
import me.rerere.rikkahub.ui.pages.ledger.LEDGER_KIND_INCOME
import me.rerere.rikkahub.ui.pages.ledger.LEDGER_KIND_TRANSFER_IN
import me.rerere.rikkahub.ui.pages.ledger.LEDGER_KIND_TRANSFER_OUT
import me.rerere.rikkahub.ui.pages.ledger.LEDGER_WALLET_AI
import me.rerere.rikkahub.ui.pages.ledger.LEDGER_WALLET_USER
import me.rerere.rikkahub.ui.pages.ledger.walletBalance

/**
 * 记账本工具：AI 可以记账、转赠、查看两个小荷包的余额和全部账单明细。
 * 与用户在 App 里用的是同一本账。
 */
fun buildLedgerTools(dao: LedgerDAO): List<Tool> = listOf(
    Tool(
        name = "ledger_tool",
        description = """
            记账本工具：你和用户共用的两个小荷包（"user" = 我的小荷包，"ai" = AI 的小荷包）。
            用 `action` 选择操作：
            - list：查看两个小荷包的余额，以及所有账单明细。
            - add：记一笔，需要 wallet（user/ai）、kind（income 收入 / expense 支出）、amount（元）、note（备注）。
            - transfer：两个小荷包之间转赠，需要 from（user/ai）、to（user/ai）、amount（元）、note。
            金额单位是元，支持小数。
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
                                add("add")
                                add("transfer")
                            }
                        )
                        put("description", "要执行的操作：list / add / transfer")
                    })
                    put("wallet", buildJsonObject {
                        put("type", "string")
                        put("description", "小荷包：user 或 ai（add 时必填）")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put("description", "类型：income 收入 或 expense 支出（add 时必填）")
                    })
                    put("from", buildJsonObject {
                        put("type", "string")
                        put("description", "转出方：user 或 ai（transfer 时必填）")
                    })
                    put("to", buildJsonObject {
                        put("type", "string")
                        put("description", "转入方：user 或 ai（transfer 时必填）")
                    })
                    put("amount", buildJsonObject {
                        put("type", "number")
                        put("description", "金额（元）")
                    })
                    put("note", buildJsonObject {
                        put("type", "string")
                        put("description", "备注")
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
                    val all = dao.getAll()
                    buildJsonObject {
                        put("我的小荷包余额（元）", all.walletBalance(LEDGER_WALLET_USER) / 100.0)
                        put("AI的小荷包余额（元）", all.walletBalance(LEDGER_WALLET_AI) / 100.0)
                        put(
                            "明细",
                            buildJsonArray {
                                all.forEach { e ->
                                    add(buildJsonObject {
                                        put("wallet", e.wallet)
                                        put("recorder", e.recorder.ifBlank { e.wallet })
                                        put("kind", e.kind)
                                        put("amount", e.amount / 100.0)
                                        put("note", e.note)
                                        put("created_at", e.createdAt)
                                    })
                                }
                            }
                        )
                    }
                }

                "add" -> {
                    val wallet = params["wallet"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it == LEDGER_WALLET_USER || it == LEDGER_WALLET_AI }
                        ?: error("wallet must be user or ai")
                    val kind = params["kind"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it == LEDGER_KIND_INCOME || it == LEDGER_KIND_EXPENSE }
                        ?: error("kind must be income or expense")
                    val amount = params["amount"]?.jsonPrimitive?.doubleOrNull ?: error("amount is required")
                    if (amount <= 0) error("amount must be positive")
                    val note = params["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    dao.upsert(
                        LedgerEntity(
                            id = Uuid.random().toString(),
                            wallet = wallet,
                            kind = kind,
                            amount = (amount * 100).roundToLong(),
                            note = note.trim(),
                            recorder = "ai",
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    buildJsonObject { put("success", true) }
                }

                "transfer" -> {
                    val from = params["from"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it == LEDGER_WALLET_USER || it == LEDGER_WALLET_AI }
                        ?: error("from must be user or ai")
                    val to = params["to"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it == LEDGER_WALLET_USER || it == LEDGER_WALLET_AI }
                        ?: error("to must be user or ai")
                    if (from == to) error("from and to must differ")
                    val amount = params["amount"]?.jsonPrimitive?.doubleOrNull ?: error("amount is required")
                    if (amount <= 0) error("amount must be positive")
                    val note = params["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val now = System.currentTimeMillis()
                    val cents = (amount * 100).roundToLong()
                    val group = Uuid.random().toString()
                    dao.upsert(
                        LedgerEntity(
                            id = "$group|out",
                            wallet = from,
                            kind = LEDGER_KIND_TRANSFER_OUT,
                            amount = cents,
                            note = note.trim(),
                            recorder = "ai",
                            createdAt = now,
                        )
                    )
                    dao.upsert(
                        LedgerEntity(
                            id = "$group|in",
                            wallet = to,
                            kind = LEDGER_KIND_TRANSFER_IN,
                            amount = cents,
                            note = note.trim(),
                            recorder = "ai",
                            createdAt = now,
                        )
                    )
                    buildJsonObject { put("success", true) }
                }

                else -> error("unknown action: $action, must be one of [list, add, transfer]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
