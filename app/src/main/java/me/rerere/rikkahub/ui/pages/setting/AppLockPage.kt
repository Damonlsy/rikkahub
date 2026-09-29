package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.data.applock.AppLockStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private data class AppRow(val pkg: String, val label: String)

/**
 * 应用锁管理页：
 * - 无障碍服务状态（拦截的开关）
 * - 已锁定应用列表（解锁）
 * - 全部可启动应用：点一行锁定/解锁、设为受保护（自定义白名单）
 *
 * 受保护 = AI 和手动都锁不了，用于保护用户绝不能被锁死的应用。
 */
@Composable
fun AppLockPage(
    appLockStore: AppLockStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
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

    // 小米/红米等 ROM 需要运行时申请「读取应用列表」，否则列表几乎为空
    var permissionRequested by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey) {
        if (!permissionRequested && !hasAppListPermission(context)) {
            permissionRequested = true
            requestAppListPermission(context)
        }
    }

    val apps = remember(refreshKey) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { runCatching { pm.getLaunchIntentForPackage(it.packageName) != null }.getOrDefault(false) }
            .mapNotNull { ai ->
                val label = runCatching { pm.getApplicationLabel(ai).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AppRow(ai.packageName, label)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
    val labels = remember(apps) { apps.associate { it.pkg to it.label } }
    fun labelOf(pkg: String): String = labels[pkg] ?: runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)

    var locked by remember(refreshKey) { mutableStateOf(appLockStore.lockedPackages()) }
    var protectedSet by remember(refreshKey) { mutableStateOf(appLockStore.protectedPackages()) }
    var editing by remember { mutableStateOf<AppRow?>(null) }
    var showKeepAlive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("应用锁") },
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
            item("status") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("状态") },
                ) {
                    item(
                        onClick = { openAccessibilitySettings(context) },
                        headlineContent = { Text("无障碍服务") },
                        supportingContent = {
                            Text(
                                if (accessibilityEnabled) {
                                    "已开启：被锁定的应用一打开就会被拦截退回桌面"
                                } else {
                                    "未开启：点这里去系统设置授权（服务名：Damonlsy 应用锁）"
                                },
                            )
                        },
                    )
                    if (apps.size <= 10) {
                        item(
                            onClick = { requestAppListPermission(context) },
                            headlineContent = { Text("读取应用列表权限") },
                            supportingContent = {
                                Text("读不到已安装的应用（小米/红米必开「读取应用列表」），点这里重新申请")
                            },
                        )
                    }
                    item(
                        onClick = { showKeepAlive = true },
                        headlineContent = { Text("开了无障碍却不拦截？") },
                        supportingContent = {
                            Text("多半是被系统杀后台了——点这里看各品牌保活步骤")
                        },
                    )
                }
            }

            if (locked.isNotEmpty()) {
                item("locked") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("已锁定（${locked.size}）") },
                    ) {
                        locked.sortedBy { labelOf(it).lowercase() }.forEach { pkg ->
                            val info = appLockStore.info(pkg)
                            item(
                                headlineContent = { Text(labelOf(pkg)) },
                                supportingContent = {
                                    val note = info?.note.orEmpty()
                                    val by = info?.by?.takeIf { it.isNotBlank() } ?: "AI"
                                    Text(if (note.isBlank()) "由 $by 锁定" else "$note · $by")
                                },
                                trailingContent = {
                                    TextButton(onClick = {
                                        appLockStore.unlock(pkg)
                                        locked = appLockStore.lockedPackages()
                                    }) {
                                        Text("解锁")
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item("allApps") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("全部应用（点一行管理）") },
                ) {
                    if (apps.isEmpty()) {
                        item(
                            headlineContent = { Text("读不到应用列表") },
                            supportingContent = {
                                Text("请授予「读取应用列表」权限后返回本页")
                            },
                            onClick = { requestAppListPermission(context) },
                        )
                    }
                    apps.forEach { app ->
                        val isLocked = app.pkg in locked
                        val isProtected = app.pkg in protectedSet
                        item(
                            onClick = { editing = app },
                            headlineContent = { Text(app.label) },
                            supportingContent = {
                                Text(
                                    when {
                                        isProtected && isLocked -> "受保护（已自动解锁，下次不会被锁）"
                                        isProtected -> "受保护：AI 和手动都锁不了"
                                        isLocked -> "已锁定：${appLockStore.info(app.pkg)?.note.orEmpty().ifBlank { "无备注" }}"
                                        else -> app.pkg
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { app ->
        var lock by remember(app) { mutableStateOf(app.pkg in locked) }
        var prot by remember(app) { mutableStateOf(app.pkg in protectedSet) }
        var note by remember(app) {
            mutableStateOf(appLockStore.info(app.pkg)?.note?.takeIf { it.isNotBlank() } ?: "先别用这个应用")
        }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(app.label) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("锁定这个应用")
                            Text(
                                "打开时弹出拦截页",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = lock && !prot,
                            enabled = !prot,
                            onCheckedChange = { lock = it },
                        )
                    }
                    if (lock && !prot) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("锁定备注") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("受保护")
                            Text(
                                if (prot) "已保护：解锁并永不锁定" else "永不锁定（AI 也锁不了）",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = prot,
                            onCheckedChange = {
                                prot = it
                                if (it) lock = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    appLockStore.setProtected(app.pkg, prot)
                    when {
                        prot -> appLockStore.unlock(app.pkg)
                        lock -> appLockStore.lock(
                            app.pkg,
                            note.ifBlank { "先别用这个应用" },
                            "手动",
                        )
                        else -> appLockStore.unlock(app.pkg)
                    }
                    locked = appLockStore.lockedPackages()
                    protectedSet = appLockStore.protectedPackages()
                    editing = null
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }

    if (showKeepAlive) {
        AlertDialog(
            onDismissRequest = { showKeepAlive = false },
            title = { Text("保持后台运行（防杀）") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "安卓会杀后台省电。服务被杀后，无障碍开关看着还是「已开」，" +
                            "但拦截已经停了。按你的品牌设置：\n" +
                            "\n通用（所有手机）\n" +
                            "· 电池 → 找到 Damonlsy → 选「无限制 / 允许后台运行」\n" +
                            "· 自启动管理里允许 Damonlsy\n" +
                            "· 最近任务里把 Damonlsy 卡片下拉加锁，别用「一键加速」清掉它\n" +
                            "\nvivo / iQOO\n" +
                            "· 设置 → 电池 → 后台高耗电 → 允许 Damonlsy\n" +
                            "· 设置 → 更多设置 → 权限管理 → 自启动 → 打开\n" +
                            "\nOPPO / realme / 一加\n" +
                            "· 电池 → 耗电管理 → 允许后台运行\n" +
                            "· 设置 → 应用管理 → 启动管理 → Damonlsy → 手动管理，全部打开\n" +
                            "\n小米 / 红米\n" +
                            "· 应用设置 → 应用管理 → Damonlsy → 省电策略 → 无限制\n" +
                            "· 自启动管理里打开 Damonlsy\n" +
                            "\n设置完从最近任务划掉 Damonlsy 再打开试一次；还不行就重启手机。",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeepAlive = false }) { Text("知道了") }
            },
        )
    }
}
