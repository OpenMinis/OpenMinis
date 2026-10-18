# 上游 1.15-beta.5 分诊清单

> 只读分析，未改动任何生产代码。每条判定都附本仓库实码证据，供你决定动工范围。
>
> 分析时间：2026-10-18　基线：本仓库 `main` = `a7630b96`（2.0.60）
> 上游参照：`0-beta-build34` = 1.15-beta.5（发布 2026-10-17）

---

## 0. 先决结论（影响整个计划）

**上游 beta.5 的源码没有发布，不存在可以 merge / cherry-pick 的 diff。**

| 检查项 | 结果 |
| --- | --- |
| tag `0-beta-build34` 的 tag 对象 `8c1592c6` | 指向 commit `b4c0661d`（tagger 时间 2026-10-17） |
| `b4c0661d` 是什么 | `Merge pull request #408 from OpenMinis/v1.14`「v1.14: iOS and Android 1.14 release source」，**commit 时间 2026-09-29** |
| tag `1.14` 指向 | 同一个 `b4c0661d` |
| GitHub compare `1.14...0-beta-build34` | `"status":"identical"`、`total_commits: 0`、`files: []` |
| `0-beta-build30/31/32/33/34` | 全部指向同一个 `b4c0661d` |
| 上游 `main` HEAD | `b4c0661d` |
| 上游分支 | 只有 `main` / `v1.10` / `v1.12` / `v1.13` / `v1.14`，**没有 1.15 分支** |

上游公开仓库的源码镜像停在 **1.14**；beta.1–beta.5 的构建源码不在公开镜像里，五个 beta tag 都只是打在 1.14 那个提交上。因此：

- ❌ 不能 `git merge upstream`，也不能 cherry-pick beta.5 的提交。
- ✅ 唯一的"上游规格"是 **release notes 文案**（中英双语，逐条可读）。
- 下面每条都是**按文案 + 本仓库实码 grep** 判定的，不是按补丁判定的。

**本仓库与上游的代码关系（实测）：**

- 本仓库是 shallow clone，5 个 graft 边界（`09fc1999`、`32083363`、`4ef29002`、`9cf3a855`、`a1a4c5b8`），最深到 `4ef29002`（上游 **1.13** 合并提交）；`b4c0661d`（1.14）**不是** HEAD 的祖先。
- `git diff upstream-beta5(1.14) HEAD`：3530 文件变化，大头是本仓库删掉的 iOS 与 deps（`src/ios/*`、`deps/lame-3.100/*`）。
- 只看 Android：1.14 有而本仓库没有的 **129** 个文件、本仓库独有 **467** 个、两边都有但改了 **309** 个 → Android 树是**独立演进**，不能按文件对齐。
- 本仓库语言集 = 1.14 的语言集 **减去 Croatian（`values-hr`）**。
- `values/strings.xml`：本仓库 2511 条，上游 1.14 是 2076 条（本仓库更大，不是简单落后）。

> 结论：这是**手工重做**的工作量，不是同步。

---

## 1. 分诊总表

beta.5 共 **32 项**（8 新功能 + 21 修复 + 3 视觉打磨），beta.4 遗留 **11 项**（3 + 7 + 1）。

| 桶 | beta.5 | beta.4 余项 | 含义 |
| --- | --- | --- | --- |
| **A 已有 / 跳过** | 6 | 4 | 本仓库已实现，不用动 |
| **B 部分已有（补差）** | 5 | 0 | 骨架在，缺文案里那部分行为 |
| **C 需要移植** | 20 | 5 | 本仓库没有，Android 适用 |
| **D 不适用 / 需实机** | 1 | 2 | 本仓库没有该机制，或只能实机判定 |

---

## 2. beta.5 逐项分诊

### 2.1 新功能（8）

| # | 上游条目 | 判定 | 证据 | 说明 |
| --- | --- | --- | --- | --- |
| F1 | 隐藏模型收进「已隐藏模型 (N)」可折叠组；长按任意模型可删除（含 API 下发的） | **B 部分** | `ui/settings/ModelEntryDetailScreen.kt:73,182,353-355` 有 `isHidden` 开关；`:350` 是"隐藏后不出现在模型列表"的说明文案；`:435` 只做"清覆盖 + 取消隐藏" | 缺可折叠分组；缺对 API 下发模型的删除入口 |
| F2 | 用量页 Today / 7 天 / 30 天 / 全部 + 汇总 + 堆叠趋势 + 按服务商/模型下钻 | **B 部分** | 已有：时间筛选 `UsageTrendChart.kt:41`、汇总 `UsageStatsScreen.kt:92,326-332`、按服务商分组 `:221`、按模型展开下钻 `:362`、占比条 `:430` | 唯一缺口：趋势图是**单序列**日柱，不是按服务商/模型**堆叠**（全文件无 stack 相关实现） |
| F3 | 停止按钮：有子代理或定时任务时询问「仅停止当前工具 / 全部停止」；长按可选「立即停止 / 停止前确认」；设置 > 外观 | **C 移植** | 全仓库无 `Stop All` / `Stop Current Tool` / `stop_confirm` 任何匹配 | 需新增交互 + 设置项 |
| F4 | 已保存的备份服务器可直接编辑（改名 / 换文件夹 / 改连接） | **C 移植** | `ui/settings/backup/` 无 rename / edit-server 逻辑 | 现在只能删了重加 |
| F5 | 新增语言：意大利语、荷兰语、匈牙利语、捷克语、波斯语 | **C 移植** | `values-*` 无 `it/nl/hu/cs/fa`；连 1.14 就有的 `values-hr` 也缺 | 纯翻译，单项工作量最大 |
| F6 | 定时任务页新增开关，隐藏首页时钟图标数字角标 | **C 移植** | 无 `scheduledBadge` / `showBadge` 匹配 | 小改动 |
| F7 | minis-config 覆盖子代理名单 + 另外 19 项设置 | **C 移植** | `config/collections/` 只有 5 个（EnvVars/Groups/Models/Providers/ThinkingRules，与 1.14 相同）；`config/fields/` 无子代理相关字段 | 需扩 ConfigBridge |
| F8 | `android-speech transcribe --engine system\|provider\|auto`，默认仍系统识别 | **B 部分** | UI 层已有引擎选择：`ui/chat/voice/InlineVoiceInputPanel.kt:329`（`system` / `provider`）；CLI 层 `sandbox/offload/SpeechOffloadHandler.kt:433-459` 的 HELP 里**没有 `--engine`** | 只缺 CLI 参数贯通 |

### 2.2 修复（21）

| # | 上游条目 | 判定 | 证据 | 说明 |
| --- | --- | --- | --- | --- |
| X1 | 本地文件夹备份不再写几千行进度；旧记录加载时清理；历史文件原子写 | **C 移植** | `backup/BackupHistory.kt:48,101` 的 `log: List<LogEntry>` **无条数上限**；`:118` 只在**过期**时清理重写；无历史文件原子写 | 本仓库存在同款设计，会累积进度行 |
| X2 | 备份 >4GB 或 >65535 文件改用 ZIP64 | **A 已有** | `backup/BackupZip.kt:30,39,43` 明确 ZIP64 与 `MAX_MEMBER_BYTES = 0xFFFFFFFF-1`，注释还写了 iOS 侧读取注意 | 跳过 |
| X3 | 恢复：以备份设置为准（含 API Key）、同 ID 恢复、同名合并、不删本机独有、计数真实；Android 备份可被 iOS 读 | **A 已有** | `backup/BackupImporter.kt:24` 明确"按 id 匹配、updatedAt 新者胜"的合并模式；`:340-343,404-407,517-518` 逐类实现；`:723-768` provider_config 保序并集合并；`backup/BackupFormat.kt:352-374` 与 iOS 同构的 JSONL 信封（注释专门强调字段名不能加 snake_case，否则跨平台包解不开） | 跨端兼容是刻意做的 |
| X4 | 附件菜单「添加文件」「照片和视频」及其他选择器打不开时提示而非闪退 | **C 移植** | `ui/chat/ChatScreen.kt:6452-6489` 三个入口（照相 / 相册 / 文件）直接调 `launcher.launch(...)`，**无 try/catch**；而其它选择器有兜底：`MainActivity.kt:839`、`webapp/WebAppActivity.kt:434,456`、`ui/browser/BrowserExternalSchemeHandler.kt:214,247`、`sandbox/offload/OpenOffloadHandler.kt:65` | 缺口正好在附件菜单这条路径 |
| X5 | 会话在不同 API 类型间切换后，不再因另一种 API 的工具 ID 失败 | **C 移植** | 全仓库无 `apiType` / `wireApi` / `apiStyle` 概念，也无跨 API 工具 ID 兼容层 | 需先实测复现，但兼容层确实不存在 |
| X6 | 自定义 OpenAI 兼容端点采用 /v1/models 返回的上下文长度与最大输出，不再按 128K 猜 | **C 移植** | `provider/openai/OpenAIModelsApi.kt` 不解析 `context_length`（对比 `provider/openrouter/OpenRouterModelsApi.kt:105` 解析了）；`ui/chat/ChatViewModel.kt:761`、`ui/settings/ModelGroupDetailScreen.kt:559,569,701` 明确在用 128K 启发式默认 | 小改动、直接改善长上下文用量 |
| X7 | AI 语音纠正失败时换下一个模型（最多 3 个，遵循分组回退），全失败说明原因 | **C 移植** | `speech/correction/VoiceCorrectionEngine.kt:79` 只做一次 `strategy.correct(...)`，无多模型回退循环；`CorrectionStrategy.kt` 也无候选模型列表 | 需加回退链 |
| X8 | Codex：刷新令牌保留 id_token 并更新 account id | **A 已有** | `auth/OpenAIOAuthManager.kt:140-142`（首次授权）与 `:231-234`（刷新）都解析 `id_token`；`:320` 有解析失败兜底日志 | 跳过 |
| X9 | 分享到 Minis 的文件/文字一定落在可见对话，并 toast 提示位置 | **A 已有** | `share/ShareReceiverActivity.kt:22-29` 明确"重新拉起 MainActivity，由 `shared_content=true` 交给 ShareCoordinator"；`:320-391` 有 `startActivity` 失败兜底；`share/ShareCoordinator.kt:13` 30s TTL、`:205` 过期 toast；`MainActivity.kt:491` 提示去 App 查看 | 落点链路完整 |
| X10 | 新对话里未发送的文字，15 分钟内再点新对话会恢复 | **C 移植** | 无任何未发送草稿恢复实现 | 小改动 |
| X11 | `minis-scheduled --model` 在创建任务时就解析模型 | **C 移植** | `sandbox/offload/ScheduledTaskOffloadHandler.kt:101` 把 `--model` 原始字符串直接写进 `modelId`，`manager.create(task)` 不做解析；解析推迟到运行时（`scheduled/ScheduledAgentRunner.kt:211-217`） | 上游改成创建时解析，能提前暴露无效模型 |
| X12 | 新一轮开始、用户正在读上一条回复时列表不再跳动 | **C 移植** | 无 `StreamFollowDeadband` 等价物（该文件在上游 1.14 独有清单里）；全仓库无跟随死区实现 | 中改动，涉及滚动状态机 |
| X13 | 屏幕上没有工具状态条时，最后一条消息下不再留空白 | **A 已有** | `ui/chat/ChatScreen.kt:2866` `toolBarHeightPx`、`:4631` 实际测量、`:4649` `SideEffect { toolBarHeightPx = 0 }`、`:4682` 传给底部避让；`:2879-2885` 注释记录了"早期版本把 reserve 错挂在高度上"的同类回归 | 已针对性实现 |
| X14 | 工具输出里的超长单行不再让预览小窗卡住应用 | **C 移植** | 有 `tools/ToolOutputPolicy.kt`、`tools/ToolOutputSpill.kt`（落盘/截断），但**预览小窗**的长行保护无实现 | 小改动，需实测复现 |
| X15 | 重新打开压缩过的会话不再出现琥珀色上下文光晕 | **D 不适用** | `ui/chat/` 全目录无 `glow` 匹配；`ui/chat/ContextRing.kt:37` 是**常驻**用量环（`RingYellow = 0xFFF2C14E`，随占用率渐变色），不是"重开压缩会话"的一次性光晕 | 本仓库没有该动效，本条无对象 |
| X16 | 悬浮胶囊始终完整在屏内（避状态栏、避横屏手势条），位置按横竖屏分别记忆 | **C 移植** | 无 `perOrientation` / `floatingCapsulePosition` / `capsuleOffset` 匹配 | 需加位置持久化 |
| X17 | Markdown 文件预览里的链接可打开；网页链接走应用内浏览器 | **C 移植** | `ui/sandbox/FilePreviewScreen.kt:232,419,440` 有 Markdown 预览（走 `MarkdownDocument`），但无链接点击处理 | 小改动 |
| X18 | `browser_use` 在页面无 body / 脚本抛错时给明确错误，而不是 "null" | **C 移植** | `browser/BrowserUseManager.kt:682` `defaultResponse = "null"`、`:1492` `result ?: "null"` —— **正是上游修的那个 bug**；只有 fetch 路径有明确错误（`:1249` "Fetch returned no body (status=…)"） | 修法清楚，风险低 |
| X19 | `android-speech`：status/languages/错误说明到位；调用方退出后停止录音且不上传；默认常规识别器，失败回退一次 | **A 已有** | `sandbox/offload/SpeechOffloadHandler.kt:309-326` 所有退出路径 destroy-once（注释写明"超时路径曾泄漏 recognizer，导致后续 ERROR_TOO_MANY_REQUESTS"）；`:330-343` S+ 优先设备识别器、失败回退常规识别器；`:37-40` 有 `languages` / `status` 子命令 | 与上游目标一致 |
| X20 | 高负载稳定性：卡住的权限弹窗 5 分钟后放弃；offload 与文件工具线程池加上限 | **B 部分** | `offload/OffloadPermissionManager.kt:149,152` 超时是 **120 秒**；线程池只有 `mcp/server/McpHttpServer.kt:83` 的 `newFixedThreadPool(4)` 与若干单线程 executor（`AgentForegroundService.kt:179`、`accessibility/AccessibilityQueryGuard.kt:19` 等），**offload / 文件工具无并发上限** | 需要你定口径：是照上游改 300 秒，还是保留 120 秒只补线程池上限 |
| X21 | 复制提示与复制按钮的读屏标签已翻译 | **C 移植** | 硬编码英文：`ui/markdown/MarkdownText.kt:299` `"Copy code"`、`ui/chat/ChatMiscViews.kt:501` `"Copy"` | 小改动，无障碍收益 |

### 2.3 视觉打磨（3）

| # | 上游条目 | 判定 | 证据 | 说明 |
| --- | --- | --- | --- | --- |
| P1 | 打开大会话先显示柔和「…」，加载完淡入 | **C 移植** | 只有 `ui/chat/ChatScreen.kt:545` `isLoadingHistory`（用于"加载更早消息"分页，`:4472`），**首次打开**的占位与淡入无实现 | 小改动 |
| P2 | 模型回退提示改 iOS 橙色卡片（列出每次失败 + 切换）；压缩进度改带转圈和「取消」的分隔行；停止选项改 iOS 操作菜单 | **B 部分** | 已有：`ui/chat/ChatIndicators.kt:79` `CompactProgressIndicator`、`ChatViewModel.kt:1929` 可取消的压缩 job、`ChatScreen.kt:2116` fallback 脉冲动画 | 缺橙色卡片与操作菜单样式 |
| P3 | 朗读音量控件从朗读胶囊上方弹出，最高 200% | **C 移植** | `ui/chat/voice/SpeechPlayerCapsule.kt:353-381` 有静音 / 语速 / 模型切换，**无音量滑块** | 小改动 |

---

## 3. beta.4 遗留项（11）

| # | 上游条目 | 判定 | 证据 |
| --- | --- | --- | --- |
| B4-F1 | 后台提问以通知出现，可直接在通知里回答（选项 / 允许 / 取消 / 其他…） | **A 已有** | `service/AskUserGate.kt:8-11,85-99` 进程级待答注册表 + 通知动作答题；`notification/AskUserNotifier`、`AgentForegroundService.kt:279-291` 问答回调 |
| B4-F2 | 等待回答时常驻通知（及 Android 16 状态胶囊）显示「等待你的回答」+ 倒计时 | **C 移植** | 提问通知通道已有，但"等待你的回答"倒计时文案无实现（`countdown` 命中项都是无关的自动重试与 shell 延时等待） |
| B4-F3 | 工具状态栏里的提问预览改成提问卡片缩略版 | **C 移植** | 无缩略预览实现（`service/` 无 question 相关状态栏渲染） |
| B4-X1 | 提问卡片内容滚到边缘时不再来回弹 | **C 移植** | 上游有 `ui/components/SheetScrollGuard.kt`，本仓库没有 |
| B4-X2 | 模型发来的问题缺类型 / 类型未知时，能按字段推断就正常显示 | **C 移植** | 无 `QuestionType` / 类型推断实现 |
| B4-X3 | Android 8–10：首次启动 shell 不再崩溃 | **D 需实机** | `build.gradle.kts:101` `minSdk = 26`（= Android 8）确实覆盖该区间，但这是运行时崩溃，**本机没有 Android 8/9/10 设备或模拟器**，静态不可判定 |
| B4-X4 | 某一个加密存储读不出来时，不再清空其它已保存凭据 | **A 已有** | `util/EncryptedPrefsFactory.kt:11,30,52` 自愈封装 + `T-android-keystore-cascade` 注释；`data/repository/ProviderRepository.kt:158`、`data/repository/EnvVarRepository.kt:47`、`auth/OAuthManager.kt:613` 都走该封装 |
| B4-X5 | 常驻通知不再显示「0 sessions \| Idle」；仅打开会话不再出现状态胶囊和计时 | **A 已有** | `service/AgentForegroundService.kt:1103` 用 plurals `bg_service_sessions`（随会话数变化），`:480,503` 零会话时自停服务；`service/ToolOverlayController.kt:574-580` 明确抑制 "Minis / Idle" 胶囊（注释写了原因） |
| B4-X6 | 列表项下嵌套的表格正常渲染为表格 | **C 移植** | 上游有 `ui/chat/TableColumnPlanner.kt`、`ui/chat/MathLineBreaker.kt`，本仓库没有 |
| B4-X7 | 语音输入：编辑转写文字时双击选词 | **A 已有** | 已实现（`ac62653f` 语音双击选词） |
| B4-P1 | 人设编辑器里「English」语言按钮不再折行 | **D 不适用** | `ui/settings/SoulSettingsScreen.kt:98` 注释明确"language picker removed; lang stays…"，本仓库人设编辑器已无该按钮 |

---

## 4. 建议落地顺序

按「收益 / 风险」，不按上游顺序：

**批次 1 — 小而独立、不碰状态机（低风险，可单独验证）**
`F4` 备份服务器可编辑、`F6` 角标开关、`F8` `--engine` 贯通、`X4` 附件菜单兜底、
`X6` 端点真实上下文长度、`X10` 草稿 15 分钟恢复、`X14` 预览长行保护、
`X17` Markdown 预览链接、`X18` `browser_use` 的 "null" 错误、`X21` 读屏标签、`P3` 朗读音量。

**批次 2 — 需要碰状态机 / 跑测试（中风险）**
`F1` 隐藏模型分组、`F2` 堆叠趋势、`X1` 备份日志上限与原子写、`X3` 恢复语义回归、
`X5` 跨 API 工具 ID、`X7` 语音纠正回退链、`X11` `--model` 创建时解析、
`X12` 列表防跳、`X16` 胶囊位置、`X20` 超时口径与线程池上限、`P1` 会话打开占位。

**批次 3 — 大件或需要产品口径**
`F3` 停止语义 + 设置项、`F5` 五个新语言、`F7` minis-config 扩展、`P2` iOS 样式对齐、
`B4-F2` / `B4-F3` / `B4-X1` / `B4-X2` / `B4-X6` 的 beta.4 遗留。

**两项我按以下默认口径执行（写在这里以便复核，冲突时以你的目标为准）：**

1. **`X20` 超时取 120 秒，不跟上游的 300 秒**；本次只补 offload / 文件工具的线程池上限。
   理由：本仓库 120 秒口径已有一整套调用方契约（`OffloadPermissionManager.kt:149,152` 与各 caller 的
   timeout 联动），改成 300 秒会让"等待人类"的窗口长于看门狗周期；上游改成 5 分钟是为了迁就手慢场景，
   而本仓库的入口更多、更需要快速失败。
2. **`F5` 五个新语言排到最后**，先做 `values-hr`（克罗地亚语，上游 1.14 已有、本仓库唯一缺的那个）。
   理由：`F5` 是纯翻译且量最大（约 2500 条 × 5 语言，上游也没有可直接复用的源串），
   收益取决于目标市场，不适合排在功能与稳定性项之前。

**另外建议顺手补 `values-hr`（克罗地亚语）**：这是上游 1.14 就有、本仓库缺的一处实际缺口，
不补的话语言集永远比上游少一个。

---

## 6. 欠账核对（2026-10-18 晚，main = `42d516e9`）

**已清偿 — 批次 1 的 11 项**（PR #73，squash = `42d516e9`）：
`F4` 备份目标可编辑、`F6` 角标开关、`F8` `--engine` 贯通、`X4` 附件菜单兜底、
`X6` 端点自报上下文长度（保留 128K 回退）、`X10` 草稿 15 分钟恢复、`X14` 预览长行保护、
`X17` Markdown 预览链接、`X18` `browser_use` 的 "null" 错误、`X21` 读屏标签、`P3` 朗读音量 0–200%。

**#72（用量页重做）并入后 `F2` 的缺口收窄**：汇总、按服务商分组、按模型下钻、品牌图标、
品牌色占比条、按所选窗口的占比（`UsageStatsScreen.kt:124,131,280` 的 `shareOfWindow`）都已落地；
仍缺「堆叠趋势图」——`UsageTrendChart.kt` 仍是单序列日柱。

**仍欠 19 项**（在 `42d516e9` 上逐项重新 grep/读码核对）：

批次 2（10 项，碰状态机）：
| # | 条目 | 复核证据（42d516e9） |
| --- | --- | --- |
| F1 | 隐藏模型可折叠分组 + 长按删除 API 模型 | 仍只有 per-entry `isHidden`（`ModelEntryDetailScreen.kt:353`）；无可折叠分组 |
| F2 | 堆叠趋势图 | `UsageTrendChart.kt` 无 stack 实现；仅 `shareOfWindow` 占比条 |
| X1 | 备份记录进度行无上限 + 历史非原子写 | `backup/BackupHistory.kt:48,101` 的 `log: List<LogEntry>` 无 cap；`:118` 只做过期清理 |
| X5 | 跨 API 工具 ID 兼容层 | 全仓库无 `apiType`/`wireApi`/`foreignTool` |
| X7 | AI 语音纠正多模型回退链 | `speech/correction/VoiceCorrectionEngine.kt:79` 单次 `strategy.correct(...)` |
| X11 | `minis-scheduled --model` 创建时解析 | `ScheduledTaskOffloadHandler.kt:101` 存原始字符串，解析推迟到运行时 |
| X12 | 列表跟随死区 | 无 `StreamFollowDeadband` 等价物 |
| X16 | 悬浮胶囊按横竖屏记忆位置 | 无 `perOrientation` / 位置持久化 |
| X20 | offload / 文件工具线程池上限 | 仅 `McpHttpServer.kt:83` pool(4) 与若干单线程 executor；权限弹窗 120s 按既定口径保留 |
| P1 | 大会话打开占位淡入 | 只有 `isLoadingHistory`（分页用，`ChatScreen.kt:545`） |

批次 3（9 项，大件 / 需要产品口径）：
| # | 条目 | 复核证据 |
| --- | --- | --- |
| F3 | 停止语义（仅停止当前工具 / 全部停止 + 长按停止前确认 + 设置项） | 无 `Stop All` / `stop_confirm` 匹配 |
| F5 | 五个新语言 | `values-it/nl/hu/cs/fa` 缺；`values-hr` 也缺（1.14 缺口） |
| F7 | minis-config 覆盖子代理名单 + 19 项设置 | `config/collections/` 仍只有 5 个，无子代理字段 |
| P2 | 模型回退橙色卡片 / 停止 iOS 操作菜单 | 压缩进度分隔行已有；橙色卡片与操作菜单缺 |
| B4-F2 | 等待回答时常驻通知倒计时 | 现在只有运行中工具的 chronometer（`AgentForegroundService.kt:1265`）；提问通知无倒计时 |
| B4-F3 | 工具状态栏提问缩略预览 | 无缩略预览实现 |
| B4-X1 | 提问卡片滚边回弹 | 无 `SheetScrollGuard` |
| B4-X2 | 问题类型推断 | 无 `QuestionType` |
| B4-X6 | 列表项下嵌套表格 | 无 `TableColumnPlanner` / `MathLineBreaker` |

不适用 / 需实机（3 项，与首轮判定一致）：
- `X15` 琥珀光晕：本仓库无该动效
- `B4-P1` 人设编辑器语言按钮：已移除（`SoulSettingsScreen.kt:98`）
- `B4-X3` Android 8–10 首次启动 shell：`minSdk = 26` 覆盖区间，需实机

---

## 7. 批次 2 交付（7/10）

本轮按点名只做 `X1 X5 X16 X11 X20 X12 F1` 七项；`F2`（堆叠趋势图）、`X7`（语音纠正回退链）、
`P1`（大会话打开占位）仍欠，留待下一轮。

| # | 落地位置 | 行为 |
| --- | --- | --- |
| F1 | `ui/settings/ProviderModelRows.kt`（新）、`data/repository/DeletedModelTombstones.kt`（新） | 隐藏模型收进「已隐藏模型 (N)」折叠组（默认收起，不再 45% 变暗内联）；长按菜单对**任意**模型都可删除；删除写入墓碑，模型刷新不再复活（`ProviderRepository.replaceEntries` 过滤） |
| X1 | `backup/BackupHistory.kt` | 单条记录进度行上限 200（首 20 行 + 最新 179 行 + 1 条「已丢弃 N 行」标记）；写盘改 temp + rename 原子替换（原来 writeText 中途被杀会留下截断 JSON，而读取端 `runCatching ?: emptyList()` 会把整段历史静默吞掉）；载入时顺带裁剪旧超长记录并回写 |
| X5 | `core/model/.../ToolIdCompat.kt`（新） | 工具 id 归一化：截断 `call_x\|fc_y` 合成前缀、`[A-Za-z0-9_-]` 之外映射为 `-`、超 64 字符时 `前 55 + "-" + 8 位 SHA-256 摘要`；`AgentTypes.sanitizeToolId` 与 `OpenAIRequestBodies.capChatToolCallId` 统一走它（原来两处各写一份） |
| X11 | `scheduled/ScheduledModelResolver.kt`（新） | `minis-scheduled create --model` 在**创建时**解析：wire id / entry uuid / 唯一显示名均可，存回规范 wire id；未知值立即失败并列出候选 id，`--allow-unknown-model` 显式放行（原来推迟到触发时才失败，且是无人值守的触发） |
| X12 | `ui/chat/StreamFollowDeadband.kt`（新） | 跟随死区：距离回退 < 24px 且距上次增长 < 250ms 时抑制滚动，只压制 markdown 重排造成的抖动；索引跨越（delta ≥ 视口高）永不被抑制 |
| X16 | `service/OverlayPlacement.kt`（新）、`BackgroundSettingsRepository`、`ToolOverlayController` | 安全区按系统栏实测裁剪（竖屏扣导航栏、横屏两侧对称扣手势条），悬浮胶囊任何方向都完整可见；位置按横/竖屏各自记忆，旋转后优先本方向自己的槽位 |
| X20 | `tools/ToolConcurrency.kt`（新） | 文件工具并发上限 8、offload handler 并发上限 4（排队 30s 未获槽位返回 `host_busy` JSON）；权限弹窗 120s 维持 §4 既定口径不变 |

架构棘轮（`scripts/ci/architecture-policy.json`）：`ProviderRepository` 抽出 `ModelRefreshPlanner.kt`（2768→2726）、
`ProviderDetailScreen` 抽出模型行到 `ProviderModelRows.kt`（1234→1088）、`ChatScreen` 删除两处 `if (false)` 死调试块与
`tagScroll`（7539→7528）、`OpenAIRequestBodies` 1309→1304、`ToolOverlayController` 1022→1008（等于上限）；
baseline 已同步收紧（只降不升）。新增/改动单测 5 个类 48 例（`ToolIdCompatTest` 12、`StreamFollowDeadbandTest` 10、
`ScheduledModelResolverTest` 10、`ModelRefreshPlannerTest` 6、`BackupHistoryLogCapTest` 10）。

**实际交付记录（2026-10-19，main = `2e247c6f`）**

| 批次 | PR | squash 提交 | 增量 | 内容 |
| --- | --- | --- | --- | --- |
| 1 Markdown 表格/列宽 | #82 | `064e7935` | 9 files +646/−34 | 移植 `TableColumnPlanner`（含 iOS 金样本），流式表格读取分隔行对齐并逐列渲染，列宽按内容比例 |
| 2 看图/存储 | #83 | `1dfbce54` | 23 files +549/−10 | `ImageExport`（原始字节保存、按字节嗅探 MIME、MediaStore Q+/legacy），两个看图入口保留原格式与元数据并新增「原图」；`StorageCleaner` + 日志缓存清理入口 |
| 3 容器 DNS | #85 | `4848c530` | 27 files +791/−39 | `ContainerDns`（解析/校验/plan）、`refreshDns` 按模式分支、`network.dnsMode`/`network.dnsCustomServers` 字段、Rootfs 设置页容器网络段；顺带把 `ConfigValue.decode` 改严格 |
| 4 聊天页门控件 | #86 | `668569d5` | 26 files +781/−18 | 接线 3 件（`FallbackPulseGate`/`SheetScrollGuard`/`SendReturnModeMenu`）+ 15 件逐件判定（见下表） |
| 4b(1/5) 无主 RUNNING 降级 | #87 | `2e247c6f` | 8 files +417/−20 | `OrphanedRunningToolGate` 纯策略 + `demoteOrphanedToolSpinners`，接在 `restoreArchivedMessages` 展示列表；16 例单测 |

批次 4b 剩余 4 件（未实现）：`SelectionDocumentOrder`（需引入 `DocOrder`(行, 子序号) 替换按屏幕 y 排序）、`ContextUsageHint(+Ui)`（需 ChatScreen 展示位）、`EmptyTurnRecovery`、`BubbleRowLocator`。

**批次 4（聊天页门控件）依赖判定（2026-10-19，分支 `feat/batch4-chat-gates`）**

上游 15 件门控件逐件比对后，实际可落地面比路线图设想的窄，原因分三类：

| 类别 | 件 | 判定依据 |
| --- | --- | --- |
| 已接线（3） | `FallbackPulseGate`、`SheetScrollGuard`、`SendReturnModeMenu` | 分别修掉「重进会话重放红脉冲」「选择器甩到底误关面板」「回车偏好不可观察」 |
| 我们有等价或更严格实现（2） | `EvictionGuard`、`SettleAfterInteractionGate` | 前者被 `ChatViewModel.canEvictFromMemory()` 覆盖且检查项更多；后者对应的 settle 重钉已被我们的滚动策略明确移除 |
| 依赖其它批次的基础设施（4） | `ImageInputPreflight`、`QueuedPromptBatching`、`TitleCandidates`、`VoiceSendGate` | 分别需要 `LLMError.httpStatus`+`effectiveInputModalities`、`QueuedPrompt.origin/isScheduledFire`、我们标题管线签名、语音 `RetryPromptQueue/FailedAudio` |
| 需结构性改造（5，转入批次 4b） | `OrphanedRunningToolGate`、`BubbleRowLocator`、`SelectionDocumentOrder`、`ContextUsageHint(+Ui)`、`EmptyTurnRecovery` | 上游的集成断言要求 `ChatViewModel` 具备 `mergeDelegateOverrides`/`delegateJobIsAlive` 这类我们结构里没有的私有函数；`SelectionDocumentOrder` 要引入 `DocOrder`(行, 子序号) 替换按屏幕 y 排序；`ContextUsageHint` 需要 ChatScreen 里的展示位 |

这 5 件不是「加一行调用」能接的：`ChatScreen`(7526)、`ChatViewModel`(6291)、`MinisTextKitSelection`(1245) 都在架构基线上，且改动本身要新增状态与排序键。因此批次 4 只交付已接线的 3 件；这 5 件的移植文件已从批次 4 分支移除（未接线的实现留在仓库里就是死码），转为批次 4b 逐件实现：每件都要先落地它依赖的状态/排序键，再接 UI。

**全量上游对比审查（2026-10-19）**：本台账只覆盖逐条点名过的条目。把 origin 与 upstream（`b4c0661d`）做
文件级 / 顶层符号级 / 字符串键级四层比对后，另有 **112 个上游源码文件、28 个部分实现文件（缺 55 个顶层符号）、
339 个用户可见文案键**完全未落地，涉及终端模拟器、helper 子代理作业、Copilot/Codex 接入、备份运行侧、
存储清理、容器 DNS 设置、计划任务详情与触发、语音 ASR 链路等——见
[upstream-parity-audit.md](upstream-parity-audit.md)。

---

## 附：本次分析用到的只读证据

- `git fetch --depth=1 upstream refs/tags/0-beta-build34` → `8c1592c6`(tag) → `b4c0661d`(commit)
- GitHub API `compare/1.14...0-beta-build34` → `status: identical`、`total_commits: 0`
- GitHub API `branches` → `main, v1.10, v1.12, v1.13, v1.14`（无 1.15）
- GitHub API `tags` → `0-beta-build30..34` 全部 = `b4c0661d`
- `git diff --name-status upstream-beta5 HEAD -- src/android/app/src/main/java` → D 129 / A 467 / M 309
- `.git/shallow` 5 个 graft 边界；`git merge-base --is-ancestor b4c0661d HEAD` → 非祖先
- 本仓库工作区：干净，`main` = `a7630b96`（本次未提交任何改动）

> 为做只读比对，在 `.git` 内新增了 `upstream` remote 与若干 `upstream-*` ref（不影响工作区）。
> 如需清理：`git remote remove upstream`。
