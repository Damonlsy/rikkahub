package me.rerere.rikkahub.ui.pages.diary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DiaryDetailPage(
    diaryId: String,
    vm: DiaryVM = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val settings = LocalSettings.current
    val diary by remember(diaryId) { vm.diaryFlow(diaryId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val comments by remember(diaryId) { vm.comments(diaryId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val aiWorking by vm.aiWorking.collectAsStateWithLifecycle()
    val bookBackground by vm.diaryBackground.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    var input by remember { mutableStateOf("") }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<DiaryCommentEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<DiaryCommentEntity?>(null) }

    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { local -> vm.setEntryBackground(diaryId, local.toString()) }
        }
    }

    val entryBackground = diary?.background ?: bookBackground

    Box(modifier = Modifier.fillMaxSize()) {
        if (entryBackground != null) {
            AsyncImage(
                model = entryBackground,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AssistantBackground(
                setting = settings,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("日记详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Lucide.ArrowLeft, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBackgroundDialog = true }) {
                            Icon(HugeIcons.Image02, contentDescription = "本篇背景")
                        }
                        val current = diary
                        if (current != null) {
                            IconButton(
                                onClick = {
                                    vm.deleteDiary(current.id)
                                    onBack()
                                },
                            ) {
                                Icon(Lucide.Trash2, contentDescription = "删除日记")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Surface(color = Color.Transparent) {
                    Column(modifier = Modifier.imePadding().navigationBarsPadding()) {
                        replyTarget?.let { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "回复 ${authorLabel(target.author)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { replyTarget = null },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Lucide.X,
                                        contentDescription = "取消回复",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = {
                                    Text(if (replyTarget != null) "回复…" else "写评论…")
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(20.dp),
                                maxLines = 4,
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val text = input
                                    input = ""
                                    diary?.let {
                                        vm.addComment(it.id, text, DIARY_AUTHOR_USER, replyTarget?.id)
                                    }
                                    replyTarget = null
                                },
                                enabled = input.isNotBlank(),
                            ) {
                                Icon(Lucide.Send, contentDescription = "发送评论")
                            }
                        }
                    }
                }
            },
        ) { padding ->
            val current = diary
            if (current == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DiaryAuthorRow(current.author)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = current.date,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = current.content,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "评论（${comments.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = { vm.aiComment(current.id, current.title, current.content) },
                        enabled = !aiWorking,
                    ) {
                        if (aiWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Lucide.Sparkles,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("让 AI 评论")
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (comments.isEmpty()) {
                    Text(
                        text = "还没有评论，说点什么吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val topLevel = comments.filter { it.parentId == null }
                    val repliesByParent = comments
                        .filter { it.parentId != null }
                        .groupBy { it.parentId }
                    topLevel.forEach { comment ->
                        DiaryCommentItem(
                            comment = comment,
                            onReply = { replyTarget = comment },
                            onLike = { vm.toggleLike(comment) },
                            onDelete = { deleteTarget = comment },
                        )
                        repliesByParent[comment.id]?.forEach { reply ->
                            DiaryCommentItem(
                                comment = reply,
                                indent = true,
                                replyToAuthor = comments.firstOrNull { it.id == reply.parentId }?.author,
                                onReply = { replyTarget = comment },
                                onLike = { vm.toggleLike(reply) },
                                onDelete = { deleteTarget = reply },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除评论") },
            text = { Text("确定要删除这条评论吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteComment(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            title = { Text("本篇日记背景") },
            text = { Text("给这一篇单独设置背景图。不设置就跟随日记本背景。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundDialog = false
                        bgPickerLauncher.launch("image/*")
                    },
                ) {
                    Text("从相册选择")
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (diary?.background != null) {
                        TextButton(
                            onClick = {
                                showBackgroundDialog = false
                                vm.setEntryBackground(diaryId, null)
                            },
                        ) {
                            Text("跟随日记本")
                        }
                    }
                    TextButton(onClick = { showBackgroundDialog = false }) {
                        Text("取消")
                    }
                }
            },
        )
    }
}

private fun authorLabel(author: String): String =
    if (author == DIARY_AUTHOR_AI) "AI" else "我"

/** 评论不带气泡框：纯文字 + 作者头像/名字 + 回复/点赞/删除。 */
@Composable
private fun DiaryCommentItem(
    comment: DiaryCommentEntity,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onDelete: () -> Unit,
    indent: Boolean = false,
    replyToAuthor: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 28.dp else 0.dp)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiaryAuthorRow(comment.author)
            if (replyToAuthor != null) {
                Text(
                    text = " 回复 ${authorLabel(replyToAuthor)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = comment.content,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReply) {
                Text("回复", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onLike) {
                Text(
                    text = if (comment.likes > 0) "♥ ${comment.likes}" else "♥ 赞",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (comment.liked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = "删除评论",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}
