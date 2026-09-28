package me.rerere.rikkahub.ui.pages.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import dev.chrisbanes.haze.glass.material3.Material3
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GraduationCap
import me.rerere.hugeicons.stroke.School
import me.rerere.rikkahub.data.repository.DeckSummary
import me.rerere.rikkahub.data.repository.StudyDecks
import me.rerere.rikkahub.ui.components.ScaleButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import org.koin.androidx.compose.koinViewModel

private val StudyCardShape = RoundedCornerShape(24.dp)

@Composable
private fun Modifier.glassCard(
    hazeState: HazeState,
    containerColor: Color = Color.White,
    opacity: Float = 0.14f,
): Modifier = this
    .hazeGlass(
        input = HazeInput.Sources(hazeState),
        style = GlassStyle.Material3(
            containerColor = containerColor.copy(alpha = opacity),
            tint = containerColor.copy(alpha = 0.72f * opacity),
        ) {
            optics(
                GlassDefaults.optics.copy(
                    blurRadius = OpticalSizeValue.Fixed(16.dp),
                    depth = OpticalSizeValue.Fixed(0.5f),
                )
            )
            shape(StudyCardShape)
        },
    )
    .border(1.dp, Color.White.copy(alpha = 0.16f), StudyCardShape)

@Composable
fun StudyPage(
    vm: StudyVM = koinViewModel(),
    onBack: () -> Unit = {},
    onOpenDeck: (String) -> Unit = {},
) {
    val settings = LocalSettings.current
    val hazeState = rememberHazeState()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        AssistantBackground(setting = settings, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("学习模式") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Lucide.ArrowLeft, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TodayStatsCard(hazeState, decks)
                    decks.forEach { deck ->
                        DeckCard(hazeState, deck, onOpenDeck)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TodayStatsCard(
    hazeState: HazeState,
    decks: List<DeckSummary>,
) {
    val todayNew = decks.sumOf { it.todayNew }
    val todayReviewed = decks.sumOf { it.todayReviewed }
    val todayMinutes = decks.sumOf { it.todayStudyMs } / 60_000

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(hazeState)
            .padding(20.dp),
    ) {
        Text(
            text = "今日学习",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem("新学", "$todayNew 词")
            StatItem("复习", "$todayReviewed 词")
            StatItem("时长", "$todayMinutes 分钟")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun DeckCard(
    hazeState: HazeState,
    deck: DeckSummary,
    onOpenDeck: (String) -> Unit,
) {
    val isHighSchool = deck.deck == StudyDecks.HIGH_SCHOOL
    val progress = if (deck.total > 0) deck.learned.toFloat() / deck.total else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(hazeState)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isHighSchool) HugeIcons.GraduationCap else HugeIcons.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = StudyDecks.displayName(deck.deck),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "已学 ${deck.learned}/${deck.total} · 掌握 ${deck.mastered}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (deck.due > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "待复习 ${deck.due}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "今日 +${deck.todayNew} · 复习 ${deck.todayReviewed} · ${deck.todayStudyMs / 60_000} 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            ScaleButton(
                onClick = { onOpenDeck(deck.deck) },
                pressedScale = 0.94f,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "开始学习",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
