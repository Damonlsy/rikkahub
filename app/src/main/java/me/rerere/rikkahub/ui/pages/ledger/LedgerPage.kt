package me.rerere.rikkahub.ui.pages.ledger

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wallet
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.LedgerEntity
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import org.koin.androidx.compose.koinViewModel

private data class StatDetail(val wallet: String, val kind: String)

private fun formatYuan(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "%s¥%d.%02d".format(sign, abs / 100, abs % 100)
}

@Composable
fun LedgerPage(
    vm: LedgerVM = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val settings = LocalSettings.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<StatDetail?>(null) }
    var selectedEntry by remember { mutableStateOf<LedgerEntity?>(null) }
    var selectedWallet by remember { mutableStateOf(LEDGER_WALLET_USER) }

    Box(modifier = Modifier.fillMaxSize()) {
        AssistantBackground(
            setting = settings,
            modifier = Modifier.fillMaxSize(),
        )
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("记账本") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Lucide.ArrowLeft, contentDescription = "返回")
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Lucide.Plus, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("记一笔")
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TabRow(
                    selectedTabIndex = if (selectedWallet == LEDGER_WALLET_USER) 0 else 1,
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = selectedWallet == LEDGER_WALLET_USER,
                        onClick = { selectedWallet = LEDGER_WALLET_USER },
                        text = { Text("我的小荷包") },
                    )
                    Tab(
                        selected = selectedWallet == LEDGER_WALLET_AI,
                        onClick = { selectedWallet = LEDGER_WALLET_AI },
                        text = { Text("AI 的小荷包") },
                    )
                }
                val walletEntries = entries.filter { it.wallet == selectedWallet }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        WalletSection(
                            wallet = selectedWallet,
                            entries = entries,
                            onStatClick = { detail = it },
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "明细（${walletEntries.size}）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(onClick = { showTransfer = true }) {
                                Icon(Lucide.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("转赠")
                            }
                        }
                    }
                    if (walletEntries.isEmpty()) {
                        item {
                            Text(
                                text = "还没有记账，点右下角记一笔吧",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(walletEntries, key = { it.id }) { entry ->
                        LedgerRow(
                            entry = entry,
                            onClick = { selectedEntry = entry },
                            onDelete = { vm.delete(entry.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        LedgerAddDialog(
            onDismiss = { showAdd = false },
            onSave = { wallet, kind, amount, note ->
                vm.add(wallet, kind, amount, note)
                showAdd = false
            },
        )
    }

    if (showTransfer) {
        LedgerTransferDialog(
            onDismiss = { showTransfer = false },
            onTransfer = { from, to, amount, note ->
                vm.transfer(from, to, amount, note)
                showTransfer = false
            },
        )
    }

    detail?.let { d ->
        LedgerDetailDialog(
            detail = d,
            entries = entries,
            onDismiss = { detail = null },
        )
    }

    selectedEntry?.let { entry ->
        LedgerEntryDetailDialog(
            entry = entry,
            onDismiss = { selectedEntry = null },
        )
    }
}

@Composable
private fun WalletSection(
    wallet: String,
    entries: List<LedgerEntity>,
    onStatClick: (StatDetail) -> Unit,
) {
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val isAi = wallet == LEDGER_WALLET_AI
    val accent = if (isAi) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val name = if (isAi) {
        assistant.name.ifBlank { "AI" }
    } else {
        settings.displaySetting.userNickname.ifBlank { "我" }
    }
    val avatar = if (isAi) assistant.avatar else settings.displaySetting.userAvatar
    val balance = entries.walletBalance(wallet)
    val income = entries.walletSum(wallet, LEDGER_KIND_INCOME)
    val expense = entries.walletSum(wallet, LEDGER_KIND_EXPENSE)
    val transferOut = entries.walletSum(wallet, LEDGER_KIND_TRANSFER_OUT)
    val transferLabel = if (isAi) "AI转赠" else "转赠给AI"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIAvatar(
                    name = name,
                    value = avatar,
                    modifier = Modifier.size(32.dp),
                    loading = false,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isAi) "$name 的小荷包" else "${name}的小荷包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            StatBox(
                label = "总资产",
                value = formatYuan(balance),
                accent = accent,
                big = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onStatClick(StatDetail(wallet, "all")) },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox(
                    label = "收入",
                    value = formatYuan(income),
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatClick(StatDetail(wallet, LEDGER_KIND_INCOME)) },
                )
                StatBox(
                    label = "支出",
                    value = formatYuan(expense),
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatClick(StatDetail(wallet, LEDGER_KIND_EXPENSE)) },
                )
                StatBox(
                    label = transferLabel,
                    value = formatYuan(transferOut),
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatClick(StatDetail(wallet, LEDGER_KIND_TRANSFER_OUT)) },
                )
            }
        }
    }
}

@Composable
private fun StatBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "statPress",
    )
    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
    ) {
        Column(
            modifier = Modifier
                .height(if (big) 92.dp else 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = if (big) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LedgerRow(
    entry: LedgerEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val recorder = entry.recorder.ifBlank { entry.wallet }
    val isIncome = entry.kind == LEDGER_KIND_INCOME || entry.kind == LEDGER_KIND_TRANSFER_IN
    val kindLabel = when (entry.kind) {
        LEDGER_KIND_INCOME -> "收入"
        LEDGER_KIND_EXPENSE -> "支出"
        LEDGER_KIND_TRANSFER_OUT -> "转赠"
        LEDGER_KIND_TRANSFER_IN -> "收到转赠"
        else -> entry.kind
    }
    val recorderName = when (recorder) {
        LEDGER_WALLET_AI -> assistant.name.ifBlank { "AI" }
        LEDGER_WALLET_USER -> settings.displaySetting.userNickname.ifBlank { "我" }
        else -> "历史记录"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UIAvatar(
                name = recorderName,
                value = when (recorder) {
                    LEDGER_WALLET_AI -> assistant.avatar
                    LEDGER_WALLET_USER -> settings.displaySetting.userAvatar
                    else -> Avatar.Dummy
                },
                modifier = Modifier.size(32.dp),
                loading = false,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.note.ifBlank { kindLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$kindLabel · $recorderName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = (if (isIncome) "+" else "-") + formatYuan(entry.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isIncome) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = "删除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LedgerDetailDialog(
    detail: StatDetail,
    entries: List<LedgerEntity>,
    onDismiss: () -> Unit,
) {
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val isAi = detail.wallet == LEDGER_WALLET_AI
    val ownerName = if (isAi) assistant.name.ifBlank { "AI" } else settings.displaySetting.userNickname.ifBlank { "我" }
    val title = when (detail.kind) {
        "all" -> "$ownerName · 全部明细"
        LEDGER_KIND_INCOME -> "$ownerName · 收入明细"
        LEDGER_KIND_EXPENSE -> "$ownerName · 支出明细"
        LEDGER_KIND_TRANSFER_OUT -> "$ownerName · 转赠明细"
        else -> ownerName
    }
    val filtered = if (detail.kind == "all") {
        entries.filter { it.wallet == detail.wallet }
    } else {
        entries.filter { it.wallet == detail.wallet && it.kind == detail.kind }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (filtered.isEmpty()) {
                Text(
                    text = "暂无记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered, key = { it.id }) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.note.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = (if (entry.kind == LEDGER_KIND_INCOME || entry.kind == LEDGER_KIND_TRANSFER_IN) "+" else "-") +
                                    formatYuan(entry.amount),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun LedgerEntryDetailDialog(
    entry: LedgerEntity,
    onDismiss: () -> Unit,
) {
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val recorder = entry.recorder.ifBlank { entry.wallet }
    val isIncome = entry.kind == LEDGER_KIND_INCOME || entry.kind == LEDGER_KIND_TRANSFER_IN
    val kindLabel = when (entry.kind) {
        LEDGER_KIND_INCOME -> "收入"
        LEDGER_KIND_EXPENSE -> "支出"
        LEDGER_KIND_TRANSFER_OUT -> "转赠"
        LEDGER_KIND_TRANSFER_IN -> "收到转赠"
        else -> entry.kind
    }
    val recorderName = when (recorder) {
        LEDGER_WALLET_AI -> assistant.name.ifBlank { "AI" }
        LEDGER_WALLET_USER -> settings.displaySetting.userNickname.ifBlank { "我" }
        else -> "历史记录"
    }
    val walletLabel = if (entry.wallet == LEDGER_WALLET_AI) "AI 的小荷包" else "我的小荷包"
    val timeText = remember(entry.createdAt) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(entry.createdAt))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIAvatar(
                    name = recorderName,
                    value = when (recorder) {
                        LEDGER_WALLET_AI -> assistant.avatar
                        LEDGER_WALLET_USER -> settings.displaySetting.userAvatar
                        else -> Avatar.Dummy
                    },
                    modifier = Modifier.size(28.dp),
                    loading = false,
                )
                Spacer(Modifier.width(8.dp))
                Text("$recorderName 记的")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow("时间", timeText)
                DetailRow("金额", (if (isIncome) "+" else "-") + formatYuan(entry.amount))
                DetailRow("类型", kindLabel)
                DetailRow("钱包", walletLabel)
                DetailRow("原因", entry.note.ifBlank { "—" })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LedgerAddDialog(
    onDismiss: () -> Unit,
    onSave: (wallet: String, kind: String, amount: Double, note: String) -> Unit,
) {
    var wallet by remember { mutableStateOf(LEDGER_WALLET_USER) }
    var kind by remember { mutableStateOf(LEDGER_KIND_EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔") },
        text = {
            Column {
                Text("小荷包", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = wallet == LEDGER_WALLET_USER,
                        onClick = { wallet = LEDGER_WALLET_USER },
                        label = { Text("我的") },
                    )
                    FilterChip(
                        selected = wallet == LEDGER_WALLET_AI,
                        onClick = { wallet = LEDGER_WALLET_AI },
                        label = { Text("AI 的") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == LEDGER_KIND_EXPENSE,
                        onClick = { kind = LEDGER_KIND_EXPENSE },
                        label = { Text("支出") },
                    )
                    FilterChip(
                        selected = kind == LEDGER_KIND_INCOME,
                        onClick = { kind = LEDGER_KIND_INCOME },
                        label = { Text("收入") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(wallet, kind, amount, note) },
                enabled = amount > 0,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun LedgerTransferDialog(
    onDismiss: () -> Unit,
    onTransfer: (from: String, to: String, amount: Double, note: String) -> Unit,
) {
    var fromUser by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转赠") },
        text = {
            Column {
                Text("方向", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = fromUser,
                        onClick = { fromUser = true },
                        label = { Text("我 → AI") },
                    )
                    FilterChip(
                        selected = !fromUser,
                        onClick = { fromUser = false },
                        label = { Text("AI → 我") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (fromUser) {
                        onTransfer(LEDGER_WALLET_USER, LEDGER_WALLET_AI, amount, note)
                    } else {
                        onTransfer(LEDGER_WALLET_AI, LEDGER_WALLET_USER, amount, note)
                    }
                },
                enabled = amount > 0,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
