package me.rerere.rikkahub.ui.pages.assistant.detail

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.AvatarPair
import me.rerere.rikkahub.data.repository.AvatarPairRepository
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun AssistantAvatarPairsPage(assistantId: String) {
    val id = remember(assistantId) { Uuid.parse(assistantId) }
    val repository: AvatarPairRepository = koinInject()
    val filesManager: FilesManager = koinInject()
    val assistant by repository.assistantFlow(id).collectAsStateWithLifecycle(initialValue = null)
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AvatarPair?>(null) }
    var renameTarget by remember { mutableStateOf<AvatarPair?>(null) }
    var applyingPairId by remember { mutableStateOf<Uuid?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成对头像库") },
                navigationIcon = {
                    val nav = me.rerere.rikkahub.ui.context.LocalNavController.current
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { if (assistant != null) showAdd = true }) {
                Icon(Lucide.Plus, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加一对")
            }
        },
    ) { padding ->
        val pairs = assistant?.avatarPairs.orEmpty()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "把两张头像放成一组",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "左边永远属于 AI，右边永远属于你。AI 想换头像时仍需你在聊天中批准。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pairs.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Lucide.Link2, contentDescription = null, modifier = Modifier.size(36.dp))
                            Text("还没有成对头像", fontWeight = FontWeight.SemiBold)
                            Text(
                                "点右下角添加两张图片，并分别标明归属。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            items(pairs, key = { it.id }) { pair ->
                AvatarPairCard(
                    pair = pair,
                    aiName = assistant?.name.orEmpty().ifBlank { "AI" },
                    userName = settings.displaySetting.userNickname.ifBlank { "我" },
                    applying = applyingPairId != null,
                    applyingThisPair = applyingPairId == pair.id,
                    onApply = {
                        if (applyingPairId != null) return@AvatarPairCard
                        applyingPairId = pair.id
                        scope.launch {
                            try {
                                repository.applyPair(id, pair.id)
                            } finally {
                                applyingPairId = null
                            }
                        }
                    },
                    onRename = { renameTarget = pair },
                    onDelete = { deleteTarget = pair },
                )
            }
        }
    }

    if (showAdd) {
        AddAvatarPairDialog(
            aiName = assistant?.name.orEmpty().ifBlank { "AI" },
            userName = settings.displaySetting.userNickname.ifBlank { "我" },
            filesManager = filesManager,
            onDismiss = { showAdd = false },
            onSave = { pair -> repository.addPair(id, pair) },
            onSaved = { showAdd = false },
        )
    }

    deleteTarget?.let { pair ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这对头像？") },
            text = { Text("“${pair.name.ifBlank { "未命名头像对" }}”的两张母版图片会一起删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deletePair(id, pair.id) }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    renameTarget?.let { pair ->
        var newName by remember(pair.id) { mutableStateOf(pair.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("给这对头像命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.renamePair(id, pair.id, newName) }
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AvatarPairCard(
    pair: AvatarPair,
    aiName: String,
    userName: String,
    applying: Boolean,
    applyingThisPair: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pair.name.ifBlank { "未命名头像对" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Lucide.Trash2, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PairPortrait("AI", aiName, pair.aiAvatar, MaterialTheme.colorScheme.tertiaryContainer)
                Icon(
                    Lucide.Link2,
                    contentDescription = "一对",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                PairPortrait("用户", userName, pair.userAvatar, MaterialTheme.colorScheme.primaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onRename, modifier = Modifier.weight(1f)) { Text("改名") }
                Button(
                    onClick = onApply,
                    enabled = !applying,
                    modifier = Modifier.weight(2f),
                ) { Text(if (applyingThisPair) "正在换上整对" else "一键换上整对") }
            }
            Text(
                "同时替换用户消息头像和 AI 消息头像",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun PairPortrait(label: String, name: String, avatar: Avatar, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UIAvatar(name = name, value = avatar, modifier = Modifier.size(72.dp), loading = false)
        Surface(shape = RoundedCornerShape(50), color = color) {
            Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AddAvatarPairDialog(
    aiName: String,
    userName: String,
    filesManager: FilesManager,
    onDismiss: () -> Unit,
    onSave: suspend (AvatarPair) -> Boolean,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var aiAvatar by remember { mutableStateOf<Avatar.Image?>(null) }
    var userAvatar by remember { mutableStateOf<Avatar.Image?>(null) }
    var ownershipTransferred by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun cleanup() {
        filesManager.deleteChatFiles(listOfNotNull(aiAvatar?.url?.toUri(), userAvatar?.url?.toUri()))
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!ownershipTransferred) cleanup()
        }
    }

    AlertDialog(
        onDismissRequest = { cleanup(); onDismiss() },
        title = { Text("添加成对头像") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("这一对的名字") },
                    placeholder = { Text("例如：秋日散步") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("AI 的头像", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        UIAvatar(
                            name = aiName,
                            value = aiAvatar ?: Avatar.Dummy,
                            modifier = Modifier.size(76.dp),
                            loading = false,
                            onUpdate = { value ->
                                if (value is Avatar.Image && value.url.toUri().scheme in setOf("file", "content")) {
                                    aiAvatar?.let { filesManager.deleteChatFiles(listOf(it.url.toUri())) }
                                    aiAvatar = value
                                }
                            },
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("你的头像", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        UIAvatar(
                            name = userName,
                            value = userAvatar ?: Avatar.Dummy,
                            modifier = Modifier.size(76.dp),
                            loading = false,
                            onUpdate = { value ->
                                if (value is Avatar.Image && value.url.toUri().scheme in setOf("file", "content")) {
                                    userAvatar?.let { filesManager.deleteChatFiles(listOf(it.url.toUri())) }
                                    userAvatar = value
                                }
                            },
                        )
                    }
                }
                Text(
                    "点击头像选择图片。保存后两张图会作为这一对的母版保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = aiAvatar != null && userAvatar != null && !saving,
                onClick = {
                    saving = true
                    val pair = AvatarPair(
                            name = name.trim(),
                            aiAvatar = requireNotNull(aiAvatar),
                            userAvatar = requireNotNull(userAvatar),
                        )
                    scope.launch {
                        if (onSave(pair)) {
                            ownershipTransferred = true
                            onSaved()
                        } else {
                            saving = false
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = { cleanup(); onDismiss() }) { Text("取消") }
        },
    )
}
