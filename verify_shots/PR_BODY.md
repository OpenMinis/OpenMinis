## 概要

基于 v1.13（App 1.1.3）的三个增强 + 两个跟进修复：

1. **用量统计页增强**（`UsageStatsScreen` 重写）：顶部多维筛选（时间/服务商/模型/Token 区间/排序/仅前 50 条）、汇总卡片、按服务商分组的模型行、可展开的逐请求明细、按模型配置价格（新增 `model_pricing` 表，DB v12→13）。
2. **每条 AI 回复「复制整段」按钮**：整段回复（含图片占位符文本）一键进剪贴板，提示「已复制」。
3. **每条 AI 回复「系统 TTS 朗读」按钮**：长文分块连续朗读，再次点击停止；切换消息自动停旧的。
4. **中文适配修复**：新增的全部字符串补齐 `values-zh` 简体翻译；筛选/排序/计数文案从硬编码改为字符串资源（`时间/服务商/模型/Token 区间/按费用排序/按 Token 排序/按请求数排序/请求数/Token 数/费用（USD）/成功率`等），未知服务商/模型也本地化。
5. **备份本地化闭环**（跟进 "本地备份，先不做网盘"）：移除「必须配置 rclone 远程目的地才能备份」的门槛——无远程时备份照常在本机生成包；新增「备份已就绪」卡片上的 **分享… / 保存到文件…** 按钮（SAF 系统文档保存），本地导出→保存→恢复（SAF 打开 .minisbak）全链路可用。
6. **MCP 配置手动连通测试**：MCP 集成页每个服务器行新增「测试连通」按钮——一键执行 `minis-mcp-cli refresh <server>`（强制重连 + tools/list），一次拿到连通性与可用工具数：成功时行副标题显示「已连通 · N 个工具」，失败显示「连接失败」；测试进行中按钮变为加载圈。

## Android-only

本 PR 只动 Android 侧（`src/android/app`），iOS 不受影响。数据库升级带导出 schema（v13）。

## 验证

- 模拟器 Android 15（arm64）安装 `app-debug.apk`，系统语言设为中文（zh-CN）。
- 真实供应商（claude-opus-5）跑过真实对话并造数：Requests 9 · Tokens 108.2k · Cost $1.9188 · 成功率 100%。
- 用量页筛选、复制、TTS 均在实机操作验证；备份：本机导出 68 KB 包 → 保存到文件（Downloads）→ 从该文件恢复（已恢复 23 / 已更新 0 / 已是最新 12）。
- MCP 连通测试：模拟器沙盒内起本地 MCP HTTP 测试服务（2 个工具，host loopback 经 10.0.2.2 直连），配置可达的 `e2e-mcp` 与不可达的 `broken` 两个服务器；UI 实测 e2e-mcp →「已连通 · 2 个工具」，broken →「连接失败」（截图 30–33）。
- 证据截图见 `verify_shots/`（含本机备份全流程）。

## 待上游注意

- `model_pricing` 表未纳入备份包（保持 .minisbak 跨平台格式不变）；如需携带价格配置可作为后续扩展。
- 本地备份的门槛变化改变了 Android 端「无目的地不可备份」的既有行为，属有意为之（对齐用户诉求与 iOS 的本地行为）。

7. **并发工具执行（"子代理"）**：模型单回合发出多个 `tool_use` 时，Android 侧不再串行逐条执行，改为并发 fan-out（镜像 iOS `AIChatViewModel+ConcurrentTools`）。每回合同时在飞工具数上限 `MAX_CONCURRENT_TOOLS = 10`，结果按原始 `tool_use` 顺序拼回（满足 Anthropic `tool_result` 顺序要求）。并发写入共享状态（`allToolBlocks` / `toolLoopDetector` / `toolInputChunkRings`）均经 `synchronized(allToolBlocks)` 保护；`executeShellCommand` 的流式回调与 delay 倒计时同步也做了 lock 包裹，避免多线程竞态。单工具路径保持原行为（走 sequential fallback），无回归。
