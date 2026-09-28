/*
 * 移植自 OrangeChat
 * 原型来自 RikkaHub (https://github.com/rikkahub/rikkahub)，原始许可 RE
 * 本项目许可 GNU AGPL v3 见本项目根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.plugin.repository.PluginRepository
import java.io.File

class PluginViewModel(
    private val pluginManager: PluginManager,
    private val pluginToolProvider: PluginToolProvider,
    private val pluginRepository: PluginRepository
) : ViewModel() {

    val plugins: StateFlow<List<PluginInfo>> = pluginManager.plugins
    val isLoading: StateFlow<Boolean> = pluginManager.isLoading

    /** 插件工具免审批开关 */
    private val _autoApprove = MutableStateFlow(false)
    val autoApprove: StateFlow<Boolean> = _autoApprove.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 预览结果：manifest + 解压到 cacheDir 的临时目录 */
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    data class PendingImport(
        val manifest: PluginManifest,
        val tempDir: File
    )

    init {
        viewModelScope.launch {
            pluginManager.refreshPlugins()
            val enabled = try {
                pluginRepository.autoApproveEnabled.first()
            } catch (e: Exception) {
                false
            }
            _autoApprove.value = enabled
            pluginToolProvider.autoApproveTools = enabled
        }
    }

    /** 切换「插件工具免审批」：写盘 + 立刻刷新 ToolProvider 里的内存开关 */
    fun setAutoApprove(enabled: Boolean) {
        _autoApprove.value = enabled
        pluginToolProvider.autoApproveTools = enabled
        viewModelScope.launch {
            try {
                pluginRepository.setAutoApproveEnabled(enabled)
            } catch (e: Exception) {
                _message.value = "保存设置失败: ${e.message}"
                _autoApprove.value = !enabled
                pluginToolProvider.autoApproveTools = !enabled
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            pluginManager.refreshPlugins()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * 预览待安装的 zip：只解压 + 读 manifest，不落盘到插件目录。
     */
    fun previewPlugin(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = pluginManager.previewPlugin(uri)
                if (result.isSuccess) {
                    val pair = result.getOrThrow()
                    _pendingImport.value = PendingImport(pair.first, pair.second)
                } else {
                    _message.value = "预览插件失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
            } catch (e: Exception) {
                _message.value = "预览插件失败: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 用户在确认弹窗点了「确认安装」 */
    fun confirmImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = pluginManager.confirmImport(pending.manifest, pending.tempDir)
                if (result.isSuccess) {
                    _message.value = "已安装 ${result.getOrThrow().manifest.name}"
                    pluginManager.refreshPlugins()
                } else {
                    _message.value = "安装失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
            } catch (e: Exception) {
                _message.value = "安装失败: ${e.message}"
            } finally {
                _busy.value = false
                runCatching { pending.tempDir.deleteRecursively() }
            }
        }
    }

    fun cancelImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        runCatching { pending.tempDir.deleteRecursively() }
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                pluginManager.togglePlugin(pluginId, enabled)
            } catch (e: Exception) {
                _message.value = "切换插件状态失败: ${e.message}"
            }
        }
    }

    fun deletePlugin(pluginId: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                if (pluginManager.deletePlugin(pluginId)) {
                    _message.value = "已删除插件"
                    pluginManager.refreshPlugins()
                } else {
                    _message.value = "删除插件失败"
                }
            } catch (e: Exception) {
                _message.value = "删除插件失败: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }
}
