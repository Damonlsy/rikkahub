package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Phone
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.db.entity.PatActionEntity
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.STICKER_MARKER_PREFIX
import me.rerere.rikkahub.utils.isCallMessage
import me.rerere.rikkahub.utils.isPatMessage
import me.rerere.rikkahub.utils.isVoiceHiddenText
import me.rerere.rikkahub.utils.isStickerMessage
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    patActions: List<PatActionEntity> = emptyList(),
    onPat: ((patText: String) -> Unit)? = null,
    hazeState: HazeState? = null,
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // 拍一拍：一行居中的小字，没有头像、没有气泡
    if (message.isCallMessage()) {
        CallRecordRow(
            text = message.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text.orEmpty(),
        )
        return
    }

    if (message.isPatMessage()) {
        PatMessageRow(
            text = message.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text.orEmpty()
        )
        return
    }

    val isSticker = isStickerMessage(message.parts)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            val aiName = assistant?.name?.ifBlank { null } ?: "AI"
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f),
                    patActions = patActions,
                    aiName = aiName,
                    onPat = { patText -> onPat?.invoke(patText) },
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f),
                    patActions = patActions,
                    aiName = aiName,
                    onPat = { patText -> onPat?.invoke(patText) },
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                assistant = assistant,
                message = message,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                stickerMessage = isSticker,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onUserMessageClick = if (message.role == MessageRole.USER && !isSticker) onEdit else null,
                patActions = patActions,
                onPat = onPat,
                hazeState = hazeState,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    message: UIMessage,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    stickerMessage: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    patActions: List<PatActionEntity> = emptyList(),
    onPat: ((patText: String) -> Unit)? = null,
    hazeState: HazeState? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    // 每条消息显示头像和名字：气泡分隔开的每一段都单独带一行头像+名字
    val perBubbleAvatar = settings.displaySetting.perBubbleAvatarName && role == MessageRole.ASSISTANT
    val perBubbleAiName = assistant?.name?.ifBlank { null } ?: "AI"
    val perBubbleAvatarRow: @Composable () -> Unit = {
        ChatMessageAssistantAvatar(
            message = message,
            loading = loading,
            model = model,
            assistant = assistant,
            patActions = patActions,
            aiName = perBubbleAiName,
            onPat = { patText -> onPat?.invoke(patText) },
        )
    }

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    // 表情包消息里的 `#sticker:<id>` 只是给模型看的标记，UI 不显示
    val visibleParts = remember(parts, stickerMessage) {
        if (stickerMessage) {
            parts.filterNot { part ->
                part is UIMessagePart.Text && part.text.startsWith(STICKER_MARKER_PREFIX)
            }
        } else {
            parts
        }
    }
    val groupedParts = remember(visibleParts) { visibleParts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    // 工具/思维链等非消息气泡：卡片保持紧凑(2/3)，但字号单独放大
                    val baseDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = baseDensity.density * 2f / 3f,
                            fontScale = baseDensity.fontScale * 1.5f,
                        ),
                    ) {
                        ChainOfThought(
                            modifier = Modifier.animateContentSize(),
                            steps = block.steps,
                            collapsedAdaptiveWidth = isReasoningOnlyBlock,
                            cardColors = CardDefaults.cardColors(
                                // 使用了非系统原装气泡时，思考链气泡一律透明
                                containerColor = if (
                                    settings.displaySetting.assistantBubbleStyle != "default" ||
                                    settings.displaySetting.userBubbleStyle != "default"
                                ) {
                                    Color.Transparent
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity)
                                },
                            ),
                        ) { step ->
                            when (step) {
                                is ThinkingStep.ReasoningStep -> {
                                    key(step.reasoning.createdAt) {
                                        ChatMessageReasoningStep(
                                            reasoning = step.reasoning,
                                            model = model,
                                            assistant = assistant,
                                            collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                        )
                                    }
                                }

                                is ThinkingStep.ToolStep -> {
                                    key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                        ChatMessageToolStep(
                                            tool = step.tool,
                                            loading = loading && !step.tool.isExecuted,
                                            onToolApproval = onToolApproval,
                                            onToolAnswer = onToolAnswer,
                                        )
                                    }
                                }

                                is ThinkingStep.ServerToolStep -> {
                                    key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                        ChatMessageServerToolStep(tool = step.tool)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                val precedingBubbleCount = visibleParts.take(block.index).sumOf { precedingPart ->
                    when (precedingPart) {
                        is UIMessagePart.Text -> {
                            if (isVoiceHiddenText(precedingPart.metadata) &&
                                settings.displaySetting.aiVoiceReplyMode == "voice_only"
                            ) {
                                0
                            } else if (role == MessageRole.ASSISTANT &&
                                settings.displaySetting.assistantSplitParagraphs
                            ) {
                                splitAssistantParagraphs(precedingPart.text).size
                            } else {
                                1
                            }
                        }
                        is UIMessagePart.Image,
                        is UIMessagePart.Audio,
                        is UIMessagePart.Video,
                        is UIMessagePart.Document -> 1
                        else -> 0
                    }
                }
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        // AI语音回复（纯语音模式）：文字部分只在该模式下隐藏，切回文字模式会重新显示
                        val hideAiVoiceText = isVoiceHiddenText(part.metadata) &&
                            settings.displaySetting.aiVoiceReplyMode == "voice_only"
                        val textContent = @Composable {
                            if (role == MessageRole.USER) {
                                val isPixel = settings.displaySetting.userBubbleStyle == "pixel"
                                val isGlass = !isPixel && settings.displaySetting.userBubbleStyle == "glass"
                                val glassRounded = RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp)
                                if (isGlass) {
                                    GlassMessageBubble(
                                        isUser = true,
                                        shape = glassRounded,
                                        hazeState = hazeState,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        onClick = { onUserMessageClick?.invoke() },
                                    ) {
                                        MarkdownBlock(
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation,
                                        )
                                    }
                                } else {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = if (isPixel) {
                                            RoundedCornerShape(0.dp)
                                        } else {
                                            RoundedCornerShape(16.dp)
                                        },
                                        color = if (isPixel) {
                                            if (isDarkTheme) Color.Black else Color.White
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity)
                                        },
                                        contentColor = if (isPixel) {
                                            if (isDarkTheme) Color.White else Color.Black
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                        border = if (isPixel) {
                                            BorderStroke(2.dp, if (isDarkTheme) Color.White else Color.Black)
                                        } else {
                                            null
                                        },
                                        onClick = { onUserMessageClick?.invoke() },
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.USER,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation
                                            )
                                        }
                                    }
                                }
                            } else {
                                val splitEnabled = settings.displaySetting.assistantSplitParagraphs
                                val paragraphs = if (splitEnabled) {
                                    splitAssistantParagraphs(part.text)
                                } else {
                                    listOf(part.text)
                                }
                                val style = settings.displaySetting.assistantBubbleStyle
                                val useBubble = settings.displaySetting.showAssistantBubble ||
                                    paragraphs.size > 1 || style != "default"
                                if (useBubble) {
                                    val isPixel = style == "pixel"
                                    val isGlass = !isPixel && style == "glass"
                                    val glassRounded = RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp)
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                                            if (perBubbleAvatar && precedingBubbleCount + paragraphIndex > 0) {
                                                key(block.index, paragraphIndex, "avatar") {
                                                    perBubbleAvatarRow()
                                                }
                                            }
                                            if (isGlass) {
                                                GlassMessageBubble(
                                                    isUser = false,
                                                    shape = glassRounded,
                                                    hazeState = hazeState,
                                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                                ) {
                                                    MarkdownBlock(
                                                        content = paragraph.replaceRegexes(
                                                            assistant = assistant,
                                                            scope = AssistantAffectScope.ASSISTANT,
                                                            visual = true,
                                                        ),
                                                        onClickCitation = handleClickCitation,
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    modifier = Modifier.animateContentSize(),
                                                    shape = if (isPixel) {
                                                        RoundedCornerShape(0.dp)
                                                    } else {
                                                        RoundedCornerShape(16.dp)
                                                    },
                                                    color = if (isPixel) {
                                                        if (isDarkTheme) Color.Black else Color.White
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity)
                                                    },
                                                    contentColor = if (isPixel) {
                                                        if (isDarkTheme) Color.White else Color.Black
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                    border = if (isPixel) {
                                                        BorderStroke(2.dp, if (isDarkTheme) Color.White else Color.Black)
                                                    } else {
                                                        null
                                                    },
                                                ) {
                                                    Column(modifier = Modifier.padding(8.dp)) {
                                                        MarkdownBlock(
                                                            content = paragraph.replaceRegexes(
                                                                assistant = assistant,
                                                                scope = AssistantAffectScope.ASSISTANT,
                                                                visual = true,
                                                            ),
                                                            onClickCitation = handleClickCitation,
                                                        )
                                                    }
                                                }
                                            }
                                            }
                                        }
                                }
                                else {
                                    if (perBubbleAvatar && precedingBubbleCount > 0) {
                                        perBubbleAvatarRow()
                                    }
                                    MarkdownBlock(
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier
                                            .animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (hideAiVoiceText) {
                            // 文字已折叠为语音条
                        } else if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        if (perBubbleAvatar && precedingBubbleCount > 0) perBubbleAvatarRow()
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        if (perBubbleAvatar && precedingBubbleCount > 0) perBubbleAvatarRow()
                        VoiceBarBubble(
                            url = part.url,
                            metadata = part.metadata,
                            isUser = role == MessageRole.USER,
                        )
                    }

                    is UIMessagePart.Image -> {
                        if (perBubbleAvatar && precedingBubbleCount > 0) perBubbleAvatarRow()
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(if (stickerMessage) 120.dp else 72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        if (perBubbleAvatar && precedingBubbleCount > 0) perBubbleAvatarRow()
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
}

/** 用户定稿的气泡渐变：145° 深蓝半透明，user 偏亮、AI 偏深（对应网页版 .msg.me/.msg.ai::after）。 */
private fun glassGradient(isUser: Boolean): Brush = Brush.linearGradient(
    colors = if (isUser) {
        listOf(Color(0x47306092), Color(0x521E3E66), Color(0x5C122848))
    } else {
        listOf(Color(0x3D28507E), Color(0x47183458), Color(0x510E223E))
    },
)

/**
 * 玻璃描边不再是均匀色：左上受光面亮、右下背光面暗，模拟真实玻璃边缘的透光不均。
 * user 偏亮一档，AI 偏沉一档，与主体渐变的明暗关系保持一致。
 */
private fun glassBorderBrush(isUser: Boolean): Brush = Brush.linearGradient(
    colors = if (isUser) {
        listOf(Color(0x6BA9D6FF), Color(0x2E96C8F5), Color(0x16123052))
    } else {
        listOf(Color(0x5296C8F5), Color(0x2282B4E8), Color(0x100C2038))
    },
)

/** 顶缘高光：从上往下衰减的一条细光线，叠在描边外半层，是玻璃「厚度感」的来源。 */
private fun glassSheenBrush(): Brush = Brush.verticalGradient(
    colors = listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)),
)

@Composable
private fun GlassMessageBubble(
    isUser: Boolean,
    shape: RoundedCornerShape,
    hazeState: HazeState?,
    contentColor: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .animateContentSize()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // Compose equivalent of `.message::after { position:absolute; inset:0 }`.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .then(
                    if (hazeState != null) {
                        Modifier.hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = HazeBlurStyle {
                                backgroundColor(Color.Transparent)
                                blurRadius(12.dp)
                                colorEffects(
                                    listOf(
                                        HazeColorEffect.colorFilter(
                                            ColorFilter.colorMatrix(
                                                ColorMatrix().apply { setToSaturation(1.6f) },
                                            ),
                                        ),
                                    ),
                                )
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .background(glassGradient(isUser), shape)
                // 外层 1dp 渐变描边做玻璃边缘；内叠 0.5dp 顶部高光只盖住描边外半层，
                // 形成外亮内暗的双层边缘，即 iOS 玻璃的 rim + sheen。
                .border(1.dp, glassBorderBrush(isUser), shape)
                .border(0.5.dp, glassSheenBrush(), shape),
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                content()
            }
        }
    }
}
/**
 * 拍一拍：一行居中的系统小字，例如「AI摸了摸你」。
 */
@Composable
private fun PatMessageRow(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * Call record: a centered pill like the pat row, with a phone icon.
 */
@Composable
private fun CallRecordRow(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 分段分气泡：按空行把助手文本拆成多段，每段渲染成一个气泡。
 * 代码围栏（```）内部的空行不拆，避免把代码块切断。
 */
private fun splitAssistantParagraphs(text: String): List<String> {
    if (text.isBlank()) return listOf(text)
    val lines = text.split("\n")
    val chunks = mutableListOf<String>()
    val current = mutableListOf<String>()
    var inFence = false

    fun flush() {
        val chunk = current.joinToString("\n").trim()
        if (chunk.isNotEmpty()) chunks.add(chunk)
        current.clear()
    }

    for (line in lines) {
        if (line.trimStart().startsWith("```")) inFence = !inFence
        if (!inFence && line.isBlank()) {
            flush()
        } else {
            current.add(line)
        }
    }
    flush()

    return if (chunks.size < 2) listOf(text) else chunks
}
