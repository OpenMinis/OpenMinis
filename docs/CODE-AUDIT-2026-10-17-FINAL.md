# Minis Ultra 深度问题清单与修复路线

**审查对象**：`D:\minis-ultra`
**源码版本**：`c352274c`
**审查日期**：2026-10-17
**报告状态**：修订后的执行版
**范围**：Android/Kotlin 主工程、C++ crash handler、服务/数据/网络/MCP/沙箱/UI/备份/语音/平台模块。

## 0. 最终统计

### 当前需要处理的修复项：39 项

| 严重度 | 数量 | 含义 |
|---|---:|---|
| Critical | 5 | 可能导致进程被杀、严重安全边界突破或高权限能力被越权调用 |
| High | 18 | 明显影响核心功能、稳定性、流式会话、长会话性能或本地安全边界 |
| Medium | 11 | 在特定输入、设备、长时间运行或异常条件下造成明显问题 |
| Low | 5 | 体验、日志卫生、兼容性或低概率维护风险 |
| **合计** | **39** | 固定 ID 去重后的当前修复项 |

39 项由 37 个原确认条目、1 个流式 native 内存专项和 1 个聊天滚动专项组成。原报告中“会话文件没有保存到手机功能”已删除；当前代码确实有 SAF 保存入口，真实问题是保存结果验证和滚动仲裁。

这 39 项不是源码匹配次数，也不是第一轮约 150 个初步怀疑的总和。每项都必须有代码位置、触发条件、影响和修复验收条件；静态确认不等于每项都已经在真实设备复现。

### 不计入 39 项的旧报告误诊、已修复或有意设计项

- `UX-2` 旧含义“丢最终帧”机制不成立；实际应关注节流是否失效。
- `UX-5`：`awaitTarget` 为死代码，生产路径没有调用者。
- `CRASH-3` 旧含义中的部分资源泄漏已有确定性释放，残留风险已拆分为当前的 executor/observer 项。
- `CRASH-6` 旧数学块结论大部分已有 fence、空行和字形防护。
- `SEC-7` 旧含义“MCPTokenBridge 文件权限自锁”主路径已修复；当前 `SEC-N1` 是另一项环境变量日志泄露问题。
- `BAK-1`：当前快照排除会话是有意设计，但需要回归测试，不计为已确认缺陷。
- `H-4`：SessionConcurrencyManager 已修复。
- `SEC-6` 旧含义 OAuth refresh 竞态已由 coalesceRefresh 覆盖；当前 `S-14` 是命令日志脱敏问题。
- `MCP-8`：生产零调用死代码。
- `P-7`：DohDns 原报告结论未被当前源码证实。
- `SAVE-5` 旧含义“缺少保存到手机功能”不成立，已改为独立的 `SCROLL-1` 滚动仲裁问题。

以上条目不进入 39 项计数。

---

# 1. 修复总顺序

修复顺序不是单纯按严重度排序，而是同时考虑用户影响、根因复用范围和修复依赖。

```text
阶段 0A  保存结果可信度与空文件
   ↓
阶段 0B  聊天滚动仲裁与用户手势优先
   ↓
阶段 1   崩溃、并发、服务生命周期
   ↓
阶段 2   流式传输与 Markdown 解析
   ↓
阶段 3   长会话性能、数据库、无界增长
   ↓
阶段 4   本地 socket、MCP、脚本执行和日志安全
   ↓
阶段 5   中低优先级体验、兼容性、维护性
   ↓
每阶段配套回归测试、灰度日志和性能基线
```

## 阶段 0A：先修保存链路（`SAVE-1`/`SAVE-2`/`SAVE-3`）

**目标**：先消除“用户看到成功，但手机上是空文件”的错误成功状态。

处理顺序：

1. 修 `SAVE-1`：`UNKNOWN_LENGTH` 不能直接返回成功。
2. 统一 `FileSaveActions` 与 `FullscreenImageViewer` 的保存实现，消除 `SAVE-2`、`SAVE-3`、`SAVE-4` 的分叉。
3. pre-Q 路径接入统一验证和失败删除。
4. MediaStore 只有验证成功后才清除 `IS_PENDING`。
5. 对源文件做稳定性检查，避免 agent 尚未写完时允许下载。
6. 为 SAF、MediaStore、pre-Q 和图片解码增加集成测试。

**完成门槛**：任何目标长度未知、写入异常、目标为 0 字节或图片解码失败，都不能显示成功 Toast，也不能发布一个看似成功的媒体项。

## 阶段 0B：修聊天反复滚动（`SCROLL-1`）

**目标**：用户手势拥有最高优先级，过期的程序滚动不得在手势结束后继续执行。

处理顺序：

1. 定义 `ScrollIntent`：来源、优先级、sessionId、目标 item、generation、过期时间。
2. 将所有 `ChatScreen` 程序滚动统一提交给一个协调器。
3. 用户开始拖动时取消所有 `stream-follow`、`trailing-row`、`settle` 请求。
4. 新 stream-follow 只保留最新目标，不把每个 tick 排进 Mutex。
5. `initial + settle` 改为一个可取消的布局后命令，或让 settle 检查 generation。
6. session 切换、编辑、重试、重新运行时淘汰旧 session 命令。
7. 统一 `SEND_FOLLOW_GRACE_MS` 与 `lastInterruptMs` 的语义。

**完成门槛**：发送后立即上拖、流式更新期间上拖、stream-end 与 FAB 同时触发、session 切换时，旧程序滚动都不能把用户拉回错误位置。

## 阶段 1：崩溃、并发和服务生命周期

处理顺序：

1. 所有手动 `StateFlow.value = value + item` 改为原子 `.update {}`。
2. 修复 `ToolOverlayController` 的 `as MinisApp`。
3. 给 Markdown blockquote 增加最大深度。
4. 加固崩溃报告写入路径，磁盘满时不能二次失败。
5. 修复 Service executor、WakeLock、sticky bus 的释放和并发问题。
6. 检查 FGS start/stop/restart 竞态并补设备级测试。
7. 修复 MCP stdio reader 死亡后 pending 请求永久等待的问题。

**完成门槛**：并发注册、Service 快速启停、overlay 初始化失败、磁盘不可写、畸形 Markdown 和 reader 线程退出都必须有可控结果。

## 阶段 2：流式传输和解析器

处理顺序：

1. 三个 provider 共享标准 SSE event buffer，正确拼接多行 `data:`。
2. Anthropic/Gemini 增加 TTFB watchdog 和 call timeout。
3. blockquote 递归限制与未闭合 fence 容错。
4. 修复 block 层每个流式 tick 全量重解析，建立与 inline 层对应的增量结构。
5. 清理热路径中的动态 `Regex(...)`。
6. 对解析器加入最大输入、最大 Matcher 次数和降级纯文本机制。

**完成门槛**：多行 SSE 不丢事件；断流能结束；超长/恶意 Markdown 不造成 StackOverflow、OOM 或长时间 UI 卡死。

## 阶段 3：长会话性能和数据完整性

处理顺序：

1. 修 `LONG-1` 数据库迁移 O(N²)，避免升级阶段 ANR。
2. 修 `LONG-2` `parts_json LIKE` 全表扫描，迁移到 FTS5 或可索引结构。
3. 修 `LONG-3` ResidentWindow 候选切点重复扫描。
4. 修 `LONG-4` streaming overlay 全量列表重建。
5. 修 `UX-1` ChatScreen/SessionListScreen 订阅过宽，拆分屏幕状态、下沉订阅。
6. 为 Evolution（`LONG-6`）和 voice correction（`LONG-7`）表增加 prune/TTL/容量上限。
7. 对 8MB 文件传输（`MCP-FT-1`）改为流式处理，避免 Base64 峰值放大。

**完成门槛**：长会话滚动、搜索、发送和恢复的耗时及内存不会随消息数呈失控增长；数据库升级有可测的时间上限。

## 阶段 4：安全边界

处理顺序：

1. `NativeOffload` 增加 peer UID/token 认证。
2. `SandboxHttpProxy` 限制来源、目标和 CONNECT 行为。
3. MCP CORS 去掉 `*`，增加 Host/Origin 校验。
4. Rhino 配置 `ClassShutter`，禁止 Java 类逃逸。
5. SecurityGate 的用户输入正则改为非回溯匹配或严格预算。
6. 脱敏命令、环境变量、API key 和 SSH 私钥相关日志。

**完成门槛**：同设备其他进程、恶意网页、沙箱脚本和异常输入不能越过既定权限边界；日志不能成为凭据泄露渠道。

## 阶段 5：低优先级体验和维护

1. 修复 Retry 按钮触摸区域。
2. 修正中文文本的 TTS locale 选择。
3. 删除终端固定 `delay(500)`，改为 ready handshake。
4. 清理生产 `println` 诊断噪音。
5. OAuth fingerprint 使用常量时间比较。
6. 清理导出缓存文件和其他可接受但不必要的临时文件。

---

# 2. 39 项问题清单

严重度说明：

- **Critical**：必须在发布前处理，或先增加强制缓解措施。
- **High**：核心功能或稳定性明显受影响，应排在正常功能开发之前。
- **Medium**：需要在相应模块重构时处理，并补回归测试。
- **Low**：不阻塞主线，但应在模块收口时处理。

## 2.1 崩溃、进程稳定性和并发

### CRASH-1，Critical：StateFlow 手动读改写竞态

**位置**：`service/SessionActivityTracker.kt:345,383,459,476`；`SubAgentActivityTracker.kt` 多处。

**证据**：代码采用 `flow.value = flow.value + item` 一类非原子更新。两个线程同时读取旧集合时，后写入者覆盖前一条注册或成员状态。SubAgent tracker 还有多处赋值只有少数同步。

**触发**：多个 session、tool 或 sub-agent 同时注册/注销，尤其是 Service 生命周期交叉期间。

**影响**：状态丢失、错误停止/恢复、重复启动，可能进一步触发前台服务异常路径。

**修复**：统一使用 `MutableStateFlow.update {}` 或单线程 actor；为注册、注销、批量恢复写并发测试。

### CRASH-2，High：Context 裸转型

**位置**：`service/ToolOverlayController.kt:113`。

**证据**：`(context.applicationContext as MinisApp)` 假设传入 Context 的 applicationContext 必定是 `MinisApp`。

**触发**：测试 Context、包装 Context、初始化顺序异常或不同进程环境。

**影响**：`ClassCastException`，overlay/工具界面启动失败，严重时直接导致 app 崩溃。

**修复**：通过依赖注入传入 repository；至少使用安全转型并返回明确失败状态。

### CRASH-4，High：blockquote 递归无上限

**位置**：`ui/chat/StreamingMarkdownParse.kt:180`。

**证据**：引用块通过递归继续解析内层内容，没有最大深度保护。

**触发**：数百层 `>` 嵌套或流式输入持续构造深层引用。

**影响**：`StackOverflowError`，并伴随重复分类和高分配量。

**修复**：最大深度约 16 层；超过上限按普通段落或纯文本渲染。

### CRASH-8，High：崩溃报告写入未充分隔离

**位置**：`crash/CrashFileReporter.kt:51`。

**证据**：`writeText()` 处于崩溃处理链路；磁盘满、权限变化、文件系统异常时仍可能抛出或静默丢失报告。

**触发**：设备存储耗尽、目录不可写、文件名冲突或异常路径。

**影响**：崩溃证据丢失；若异常逃出报告器，可能造成二次异常。

**修复**：使用最小化的 `try/catch`、容量检查、临时文件原子替换和 fallback 日志；崩溃处理器不能依赖复杂应用状态。

### H-3，Medium：ForegroundService executor 生命周期不完整

**位置**：`service/AgentForegroundService.kt:179`。

**证据**：`islandProbeExecutor` 没有在 `onDestroy` 对称 shutdown。

**触发**：Service 反复 start/stop、进程恢复或配置变化。

**影响**：线程泄漏、旧 Service 继续回调、资源增长，间接提高 ANR/崩溃概率。

**修复**：在 `onDestroy` 关闭 executor；为重复 stop/start 加生命周期测试。

### H-5，Medium：Sticky bus 读清操作非原子

**位置**：`service/OverlayComposeBus.kt:34-38`。

**证据**：`consumeSticky` 的读取和清空不是一个原子操作。

**触发**：多个 collector 或生命周期重建同时消费 overlay 事件。

**影响**：事件重复消费或丢失，导致 UI/Service 状态错乱。

**修复**：使用 Channel、单线程消费者或 `updateAndGet` 风格的原子交换。

## 2.2 流式传输与内容正确性

### PROV-1，High：SSE 多行 data 未拼接

**位置**：`OpenAIRawStream.kt:575-581`、`AnthropicProvider.kt:219-233`，Gemini 同构路径。

**证据**：逐行读取后直接 parse 单行 JSON；同一 SSE event 的多行 `data:` 没有按协议拼接，失败后被 `catch/continue` 静默丢弃。

**触发**：provider 将一个事件拆成多行，或返回较长/特殊 JSON。

**影响**：流式文本、tool call 或结束事件丢失，UI 卡在 thinking 或内容不完整。

**修复**：实现共享 SSE parser，按空行结束 event，拼接所有 data 行并保留 event/id 语义。

### CRASH-5，High：未闭合代码围栏吞没后续内容

**位置**：`ui/chat/StreamingMarkdownParse.kt:113-126`。

**证据**：`trimmed.startsWith("```")` 分支的 `while (i < lines.size)` 循环在输入结束时直接退出；未闭合 fence 后面的所有行被整体归入 code block，且没有 fence 未闭合的降级提示。

**触发**：流式期间 fence 尚未闭合、模型输出被截断、或模型忘记闭合 fence。

**影响**：回复后半部分全部按代码渲染，用户看到内容“消失”，是流式内容丢失的高频来源。

**修复**：输入耗尽时检测未闭合 fence，降级为普通文本渲染或加“未闭合”提示；流式场景对最后一次 parse 的 fence 状态保持宽容。

### CRASH-7，Medium：equals 只比长度——等长流式替换显示陈旧内容

**位置**：`ui/chat/ChatFlatItems.kt:121-134`（AssistantMarkdownBlock）、`ui/chat/ChatFlatItems.kt:256-263`（AssistantLegacyContent）。

**证据**：`rawText.length == other.rawText.length` 一类比较只对比长度；等长内容替换时 LazyColumn 的 skip 判定误判为未变化，界面继续显示旧文本。

**触发**：流式替换、翻译覆盖、同长度重试结果。

**影响**：内容看起来没有更新，比崩溃更隐蔽。

**修复**：对比内容 hash 或分块比对；至少对 segmentText 使用完整 equals。

### PROV-2，High：部分 provider 缺 TTFB watchdog

**位置**：Anthropic read timeout 约 10 分钟；Gemini 同构路径。

**证据**：OpenAI 路径有 watchdog，Anthropic/Gemini 缺少等价首字节超时。

**触发**：连接建立但服务端不发送首字节、代理半开或网络挂起。

**影响**：UI 长时间保持 thinking，重试和取消体验失效。

**修复**：首字节超时、总请求超时、取消时关闭 OkHttp call，并把状态写回 UI。

### ICU-1，Critical：流式期间块级全量重解析引发 ICU native 内存累积

**位置**：`ui/chat/StreamingMarkdownParseCaches.kt:102-105`（blocksLru 以整段文本为 key）；`ui/chat/StreamingMarkdownParse.kt`（块层逐行分类）。

**证据**：`blocksLru` 以整段消息文本为 key，流式期文本每 tick 变化，缓存必 MISS，每 tick 全量重解析全部块；块层没有 inline 层已有的“冻结前缀复用 + 未闭合后缀重扫”增量结构。每次正则匹配 ICU 将行复制为 UTF-16 native 内存，Java 堆压力不足时 GC 长时间不运行，native 复制品堆积直至进程被杀。用户实测 12.6 万次分配约 9GB 撑爆进程，与仓库内 `text/BoundedText.kt:5-17` 记录的 2026-09-18 同型崩溃机制一致。

**触发**：长回复流式输出、Java 堆长时间无 GC、低内存设备。

**影响**：流式输出期间进程 native 内存持续增长直至被系统杀掉，即用户实测反复崩溃。

**修复**：块层建立增量解析结构（冻结前缀 block 列表直接复用，只解析新增/变化行）；每 tick Matcher 分配次数超阈值时只分类视口内块；blockquote 递归深度限制配合，减少 O(depth × lines) 分配。

## 2.3 安全问题

### SEC-1，Critical：NativeOffload socket 无认证

**位置**：`NativeOffload.kt:233-252`。

**证据**：抽象 socket `native-offload-linux` 缺少 peer UID/token 认证；请求携带 sessionId 并可触发高权限 handler。

**触发**：同设备其他进程能连接该 socket 并伪造请求。

**影响**：越过应用边界执行宿主侧能力，例如 su、通讯录、定位等 handler。

**修复**：校验 peer credentials、每次启动随机 token、最小化 handler 权限和请求白名单。

### SEC-2，Critical：SandboxHttpProxy 可被当作开放本地代理

**位置**：`SandboxHttpProxy.kt:23,70,129-151`。

**证据**：监听 `127.0.0.1:17890`，未验证请求来源，CONNECT/目标限制不足。

**触发**：同设备恶意应用或被利用的本地网页访问代理。

**影响**：SSRF、访问本机服务或代理受限网络目标。

**修复**：随机端口/令牌、校验调用方、严格 allowlist、禁止内网和 metadata 地址、限制 CONNECT。

### SEC-3，High：SecurityGate 正则热路径存在 ReDoS 风险

**位置**：`SecurityGateImpl.kt:633-638`。

**证据**：每次工具调用编译多次正则并做多重匹配，用户可影响的模式存在指数回溯可能。

**触发**：恶意或复杂规则、长输入、频繁工具调用。

**影响**：主线程/决策线程卡顿，造成 ANR 或请求堆积。

**修复**：预编译受控模式；改为 glob/状态机；限制输入长度和匹配时间。

### SEC-4，High：MCP CORS 与本地 bearer 组合过宽

**位置**：`McpServerCore.kt:316,341`。

**证据**：`Access-Control-Allow-Origin: *` 同时存在本地 bearer 服务。

**触发**：恶意网页诱导用户打开后，从浏览器请求本机 MCP 服务。

**影响**：跨源调用本地工具、读取或执行 MCP 暴露的能力。

**修复**：严格 Origin/Host allowlist，收紧 CORS，token 不得成为唯一边界。

### SEC-5，Critical：Rhino 未设置 ClassShutter

**位置**：`ExecuteCodeTool.kt:82-144`。

**证据**：脚本运行环境未限制 Java 类可见性。

**触发**：模型或不可信脚本构造 Java interop 调用。

**影响**：可能访问 `Runtime`、文件系统、进程或宿主 API，逃逸预期脚本沙箱。

**修复**：配置 `ClassShutter`、白名单 Java API、禁用危险反射并在进程级沙箱中运行。

### S-14，High：完整命令进入 logcat

**位置**：`ShellExecutor.kt:162,292,304`；`ExecutionCoordinator.kt:206`。

**证据**：日志记录完整命令，命令可能包含 token、密码、URL 参数或私钥。

**触发**：执行带凭据的 shell、SSH 或网络命令。

**影响**：logcat、诊断包或调试权限进程获取凭据。

**修复**：结构化日志只记录命令类型、hash、退出码；敏感参数统一脱敏。

### SEC-N1，Medium：NativeOffload 记录完整环境变量

**位置**：`NativeOffload.kt:286`。

**证据**：`Log.d` 输出整包 environment。

**触发**：环境中存在 API key、代理密码或会话凭据。

**影响**：敏感信息进入日志。

**修复**：只记录键名和安全摘要，禁止输出 value。

## 2.4 长会话、数据库与内存

### LONG-1，High：数据库迁移 O(N²)

**位置**：`AppDatabase.kt:469-493`。

**证据**：迁移过程中对历史消息重复查询/扫描。

**触发**：大量消息或长期使用后的升级。

**影响**：升级耗时、ANR、迁移中断风险。

**修复**：批量 SQL、窗口函数、临时索引和可恢复迁移；以 1 万/10 万消息压测。

### LONG-2，High：parts_json LIKE 全表扫描

**位置**：`ChatDao.kt:202-208`、`findFirstMessageSnippet:878-894`。

**证据**：JSON 文本 LIKE 无法使用有效索引，SessionListViewModel 对每个命中会话再次扫描。

**触发**：历史会话搜索、会话列表刷新。

**影响**：搜索延迟随数据量增长，数据库和 UI 线程负载升高。

**修复**：规范化可搜索片段或 FTS5；分页并避免 N+1 查询。

### LONG-3，High：ResidentWindow 候选切点重复扫描

**位置**：`ResidentWindow.kt:110-139`。

**证据**：每个候选切点重复扫描 suffix，长消息序列出现高阶复杂度。

**触发**：4000+ 消息、频繁窗口恢复/裁剪。

**影响**：长会话恢复和滚动明显变慢，可能触发 ANR。

**修复**：预计算边界、缓存配对状态，改为线性或摊销线性算法。

### LONG-4，High：streaming overlay 每个 delta 全量重建

**位置**：`ChatFlatItems.kt:287`。

**证据**：每次流式更新对完整列表执行 map/重建。

**触发**：长回复、低延迟 delta、高频流式更新。

**影响**：CPU、分配和 Compose 重组量随历史长度增长。

**修复**：按 message id 增量更新，稳定 immutable 前缀，避免全列表复制。

### UX-1，High：ChatScreen 订阅过宽

**位置**：`ChatScreen.kt`，约 7609 行、84 处 `collectAsState`；`SessionListScreen.kt`（23 处、3327 行）同型较轻（原 UX-1b，并入本项计数）。

**证据**：多个 Flow 变化会使大范围 composable 重组。

**触发**：流式输出、工具状态、键盘、滚动和会话状态同时变化。

**影响**：长会话卡顿、掉帧、滚动竞争放大。

**修复**：拆分屏幕状态、下沉订阅、使用稳定 key 和 derived state。

### LONG-6，Medium：EvolutionStore 只增不删

**位置**：`EvolutionStore.kt` 的 observations/tool_fails 表。

**证据**：存在插入调用，没有 TTL、容量上限或定期 prune。

**触发**：长期运行、频繁工具失败或自动演化。

**影响**：数据库持续增长，查询和备份变慢。

**修复**：按时间/数量保留，后台 prune，增加数据库大小指标。

### LONG-7，Medium：语音 correction_events 只增不删

**位置**：`VoiceCorrectionDb.kt`，`recordCorrectionEvent` 的多个调用方。

**证据**：没有 maxAge/prune，仅提供手动 `clearAll`。

**触发**：长期语音输入。

**影响**：数据无界增长，备份和查询成本增加。

**修复**：按时间和数量保留，迁移时增加索引和清理策略。

## 2.5 文件保存与用户体验

### SAVE-1，High：UNKNOWN_LENGTH 被当作保存成功

**位置**：`FileSaveGuard.kt:30-34`；`FileSaveActions.kt:39-55`。

**证据**：`dstLen == UNKNOWN_LENGTH` 直接返回 true；读取目标长度异常时通过 `getOrDefault(UNKNOWN_LENGTH)` 折叠成同一结果。

**触发**：OEM SAF provider 返回 -1、异步落盘、读取目标失败或 provider 行为不完整。

**影响**：输出实际为 0 字节，但 UI 显示 `file_saved_toast`。

**修复**：未知长度不能成功；关闭并 flush 后重新验证内容或至少验证可读字节；无法验证就报“无法确认保存结果”。

### SAVE-2，Medium：pre-Q 文件保存无完整校验

**位置**：`FileSaveActions.kt:110-118`。

**证据**：pre-Q 分支直接 copy，缺统一目标长度/内容验证和失败删除。

**触发**：旧 Android、存储异常、复制中断或目标文件创建后写入失败。

**影响**：留下空/截断文件并可能误报成功。

**修复**：接入统一保存守卫，验证失败删除目标。

### SAVE-3，High：图片保存路径独立且失败静默

**位置**：`FullscreenImageViewer.kt:321-327,487-544`；`ImageGalleryViewer.kt:312-317`。

**证据**：`loadBitmap == null` 时没有失败提示；图库路径使用 `dstLen != 0L`，长度为 -1 也会判成功；未统一使用 `FileSaveGuard`。

**触发**：源文件不可读、图片解码失败、MediaStore provider 返回未知长度。

**影响**：用户点击下载无反馈，或发布空媒体项。

**修复**：检查 `Bitmap.compress()` 返回值；复用统一守卫；验证前不清 `IS_PENDING`；显示失败提示。

### SAVE-4，Low：crash zip 复制后无长度校验

**位置**：`CrashFrequencyDetector.kt:845-865`。

**证据**：压缩包复制后没有同等的目标大小/内容验证。

**触发**：崩溃报告分享、存储异常或复制中断。

**影响**：分享损坏或空的 crash zip。

**修复**：统一 staged copy、flush、验证、失败删除流程。

### SCROLL-1，High：27 条程序化滚动路径竞争，Mutex 只串行化不取消过期命令

**位置**：`ui/chat/ChatScreen.kt`（27 个 `tracedScrollToItem()` 调用点）；`ui/chat/ChatScrollController.kt:23-50`。

**证据**：发送、session 进入、FAB、流式采样、stream-end、trailing-row、搜索、重试、编辑、重新运行等路径共享同一个 `LazyListState`，共享的 controller 只有 `commandMutex.withLock`——保证串行但不判断命令是否过时，也不取消队列中的旧命令。`SEND_FOLLOW_GRACE_MS = 2_000L`（ChatScreen.kt:326）与 `lastInterruptMs = 1_000ms`（:1683）窗口不一致，发送后 1~2 秒内用户上拖仍被拽回；120ms 流式采样叠加后一轮回复可见 5+ 次程序化滚动。

**触发**：发送后立即上拖、流式更新期间上拖、stream-end 与 FAB 同时触发、session 切换时旧 session 命令未取消。

**影响**：用户反复被拉回底部或位置跳动，是用户实测“反复滚动”的直接来源。

**修复**：全部路径收编协调器并加 generation、取消、合并和用户手势优先级；统一两个宽限常量；settle 命令过期后不执行。

### UX-3，Medium：Retry 触摸区域过小

**位置**：`ChatAssistantMessageUI.kt:327-345`。

**证据**：Retry 可点击区域约 22dp，低于常用触控尺寸。

**触发**：小屏设备、单手操作、连续重试。

**影响**：误触和点击失败。

**修复**：扩大语义点击区域至至少 48dp，视觉图标尺寸可保持不变。

### TTS-1，Low：TTS 硬编码 Locale.US

**位置**：`TextToSpeechManager.kt:118`。

**证据**：始终使用 `Locale.US`。

**触发**：中文回复、设备默认语言非英语。

**影响**：中文使用英文音素朗读，体验明显下降。

**修复**：根据文本语言、设备 locale 和可用 voice 选择语言；无 voice 时给出明确 fallback。

## 2.6 MCP、配置、终端和其他中低风险问题

### MCP-STDIO-1，High：stdio JSON-RPC string id 无法匹配且 reader 死亡无传播

**位置**：`McpStdioClient.kt:131,143`。

**证据**：`optInt("id")` 无法处理 JSON-RPC 合法的 string id；reader catch 后线程可能退出，pending 请求没有统一失败通知。

**触发**：服务端使用字符串 id、stdio reader 遇到异常 EOF。

**影响**：请求永久等待或超时，MCP 工具不可用。

**修复**：id 使用 JSON 原值比较；reader 退出时 fail all pending；补重连和取消。

### CFG-1，Medium：runBlocking 包住配置确认门

**位置**：`ConfigBridge.kt:279,940`。

**证据**：确认门内 `runBlocking` 可能等待 120 秒。

**触发**：offload 工作线程并发写配置、用户确认不返回或 UI 生命周期变化。

**影响**：worker 池耗尽、工具调用级联阻塞。

**修复**：挂起式确认、超时和取消传播；确认请求按 session 排队而不是阻塞共享 worker。

### MCPOAUTH-1，Medium：OAuth 固定端口冲突

**位置**：`MCPOAuthController.kt:260`。

**证据**：固定端口 54546，fallbackPorts 默认未传入。

**触发**：端口已被其他进程占用或残留实例未退出。

**影响**：OAuth 登录失败。

**修复**：动态可用端口、显式 fallback 列表、启动前探测和清理。

### MCP-FT-1，Medium：文件传输 readBytes + Base64 峰值过高

**位置**：`FileTools.kt:158-166`。

**证据**：8MB 文件先完整读入，再 Base64 放大，峰值可达约 30MB 以上。

**触发**：沙箱/客户端传输大文件或并发传输。

**影响**：内存峰值、GC 抖动甚至 OOM。

**修复**：分块流式编码，设置并发和大小限制。

### TERM-1，Low：PTY 初始化固定 delay

**位置**：`TerminalScreen.kt:105`。

**证据**：固定等待 500ms 后发送 `initCommand`。

**触发**：慢设备、冷启动、shell 初始化脚本较长。

**影响**：命令发到未就绪 shell，终端首条命令丢失。

**修复**：等待 ready marker 或 PTY 状态事件，超时再报错。

### SEND-DIAG-1，Low：生产日志残留 println

**位置**：`ChatViewModelSendExt.kt:351`。

**证据**：每次发送输出 STALL-DIAG 快照。

**触发**：正常发送流程。

**影响**：日志噪音、性能轻微下降，妨碍真实问题定位。

**修复**：使用受控 logger，默认关闭，仅 debug/采样启用。

### OAUTH-FP-1，Low：fingerprint 比较不是常量时间

**位置**：`OAuthManager.kt:154`。

**证据**：使用 `!=` 比较 SHA-256 fingerprint。

**触发**：fingerprint 校验失败路径。

**影响**：实际风险很低，因为比较的是摘要而非原始密钥；仍不符合安全比较规范。

**修复**：使用 constant-time byte comparison，并保留失败审计日志但不输出敏感值。

---

# 3. 反复滚动专项证据

当前 `ChatScreen.kt` 实际有 **27 个** `tracedScrollToItem()` 调用点，主要包括：

- `1384,1386`：SESSION-REENTRY 初始定位与 settle
- `1392,1394`：FAB-DOWN 初始定位与 settle
- `1490,1492`：发送初始定位与 settle
- `1574`：resume/retry/rerun force scroll
- `1588`：messages.size 用户发送兜底
- `1693`：streaming-content 采样滚动
- `1718`：stream-end re-pin
- `3050`：bottomReserve 变化
- `3241,3247`：搜索两阶段定位
- `3285,3386,3392`：FAB-UP 路径
- `3741`：trailing-row pin
- `4063,4065`：resume banner
- `4243,4266,4449,4479`：重试/编辑/重新运行
- `4876,4881`：FAB-DOWN 另一条路径
- `6047,6049`：键盘发送路径

`ChatScrollController` 的 `Mutex` 只能串行化，不能取消已经过时的命令。因而“调用点统一到 controller”不是充分修复条件，必须加 generation、取消、合并和用户手势优先级。

---

# 4. 崩溃专项结论

当前审查确实看到 APP 崩溃相关问题，但要区分静态高危与设备上某一次崩溃的最终根因：

- 已有高可信静态崩溃路径：`as MinisApp`、深层 blockquote、Rhino 脚本逃逸导致的进程级风险、服务生命周期/并发状态错误。
- 用户实测流式崩溃的核心方向是 ICU/native 内存压力：块层流式期间仍可能以完整文本为 key 反复 miss 并重解析，虽然已有节流、行窗口和缓存防护，仍未根治。
- 前台服务历史上已有启动超时和 stop/start 交叉修复提交，必须通过设备级回归确认当前版本没有回归。
- 没有设备 crash stack 时，不能把所有静态问题都声称为同一次崩溃的唯一根因。

---

# 5. 修复依赖和验收顺序

## 第一轮：P0 用户问题

代码范围：

- `FileSaveGuard.kt`
- `FileSaveActions.kt`
- `FullscreenImageViewer.kt`
- `ImageGalleryViewer.kt`
- `FilePreviewScreen.kt`
- `ChatScrollController.kt`
- `ChatScrollPolicy.kt`
- `ChatScreen.kt`

测试要求：

- SAF provider 返回 -1；
- 目标保持 0 字节；
- close 后延迟可见；
- Bitmap decode/compress 失败；
- pre-Q 复制失败；
- 保存后返回会话；
- 发送后立即上滑；
- streaming tick 与用户拖动重叠；
- session 切换取消旧滚动；
- stale generation 不执行。

## 第二轮：并发和崩溃

先完成 `.update {}`、安全 Context、递归深度和资源生命周期，再修改流式解析。原因是并发和生命周期问题会污染后续所有运行时测试结果。

## 第三轮：流式解析

先共享 SSE parser，再做 block 增量解析。不能只加 throttle，因为 throttle 只降低触发频率，不降低一次全量解析的单次成本。

## 第四轮：性能与数据

先迁移和搜索，再做 Compose 拆分；先处理影响所有长会话的公共路径，再处理单模块表的 prune。

## 第五轮：安全

socket/MCP/Rhino 先收边界，再做日志脱敏；否则调试日志可能继续泄露安全测试中的凭据。

---

# 6. 发布前验收门槛

1. `./gradlew test` 通过。
2. Android instrumentation/save tests 通过，覆盖 SAF、MediaStore、pre-Q。
3. 反复滚动测试通过，用户拖动期间没有程序滚动接管。
4. 10 万消息数据库迁移和搜索有耗时、内存基线。
5. 长流式输出测试确认 native/Java 内存不会持续无界增长。
6. FGS 快速 start/stop/restart、进程恢复、设备旋转通过。
7. NativeOffload、MCP、Rhino 和代理的拒绝用例通过。
8. 运行一次 release 构建，确认 debug server、诊断日志和敏感输出未进入生产包。

---

# 7. 报告边界

本报告是基于源码和静态调用链的修复排序报告。39 项中的“已确认”表示源码机制和触发条件成立，不等于每项都已在真实设备复现。尤其是：

- 文件保存的 OEM provider 行为需要 instrumentation 验证。
- 滚动问题的用户可见频率需要录屏/Perfetto 与 scroll trace 验证。
- ICU/native 内存问题需要真实长流和 native allocation 监控确认。
- FGS 崩溃需要设备 crash stack 对应到具体异常。

因此，修复时应先加回归测试和诊断埋点，再逐项改动。报告中的 39 项已去重，不包含那 9 个被推翻、已修复或有意设计的旧条目。
