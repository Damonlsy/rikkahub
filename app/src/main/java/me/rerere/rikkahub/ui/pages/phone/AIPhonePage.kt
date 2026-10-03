package me.rerere.rikkahub.ui.pages.phone

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.UserRound
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.PhoneMemo
import me.rerere.rikkahub.data.repository.AIPhoneRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import org.koin.compose.koinInject

private enum class PhoneApp { HOME, ALBUM, MEMOS, CHAT, CONTACT }

@Composable
fun AIPhonePage(assistantId: String, onBack: () -> Unit) {
    val id = remember(assistantId) { Uuid.parse(assistantId) }
    val repository: AIPhoneRepository = koinInject()
    val conversations: ConversationRepository = koinInject()
    val assistant by repository.assistantFlow(id).collectAsStateWithLifecycle(initialValue = null)
    var app by remember { mutableStateOf(PhoneApp.HOME) }
    var unlocked by remember(id) { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var wrongCode by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var chats by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val lockWallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { scope.launch { repository.setWallpaper(id, lockScreen = true, source = it) } }
    }
    val homeWallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { scope.launch { repository.setWallpaper(id, lockScreen = false, source = it) } }
    }
    LaunchedEffect(id) { chats = conversations.getRecentConversations(id, 20) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${assistant?.name.orEmpty().ifBlank { "AI" }} 的小手机") },
                navigationIcon = {
                    IconButton(onClick = if (app == PhoneApp.HOME) onBack else ({ app = PhoneApp.HOME })) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showWallpaperDialog = true }) {
                        Icon(Lucide.Images, contentDescription = "更换壁纸")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFE8EDF5)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxSize(0.92f),
                shape = RoundedCornerShape(42.dp),
                color = Color(0xFFF7F8FC),
                shadowElevation = 12.dp,
            ) {
                if (!unlocked) {
                    PhoneLockScreen(
                        assistantName = assistant?.name.orEmpty().ifBlank { "AI" },
                        passcode = passcode,
                        wrongCode = wrongCode,
                        wallpaper = assistant?.phoneLockWallpaper,
                        onPasscodeChange = {
                            passcode = it.filter(Char::isDigit).take(4)
                            wrongCode = false
                        },
                        onUnlock = {
                            if (passcode == assistant?.phonePasscode.orEmpty().ifBlank { "0000" }) unlocked = true
                            else wrongCode = true
                        },
                    )
                } else when (app) {
                    PhoneApp.HOME -> PhoneHome(
                        assistantName = assistant?.name.orEmpty().ifBlank { "AI" },
                        wallpaper = assistant?.phoneHomeWallpaper,
                        onOpen = { app = it },
                    )
                    PhoneApp.ALBUM -> AlbumApp(assistant?.phoneAlbum.orEmpty())
                    PhoneApp.MEMOS -> MemoApp(assistant?.phoneMemos.orEmpty())
                    PhoneApp.CHAT -> ChatApp(chats)
                    PhoneApp.CONTACT -> ContactApp(
                        assistantName = assistant?.name.orEmpty().ifBlank { "AI" },
                        avatar = assistant?.avatar,
                        remark = assistant?.phoneUserRemark.orEmpty(),
                    )
                }
            }
        }
    }

    if (showWallpaperDialog) {
        AlertDialog(
            onDismissRequest = { showWallpaperDialog = false },
            title = { Text("手机壁纸") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("锁屏屏保", fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = {
                            showWallpaperDialog = false
                            lockWallpaperPicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (assistant?.phoneLockWallpaper == null) "选择图片" else "更换图片") }
                    if (assistant?.phoneLockWallpaper != null) {
                        TextButton(
                            onClick = { scope.launch { repository.setWallpaper(id, lockScreen = true, source = null) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("移除锁屏屏保") }
                    }
                    Text("桌面壁纸", fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = {
                            showWallpaperDialog = false
                            homeWallpaperPicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (assistant?.phoneHomeWallpaper == null) "选择图片" else "更换图片") }
                    if (assistant?.phoneHomeWallpaper != null) {
                        TextButton(
                            onClick = { scope.launch { repository.setWallpaper(id, lockScreen = false, source = null) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("移除桌面壁纸") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWallpaperDialog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun PhoneLockScreen(
    assistantName: String,
    passcode: String,
    wrongCode: Boolean,
    wallpaper: String?,
    onPasscodeChange: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF182238))) {
        wallpaper?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (wallpaper == null) 0f else 0.28f)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Text(
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            color = Color.White,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
        )
        Text("$assistantName 的手机", color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.height(42.dp))
        OutlinedTextField(
            value = passcode,
            onValueChange = onPasscodeChange,
            label = { Text("四位密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        if (wrongCode) Text("密码不对", color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.White,
            modifier = Modifier.clickable(enabled = passcode.length == 4, onClick = onUnlock),
        ) {
            Text(
                "解锁",
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 11.dp),
                color = Color(0xFF182238),
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("初始密码 0000，AI 可以修改", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhoneHome(assistantName: String, wallpaper: String?, onOpen: (PhoneApp) -> Unit) {
    val pager = rememberPagerState(pageCount = { 2 })
    Box(
        modifier = Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(Color(0xFFBCD9FF), Color(0xFFE9D4FF), Color(0xFFFFDFD0)),
                )
            ),
    ) {
        wallpaper?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(50), color = Color(0xFF151923), modifier = Modifier.size(86.dp, 27.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("AI", color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
            Text("● )))", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(26.dp))
        Text(assistantName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF172033))
        Text("私人空间", color = Color(0xFF4C5870), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().height(132.dp),
        ) { page ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (page == 0) {
                    PhoneAppIcon("相册", Color(0xFFFFE4E7), Lucide.Images) { onOpen(PhoneApp.ALBUM) }
                    PhoneAppIcon("备忘录", Color(0xFFFFF0B8), Lucide.NotebookPen) { onOpen(PhoneApp.MEMOS) }
                } else {
                    PhoneAppIcon("聊天", Color(0xFFCFF3DB), Lucide.MessageCircle) { onOpen(PhoneApp.CHAT) }
                    PhoneAppIcon("联系人", Color(0xFFD9E5FF), Lucide.UserRound) { onOpen(PhoneApp.CONTACT) }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(2) { index ->
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (pager.currentPage == index) Color(0xFF34495E) else Color.White.copy(alpha = 0.7f))
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.74f),
            shadowElevation = 5.dp,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PhoneAppIcon("相册", Color(0xFFFFE4E7), Lucide.Images) { onOpen(PhoneApp.ALBUM) }
                PhoneAppIcon("聊天", Color(0xFFCFF3DB), Lucide.MessageCircle) { onOpen(PhoneApp.CHAT) }
                PhoneAppIcon("联系人", Color(0xFFD9E5FF), Lucide.UserRound) { onOpen(PhoneApp.CONTACT) }
            }
        }
        }
    }
}

@Composable
private fun PhoneAppIcon(label: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = color, modifier = Modifier.size(60.dp), shadowElevation = 5.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(29.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AlbumApp(album: List<me.rerere.rikkahub.data.model.PhoneAlbumItem>) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        AppTitle("相册", "AI 从聊天图片中自己收藏")
        if (album.isEmpty()) EmptyApp("相册还是空的") else LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(album, key = { it.id }) { photo ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = photo.caption,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                    )
                    if (photo.caption.isNotBlank()) Text(photo.caption, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MemoApp(memos: List<PhoneMemo>) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        AppTitle("备忘录", "AI 写给自己的内容")
        if (memos.isEmpty()) EmptyApp("还没有备忘录") else LazyColumn(
            contentPadding = PaddingValues(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(memos.sortedByDescending { it.updatedAt }, key = { it.id }) { memo ->
                val color = remember(memo.background) { runCatching { Color(android.graphics.Color.parseColor(memo.background)) }.getOrDefault(Color(0xFFFFF4C2)) }
                Surface(shape = RoundedCornerShape(18.dp), color = color, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(memo.title.ifBlank { "无标题" }, fontWeight = FontWeight.Bold)
                        Text(memo.content, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatApp(conversations: List<Conversation>) {
    var selected by remember { mutableStateOf<Conversation?>(null) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) { Icon(Lucide.ArrowLeft, contentDescription = "返回会话列表") }
                Text(selected?.title.orEmpty().ifBlank { "未命名对话" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        } else {
            AppTitle("聊天", "AI 可以回看与你的最近对话")
        }
        selected?.let { conversation ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(conversation.currentMessages, key = { it.id }) { message ->
                    val isUser = message.role == MessageRole.USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isUser) Color(0xFFD7E8FF) else Color.White,
                            modifier = Modifier.fillMaxWidth(0.84f),
                        ) {
                            Text(
                                message.toText().ifBlank { "图片或附件" },
                                modifier = Modifier.padding(12.dp),
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            return@Column
        }
        if (conversations.isEmpty()) EmptyApp("还没有聊天") else LazyColumn(
            contentPadding = PaddingValues(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                val latest = conversation.currentMessages.lastOrNull()
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().clickable { selected = conversation },
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(conversation.title.ifBlank { "未命名对话" }, fontWeight = FontWeight.SemiBold)
                        Text(
                            latest?.toText().orEmpty().ifBlank { if (latest?.role == MessageRole.USER) "图片或附件" else "暂无内容" },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactApp(assistantName: String, avatar: me.rerere.rikkahub.data.model.Avatar?, remark: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UIAvatar(name = assistantName, value = avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy, modifier = Modifier.size(82.dp), loading = false)
        Text("用户备注", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Text(
                remark.ifBlank { "AI 还没有写备注" },
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AppTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyApp(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
