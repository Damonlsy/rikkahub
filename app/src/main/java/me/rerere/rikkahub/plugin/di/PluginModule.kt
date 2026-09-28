/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.di

import me.rerere.rikkahub.data.security.SecurityAuditRepository
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 插件模块依赖注入
 */
val pluginModule = module {
    // 安全审计日志（插件安装 / 完整性失败 / 越权拦截）
    single { SecurityAuditRepository(androidContext()) }

    // Scanner
    single { PluginScanner(androidContext(), get()) }

    // Repository
    single { PluginRepository(androidContext()) }

    // Loader - 需要 OkHttpClient 和 SettingsStore（用于解析 model 类型配置）
    single { PluginLoader(androidContext(), get<OkHttpClient>(), get()) }

    // Manager
    single { PluginManager(androidContext(), get(), get(), get(), get()) }

    // Provider - 需要 PluginManager 以确保插件已初始化
    single { PluginToolProvider(get(), get(), get()) }
}
