package me.rerere.rikkahub.ui.components.message

import android.os.SystemClock
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.PatActionEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.toComposeAvatarShape
import me.rerere.rikkahub.utils.PAT_AUTHOR_USER
import me.rerere.rikkahub.utils.buildPatTextSamples

private const val DOUBLE_TAP_TIMEOUT = 300L

/**
 * 头像双击的检测状态。
 *
 * [UIAvatar] 自己是 clickable 的，子节点会把点击消费掉、外层就收不到了，
 * 所以它那边只能靠「记录两次点击的间隔」来判断双击；模型图标不可点，直接用 combinedClickable。
 */
private class AvatarDoubleTapState {
    var lastClickAt: Long = 0L

    fun onChildClick(now: Long, onDoubleTap: () -> Unit) {
        if (now - lastClickAt < DOUBLE_TAP_TIMEOUT) {
            lastClickAt = 0
            onDoubleTap()
        } else {
            lastClickAt = now
        }
    }
}

@Composable
private fun rememberAvatarDoubleTapState(): AvatarDoubleTapState = remember { AvatarDoubleTapState() }

/**
 * 拍一拍菜单：双击头像后弹出。
 *
 * 选项不是写死的语录，而是从用户的词库里随机拼出来的几条完整句子
 * （方式 + 动作 + 部位），每次打开菜单都会重新随机。
 */
@Composable
private fun PatMenu(
    expanded: Boolean,
    title: String,
    previews: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        HorizontalDivider()
        if (previews.isEmpty()) {
            DropdownMenuItem(
                text = { Text("词库还是空的") },
                onClick = onDismiss,
                enabled = false,
            )
        }
        previews.forEach { preview ->
            DropdownMenuItem(
                text = { Text(preview) },
                onClick = {
                    onDismiss()
                    onPick(preview)
                },
            )
        }
    }
}

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
    patActions: List<PatActionEntity> = emptyList(),
    aiName: String = "",
    onPat: ((patText: String) -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val avatarShape = settings.displaySetting.chatAvatarShape.toComposeAvatarShape()
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        var showPatMenu by remember { mutableStateOf(false) }
        val doubleTapState = rememberAvatarDoubleTapState()
        val previews = remember(showPatMenu, patActions, aiName) {
            if (showPatMenu) {
                buildPatTextSamples(
                    words = patActions,
                    owner = PAT_AUTHOR_USER,
                    aiName = aiName,
                    patAi = false,
                )
            } else {
                emptyList()
            }
        }

        Box(modifier = modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onDoubleClick = { showPatMenu = true },
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        maxLines = 1,
                    )
                    UIAvatar(
                        name = nickname,
                        modifier = Modifier.size(32.dp),
                        value = avatar,
                        loading = false,
                        onClick = {
                            doubleTapState.onChildClick(SystemClock.uptimeMillis()) { showPatMenu = true }
                        },
                        shape = avatarShape,
                    )
                }
            }
            PatMenu(
                expanded = showPatMenu,
                title = "拍自己",
                previews = previews,
                onDismiss = { showPatMenu = false },
                onPick = { text -> onPat?.invoke(text) },
            )
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
    patActions: List<PatActionEntity> = emptyList(),
    aiName: String = "",
    onPat: ((patText: String) -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val avatarShape = settings.displaySetting.chatAvatarShape.toComposeAvatarShape()
    val showIcon = settings.displaySetting.showModelIcon
    val useAssistantAvatar = assistant?.useAssistantAvatar == true
    if (message.role == MessageRole.ASSISTANT && (model != null || useAssistantAvatar)) {
        var showPatMenu by remember { mutableStateOf(false) }
        val doubleTapState = rememberAvatarDoubleTapState()
        val previews = remember(showPatMenu, patActions, aiName) {
            if (showPatMenu) {
                buildPatTextSamples(
                    words = patActions,
                    owner = PAT_AUTHOR_USER,
                    aiName = aiName,
                    patAi = true,
                )
            } else {
                emptyList()
            }
        }

        Box(modifier = modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onDoubleClick = { showPatMenu = true },
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (useAssistantAvatar) {
                    if (showIcon) {
                        UIAvatar(
                            name = assistant.name,
                            modifier = Modifier.size(32.dp),
                            value = assistant.avatar,
                            loading = loading,
                            onClick = {
                                doubleTapState.onChildClick(SystemClock.uptimeMillis()) { showPatMenu = true }
                            },
                            shape = avatarShape,
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (settings.displaySetting.showModelName) {
                            Text(
                                text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                maxLines = 1,
                            )
                        }
                    }
                } else if (model != null) {
                    if (showIcon) {
                        AutoAIIcon(
                            name = model.modelId,
                            modifier = Modifier.size(32.dp),
                            loading = loading,
                            shape = avatarShape,
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (settings.displaySetting.showModelName) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            PatMenu(
                expanded = showPatMenu,
                title = "拍 TA",
                previews = previews,
                onDismiss = { showPatMenu = false },
                onPick = { text -> onPat?.invoke(text) },
            )
        }
    }
}
