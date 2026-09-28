package me.rerere.rikkahub.ui.pages.diary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.DiaryEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import java.time.LocalDate
import java.time.YearMonth
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val DiaryCardShape = RoundedCornerShape(24.dp)

@Composable
fun DiaryPage(
    vm: DiaryVM = koinViewModel(),
    onBack: () -> Unit = {},
    onOpenDiary: (String) -> Unit = {},
) {
    val settings = LocalSettings.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val aiWorking by vm.aiWorking.collectAsStateWithLifecycle()
    val diaryBackground by vm.diaryBackground.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    var showEditor by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { local -> vm.setDiaryBackground(local.toString()) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (diaryBackground != null) {
            AsyncImage(
                model = diaryBackground,
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
                    title = { Text("日记本") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Lucide.ArrowLeft, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCalendar = true }) {
                            Icon(Lucide.Calendar, contentDescription = "日历")
                        }
                        IconButton(onClick = { showBackgroundDialog = true }) {
                            Icon(HugeIcons.Image02, contentDescription = "日记本背景")
                        }
                        if (aiWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(end = 4.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        IconButton(
                            onClick = { vm.aiWriteDiary() },
                            enabled = !aiWorking,
                        ) {
                            Icon(Lucide.Sparkles, contentDescription = "让 AI 写一篇")
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showEditor = true },
                ) {
                    Icon(Lucide.NotebookPen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("写日记")
                }
            },
        ) { padding ->
            when {
                state.loading -> DiarySkeletonGrid(contentPadding = padding)
                state.diaries.isEmpty() -> DiaryEmptyHint(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
                else -> {
                    val visibleDiaries = if (selectedDate == null) {
                        state.diaries
                    } else {
                        state.diaries.filter { it.date == selectedDate.toString() }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        if (selectedDate != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "只看 ${selectedDate}",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { selectedDate = null }) {
                                    Text("显示全部")
                                }
                            }
                        }
                        if (visibleDiaries.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "这一天还没有日记",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(visibleDiaries, key = { it.id }) { diary ->
                                    DiaryCard(
                                        diary = diary,
                                        onClick = { onOpenDiary(diary.id) },
                                        onDelete = { vm.deleteDiary(diary.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            title = { Text("日记本背景") },
            text = { Text("给日记本单独设置一张背景图。每篇日记的底色会自动从背景图里取一个和谐颜色。") },
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
                    if (diaryBackground != null) {
                        TextButton(
                            onClick = {
                                showBackgroundDialog = false
                                vm.setDiaryBackground(null)
                            },
                        ) {
                            Text("移除背景")
                        }
                    }
                    TextButton(onClick = { showBackgroundDialog = false }) {
                        Text("取消")
                    }
                }
            },
        )
    }

    if (showCalendar) {
        DiaryCalendarDialog(
            diaries = state.diaries,
            selectedDate = selectedDate,
            onSelect = { date ->
                selectedDate = date
                showCalendar = false
            },
            onDismiss = { showCalendar = false },
        )
    }

    if (showEditor) {
        DiaryEditorDialog(
            onDismiss = { showEditor = false },
            onSave = { title, content ->
                vm.addDiary(title, content, DIARY_AUTHOR_USER)
                showEditor = false
            },
        )
    }
}

@Composable
private fun DiaryCard(
    diary: DiaryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        label = "diaryCardPress",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(DiaryCardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = DiaryCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 156.dp)
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = diary.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = diary.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DiaryAuthorRow(diary.author)
                Text(
                    text = diary.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 显示某条内容的作者头像和名字，跟随系统设置（用户头像/昵称、助手头像/名字）。 */
@Composable
fun DiaryAuthorRow(author: String, modifier: Modifier = Modifier) {
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    if (author == DIARY_AUTHOR_AI) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                modifier = Modifier.size(20.dp),
                loading = false,
            )
            Text(
                text = assistant.name.ifBlank { "AI" },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    } else {
        val nickname = settings.displaySetting.userNickname
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = nickname.ifBlank { "我" },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            UIAvatar(
                name = nickname,
                value = settings.displaySetting.userAvatar,
                modifier = Modifier.size(20.dp),
                loading = false,
            )
        }
    }
}

@Composable
private fun DiarySkeletonGrid(contentPadding: PaddingValues) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .clip(DiaryCardShape)
                    .background(base.copy(alpha = 0.12f))
                    .shimmer(
                        isLoading = true,
                        shimmerColor = base.copy(alpha = 0.32f),
                        backgroundColor = base.copy(alpha = 0.10f),
                    ),
            )
        }
    }
}

@Composable
private fun DiaryEmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Lucide.NotebookPen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "还没有日记，点右下角写一篇吧",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiaryEditorDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写日记") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, content) },
                enabled = content.isNotBlank(),
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
private fun DiaryCalendarDialog(
    diaries: List<DiaryEntity>,
    selectedDate: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val entryDates = remember(diaries) {
        diaries.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()
    }
    val today = LocalDate.now()
    val firstDay = month.atDay(1)
    val leading = firstDay.dayOfWeek.value - 1 // 周一为 0
    val daysInMonth = month.lengthOfMonth()
    val rows = (leading + daysInMonth + 6) / 7

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text(
                    text = "${month.year} 年 ${month.monthValue} 月",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
            }
        },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                        Text(
                            text = w,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - leading + 1
                            if (day in 1..daysInMonth) {
                                val date = month.atDay(day)
                                val hasEntry = date in entryDates
                                val isSelected = date == selectedDate
                                val isToday = date == today
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isSelected -> MaterialTheme.colorScheme.primary
                                                isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .clickable { onSelect(date) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = day.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        if (hasEntry) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) {
                                                            MaterialTheme.colorScheme.onPrimary
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        }
                                                    ),
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(null) }) { Text("显示全部") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
