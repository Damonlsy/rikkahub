/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid

/**
 * 工具名命名空间
 *
 * 为不同 MCP server / 插件 分配不同前缀，避免同一个 Provider 的 tools
 * 出现重名（会触发 400 "Tool names must be unique"）。
 *
 * 为什么用 8 位短哈希而不是完整 UUID/pluginId:
 * - Anthropic / OpenAI 的接口对 tool name 有 64 字符上限
 * - 完整 UUID(36 字符) + 分隔符和前缀已占 43+ 字符，留给原始工具名只剩 21 字符，非常局促
 * - pluginId(反向域名格式，如 com.example.plugin.marketplace)又长又含点号(非法字符)
 * - 8 位十六进制(只含 0-9a-f): 前缀固定 13 字符，留给原始工具名 51 字符，也不含非法字符
 */
object ToolNaming {
    private const val PLUGIN_PREFIX = "plg_"
    private const val MCP_PREFIX = "mcp__"
    private const val SHORT_KEY_LENGTH = 8
    // 插件前缀(4) + 短哈希(8) + 分隔符(1) = 13
    private const val PLUGIN_HEADER_LENGTH = PLUGIN_PREFIX.length + SHORT_KEY_LENGTH + 1

    /**
     * 构造插件工具的完整工具名
     *
     * 格式: plg_ + 8位十六进制(pluginId 的 hashCode) + _ + 原始工具名
     *
     * @param pluginId 插件 id(反向域名格式, 如 com.example.plugin.marketplace)
     * @param toolName 插件声明的原始工具名
     */
    fun buildPluginToolName(pluginId: String, toolName: String): String {
        val shortKey = String.format("%08x", pluginId.hashCode())
        return "$PLUGIN_PREFIX${shortKey}_$toolName"
    }

    /**
     * 构造 MCP 工具的完整工具名
     *
     * 格式: mcp__ + serverName + __ + 原始工具名
     */
    fun buildMcpToolName(serverName: String, toolName: String): String {
        return "$MCP_PREFIX${serverName}__$toolName"
    }

    /**
     * 还原为显示名
     *
     * 插件工具剥掉固定前缀(前缀4 + 短哈希8 + 分隔符1 = 13)，
     * MCP 工具剥掉 `mcp__<server>__`，其余(内置/系统/功能工具)原样返回。
     */
    fun toDisplayName(name: String): String = when {
        name.length > PLUGIN_HEADER_LENGTH && name.startsWith(PLUGIN_PREFIX) ->
            name.substring(PLUGIN_HEADER_LENGTH)

        name.startsWith(MCP_PREFIX) -> {
            val rest = name.substring(MCP_PREFIX.length)
            val sep = rest.indexOf("__")
            if (sep >= 0) rest.substring(sep + 2) else rest
        }

        else -> name
    }

    /**
     * 判断一个名字是否来自 MCP 工具(用于 UI 上打标签)
     */
    fun isMcpToolName(name: String) = name.startsWith(MCP_PREFIX)

    /**
     * 判断一个名字是否来自插件
     */
    fun isPluginToolName(name: String) = name.startsWith(PLUGIN_PREFIX)

    /**
     * MCP server 名(取 `mcp__<server>__` 段)，非 MCP 工具返回 null
     */
    fun mcpServerName(name: String): String? {
        if (!isMcpToolName(name)) return null
        val rest = name.substring(MCP_PREFIX.length)
        val sep = rest.indexOf("__")
        return if (sep >= 0) rest.substring(0, sep) else null
    }

    /**
     * 兼容上游签名：以 Uuid 作为 MCP server 的 key 时构造短名
     */
    fun buildMcpToolName(serverId: Uuid, toolName: String): String {
        val shortKey = String.format("%08x", serverId.toString().hashCode())
        return "mcp_${shortKey}_$toolName"
    }
}
