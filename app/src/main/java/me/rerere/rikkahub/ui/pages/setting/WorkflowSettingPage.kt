package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.workflow.WorkflowStore
import me.rerere.rikkahub.service.AppLockAccessibilityService
import me.rerere.rikkahub.service.WorkflowService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun WorkflowSettingPage(
    onBack: () -> Unit,
    workflowStore: WorkflowStore = koinInject(),
    workflowService: WorkflowService = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by workflowStore.config.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityEnabled = remember(refreshKey) { isAppLockServiceEnabled(context) }
    val usageGranted = remember(refreshKey) { context.hasUsageStatsPermission() }

    var interval by remember(config.intervalMinutes) { mutableFloatStateOf(config.intervalMinutes.toFloat()) }
    var threshold by remember(config.thresholdMinutes) { mutableFloatStateOf(config.thresholdMinutes.toFloat()) }
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("定时查岗") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("workflowStatus") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("权限状态") },
                ) {
                    item(
                        onClick = { openAccessibilitySettings(context) },
                        headlineContent = { Text("无障碍（读屏幕内容）") },
                        supportingContent = {
                            Text(
                                if (accessibilityEnabled) "已开启：可以读取当前屏幕文字、显示悬浮头像"
                                else "点这里去系统无障碍里授权（服务名：Damonlsy 应用锁）"
                            )
                        },
                    )
                    item(
                        onClick = { context.openUsageAccessSettings() },
                        headlineContent = { Text("使用情况访问（应用时长）") },
                        supportingContent = {
                            Text(
                                if (usageGranted) "已开启：可以查询应用使用时长排行榜"
                                else "点这里去系统设置里授权，否则查不到时长"
                            )
                        },
                    )
                }
            }

            item("workflowSwitch") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("定时查岗") },
                ) {
                    item(
                        headlineContent = { Text("开启定时查岗") },
                        supportingContent = {
                            Text("开启后每隔一段时间，AI 会查一次你的使用时长和屏幕内容，然后按自己的意思发一条消息；白名单外应用用太久还可以由 AI 决定锁不锁。会有一条常驻通知。")
                        },
                        trailingContent = {
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { enabled ->
                                    workflowStore.update { it.copy(enabled = enabled) }
                                },
                            )
                        },
                    )
                }
            }

            item("workflowParams") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("参数") },
                ) {
                    item(
                        headlineContent = { Text("查岗间隔：${interval.roundToInt()} 分钟") },
                        supportingContent = {
                            Column {
                                Text("每隔这么久就自动查一次岗。")
                                Slider(
                                    value = interval,
                                    onValueChange = { interval = it },
                                    onValueChangeFinished = {
                                        workflowStore.update { it.copy(intervalMinutes = interval.roundToInt()) }
                                    },
                                    valueRange = 1f..180f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("锁应用阈值：${threshold.roundToInt()} 分钟") },
                        supportingContent = {
                            Column {
                                Text("白名单外应用使用超过这个时长，AI 才可能锁它。")
                                Slider(
                                    value = threshold,
                                    onValueChange = { threshold = it },
                                    onValueChangeFinished = {
                                        workflowStore.update { it.copy(thresholdMinutes = threshold.roundToInt()) }
                                    },
                                    valueRange = 5f..240f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                }
            }

            item("workflowTest") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("测试") },
                ) {
                    item(
                        headlineContent = { Text("立即试一次") },
                        supportingContent = { Text("马上跑一次工作流：查时长 + 读屏幕 + 让 AI 自己决定说什么 + 弹头像。") },
                        trailingContent = {
                            Button(
                                enabled = !testRunning,
                                onClick = {
                                    testRunning = true
                                    scope.launch {
                                        val outcome = runCatching {
                                            workflowService.runOnce(force = true)
                                        }.getOrNull()
                                        testRunning = false
                                        testResult = if (outcome == null) {
                                            "没生成出来：检查是否配了模型 / 权限"
                                        } else {
                                            buildString {
                                                append(outcome.message)
                                                if (outcome.lockedApps.isNotEmpty()) {
                                                    append("\n\n已锁定：")
                                                    append(outcome.lockedApps.joinToString("、"))
                                                }
                                            }
                                        }
                                        if (outcome != null) {
                                            AppLockAccessibilityService.showCompanion(
                                                name = outcome.assistantName,
                                                avatar = outcome.assistantAvatar,
                                                conversationId = outcome.conversationId,
                                            )
                                        }
                                    }
                                },
                            ) {
                                Text(if (testRunning) "运行中…" else "运行")
                            }
                        },
                    )
                }
            }
        }
    }

    testResult?.let { result ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text("查岗结果") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { testResult = null }) { Text("好") }
            },
        )
    }
}
