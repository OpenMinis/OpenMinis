package com.openminis.app.plugins

import com.openminis.app.data.repository.MCPRepository

/**
 * One-tap MCP server presets. Shown on Settings → MCP, not the plugin market.
 *
 * HTTP entries adapted from XINCODE-Public PluginStore MCP catalog
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * GitHub-token connectors are intentionally not ported.
 */
object PluginCatalog {

    data class McpPreset(
        val id: String,
        val name: String,
        val description: String,
        val url: String? = null,
        val command: String? = null,
        val args: List<String> = emptyList(),
    ) {
        fun toServer(): MCPRepository.MCPServerConfig = MCPRepository.MCPServerConfig(
            id = id,
            note = name,
            enabled = true,
            url = url,
            command = command,
            args = args,
        )

        fun matches(server: MCPRepository.MCPServerConfig): Boolean =
            server.id == id || (!url.isNullOrBlank() && server.url == url)
    }

    val MCP_PRESETS: List<McpPreset> = listOf(
        McpPreset(
            id = "microsoft_learn_mcp",
            name = "Microsoft Learn",
            description = "Microsoft documentation search over MCP.",
            url = "https://learn.microsoft.com/api/mcp",
        ),
        McpPreset(
            id = "context7_mcp",
            name = "Context7",
            description = "Up-to-date library docs for coding agents.",
            url = "https://mcp.context7.com/mcp",
        ),
        McpPreset(
            id = "deepwiki_mcp",
            name = "DeepWiki",
            description = "Ask questions about public GitHub repositories.",
            url = "https://mcp.deepwiki.com/mcp",
        ),
        // [T-mcp-builtin-servers] Five zero-dependency Python stdio servers
        // bundled under /usr/local/lib/minis-mcp-servers/ (ported from
        // taixu's BuiltinMcpPresets). They need only python3 in the guest —
        // no pip, no npx, no network.
        McpPreset(
            id = "minis_git",
            name = "Git 仓库协同",
            description = "零依赖 Git MCP：status/diff/log/commit/blame，自动定位 /var/minis/workspace 下的仓库。",
            command = "python3",
            args = listOf("-u", "/usr/local/lib/minis-mcp-servers/git_mcp_server.py", "--repository", "/var/minis/workspace"),
        ),
        McpPreset(
            id = "minis_sqlite",
            name = "SQLite 数据库探索",
            description = "零依赖 SQLite MCP：read/write 查询、表结构、建表。默认库 /var/minis/workspace/minis.db。",
            command = "python3",
            args = listOf("-u", "/usr/local/lib/minis-mcp-servers/sqlite_mcp_server.py", "--db-path", "/var/minis/workspace/minis.db"),
        ),
        McpPreset(
            id = "minis_codegraph",
            name = "CodeGraph 代码知识图谱",
            description = "零依赖代码结构 MCP：符号定义、调用链、影响面（Python/Kotlin/Java/C/C++/JS/TS/Smali），大幅减少代码探索的 Token 与轮次。",
            command = "python3",
            args = listOf("-u", "/usr/local/lib/minis-mcp-servers/codegraph_mcp_server.py", "--repository", "/var/minis/workspace"),
        ),
        McpPreset(
            id = "minis_apktool",
            name = "Android 逆向与 APK 审计",
            description = "APK 反编译、清单权限解析、硬编码凭据提取、Smali 检索与重打包签名（依赖 android-suite 的 apktool/jadx 工具链）。",
            command = "python3",
            args = listOf("-u", "/usr/local/lib/minis-mcp-servers/apktool_mcp_server.py"),
        ),
        McpPreset(
            id = "minis_websearch",
            name = "Web 搜索（免 API Key）",
            description = "零依赖多引擎网络搜索与网页正文抓取：Baidu/Bing/DuckDuckGo 直连 + 正文清洗，无需 Node.js/npx。",
            command = "python3",
            args = listOf("-u", "/usr/local/lib/minis-mcp-servers/websearch_mcp_server.py"),
        ),
    )
}
