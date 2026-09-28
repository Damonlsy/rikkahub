package me.rerere.rikkahub.ui.pages.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.roundToLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.LedgerDAO
import me.rerere.rikkahub.data.db.entity.LedgerEntity

const val LEDGER_WALLET_USER = "user"
const val LEDGER_WALLET_AI = "ai"
const val LEDGER_KIND_INCOME = "income"
const val LEDGER_KIND_EXPENSE = "expense"
const val LEDGER_KIND_TRANSFER_OUT = "transfer_out"
const val LEDGER_KIND_TRANSFER_IN = "transfer_in"

/** 余额 = 收入 + 转入 − 支出 − 转出 */
fun List<LedgerEntity>.walletBalance(wallet: String): Long =
    filter { it.wallet == wallet }.sumOf {
        when (it.kind) {
            LEDGER_KIND_INCOME, LEDGER_KIND_TRANSFER_IN -> it.amount
            LEDGER_KIND_EXPENSE, LEDGER_KIND_TRANSFER_OUT -> -it.amount
            else -> 0L
        }
    }

fun List<LedgerEntity>.walletSum(wallet: String, kind: String): Long =
    filter { it.wallet == wallet && it.kind == kind }.sumOf { it.amount }

class LedgerVM(
    private val dao: LedgerDAO,
) : ViewModel() {
    val entries: StateFlow<List<LedgerEntity>> = dao.listAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(wallet: String, kind: String, amountYuan: Double, note: String) {
        if (amountYuan <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(
                LedgerEntity(
                    id = Uuid.random().toString(),
                    wallet = wallet,
                    kind = kind,
                    amount = (amountYuan * 100).roundToLong(),
                    note = note.trim(),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 小荷包之间转赠：同时写出一条转出和一条转入。 */
    fun transfer(fromWallet: String, toWallet: String, amountYuan: Double, note: String) {
        if (amountYuan <= 0 || fromWallet == toWallet) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cents = (amountYuan * 100).roundToLong()
            val group = Uuid.random().toString()
            dao.upsert(
                LedgerEntity(
                    id = "$group|out",
                    wallet = fromWallet,
                    kind = LEDGER_KIND_TRANSFER_OUT,
                    amount = cents,
                    note = note.trim(),
                    createdAt = now,
                )
            )
            dao.upsert(
                LedgerEntity(
                    id = "$group|in",
                    wallet = toWallet,
                    kind = LEDGER_KIND_TRANSFER_IN,
                    amount = cents,
                    note = note.trim(),
                    createdAt = now,
                )
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 转赠是成对写入的，删一条要连带删掉配对的那条
            when {
                id.endsWith("|out") -> {
                    dao.delete(id)
                    dao.delete(id.removeSuffix("|out") + "|in")
                }
                id.endsWith("|in") -> {
                    dao.delete(id)
                    dao.delete(id.removeSuffix("|in") + "|out")
                }
                else -> dao.delete(id)
            }
        }
    }
}
