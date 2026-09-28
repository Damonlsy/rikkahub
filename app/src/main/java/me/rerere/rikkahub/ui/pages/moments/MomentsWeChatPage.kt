package me.rerere.rikkahub.ui.pages.moments

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.MomentCommentEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 微信版朋友圈：封面背景图 + 头像昵称 + 时间线（可滑动），用户自己发的帖子显示「我」。 */
@Composable
fun MomentsWeChatPage(
    vm: MomentVM = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val settings = LocalSettings.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val cover by vm.cover.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    var showComposer by remember { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val local = filesManager.createChatFilesByContents(listOf(it))
            local.firstOrNull()?.let { u -> vm.setCover(u.toString()) }
        }
    }

    if (showComposer) {
        MomentComposer(
            onBack = { showComposer = false },
            onPost = { content, images ->
                vm.addMoment(content, images, MOMENT_AUTHOR_USER)
                showComposer = false
            },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                WeChatCoverHeader(
                    settings = settings,
                    cover = cover,
                    onClickCover = { coverPicker.launch("image/*") },
                )
            }
            items(state.items, key = { it.moment.id }) { item ->
                WeChatMomentCard(item = item, vm = vm)
            }
            item { Spacer(Modifier.height(48.dp)) }
        }

        // 悬浮操作栏（叠在封面上）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = "返回", tint = Color.White)
            }
            IconButton(onClick = { showComposer = true }) {
                Icon(Lucide.Camera, contentDescription = "发动态", tint = Color.White)
            }
        }
    }
}

@Composable
private fun WeChatCoverHeader(
    settings: Settings,
    cover: String?,
    onClickCover: () -> Unit,
) {
    val nickname = settings.displaySetting.userNickname.ifBlank { "我" }
    val avatarSize = 56.dp
    val coverHeight = 320.dp
    // 头像 2/3 在封面内、1/3 露在封面下方
    val overlap = avatarSize / 3

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(coverHeight + overlap)
            .clickable(onClick = onClickCover),
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = "封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .clipToBounds(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF4A4A5A), Color(0xFF1C1C24)),
                        ),
                    ),
            )
        }
        // 右下角：昵称 + 头像（头像压在封面底边上）
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nickname,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            WeChatAvatar(author = MOMENT_AUTHOR_USER, size = avatarSize)
        }
    }
}

@Composable
private fun WeChatMomentCard(
    item: MomentItem,
    vm: MomentVM,
) {
    val settings = LocalSettings.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showComment by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<MomentCommentEntity?>(null) }
    var actionComment by remember { mutableStateOf<MomentCommentEntity?>(null) }
    var showDelete by remember { mutableStateOf(false) }

    val images = vm.imagesOf(item.moment)
    val myLike = item.likes.any { it.author == MOMENT_AUTHOR_USER }
    val myFavorite = item.favorites.any { it.author == MOMENT_AUTHOR_USER }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // 头像在左
        WeChatAvatar(item.moment.author, 40.dp)
        Spacer(Modifier.width(10.dp))
        // 昵称、内容、图片、时间、点赞评论全部排在头像右侧
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = weChatAuthorName(settings, item.moment.author),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            if (item.moment.content.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.moment.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                WeChatImages(images)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relativeTimeWeChat(item.moment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Text(
                            text = "···",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (myLike) "取消赞" else "赞") },
                            onClick = { vm.toggleLike(item.moment.id, MOMENT_AUTHOR_USER); showMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("评论") },
                            onClick = { replyTarget = null; showComment = true; showMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(if (myFavorite) "取消收藏" else "收藏") },
                            onClick = { vm.toggleFavorite(item.moment.id, MOMENT_AUTHOR_USER); showMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = { showDelete = true; showMenu = false },
                        )
                    }
                }
            }

            // 点赞 + 评论：同一个色块，中间一条很浅的分割线
            if (item.likes.isNotEmpty() || item.comments.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (item.likes.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Lucide.Heart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = item.likes.joinToString("，") { weChatAuthorName(settings, it.author) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (item.likes.isNotEmpty() && item.comments.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .height(0.6.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
                        )
                    }
                    item.comments.forEach { c ->
                        Text(
                            text = weChatCommentText(settings, c),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (c.author == MOMENT_AUTHOR_USER) {
                                        actionComment = c
                                    } else {
                                        replyTarget = c
                                        showComment = true
                                    }
                                }
                                .padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
    )

    if (showComment) {
        WeChatCommentDialog(
            settings = settings,
            replyTarget = replyTarget,
            onDismiss = { showComment = false },
            onSubmit = { content ->
                vm.addComment(item.moment.id, content, MOMENT_AUTHOR_USER, replyTarget?.author)
                showComment = false
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条动态？") },
            confirmButton = {
                TextButton(onClick = { vm.deleteMoment(item.moment.id); showDelete = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }

    actionComment?.let { c ->
        AlertDialog(
            onDismissRequest = { actionComment = null },
            title = { Text(c.content) },
            confirmButton = {
                TextButton(onClick = {
                    context.writeClipboardText(c.content)
                    actionComment = null
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.deleteComment(c.id)
                    actionComment = null
                }) { Text("删除") }
            },
        )
    }
}

@Composable
private fun WeChatImages(images: List<String>) {
    val shape = RoundedCornerShape(4.dp)
    if (images.size == 1) {
        AsyncImage(
            model = images[0],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.66f)
                .aspectRatio(1f)
                .clip(shape),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        images.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { img ->
                    AsyncImage(
                        model = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(shape),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WeChatAvatar(author: String, size: Dp) {
    val settings = LocalSettings.current
    val (name, avatar) = if (author == MOMENT_AUTHOR_AI) {
        val a = settings.getCurrentAssistant()
        a.name to a.avatar
    } else {
        settings.displaySetting.userNickname to settings.displaySetting.userAvatar
    }
    // 微信朋友圈头像：圆角方形（圆角约为头像尺寸的 15%）
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.15f))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        when (avatar) {
            is Avatar.Image -> AsyncImage(
                model = avatar.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            is Avatar.Emoji -> Text(
                text = avatar.content,
                style = MaterialTheme.typography.titleMedium,
            )

            is Avatar.Dummy -> Text(
                text = name.trim().take(1).ifBlank { "?" }.uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun WeChatCommentDialog(
    settings: Settings,
    replyTarget: MomentCommentEntity?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (replyTarget != null) "回复 ${weChatAuthorName(settings, replyTarget.author)}" else "评论")
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("写点什么…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("发送") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 微信版：用户自己发的一律显示「我」，AI 显示助手名。 */
private fun weChatAuthorName(settings: Settings, author: String): String =
    if (author == MOMENT_AUTHOR_AI) {
        settings.getCurrentAssistant().name.ifBlank { "AI" }
    } else {
        "我"
    }

private fun weChatCommentText(settings: Settings, c: MomentCommentEntity): String {
    val name = weChatAuthorName(settings, c.author)
    return if (c.replyTo != null) {
        "$name 回复 ${weChatAuthorName(settings, c.replyTo)}: ${c.content}"
    } else {
        "$name: ${c.content}"
    }
}

private fun relativeTimeWeChat(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        else -> {
            val h = min / 60
            if (h < 24) "$h 小时前"
            else {
                val d = h / 24
                if (d < 7) "$d 天前"
                else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
            }
        }
    }
}

@Composable
internal fun MomentComposer(
    onBack: () -> Unit,
    onPost: (String, List<String>) -> Unit,
) {
    val filesManager: FilesManager = koinInject()
    var content by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        val local = filesManager.createChatFilesByContents(uris.take(9 - images.size))
        images = (images + local.map { it.toString() }).take(9)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, contentDescription = "返回") }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onPost(content, images) },
                enabled = content.isNotBlank() || images.isNotEmpty(),
            ) { Text("发表") }
        }
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            placeholder = { Text("这一刻的想法…") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (images.size < 9) pickerLauncher.launch("image/*")
                    },
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Lucide.Camera,
                        contentDescription = "添加图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${images.size}/9",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (images.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEachIndexed { index, img ->
                    Box {
                        AsyncImage(
                            model = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                                .clickable { images = images.filterIndexed { i, _ -> i != index } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("×", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
