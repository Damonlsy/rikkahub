package me.rerere.rikkahub.ui.pages.diary

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.data.db.entity.AnniversaryEntity
import me.rerere.rikkahub.data.db.entity.PeriodRecordEntity
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val AnniversaryCardShape = RoundedCornerShape(20.dp)
private val PeriodCardShape = RoundedCornerShape(16.dp)

private enum class DateTarget { START, END }

/**
 * 日记本里的独立「纪念日与经期」页。
 *
 * 两个板块上下分割（粗版）：上面偏浪漫的纪念日（倒数徽章），下面是经期（预测 + 任意日期补记 + 记录列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryMomentsPage(
    vm: DiaryVM = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val periods by vm.periods.collectAsStateWithLifecycle(initialValue = emptyList())
    val anniversaries by vm.anniversaries.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now()

    var showAnnivEditor by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var pickedDate by remember { mutableStateOf(today) }
    var pickedEnd by remember { mutableStateOf(today.plusDays(4)) }

    val episodes = remember(periods) {
        buildEpisodes(periods.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() })
    }
    val nextPeriodStart = episodes.lastOrNull()?.let { anchor ->
        anchor.start.plusDays(
            PERIOD_CYCLE_DAYS * ((ChronoUnit.DAYS.between(anchor.start, today) / PERIOD_CYCLE_DAYS) + 1)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("纪念日与经期") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ============ 板块一：纪念日 ============
            item(key = "annivHeader") {
                SectionHeader(
                    emoji = "💗",
                    title = "纪念日",
                    subtitle = "把值得记住的日子都放在这里",
                    action = {
                        TextButton(onClick = { showAnnivEditor = true }) { Text("＋ 加一个") }
                    },
                )
            }
            if (anniversaries.isEmpty()) {
                item(key = "annivEmpty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AnniversaryCardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = AnnivAmber.copy(alpha = 0.10f),
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "还没有纪念日 · 点右上角「＋ 加一个」",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                val sorted = anniversaries.sortedWith(
                    compareBy { a ->
                        val days = ChronoUnit.DAYS.between(today, a.nextOccurrence(today)).toInt()
                        if (days >= 0) days else 100_000 - days
                    },
                )
                items(sorted, key = { "anniv-${it.id}" }) { anniversary ->
                    AnniversaryRow(
                        anniversary = anniversary,
                        today = today,
                        onDelete = { vm.deleteAnniversary(anniversary.id) },
                    )
                }
            }

            item(key = "divider") {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ============ 板块二：经期 ============
            item(key = "periodHeader") {
                SectionHeader(
                    emoji = "🌸",
                    title = "经期",
                    subtitle = "忘记记也没关系，往下可以补记任意一天",
                    action = {},
                )
            }

            item(key = "periodPredict") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = PeriodCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = PeriodPink.copy(alpha = 0.10f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when {
                                nextPeriodStart == null -> "还没有记录，先在下面补记一下，之后就能自动预测"
                                else -> {
                                    val days = ChronoUnit.DAYS.between(today, nextPeriodStart)
                                    when {
                                        days == 0L -> "按周期推算，下次经期就是今天"
                                        else -> "下次经期约 ${nextPeriodStart.monthValue} 月 ${nextPeriodStart.dayOfMonth} 日（还有 $days 天）"
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = PeriodPink,
                        )
                        if (episodes.isNotEmpty()) {
                            val last = episodes.last()
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "上一段：${last.start.monthValue} 月 ${last.start.dayOfMonth} 日" +
                                    (if (last.end != last.start) " ~ ${last.end.monthValue} 月 ${last.end.dayOfMonth} 日" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "periodBackfill") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = PeriodCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "补记经期",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "开始：${pickedDate.year} 年 ${pickedDate.monthValue} 月 ${pickedDate.dayOfMonth} 日",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { dateTarget = DateTarget.START }) {
                                Text(if (pickedDate == today) "选日期" else "改日期")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val end = if (pickedEnd.isBefore(pickedDate)) pickedDate else pickedEnd
                            Text(
                                text = "结束：${end.year} 年 ${end.monthValue} 月 ${end.dayOfMonth} 日",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { dateTarget = DateTarget.END }) {
                                Text("改日期")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(1 to "轻", 2 to "中", 3 to "重").forEach { (flow, label) ->
                                FilledTonalButton(
                                    onClick = {
                                        val start = pickedDate
                                        val finish = if (pickedEnd.isBefore(start)) start else pickedEnd
                                        var day = start
                                        while (!day.isAfter(finish)) {
                                            vm.addPeriodRecord(day, flow)
                                            day = day.plusDays(1)
                                        }
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = PeriodPink.copy(alpha = 0.18f),
                                        contentColor = PeriodPink,
                                    ),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "会把开始到结束的每一天都记上",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (periods.isEmpty()) {
                item(key = "periodEmpty") {
                    Text(
                        text = "还没有经期记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                val episodes = buildEpisodes(
                    periods.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                )
                items(episodes, key = { "epi-${it.start}" }) { episode ->
                    val inRange = periods.filter { p ->
                        runCatching { LocalDate.parse(p.date) }.getOrNull()?.let {
                            !it.isBefore(episode.start) && !it.isAfter(episode.end)
                        } == true
                    }
                    val flow = inRange.minByOrNull { it.date }?.flow ?: 2
                    PeriodRow(
                        startDate = episode.start,
                        endDate = episode.end,
                        flow = flow,
                        today = today,
                        onDelete = { inRange.forEach { vm.deletePeriodRecord(it.id) } },
                    )
                }
            }
        }
    }

    if (showAnnivEditor) {
        AnniversaryEditorDialog(
            defaultDate = today,
            onDismiss = { showAnnivEditor = false },
            onSave = { title, date, yearly ->
                vm.addAnniversary(title, date, yearly, emoji = "")
                showAnnivEditor = false
            },
        )
    }

    dateTarget?.let { target ->
        val initial = if (target == DateTarget.START) pickedDate else pickedEnd
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val chosen = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            if (target == DateTarget.START) {
                                pickedDate = chosen
                                if (pickedEnd.isBefore(chosen)) pickedEnd = chosen.plusDays(4)
                            } else {
                                pickedEnd = chosen
                            }
                        }
                        dateTarget = null
                    },
                ) {
                    Text("就这天")
                }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun SectionHeader(
    emoji: String,
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji, fontSize = 22.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action()
    }
}

@Composable
private fun AnniversaryRow(
    anniversary: AnniversaryEntity,
    today: LocalDate,
    onDelete: () -> Unit,
) {
    val next = anniversary.nextOccurrence(today)
    val days = ChronoUnit.DAYS.between(today, next)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AnniversaryCardShape,
        colors = CardDefaults.cardColors(
            containerColor = AnnivAmber.copy(alpha = 0.12f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AnnivAmber.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = anniversary.emoji.ifBlank { "💗" },
                    fontSize = 20.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anniversary.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append(anniversary.date)
                        if (anniversary.repeatYearly) append(" · 每年")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when {
                    days == 0L -> "就是今天！"
                    days > 0 -> "还有 $days 天"
                    else -> "已过 ${abs(days)} 天"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (days == 0L) AnnivAmber else AnnivAmber.copy(alpha = 0.85f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeriodRow(
    startDate: LocalDate,
    endDate: LocalDate,
    flow: Int,
    today: LocalDate,
    onDelete: () -> Unit,
) {
    val days = ChronoUnit.DAYS.between(startDate, endDate) + 1
    val daysAgo = ChronoUnit.DAYS.between(endDate, today)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = PeriodCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (startDate == endDate) {
                        "${startDate.year} 年 ${startDate.monthValue} 月 ${startDate.dayOfMonth} 日"
                    } else {
                        "${startDate.year} 年 ${startDate.monthValue} 月 ${startDate.dayOfMonth} 日 ~ " +
                            "${endDate.monthValue} 月 ${endDate.dayOfMonth} 日（$days 天）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (daysAgo > 0) {
                    Text(
                        text = "${daysAgo} 天前结束",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = flowLabel(flow),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = PeriodPink,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
