package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.PatActionDAO
import me.rerere.rikkahub.data.db.dao.StickerDAO
import me.rerere.rikkahub.data.db.entity.PatActionEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.PAT_AUTHOR_AI
import me.rerere.rikkahub.utils.PAT_AUTHOR_USER
import me.rerere.rikkahub.utils.PAT_SLOT_ALL
import me.rerere.rikkahub.utils.PAT_SLOT_LABELS
import me.rerere.rikkahub.utils.PatDefaults
import me.rerere.rikkahub.utils.STICKER_OWNER_AI
import me.rerere.rikkahub.utils.STICKER_OWNER_USER
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SettingStickerPatPage() {
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val stickerDao: StickerDAO = koinInject()
    val patActionDao: PatActionDAO = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) {
        PatDefaults.ensureSeeded(context, patActionDao)
    }

    val patActions by patActionDao.listActions().collectAsStateWithLifecycle(initialValue = emptyList())
    val allStickers by stickerDao.listAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val myStickerCount = allStickers.count { it.owner == STICKER_OWNER_USER }
    val aiStickerCount = allStickers.count { it.owner == STICKER_OWNER_AI }

    var wordOwner by remember { mutableStateOf(PAT_AUTHOR_USER) }
    var pendingSlot by remember { mutableStateOf(PAT_SLOT_ALL.first()) }
    var showAddWord by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("表情包与拍一拍") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text("表情包") },
                ) {
                    item(
                        headlineContent = { Text("表情包功能") },
                        supportingContent = { Text("关掉后输入框里就没有表情包了，AI 也收不到发表情包的工具") },
                        trailingContent = {
                            Switch(
                                checked = settings.displaySetting.stickerEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(
                                                displaySetting = it.displaySetting.copy(
                                                    stickerEnabled = checked
                                                )
                                            )
                                        }
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("我的表情包库") },
                        supportingContent = { Text("点输入框右边的 + → 表情包，就能上传和发送") },
                        trailingContent = { Text("$myStickerCount 张") },
                    )
                    item(
                        headlineContent = { Text("AI 的表情包库") },
                        supportingContent = { Text("AI 只会从这个库里发表情包，也可以在表情包面板里帮它收藏") },
                        trailingContent = { Text("$aiStickerCount 张") },
                    )
                }
            }

            // 两套词库完全分开：你管你的，AI 管 AI 的
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = wordOwner == PAT_AUTHOR_USER,
                        onClick = { wordOwner = PAT_AUTHOR_USER },
                        label = { Text("我的词库") },
                    )
                    FilterChip(
                        selected = wordOwner == PAT_AUTHOR_AI,
                        onClick = { wordOwner = PAT_AUTHOR_AI },
                        label = { Text("AI 的词库") },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text("拍一拍词库") },
                ) {
                    item(
                        headlineContent = { Text("一句话怎么拼") },
                        supportingContent = {
                            Text(
                                if (wordOwner == PAT_AUTHOR_USER) {
                                    "我（方式）（动作）AI（部位）· 例如：我用手轻轻捏了捏AI"
                                } else {
                                    "AI（方式）（动作）你（部位）· 例如：AI悄悄地捏了捏你脸蛋"
                                }
                            )
                        },
                    )

                    PAT_SLOT_ALL.forEach { slot ->
                        val label = PAT_SLOT_LABELS.getValue(slot)
                        val words = patActions.filter { it.author == wordOwner && it.slot == slot }

                        item(
                            overlineContent = { Text(label) },
                            onClick = {
                                pendingSlot = slot
                                showAddWord = true
                            },
                            leadingContent = { Icon(HugeIcons.Add01, null) },
                            headlineContent = { Text("新增$label") },
                            supportingContent = { Text("点这里加一个词，比如「${PatDefaults.WORDS.getValue(slot).first()}」") },
                        )

                        if (words.isEmpty()) {
                            item(
                                overlineContent = { Text(label) },
                                headlineContent = { Text("这里没有${label}词") },
                                supportingContent = { Text("没关系，拍一拍那句话里就直接省掉这一段，不会拿别的词顶上") },
                            )
                        }

                        words.forEach { word ->
                            item(
                                overlineContent = { Text(label) },
                                headlineContent = { Text(word.text) },
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            scope.launch { patActionDao.delete(word.id) }
                                        }
                                    ) {
                                        Icon(HugeIcons.Delete02, contentDescription = "删除")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddWord) {
        AddPatWordDialog(
            slot = pendingSlot,
            onConfirm = { text ->
                showAddWord = false
                scope.launch {
                    patActionDao.upsert(
                        PatActionEntity(
                            id = Uuid.random().toString(),
                            text = text,
                            author = wordOwner,
                            slot = pendingSlot,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
            },
            onDismiss = { showAddWord = false },
        )
    }
}

@Composable
private fun AddPatWordDialog(
    slot: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var selectedSlot by remember { mutableStateOf(slot) }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "新增拍一拍词", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PAT_SLOT_ALL.forEach { item ->
                        val label = PAT_SLOT_LABELS.getValue(item)
                        FilterChip(
                            selected = selectedSlot == item,
                            onClick = { selectedSlot = item },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    text = "三个槽位拼成一句话，人称由系统自动拼。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(PatDefaults.WORDS.getValue(selectedSlot).first()) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = text.isNotBlank(),
                        onClick = { onConfirm(text.trim()) }
                    ) { Text("确定") }
                }
            }
        }
    }
}
