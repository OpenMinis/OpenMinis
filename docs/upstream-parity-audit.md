# 上游对比审查：origin 相对 upstream 未实现 / 未完全实现清单

审查日期 2026-10-19。对比双方：

- **origin**（本仓库，tall-1997/OpenMinis-Linux）`origin/main` = `5ee238b8`
- **upstream**（OpenMinis/OpenMinis）`upstream/main` = `b4c0661d`（= tag `1.14`；`upstream/v1.14` = `110b35f2` 仅多一条文档提交）

> 说明：`upstream-beta5` tag 与 `1.17-linux`…`1.36.53-linux` 系列并不是更完整的实现。前者指向同一个 `b4c0661d`；
> 后者是更早、更瘦的 Linux 快照（android 文件 845–1102），不含本文列出的这些特性。

## 0. 结论摘要

1. **完全未实现**：上游有、我们全仓没有对应实现的源码文件 **112 个**（按文件名配对，已排除跨模块搬迁造成的误判），
   覆盖终端模拟器、helper 子代理作业、Copilot/Codex 登录、备份运行控制器与通知、存储清理、容器 DNS 设置、
   首启引导、崩溃收集、沙箱限流、聊天页十余个独立门控/守护件。
2. **未完全实现**：两边都有同名文件、但上游顶层声明在我们文件里找不到的共 **28 个文件 / 55 个符号**，
   其中 `StreamingMarkdownText.kt`（缺 10）、`ChatToolFormatting.kt`（缺 5）、`FullscreenImageViewer.kt`（缺 4）、
   `StreamingFade.kt`（缺 4）最集中。
3. **用户可见文案缺口**：上游 `values/strings.xml` 有 **339 个键**在我们这边完全不存在，
   它们按功能域聚成清晰的成组缺块（计划任务详情/触发器/投递 69、子代理与 agent 控制台 58、
   子代理管理页 27、helper 状态 14、容器 DNS 14、Copilot 免责 10、ASR 交互 10、备份通知与删除 19、存储清理 12 等）。
4. 抽 99 个上游特征符号在**我们全仓（含其它模块与测试）**逐个反查：**91 个完全不存在**，
   其余经核实属「换了写法/换到别处」的差异化实现（见 §5），不计为缺口。

## 1. 方法

四层比对，全部为可复现的确定性检查（脚本落在 `%TEMP%\dsh-parity-*.py`）：

| 层 | 检查 | 为什么这样做 |
| --- | --- | --- |
| L1 | 上游主源码 Kotlin 文件按**文件名**配对（跨模块），列出无对应者 | 全路径比对会把我们搬进 `core/model`、`provider/api` 的文件误判为缺失 |
| L2 | 两边 `res/values/strings.xml` 的键集合求差 | 用户可见文案是最直接的「功能存在与否」证据 |
| L3 | 同名文件内**顶层声明**集合求差 | 只比文件存在与否会漏掉「文件在、功能被砍」的部分实现 |
| L4 | 把 L1/L3 结论中的特征符号放回**我们全仓**（含测试）反查 | 排除改名/换位造成的假缺口 |

## 2. 量化总览

| 指标 | upstream | origin | 差异 |
| --- | --- | --- | --- |
| 主源码 Kotlin 文件 | 581 | 968 | 我们多 387（多模块拆分 + 自研功能） |
| 去重文件名 | 580 | 967 | — |
| 上游有、我们无同名文件 | — | — | **112** |
| 共有文件名 | 468 | — | 其中 **28** 个缺上游顶层符号 |
| `values/strings.xml` 键 | 2084 | 2538 | 上游独有 **339**，我们独有 **793**（自研功能） |

## 3. 完全未实现（按功能域）

### 3.1 终端模拟器（整套）
上游 `ui/terminal/` 下有 `AnsiParser.kt`、`terminal/canvas/`（4 个）、`terminal/emulator/`（4 个）；我们只有
`TerminalSanitizer` 这类输出清洗与 `pty_bridge.c`，没有可交互终端界面。**属于独立大件**。

### 3.2 helper 子代理作业子系统
上游 `agent/jobs/`：`AgentCallback.kt`、`AgentJobRegistry.kt`、`AgentSettings.kt`、`HelperRunner.kt`、`ModelTierResolver.kt`，
配套 `ui/chat/HelperUi.kt`、`ui/settings/SubAgentsScreen.kt`、`ui/settings/AgentToolsSettingsScreen.kt`，
文案成组缺口：`agent_detail_*` 24、`agent_error_*` 8、`agent_control_*` 7、`agent_settings_*` 5、`agent_strategy_*` 4、
`agent_tools_*` 4、`agent_callback_*` 3、`agent_status_*` 3、`sub_agent_*` 20、`sub_agents_*` 7、`helper_*` 14。
我们已有 `SubAgentActivityTracker` / `SubAgentEventBus` / `AgentTools`（子代理可运行），但**没有上游那套作业注册、
模型分层解析、helper 控制台与设置面**。

### 3.3 Copilot / Codex 接入
上游 `auth/CopilotOAuthManager.kt`、`auth/CopilotDeviceFlow.kt`、`auth/OAuthForegroundGate.kt`、`auth/OAuthLogRedaction.kt`、
`auth/ClaudeCliMimicryHeaders.kt`、`util/CopilotFeatureFlag.kt`、`provider/openai/CodexModelsApi.kt`、
`ui/settings/CopilotDisclaimerDialog.kt`，文案 `copilot_disclaimer_*` 10 个键。我们有其它厂商的 OAuth 管理器，
但没有 Copilot 设备码流程与 Codex 模型发现（`CodexDiscoveryAuthException` 亦缺）。

### 3.4 备份运行侧
上游 `backup/BackupPackageName.kt`、`BackupRecordMapper.kt`、`BackupRunController.kt`、`BackupSubAgents.kt`、
`service/BackupForegroundService.kt`、`debug/BackupDebugMethods.kt`；文案 `backup_notif_*` 10、`backup_delete_*` 9。
我们已有备份目标编辑（批次 1 的 F4）与历史记录（批次 2 的 X1），但**没有备份专用前台服务/通知、
没有「删除备份文件」确认流、没有运行控制器**。

### 3.5 恢复侧收尾
上游 `ui/settings/backup/RestoreEta.kt`、`RestoreReportIssues.kt`；文案 `restore_stop_*` 5。我们缺恢复中止、
剩余时间估算、恢复报告问题入口。

### 3.6 存储管理
上游 `ui/settings/StorageUsageViewModel.kt`（另有 `StorageManagementScreen.kt` 772 行，我们 401 行），
缺 `LogsAndCachesCleaner`、`aggregateSessionStorage`；文案 `storage_batch_*` 6、`storage_clear_logs_caches_*` 6。
=> 我们**没有「清理日志与缓存」**这一能力，也没有批量清理。

### 3.7 容器 DNS 设置
上游 `sandbox/ContainerDns.kt`、`config/fields/ContainerDnsFields.kt`、`ui/sandbox/ContainerNetworkSection.kt`，
文案 `rootfs_dns_*` 14 个键。我们只有 `RootfsManager` 里的 DNS 写入，**没有自动/自定义 DNS 的设置面与配置字段**。

### 3.8 计划任务（详情 / 触发器 / 投递 / 卡片）
上游 `scheduled/PrefilledToolCall.kt`、`ScheduledCompletionTriggers.kt`、`ScheduledTargetDelivery.kt`、
`ScheduledTaskDetailModel.kt`、`ScheduledTaskMarker.kt`、`ui/chat/ScheduledTaskCard.kt`、`ScheduledTaskDetailSheet.kt`；
`ScheduledTask.kt` 里我们缺 `ScheduledTriggerKind`。文案 `scheduled_detail_*` 28、`scheduled_trigger_*` 11、
`scheduled_card_*` 9、`scheduled_delivery_*` 7、`scheduled_status_*` 6、`scheduled_task_field_*` 4、`scheduled_unit_*` 4。
我们已有 CLI 与编辑页（批次 2 的 X11 做了模型解析），但**没有完成触发（on-complete）、投递目标、
详情页与聊天内任务卡片**。

### 3.9 语音链路（ASR 交互与音色角色）
上游 `speech/VoiceAsrRetry.kt`、`SkippableAsrAttempts.kt`、`VadCaptureWindow.kt`、`VadManualStopOutcome.kt`、
`MicInUse.kt`、`ReentrantSafeRelease.kt`、`TtsLocaleResolver.kt`、`VoicePipelineLog.kt`、`correction/ScreenContextBuilder.kt`；
`data/model/VoiceModality.kt` 缺 `VoiceRole`，`VoiceProviderVendors.kt` 缺 `ZhipuVoiceProvider`；文案 `voice_asr_*` 10。
=> ASR 重试/丢弃交互、VAD 采集窗口、音色角色、智谱语音厂商均未实现。

### 3.10 沙箱与浏览器稳定性
上游 `sandbox/GlobalShellThrottle.kt`、`FreshProcessShell.kt`、`ForegroundCommandGroup.kt`、
`browser/BrowserActionGuard.kt`、`BrowserTabPoolRegistry.kt`、`HeadlessRenderHost.kt`、`sandbox/offload/BrowserCliPools.kt`。
注意：我们批次 2 的 X20（并发上限）与上游这些是**不同层次**——上游按「shell 节流 / 新进程 shell / 前台命令组」
细分，我们只做了统一并发上限。

### 3.11 聊天页独立门控件（十余个，可逐个移植）
上游独有：`BubbleRowLocator`、`ContextUsageHint(+Ui)`、`EmptyTurnRecovery`、`EvictionGuard`、`FallbackPulseGate`、
`ImageInputPreflight`、`MathLineBreaker`、`OrphanedRunningToolGate`、`QueuedPromptBatching`、`SelectionDocumentOrder`、
`SendReturnModeMenu`、`SettleAfterInteractionGate`、`TableColumnPlanner`、`TitleCandidates`、`VoiceScreenContext`、
`voice/VoiceSendGate`；对应文案缺口 `thinking_rules_error_*` 等。其中 `SheetScrollGuard`（`ui/components/`）
即既有台账里的 B4-X1，`FallbackPulseGate` 对应 P2 的一部分，`TableColumnPlanner`+`MathLineBreaker` 对应 B4-X6。

### 3.12 诊断、稳定性与杂项
`AppBuildInfo`、`crash/ExitInfoCollector`、`data/ContextOverflowGuard`、`data/PayloadSizeAudit`、`data/DeviceIdentity`、
`data/session/`（4 个）、`data/repository/ModelEntryRanking`、`ModelReconciler`、`diagnostics/`、`share/`、
`ui/onboarding/`（2 个，文案 `onboarding_models_*` 4）、`ui/webview/`、`ui/components/` 的 `DecorativeAnimation`、
`HardwareKeyboard`、`MenuItemIcon`、`ModelSearch`、`ReopenOnResume`。

## 4. 未完全实现（同名文件里缺上游顶层符号）

| 缺符号数 | 上游行数 | 我们行数 | 文件 | 缺的符号 |
| --- | --- | --- | --- | --- |
| 10 | 4570 | 1483 | `ui/chat/StreamingMarkdownText.kt` | LocalMarkdownBlankTapHandler, MdTableAlign, ParsedTable, debugParseBlockRaws, debugParseIncrementalRaws, fence |
| 5 | 190 | 108 | `ui/chat/ChatToolFormatting.kt` | formatStepDuration, friendlyToolTitleFor, stepTimestampFormatter, toolAccentColorInt, toolIconResFor |
| 4 | 657 | 510 | `ui/components/FullscreenImageViewer.kt` | OriginalImage, loadOriginalImage, saveImageToGallery, saveToGallery |
| 4 | 391 | 222 | `ui/chat/StreamingFade.kt` | FadeBirth, FadeColorHolder, FadeShownText, fadeBirthPlan |
| 2 | 1338 | 1222 | `ui/chat/ChatAssistantMessageUI.kt` | UsageCapsule, shouldAutoCollapseThinking |
| 2 | 561 | 887 | `data/db/ChatDao.kt` | PartsJsonRow, SessionSearchRow |
| 2 | 387 | 239 | `ui/chat/ChatModels.kt` | ChatTokenUsage, QueuedPromptOrigin |
| 2 | 1225 | 1813 | `data/repository/ChatRepository.kt` | OffloadToolResult, OffloadToolUse |
| 2 | 17571 | 6291 | `ui/chat/ChatViewModel.kt` | WarmUpTrim, warmUpBudget |
| 2 | 572 | 492 | `ui/components/ModelEntryPicker.kt` | modelEntryPickerSearch, rememberModelEntryPickerSections |
| 2 | 3652 | 3311 | `ui/sessions/SessionListScreen.kt` | EMPTY_GROUP_GRACE_MS, emptyGroupIsStillRelevant |
| 2 | 772 | 401 | `ui/settings/StorageManagementScreen.kt` | LogsAndCachesCleaner, aggregateSessionStorage |
| 1 | 1342 | 467 | `ui/chat/ChatComposerWidgets.kt` | rememberChildLiveToolBlock |
| 1 | 982 | 1232 | `ui/chat/ChatFlatItems.kt` | streamingOverlaySubset |
| 1 | 1084 | 887 | `ui/chat/ChatMiscViews.kt` | LocalToolPreviewEnabled |
| 1 | 464 | 107 | `data/ContextPolicy.kt` | ContextSizeMeter |
| 1 | 436 | 271 | `speech/correction/CorrectionStrategy.kt` | CorrectionLanguageProfile |
| 1 | 366 | 348 | `ui/sessions/GroupPickerSheet.kt` | groupPickerOrder |
| 1 | 812 | 851 | `MainActivity.kt` | keepScreenOnNeedsChange |
| 1 | 1711 | 1672 | `ui/chat/MinisTextKitGesture.kt` | inlineSelectionActionCount |
| 1 | 1314 | 1245 | `ui/chat/MinisTextKitSelection.kt` | sameBlockOrder |
| 1 | 437 | 452 | `ui/settings/ModelEntryDetailScreen.kt` | buildModelOverrides |
| 1 | 658 | 513 | `data/model/ProviderConfig.kt` | ThinkingLevelSerializer |
| 1 | 3914 | 2724 | `data/repository/ProviderRepository.kt` | CodexDiscoveryAuthException |
| 1 | 408 | 284 | `scheduled/ScheduledTask.kt` | ScheduledTriggerKind |
| 1 | 64 | 41 | `ui/sessions/SearchHighlight.kt` | snippetAround |
| 1 | 220 | 122 | `data/model/VoiceModality.kt` | VoiceRole |
| 1 | 1151 | 991 | `provider/voice/VoiceProviderVendors.kt` | ZhipuVoiceProvider |

重点三项：

- `ui/chat/StreamingMarkdownText.kt`：上游 4570 行、我们 1483 行，缺 `ParsedTable` / `MdTableAlign` /
  `parseTableAlignments`（表格对齐解析）、`fencedCodeContent`、`safeInlineSplitOffset`、`isSealedBoundary`、
  `LocalMarkdownBlankTapHandler` 等 —— 流式 Markdown 的增量解析与表格支持不完整。
- `ui/components/FullscreenImageViewer.kt`：缺 `saveToGallery` / `saveImageToGallery` / `loadOriginalImage` /
  `OriginalImage` —— 全屏看图**不能存相册、不能看原图**。
- `ui/chat/StreamingFade.kt`：缺 `fadeBirthPlan` / `FadeBirth` / `FadeShownText` / `FadeColorHolder` ——
  流式淡入的「出生计划」策略未实现。

## 5. 已核实为差异化实现（不是缺口，避免误判）

| 项 | 上游形态 | 我们的形态 |
| --- | --- | --- |
| 本地文件夹备份投递 | `backup/remote/LocalFolderDelivery.kt` | `backup/PhoneBackupFolderStore.kt` + `data/MountedFoldersStore.kt` + `io/VerifiedCopy.kt`（SAF 已接入） |
| 子代理运行 | `agent/jobs/*` | `service/SubAgentActivityTracker.kt` + `SubAgentEventBus.kt` + `tools/AgentTools.kt`（可运行，缺控制台/注册表） |
| 终端能力 | `ui/terminal/*` | `sandbox/PersistentShell.kt` + `pty_bridge.c` + `TerminalSanitizer`（有 shell 与清洗，缺终端 UI） |
| 厂商 OAuth | `CopilotOAuthManager` 等 | `OpenAIOAuthManager` / `ClaudeOAuthManager` / `GeminiOAuthManager` / `KimiOAuthManager` / `XAIOAuthManager`（多厂商已支持，缺 Copilot/Codex） |
| 用量与上下文 | 上游 `ContextSizeMeter` / `ContextUsageHint` | 我们 `ui/chat/TokenUsageSheet.kt` + 用量页（`UsageStatsScreen` / `UsageTrendChart`），形制不同 |
| 上下文策略 | `data/ContextPolicy.kt` 464 行 | 我们 107 行，另有压缩/裁剪逻辑分散在其它文件 |
| Markdown 导出/分享 | `share/*` | 我们 `share/ChatExporter.kt` 等 |

## 6. 与既有台账的关系

`docs/upstream-beta5-triage.md` §6/§7 记的是**逐条点名过的** 19 项欠账（批次 2 后剩 13 项）。
本次审查把面扩到「全量上游对比」，发现的缺口**远多于**那份台账：台账里的 F2/X7/P1/F3/F5/F7/P2/B4-* 只是其中一部分，
而 3.1–3.12 里的终端、helper 作业、Copilot、备份运行侧、存储清理、容器 DNS、计划任务详情、语音链路等
都还没有进过任何批次。

## 7. 建议分批（按用户可见度 / 可移植性排序）

| 批 | 内容 | 依据 | 形态 |
| --- | --- | --- | --- |
| A | Markdown 表格对齐 + 列规划（B4-X6 的全量）+ 流式增量解析补齐 | §4 最大单点缺口 | 可移植 |
| B | 全屏看图存相册 / 原图 + 存储清理（日志缓存/批量） | 用户天天碰得到 | 可移植 |
| C | 计划任务详情页 + 完成触发 + 投递目标 + 聊天内任务卡片 | 文案缺口 69 键 | 可移植（需适配我们的 CLI/编辑页） |
| D | helper/子代理控制台（作业注册表 + 分层解析 + 设置面 + helper UI） | 文案缺口 58+27+14 键 | 部分可移植 |
| E | 备份通知/前台服务/运行控制器 + 恢复中止/ETA/报告 | 与既有备份线衔接 | 可移植 |
| F | 容器 DNS 设置（字段 + 设置面） | 文案 14 键，改动面小 | 可移植 |
| G | Copilot 登录 + Codex 模型发现 + 免责声明 | 新厂商接入 | 可移植（注意鉴权合规） |
| H | 语音：ASR 重试/丢弃、VAD 采集窗口、音色角色、智谱语音 | 与 X7 语音纠正同域 | 可移植 |
| I | 聊天页独立门控件批量移植（SheetScrollGuard / FallbackPulseGate / EvictionGuard / EmptyTurnRecovery / SettleAfterInteractionGate / OrphanedRunningToolGate / ImageInputPreflight / BubbleRowLocator / VoiceSendGate / SendReturnModeMenu / SelectionDocumentOrder / QueuedPromptBatching / TitleCandidates / ContextUsageHint + UsageCapsule / shouldAutoCollapseThinking） | 每个 25–150 行、互相独立 | 可移植 |
| J | 沙箱细分限流（GlobalShellThrottle / FreshProcessShell / ForegroundCommandGroup）、浏览器稳定性（ActionGuard / TabPoolRegistry / HeadlessRenderHost） | 稳定性 | 可移植 |
| K | 终端模拟器（ANSI + canvas + emulator） | 独立大件 | 可移植但量大 |
| L | 诊断与杂项（BuildInfo / ExitInfo / ContextOverflowGuard / PayloadSizeAudit / DeviceIdentity / Onboarding / ModelSearch / HardwareKeyboard / DecorativeAnimation / ReopenOnResume / MenuItemIcon / ModelEntryRanking / ModelReconciler / data/session 4 件） | 体感与可维护性 | 可移植 |

## 附录 A：上游有、我们无同名文件（112）

- `agent/jobs/AgentCallback.kt`
- `agent/jobs/AgentJobRegistry.kt`
- `service/AgentNotificationContent.kt`
- `agent/jobs/AgentSettings.kt`
- `tools/AgentToolSwitch.kt`
- `ui/settings/AgentToolsSettingsScreen.kt`
- `ui/terminal/AnsiParser.kt`
- `AppBuildInfo.kt`
- `service/BackgroundInterruptionTracker.kt`
- `debug/BackupDebugMethods.kt`
- `service/BackupForegroundService.kt`
- `backup/BackupPackageName.kt`
- `backup/BackupRecordMapper.kt`
- `backup/BackupRunController.kt`
- `backup/BackupSubAgents.kt`
- `browser/BrowserActionGuard.kt`
- `sandbox/offload/BrowserCliPools.kt`
- `browser/BrowserTabPoolRegistry.kt`
- `ui/chat/BubbleRowLocator.kt`
- `auth/ClaudeCliMimicryHeaders.kt`
- `provider/openai/CodexModelsApi.kt`
- `sandbox/ContainerDns.kt`
- `config/fields/ContainerDnsFields.kt`
- `ui/sandbox/ContainerNetworkSection.kt`
- `data/ContextOverflowGuard.kt`
- `ui/chat/ContextUsageHint.kt`
- `ui/chat/ContextUsageHintUi.kt`
- `auth/CopilotDeviceFlow.kt`
- `ui/settings/CopilotDisclaimerDialog.kt`
- `util/CopilotFeatureFlag.kt`
- `auth/CopilotOAuthManager.kt`
- `ui/components/DecorativeAnimation.kt`
- `data/DeviceIdentity.kt`
- `ui/chat/EmptyTurnRecovery.kt`
- `ui/chat/EvictionGuard.kt`
- `crash/ExitInfoCollector.kt`
- `ui/chat/FallbackPulseGate.kt`
- `sandbox/ForegroundCommandGroup.kt`
- `sandbox/FreshProcessShell.kt`
- `sandbox/GlobalShellThrottle.kt`
- `ui/components/HardwareKeyboard.kt`
- `browser/HeadlessRenderHost.kt`
- `agent/jobs/HelperRunner.kt`
- `ui/chat/HelperUi.kt`
- `ui/chat/ImageInputPreflight.kt`
- `backup/remote/LocalFolderDelivery.kt`
- `ui/chat/MathLineBreaker.kt`
- `ui/components/MenuItemIcon.kt`
- `speech/MicInUse.kt`
- `data/repository/ModelEntryRanking.kt`
- `data/repository/ModelReconciler.kt`
- `ui/components/ModelSearch.kt`
- `agent/jobs/ModelTierResolver.kt`
- `auth/OAuthForegroundGate.kt`
- `auth/OAuthLogRedaction.kt`
- `ui/onboarding/OnboardingModelFetch.kt`
- `ui/onboarding/OnboardingScreen.kt`
- `provider/OpenCodeSessionHeader.kt`
- `data/session/OrphanChildSweeper.kt`
- `ui/chat/OrphanedRunningToolGate.kt`
- `data/PayloadSizeAudit.kt`
- `scheduled/PrefilledToolCall.kt`
- `sandbox/PtyBridge.kt`
- `ui/chat/QueuedPromptBatching.kt`
- `provider/openai/ReasoningDetails.kt`
- `speech/ReentrantSafeRelease.kt`
- `ui/components/ReopenOnResume.kt`
- `ui/settings/backup/RestoreEta.kt`
- `ui/settings/backup/RestoreReportIssues.kt`
- `scheduled/ScheduledCompletionTriggers.kt`
- `scheduled/ScheduledTargetDelivery.kt`
- `ui/chat/ScheduledTaskCard.kt`
- `scheduled/ScheduledTaskDetailModel.kt`
- `ui/chat/ScheduledTaskDetailSheet.kt`
- `scheduled/ScheduledTaskMarker.kt`
- `speech/correction/ScreenContextBuilder.kt`
- `ui/chat/SelectionDocumentOrder.kt`
- `ui/chat/SendReturnModeMenu.kt`
- `data/session/SessionDeleter.kt`
- `share/SessionExporter.kt`
- `sandbox/SessionMounts.kt`
- `data/repository/SessionSearch.kt`
- `data/session/SessionStorage.kt`
- `data/session/SessionTree.kt`
- `ui/chat/SettleAfterInteractionGate.kt`
- `ui/components/SheetScrollGuard.kt`
- `sandbox/ShellExecutionStrategy.kt`
- `sandbox/ShellExitCode.kt`
- `data/repository/SkillDiskSignature.kt`
- `speech/SkippableAsrAttempts.kt`
- `sandbox/StderrDrain.kt`
- `ui/settings/StorageUsageViewModel.kt`
- `diagnostics/StreamJitterProbe.kt`
- `data/model/SubAgentDefinition.kt`
- `ui/settings/SubAgentsScreen.kt`
- `ui/chat/TableColumnPlanner.kt`
- `ui/terminal/emulator/TerminalBuffer.kt`
- `ui/terminal/canvas/TerminalCanvasView.kt`
- `ui/terminal/emulator/TerminalEmulator.kt`
- `ui/terminal/canvas/TerminalInputView.kt`
- `ui/terminal/canvas/TerminalKeyMapper.kt`
- `ui/terminal/canvas/TerminalNativeView.kt`
- `ui/terminal/emulator/TerminalTypes.kt`
- `ui/chat/TitleCandidates.kt`
- `speech/TtsLocaleResolver.kt`
- `speech/VadCaptureWindow.kt`
- `speech/VadManualStopOutcome.kt`
- `speech/VoiceAsrRetry.kt`
- `speech/VoicePipelineLog.kt`
- `ui/chat/VoiceScreenContext.kt`
- `ui/chat/voice/VoiceSendGate.kt`
- `ui/webview/WebViewRenderProcess.kt`

## 附录 B：339 个上游独有字符串键（按前两段聚类）

| 键数 | 组 | 示例 |
| --- | --- | --- |
| 28 | `scheduled_detail_*` | `scheduled_detail_close`, `scheduled_detail_collapse`, `scheduled_detail_copied` |
| 24 | `agent_detail_*` | `agent_detail_current_tool`, `agent_detail_delivery`, `agent_detail_delivery_value` |
| 20 | `sub_agent_*` | `sub_agent_builtin_badge`, `sub_agent_builtin_description`, `sub_agent_builtin_name` |
| 14 | `rootfs_dns_*` | `rootfs_dns_auto`, `rootfs_dns_auto_desc`, `rootfs_dns_custom` |
| 11 | `scheduled_trigger_*` | `scheduled_trigger_daily`, `scheduled_trigger_day_separator`, `scheduled_trigger_from` |
| 10 | `backup_notif_*` | `backup_notif_channel`, `backup_notif_done_text`, `backup_notif_done_title` |
| 10 | `copilot_disclaimer_*` | `copilot_disclaimer_accept`, `copilot_disclaimer_cancel`, `copilot_disclaimer_p1` |
| 10 | `voice_asr_*` | `voice_asr_discard`, `voice_asr_failed_message`, `voice_asr_failed_title` |
| 9 | `backup_delete_*` | `backup_delete_files_confirm_action`, `backup_delete_files_confirm_body`, `backup_delete_files_confirm_title` |
| 9 | `helper_status_*` | `helper_status_cancelled`, `helper_status_completed`, `helper_status_failed` |
| 9 | `scheduled_card_*` | `scheduled_card_cancel`, `scheduled_card_cancel_confirm_body`, `scheduled_card_cancel_confirm_keep` |
| 8 | `agent_error_*` | `agent_error_auth_failed`, `agent_error_cancelled`, `agent_error_context_limit` |
| 7 | `agent_control_*` | `agent_control_checked`, `agent_control_checked_n`, `agent_control_generic` |
| 7 | `scheduled_delivery_*` | `scheduled_delivery_child_other`, `scheduled_delivery_child_this`, `scheduled_delivery_new` |
| 7 | `sub_agents_*` | `sub_agents_add`, `sub_agents_limit_reached`, `sub_agents_list_footer` |
| 6 | `scheduled_status_*` | `scheduled_status_completed`, `scheduled_status_deleted`, `scheduled_status_disabled` |
| 6 | `storage_batch_*` | `storage_batch_clear_button`, `storage_batch_clear_message`, `storage_batch_clear_result` |
| 6 | `storage_clear_*` | `storage_clear_logs_caches_button`, `storage_clear_logs_caches_confirm`, `storage_clear_logs_caches_confirm_message` |
| 5 | `agent_settings_*` | `agent_settings_allow`, `agent_settings_allow_footer`, `agent_settings_not_configured` |
| 5 | `helper_*` | `helper_close`, `helper_label`, `helper_resume` |
| 5 | `restore_stop_*` | `restore_stop_action`, `restore_stop_cancel`, `restore_stop_confirm` |
| 4 | `agent_strategy_*` | `agent_strategy_default_group`, `agent_strategy_inherited`, `agent_strategy_pinned` |
| 4 | `agent_tools_*` | `agent_tools_agents`, `agent_tools_agents_footer`, `agent_tools_browser` |
| 4 | `onboarding_models_*` | `onboarding_models_add_manually`, `onboarding_models_fetch_failed`, `onboarding_models_fetch_timeout` |
| 4 | `scheduled_task_*` | `scheduled_task_field_thinking`, `scheduled_task_field_trigger`, `scheduled_task_target_agent` |
| 4 | `scheduled_unit_*` | `scheduled_unit_day`, `scheduled_unit_hour`, `scheduled_unit_min` |
| 4 | `thinking_rules_*` | `thinking_rules_error_off_value_required`, `thinking_rules_error_path_required`, `thinking_rules_error_path_reserved` |
| 3 | `agent_callback_*` | `agent_callback_progress`, `agent_callback_result`, `agent_callback_turn` |
| 3 | `agent_status_*` | `agent_status_never_started`, `agent_status_queued`, `agent_status_starting` |
| 3 | `backup_dest_*` | `backup_dest_add_local_folder`, `backup_dest_kind_local_folder`, `backup_dest_local_folder_no_permission` |
| 3 | `log_detail_*` | `log_detail_level_footer`, `log_detail_level_info`, `log_detail_level_verbose` |
| 3 | `model_picker_*` | `model_picker_edit_provider`, `model_picker_no_model_selected`, `model_picker_search_capped` |
| 3 | `onboarding_provider_*` | `onboarding_provider_api_key_label`, `onboarding_provider_subtitle`, `onboarding_provider_title` |
| 3 | `provider_oauth_*` | `provider_oauth_sign_in_again`, `provider_oauth_sign_in_expired`, `provider_oauth_sign_in_expired_detail` |
| 3 | `restore_eta_*` | `restore_eta_almost_done`, `restore_eta_minutes`, `restore_eta_seconds` |
| 3 | `send_return_*` | `send_return_mode_a11y_action`, `send_return_mode_newline`, `send_return_mode_sends` |
| 2 | `appearance_tool_*` | `appearance_tool_preview_footer`, `appearance_tool_preview_title` |
| 2 | `backup_device_*` | `backup_device_name`, `backup_device_name_footer` |
| 2 | `backup_report_*` | `backup_report_notices`, `backup_report_notices_footer` |
| 2 | `backup_save_*` | `backup_save_failed`, `backup_save_to_device` |
| 2 | `backup_saved_*` | `backup_saved_to_device`, `backup_saved_to_device_log` |
| 2 | `backup_share_*` | `backup_share_failed`, `backup_share_package` |
| 2 | `chat_compact_*` | `chat_compact_above_confirm`, `chat_compact_above_dialog_body` |
| 2 | `copilot_login_*` | `copilot_login_instructions`, `copilot_login_title` |
| 2 | `helper_resume_*` | `helper_resume_failed`, `helper_resume_queued` |
| 2 | `onboarding_welcome_*` | `onboarding_welcome_subtitle`, `onboarding_welcome_title` |
| 2 | `overlay_*` | `overlay_streaming`, `overlay_thinking` |
| 2 | `provider_refresh_*` | `provider_refresh_auth_failed_body`, `provider_refresh_auth_failed_title` |
| 2 | `settings_agent_*` | `settings_agent_tools`, `settings_agent_tools_subtitle` |
| 2 | `token_usage_*` | `token_usage_output_speed`, `token_usage_section_speed` |
| 2 | `voice_mic_*` | `voice_mic_in_use`, `voice_mic_unavailable` |
| 1 | `about_built_*` | `about_built_format` |
| 1 | `add_provider_*` | `add_provider_subtitle_copilot` |
| 1 | `agent_model_*` | `agent_model_group_unavailable` |
| 1 | `agent_open_*` | `agent_open_detail` |
| 1 | `agent_pinned_*` | `agent_pinned_group_unavailable` |
| 1 | `agent_*` | `agent_working` |
| 1 | `backup_hide_*` | `backup_hide_passphrase` |
| 1 | `backup_no_*` | `backup_no_destination_hint` |
| 1 | `backup_restoring_*` | `backup_restoring_percent` |
| 1 | `backup_result_*` | `backup_result_local_pending` |
| 1 | `backup_saving_*` | `backup_saving_to_device` |
| 1 | `backup_show_*` | `backup_show_passphrase` |
| 1 | `bg_service_*` | `bg_service_notification_text_running` |
| 1 | `chat_context_*` | `chat_context_usage_hint` |
| 1 | `chat_longpress_*` | `chat_longpress_compact_above` |
| 1 | `common_*` | `common_next` |
| 1 | `context_overflow_*` | `context_overflow_selfheal_notice` |
| 1 | `export_summary_*` | `export_summary_sessions` |
| 1 | `file_open_*` | `file_open_failed_toast` |
| 1 | `helper_start_*` | `helper_start_failed` |
| 1 | `image_input_*` | `image_input_rejected_hint` |
| 1 | `log_section_*` | `log_section_detail_level` |
| 1 | `model_group_*` | `model_group_detail_stale_member` |
| 1 | `model_not_*` | `model_not_listed_by_provider` |
| 1 | `mount_picker_*` | `mount_picker_unavailable` |
| 1 | `oauth_completing_*` | `oauth_completing_sign_in` |
| 1 | `oauth_sign_*` | `oauth_sign_in_failed` |
| 1 | `oauth_status_*` | `oauth_status_signing_in` |
| 1 | `oauth_waiting_*` | `oauth_waiting_for_authorization` |
| 1 | `onboarding_get_*` | `onboarding_get_started` |
| 1 | `onboarding_save_*` | `onboarding_save_api_key` |
| 1 | `onboarding_*` | `onboarding_saved` |
| 1 | `onboarding_search_*` | `onboarding_search_models` |
| 1 | `overlay_task_*` | `overlay_task_index` |
| 1 | `restore_browse_*` | `restore_browse_delete_note` |
| 1 | `rootfs_network_*` | `rootfs_network_section` |
| 1 | `sessionlist_welcome_*` | `sessionlist_welcome_restore_backup` |
| 1 | `settings_*` | `settings_agents` |
| 1 | `settings_agents_*` | `settings_agents_subtitle` |
| 1 | `storage_overview_*` | `storage_overview_logs_caches` |
| 1 | `tool_pager_*` | `tool_pager_position` |
| 1 | `voice_send_*` | `voice_send_still_transcribing` |

## 附录 C：L4 反查中「全仓完全不存在」的 91 个上游特征符号

```
saveToGallery                       loadOriginalImage                   AnsiParser                          CopilotOAuthManager
CopilotDeviceFlow                   CodexModelsApi                      ClaudeCliMimicryHeaders             HelperRunner
AgentJobRegistry                    ModelTierResolver                   HelperUi                            parseTableAlignments
MdTableAlign                        ParsedTable                         TableColumnPlanner                  MathLineBreaker
snippetAround                       LanguageProfile                     UsageCapsule                        shouldAutoCollapseThinking
ContextSizeMeter                    ContextUsageHint                    ScheduledTriggerKind                ScheduledCompletionTriggers
ScheduledTargetDelivery             PrefilledToolCall                   VoiceRole                           ZhipuVoiceProvider
SkippableAsrAttempts                VoiceAsrRetry                       BackupPackageName                   BackupSubAgents
LocalFolderDelivery                 BackupForegroundService             SheetScrollGuard                    FallbackPulseGate
EvictionGuard                       EmptyTurnRecovery                   SettleAfterInteractionGate          OrphanedRunningToolGate
ImageInputPreflight                 DeviceIdentity                      GlobalShellThrottle                 FreshProcessShell
ContextOverflowGuard                PayloadSizeAudit                    ExitInfoCollector                   SubAgentsScreen
AgentToolsSettingsScreen            StorageUsageViewModel               RestoreEta                          RestoreReportIssues
ModelEntryRanking                   ModelReconciler                     OffloadToolUse                      OffloadToolResult
WarmUpTrim                          streamingOverlaySubset              fadeBirthPlan                       buildModelOverrides
groupPickerOrder                    keepScreenOnNeedsChange             formatStepDuration                  friendlyToolTitleFor
toolIconResFor                      toolAccentColorInt                  DecorativeAnimation                 ModelSearch
ReopenOnResume                      MenuItemIcon                        HeadlessRenderHost                  BrowserTabPoolRegistry
BrowserActionGuard                  ContainerDns                        LocalToolPreviewEnabled             rememberChildLiveToolBlock
QueuedPromptOrigin                  ChatTokenUsage                      modelEntryPickerSearch              rememberModelEntryPickerSections
emptyGroupIsStillRelevant           aggregateSessionStorage             LogsAndCachesCleaner                sendReturnMode
SelectionDocumentOrder              BubbleRowLocator                    VoiceSendGate                       ContainerNetworkSection
ContainerDnsFields                  BackupDebugMethods                  AppBuildInfo
```
