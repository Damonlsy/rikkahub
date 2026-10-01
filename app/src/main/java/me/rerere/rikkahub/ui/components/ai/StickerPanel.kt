package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Sticker
import me.rerere.rikkahub.data.ai.StickerClassifier
import me.rerere.rikkahub.data.db.dao.StickerDAO
import me.rerere.rikkahub.data.db.entity.StickerEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.utils.STICKER_OWNER_AI
import me.rerere.rikkahub.utils.STICKER_OWNER_USER
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import androidx.core.net.toUri

private fun ownerLabel(owner: String) = if (owner == STICKER_OWNER_USER) "我的" else "AI 的"

/**
 * 表情包面板：两套独立的库（我的 / AI 的），点一下就发出去。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPanel(
    onSend: (StickerEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stickerDao: StickerDAO = koinInject()
    val filesManager: FilesManager = koinInject()
    val stickerClassifier: StickerClassifier = koinInject()
    val scope = rememberCoroutineScope()

    var owner by remember { mutableStateOf(STICKER_OWNER_USER) }
    val allStickers by stickerDao.listAll().collectAsState(initial = emptyList())
    val stickers = remember(allStickers, owner) {
        allStickers.filter { it.owner == owner }
    }

    var menuFor by remember { mutableStateOf<StickerEntity?>(null) }
    var renaming by remember { mutableStateOf<StickerEntity?>(null) }

    val pickStickers = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val targetOwner = owner
            scope.launch {
                filesManager.createChatFilesByContents(uris).forEach { uri ->
                    stickerDao.upsert(
                        StickerEntity(
                            id = Uuid.random().toString(),
                            uri = uri.toString(),
                            owner = targetOwner,
                            name = "",
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
                // 新导入的让 AI 悄悄看一遍图，写好分类和描述，之后挑图不瞎猜
                stickerClassifier.classifyPending()
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OwnerTab(owner = owner, label = "我的", target = STICKER_OWNER_USER) { owner = it }
                OwnerTab(owner = owner, label = "AI 的", target = STICKER_OWNER_AI) { owner = it }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { pickStickers.launch("image/*") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = "添加表情包")
                }
            }

            if (stickers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Sticker,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = "${ownerLabel(owner)}还没有表情包",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = stickers, key = { it.id }) { sticker ->
                        Box {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .combinedClickable(
                                        onClick = {
                                            menuFor = null
                                            onSend(sticker)
                                        },
                                        onLongClick = { menuFor = sticker },
                                    ),
                            ) {
                                AsyncImage(
                                    model = sticker.uri,
                                    contentDescription = sticker.name.ifEmpty { "表情包" },
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor?.id == sticker.id,
                                onDismissRequest = { menuFor = null },
                                modifier = Modifier.widthIn(min = 180.dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("发送") },
                                    onClick = {
                                        menuFor = null
                                        onSend(sticker)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("存到「${ownerLabel(if (owner == STICKER_OWNER_USER) STICKER_OWNER_AI else STICKER_OWNER_USER)}」") },
                                    onClick = {
                                        val targetOwner =
                                            if (owner == STICKER_OWNER_USER) STICKER_OWNER_AI else STICKER_OWNER_USER
                                        val source = sticker
                                        menuFor = null
                                        scope.launch {
                                            if (stickerDao.getByOwner(targetOwner).none { it.uri == source.uri }) {
                                                stickerDao.upsert(
                                                    StickerEntity(
                                                        id = Uuid.random().toString(),
                                                        uri = source.uri,
                                                        owner = targetOwner,
                                                        name = source.name,
                                                        createdAt = System.currentTimeMillis(),
                                                    )
                                                )
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = {
                                        renaming = sticker
                                        menuFor = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        val source = sticker
                                        menuFor = null
                                        scope.launch {
                                            // 另一个库可能共用同一个文件，只有没人用了才能真正删掉
                                            val usedByOthers = stickerDao.countByUri(source.uri)
                                            stickerDao.delete(source.id)
                                            if (usedByOthers <= 1) {
                                                filesManager.deleteChatFiles(listOf(source.uri.toUri()))
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { sticker ->
        RenameStickerDialog(
            initialName = sticker.name,
            onConfirm = { newName ->
                renaming = null
                scope.launch {
                    stickerDao.upsert(sticker.copy(name = newName))
                }
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun OwnerTab(
    owner: String,
    label: String,
    target: String,
    onClick: (String) -> Unit,
) {
    val selected = owner == target
    Surface(
        onClick = { onClick(target) },
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = HugeIcons.Sticker,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun RenameStickerDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
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
                Text(text = "表情包备注名", style = MaterialTheme.typography.titleMedium)
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("方便以后认出这张图") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(name.trim()) }) { Text("确定") }
                }
            }
        }
    }
}
