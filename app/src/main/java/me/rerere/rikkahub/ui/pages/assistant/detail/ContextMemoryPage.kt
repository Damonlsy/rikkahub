package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.model.ContextMemory
import me.rerere.rikkahub.data.model.ContextMemoryKind
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ContextMemoryPage(id: String) {
    val repository: MemoryRepository = koinInject()
    val assistantId = id
    var memories by remember { mutableStateOf<List<ContextMemory>>(emptyList()) }
    var compressionRecords by remember { mutableStateOf<List<ContextMemory>>(emptyList()) }
    var editing by remember { mutableStateOf<ContextMemory?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refresh = {
        scope.launch(Dispatchers.IO) {
            memories = repository.getContextMemories(assistantId)
            compressionRecords = repository.getCompressionRecords(assistantId)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("上下文记忆库") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showAdd = true }) { Icon(HugeIcons.Add01, "新增记忆") }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("每次上下文压缩都会保留完整摘要；长期信息会单独整理并供后续对话检索。", style = MaterialTheme.typography.bodySmall)
            }
            if (compressionRecords.isNotEmpty()) {
                item { Text("压缩记录", style = MaterialTheme.typography.titleMedium) }
                items(compressionRecords, key = { "compression-${it.id}" }) { record ->
                    Card(onClick = { editing = record }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("压缩总结", style = MaterialTheme.typography.labelMedium)
                            Text(record.content)
                            Text("来源会话：${record.sourceConversationId.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item { Text("长期记忆", style = MaterialTheme.typography.titleMedium) }
            if (memories.isEmpty()) item { Text("还没有从压缩记录中提取到长期信息。") }
            items(memories, key = { it.id }) { memory ->
                Card(onClick = { editing = memory }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(memory.kind.name, style = MaterialTheme.typography.labelMedium)
                        Text(memory.content)
                        Text("可信度 ${(memory.confidence * 100).toInt()}% · 重要性 ${(memory.importance * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        memory.sourceConversationId?.let { Text("来源：$it", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
    MemoryEditor(
        memory = editing,
        visible = editing != null || showAdd,
        onDismiss = { editing = null; showAdd = false },
        onSave = { content, kind, confidence, importance ->
            scope.launch(Dispatchers.IO) {
                repository.upsertContextMemory(assistantId, content, kind, confidence = confidence, importance = importance)
                refresh()
            }
            editing = null
            showAdd = false
        },
        onDelete = editing?.let { memory ->
            {
                scope.launch(Dispatchers.IO) {
                    repository.deleteMemory(memory.id)
                    refresh()
                }
                editing = null
            }
        },
    )
}

@Composable
private fun MemoryEditor(
    memory: ContextMemory?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, ContextMemoryKind, Float, Float) -> Unit,
    onDelete: (() -> Unit)?,
) {
    if (!visible) return
    var content by remember(memory?.id) { mutableStateOf(memory?.content.orEmpty()) }
    var kind by remember(memory?.id) { mutableStateOf(memory?.kind ?: ContextMemoryKind.OTHER) }
    var confidence by remember(memory?.id) { mutableStateOf(memory?.confidence ?: 0.65f) }
    var importance by remember(memory?.id) { mutableStateOf(memory?.importance ?: 0.5f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (memory == null) "新增上下文记忆" else "编辑上下文记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(content, { content = it }, label = { Text("内容") }, minLines = 3)
                Text("分类：${kind.name}", style = MaterialTheme.typography.labelMedium)
                Slider(value = kind.ordinal.toFloat(), onValueChange = { kind = ContextMemoryKind.entries[it.toInt().coerceIn(ContextMemoryKind.entries.indices)] }, valueRange = 0f..(ContextMemoryKind.entries.lastIndex.toFloat()))
                Text("可信度 ${(confidence * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = confidence, onValueChange = { confidence = it }, valueRange = 0f..1f)
                Text("重要性 ${(importance * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = importance, onValueChange = { importance = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = { TextButton(enabled = content.isNotBlank(), onClick = { onSave(content.trim(), kind, confidence, importance) }) { Text("保存") } },
        dismissButton = {
            Column {
                onDelete?.let { TextButton(onClick = it) { Text("移入回收站") } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
