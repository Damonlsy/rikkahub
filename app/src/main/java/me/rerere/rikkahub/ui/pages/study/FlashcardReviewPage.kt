package me.rerere.rikkahub.ui.pages.study

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import me.rerere.rikkahub.data.db.entity.StudyWordEntity
import me.rerere.rikkahub.data.repository.StudyDecks
import me.rerere.rikkahub.ui.components.ScaleButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import org.koin.androidx.compose.koinViewModel

private val FlashcardShape = RoundedCornerShape(28.dp)

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
                    blurRadius = OpticalSizeValue.Fixed(18.dp),
                    depth = OpticalSizeValue.Fixed(0.5f),
                )
            )
            shape(FlashcardShape)
        },
    )
    .border(1.dp, Color.White.copy(alpha = 0.18f), FlashcardShape)

@Composable
fun FlashcardReviewPage(
    deckId: String,
    onBack: () -> Unit = {},
    vm: StudyVM = koinViewModel(),
) {
    val settings = LocalSettings.current
    val hazeState = rememberHazeState()
    val queue by vm.queue.collectAsStateWithLifecycle()
    var index by remember { mutableIntStateOf(0) }
    val reviewedState = remember { mutableIntStateOf(0) }
    val learnedState = remember { mutableIntStateOf(0) }
    val startTime = remember { System.currentTimeMillis() }

    LaunchedEffect(deckId) {
        vm.openDeck(deckId)
    }
    LaunchedEffect(queue.size) {
        if (index >= queue.size) {
            index = (queue.size - 1).coerceAtLeast(0)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.saveSession(
                deckId = deckId,
                durationMs = System.currentTimeMillis() - startTime,
                reviewed = reviewedState.intValue,
                learned = learnedState.intValue,
            )
        }
    }

    val current = queue.getOrNull(index)
    val total = queue.size

    fun onGrade(known: Boolean) {
        val word = current ?: return
        if (word.learnedAt == 0L) learnedState.intValue++
        reviewedState.intValue++
        vm.grade(deckId, word.id, known)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AssistantBackground(setting = settings, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(StudyDecks.displayName(deckId)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Lucide.ArrowLeft, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (total > 0) {
                            Text(
                                text = "${index + 1}/$total",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (index + 1).toFloat() / total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (current != null) {
                    FlipCard(hazeState = hazeState, word = current)
                } else {
                    CompletionCard(hazeState = hazeState, onBack = onBack)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (current != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ScaleButton(
                            onClick = { onGrade(false) },
                            modifier = Modifier.weight(1f),
                            pressedScale = 0.94f,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                                        RoundedCornerShape(20.dp),
                                    )
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "不记得",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        ScaleButton(
                            onClick = { onGrade(true) },
                            modifier = Modifier.weight(1f),
                            pressedScale = 0.94f,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        RoundedCornerShape(20.dp),
                                    )
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "记得",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FlipCard(
    hazeState: HazeState,
    word: StudyWordEntity,
) {
    var flipped by remember(word.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "flip",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clickable { flipped = !flipped },
    ) {
        if (rotation <= 90f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .glassCard(hazeState),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = word.word,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = word.phonetic,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "点击卡片查看释义",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .glassCard(hazeState),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = word.meaning,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = word.example,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = word.exampleMeaning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionCard(
    hazeState: HazeState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(hazeState)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "本组卡片已学完",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "去喝杯水，休息一下吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(20.dp))
        ScaleButton(
            onClick = onBack,
            pressedScale = 0.94f,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "返回",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
