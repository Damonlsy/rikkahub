/*
 * 移植自 OrangeChat
 * 原型来自 RikkaHub (https://github.com/rikkahub/rikkahub)，原始许可 RE
 * 本项目许可 GNU AGPL v3 见本项目根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private const val TAG = "PluginManagePage"

/**
 * 插件管理页（B 批第一版）：列表 / 导入 / 开关 / 删除 / 插件工具免审批 / 安全提示。
 * 文件夹、详情、插件自定义 UI 等在 C 批补齐。
 */
@Composable
fun PluginManagePage(
    viewModel: PluginViewModel = koinViewModel()
) {
    val plugins by viewModel.plugins.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()
    val autoApprove by viewModel.autoApprove.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSecurityNotice by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PluginInfo?>(null) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.previewPlugin(uri)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("插件") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SecurityBanner(
                    onViewNotice = { showSecurityNotice = true }
                )
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("插件操作") },
                ) {
                    item(
                        onClick = {
                            if (!busy) {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream")
                                )
                            }
                        },
                        leadingContent = { Icon(HugeIcons.FileImport, null) },
                        headlineContent = { Text("导入插件 (ZIP)") },
                        supportingContent = { Text("从系统文件选择器挑一个插件 zip 包安装") },
                    )
                    item(
                        onClick = { if (!busy) viewModel.refresh() },
                        leadingContent = { Icon(HugeIcons.Refresh03, null) },
                        headlineContent = { Text("重新扫描插件目录") },
                        supportingContent = { Text("手动改动插件目录后可在此刷新") },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("审批") },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Tick01, null) },
                        headlineContent = { Text("插件工具免审批") },
                        supportingContent = {
                            Text(
                                "开启后 AI 调用插件工具时不再弹确认卡，直接执行。" +
                                    "插件依然只能访问 manifest 里声明的网络白名单，" +
                                    "建议只在信任已安装插件时打开。"
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = autoApprove,
                                onCheckedChange = { viewModel.setAutoApprove(it) }
                            )
                        }
                    )
                }
            }

            item {
                if (isLoading || busy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (busy) "正在处理…" else "正在加载插件…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                if (plugins.isEmpty() && !isLoading) {
                    Text(
                        text = "还没有插件。点上面的「导入插件」安装一个 ZIP。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                } else if (plugins.isNotEmpty()) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("已安装插件 (${plugins.size})") },
                    ) {
                        plugins.forEach { plugin ->
                            item(
                                modifier = Modifier.padding(bottom = 4.dp),
                                leadingContent = {
                                    Text(
                                        text = plugin.manifest.icon.ifBlank { "📦" },
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = plugin.manifest.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        Text(
                                            text = "v${plugin.manifest.version} · ${plugin.manifest.id}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (plugin.manifest.description.isNotBlank()) {
                                            Text(
                                                text = plugin.manifest.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (plugin.manifest.tools.isNotEmpty()) {
                                            Text(
                                                text = "${plugin.manifest.tools.size} 个工具" +
                                                    if (plugin.manifest.hooks.isNotEmpty())
                                                        " · ${plugin.manifest.hooks.size} 个钩子"
                                                    else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        plugin.loadError?.let { error ->
                                            Text(
                                                text = "加载失败: $error",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        if (plugin.manifest.allowedHosts.isEmpty()) {
                                            Text(
                                                text = "未声明网络白名单，已禁止联网",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { pendingDelete = plugin }) {
                                            Icon(HugeIcons.Delete02, contentDescription = "删除插件")
                                        }
                                        Switch(
                                            checked = plugin.isEnabled,
                                            onCheckedChange = { checked ->
                                                viewModel.togglePlugin(plugin.manifest.id, checked)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("确认安装插件?") },
            text = {
                Column {
                    Text(
                        "${pending.manifest.name}  v${pending.manifest.version}"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(pending.manifest.description)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("作者: ${pending.manifest.author}")
                    Text("ID: ${pending.manifest.id}")
                    if (pending.manifest.tools.isNotEmpty()) {
                        Text("工具: ${pending.manifest.tools.joinToString { it.name }}")
                    }
                    if (pending.manifest.hooks.isNotEmpty()) {
                        Text("钩子: ${pending.manifest.hooks.joinToString { it.event }}")
                    }
                    if (pending.manifest.allowedHosts.isNotEmpty()) {
                        Text("联网白名单: ${pending.manifest.allowedHosts.joinToString()}")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.plugin_import_security_warning_message),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) {
                    Text("确认安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text("取消")
                }
            }
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除插件?") },
            text = { Text("将删除「${plugin.manifest.name}」的插件目录，该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = plugin.manifest.id
                        pendingDelete = null
                        viewModel.deletePlugin(id)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSecurityNotice) {
        AlertDialog(
            onDismissRequest = { showSecurityNotice = false },
            title = { Text(stringResource(R.string.plugin_import_security_warning_title)) },
            text = {
                Text(stringResource(R.string.plugin_import_security_warning_message))
            },
            confirmButton = {
                TextButton(onClick = { showSecurityNotice = false }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun SecurityBanner(
    onViewNotice: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.plugin_import_security_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.plugin_import_security_warning_message),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onViewNotice) {
                Text(stringResource(R.string.plugin_import_security_warning_action))
            }
        }
    }
}
