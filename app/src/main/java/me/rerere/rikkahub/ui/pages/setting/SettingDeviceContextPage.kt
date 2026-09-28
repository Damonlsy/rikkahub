package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Damonlsy fork：设备上下文注入设置。
 *
 * 这里控制「时间 / 电量 / 天气 / 最近用过的应用」要不要拼进系统提示词给 AI 看。
 * 这些内容不会显示在聊天界面上，只是让模型不用调工具就知道。
 */
@Composable
fun SettingDeviceContextPage() {
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationGranted = remember(refreshKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val usageGranted = remember(refreshKey) { context.hasUsageStatsPermission() }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshKey++
    }

    val config = settings.deviceContext
    var windowMinutes by remember(config.recentAppsWindowMinutes) {
        mutableFloatStateOf(config.recentAppsWindowMinutes.toFloat())
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("设备上下文注入") },
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
            item("permissions") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("权限") },
                ) {
                    item(
                        onClick = {
                            if (!locationGranted) {
                                locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        headlineContent = { Text("定位权限（天气用）") },
                        supportingContent = {
                            Text(
                                if (locationGranted) {
                                    "已授权：按当前坐标去 Open-Meteo 查天气，10 分钟内不重复请求"
                                } else {
                                    "点这里授权，不授权就不注入天气（其它三项照常）"
                                }
                            )
                        },
                    )
                    item(
                        onClick = { context.openUsageAccessSettings() },
                        headlineContent = { Text("使用情况访问（最近应用用）") },
                        supportingContent = {
                            Text(
                                if (usageGranted) {
                                    "已授权：会把最近切过的应用按时间告诉 AI"
                                } else {
                                    "点这里去系统设置授权，不授权就不注入最近应用"
                                }
                            )
                        },
                    )
                }
            }

            item("switches") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("注入内容") },
                ) {
                    item(
                        headlineContent = { Text("总开关") },
                        supportingContent = {
                            Text("关掉后 AI 拿不到这些信息，也不会再拼进系统提示词")
                        },
                        trailingContent = {
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update { it.copy(deviceContext = it.deviceContext.copy(enabled = checked)) }
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("时间") },
                        supportingContent = { Text("年月日、星期、具体几点几分、时区") },
                        trailingContent = {
                            Switch(
                                checked = config.includeTime,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(deviceContext = it.deviceContext.copy(includeTime = checked))
                                        }
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("电量") },
                        supportingContent = { Text("当前百分比、有没有在充电、用的什么充电器") },
                        trailingContent = {
                            Switch(
                                checked = config.includeBattery,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(deviceContext = it.deviceContext.copy(includeBattery = checked))
                                        }
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("天气") },
                        supportingContent = { Text("天气现象、气温、体感、湿度、风速") },
                        trailingContent = {
                            Switch(
                                checked = config.includeWeather,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(deviceContext = it.deviceContext.copy(includeWeather = checked))
                                        }
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("最近用过的应用") },
                        supportingContent = { Text("最近切过的 App 名字和时间，按最近到最早排") },
                        trailingContent = {
                            Switch(
                                checked = config.includeRecentApps,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(deviceContext = it.deviceContext.copy(includeRecentApps = checked))
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item("recentWindow") {
                if (config.enabled && config.includeRecentApps) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("最近应用往回看多久") },
                    ) {
                        item(
                            headlineContent = {
                                Slider(
                                    value = windowMinutes,
                                    onValueChange = { windowMinutes = it },
                                    onValueChangeFinished = {
                                        scope.launch {
                                            settingsStore.update {
                                                it.copy(
                                                    deviceContext = it.deviceContext.copy(
                                                        recentAppsWindowMinutes = windowMinutes.roundToInt(),
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                    valueRange = 15f..240f,
                                    steps = 14,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            supportingContent = {
                                Text("回看 ${windowMinutes.roundToInt()} 分钟内的应用切换记录")
                            },
                        )
                    }
                }
            }

            item("preview") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("会注入成什么样") },
                ) {
                    item(
                        headlineContent = {
                            Text(
                                """
                                <device_context>
                                  当前时间：2026-09-26 14:32:05，星期六，时区 Asia/Shanghai（UTC+08:00）
                                  手机电量：76%，充电中（USB）
                                  晴，气温 21.5℃，体感 20.8℃，湿度 78%，风 2.4 m/s
                                  最近 60 分钟内切过的应用（从最近到最早）：QQ（3 分钟前）、微信（21 分钟前）
                                </device_context>
                                """.trimIndent()
                            )
                        },
                        supportingContent = { Text("只加在系统提示词里，聊天界面上看不到") },
                    )
                }
            }
        }
    }
}
