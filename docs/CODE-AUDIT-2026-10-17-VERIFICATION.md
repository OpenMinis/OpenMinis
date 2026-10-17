# Minis Ultra 审计报告复核（2026-10-17）

**复核对象**：`docs/CODE-AUDIT-2026-10-17-FINAL.md`（39 项）
**代码版本**：`c352274c`（工作区仅有该报告未跟踪、`docs/performance-optimization-roadmap.md` 被删除，源码无改动）
**复核方式**：静态阅读调用链 + 逐条核对文件/行号/机制；未在设备运行，未执行 Gradle 测试
**复核结论**：**33 项实质成立，3 项不成立，3 项机制需修正**

---

## 0. 结论摘要

| 判定 | 数量 | 条目 |
|---|---:|---|
| ✅ 成立（机制与影响均与源码一致） | 33 | 见 §2 |
| ⚠️ 部分成立 / 机制需修正 | 3 | `ICU-1`、`SEC-1`、`MCP-STDIO-1` |
| ❌ 不成立（误诊） | 3 | `SEC-N1`、`TTS-1`、`OAUTH-FP-1` |

另有 4 项虽然机制成立，但**严重度被高估**：`CRASH-1`、`SEC-2`、`PROV-1`、`UX-1`；1 项属**有意契约**而非缺陷：`SAVE-1`（测试已显式钉住）。

总体判断：这份报告不是泛泛而谈的扫描结果——文件、行号、常量值（27 个滚动调用点、`SEND_FOLLOW_GRACE_MS=2000`、`lastInterruptMs=1000`、84 处 `collectAsState`、`MIGRATION_20_21` 的相关子查询）都能对上。**可以作为修复路线使用**，但按原文照单全改会浪费三处工时，并在 `SAVE-1` 上撞到既有测试契约。

---

## 1. 复核方法

1. 对每个条目定位到具体符号（函数/常量/查询），确认代码形状而非仅行号。
2. 区分三层：**机制是否存在** → **触发条件是否可达** → **影响是否被夸大**。
3. 对安全项额外检查"默认配置是否启用"和"是否已有其他边界"（SELinux、bearer、开关）。
4. 对性能项检查是否已有缓解（缓存、节流、上限常量、增量路径）；有缓解的降级，不否定根因。

---

## 2. 逐项复核

### 2.1 崩溃、进程稳定性与并发

| ID | 判定 | 复核证据与修正 |
|---|---|---|
| `CRASH-1` | ✅ 成立，**Critical → High** | `SessionActivityTracker.kt:345/383/459/476` 与 `SubAgentActivityTracker.kt:101/142/152/198/407` 均为非原子读改写；同文件 352/366 又用了 `synchronized(this)`，说明并发路径确实存在且未统一。`jobs` 是普通 `HashMap`（121/125），`attachJob` 与 `stop` 可跨线程。降级理由：丢更新需要精确交错的并发注册/注销，影响是"会话被漏记 → 服务提前停"，不是必然崩溃；但 `setActive` 丢失会让前台服务在流式期间被回收，仍应尽早修。 |
| `CRASH-2` | ✅ 成立 | `ToolOverlayController.kt:113` 裸 `as MinisApp`。`AgentForegroundService:551` 用 `as? MinisApp ?: return` 做了保护，但控制器构造仍硬转；测试 Context / 包装 Context 会 `ClassCastException`。同型问题另有 6 处（`ChatViewModel:2137`、`AppNavigation:1751` 等），建议一并收敛，不止修这一行。 |
| `CRASH-4` | ✅ 成立 | `StreamingMarkdownParse.kt:180` 无深度参数，纯递归；`innerBlocks` 每层重新 `joinToString`，深度 d 时是 O(d×lines) 分配。CommonMark 建议上限 16 层是合理修法。 |
| `CRASH-8` | ✅ 成立 | `CrashFileReporter.kt:51` 直接 `out.writeText(body)`，无 `try/catch`、无容量检查、无临时文件。ACRA sender 抛异常会丢报告；且 `mkdirs()` 结果未检查。 |
| `H-3` | ✅ 成立，**Medium → Low/Medium** | `AgentForegroundService.kt:179` 的 `islandProbeExecutor` 在 `onDestroy`（524-536）未 shutdown；全文件仅 935 行注释提到释放。线程是 daemon、任务是短探测，泄漏量 = 服务重建次数 × 1 线程，影响有限，但确属生命周期不对称。 |
| `H-5` | ✅ 成立 | `OverlayComposeBus.kt:34-38` 先读 `sticky` 再判 session 再置 null，两个 collector 可同时读到同一请求并各自消费（重复投递）。修法用 CAS 循环：匹配到目标 session 才 `compareAndSet(s, null)`。 |

### 2.2 流式传输与解析

| ID | 判定 | 复核证据与修正 |
|---|---|---|
| `PROV-1` | ✅ 成立，**High → Medium** | 三家均是逐行 `readLine()`：`OpenAIRawStream.kt:538-581`、`AnthropicProvider.kt:219-236`、`GeminiProvider.kt:152`。只处理 `data:` 单行，忽略 `event:`/`id:`，多行 `data:` 会被拆成多个 `JSONObject` 解析失败后 `continue`。降级理由：主流 provider 的 JSON 事件本就是单行，多行 `data:` 在合规代理上才出现；属于协议正确性缺陷，不是高频主路径。修法仍是共享 SSE 解析器（按空行聚合），顺带统一 `[DONE]`/`event:` 语义。 |
| `CRASH-5` | ✅ 成立 | `StreamingMarkdownParse.kt:113-126`：`while (i < lines.size)` 到输入末尾直接退出，`addCodeChunks`（`StreamingMarkdownText.kt:899`）把整段当 `CodeBlock`，没有"未闭合"标记或降级。流式中 fence 未闭合时，后续段落全部按代码渲染。 |
| `CRASH-7` | ✅ 成立 | `ChatFlatItems.kt:121-134` 与 `256-263` 的 `equals` 只比 `length`（注释自称 cheap-equals），hashCode 同样只用长度。等长替换（翻译覆盖、同长度重试）会跳过重组，显示旧文本。修法：对 `segmentText`/`content` 用完整 `equals`，或缓存内容 hash。 |
| `PROV-2` | ✅ 成立 | Anthropic `AnthropicProvider.kt:90-92`：`connect 30s / read 10min`，无首字节 watchdog；Gemini `GeminiProvider.kt:56-58` 同型。OpenAI 侧有 `STREAM_TTFB_TIMEOUT_MS=120s` 和上传 cap（`OpenAIRawStream.kt:84-96`）。10 分钟 thinking 是真实可复现的挂起形态。 |
| `ICU-1` | ⚠️ **结论成立，机制需修正** | 见 §3.1。核心成立：live 分支每个 delta 都 `parseMarkdownBlocks(displayContent)` 全量重解析（`StreamingMarkdownBlockBody.kt:147-179`），块层没有 inline 层已有的冻结前缀增量。但"`blocksLru` 以整段文本为 key 导致每 tick MISS"不准确：live 路径根本不查 `blocksLru`，只在 >2000 字时 `putBlocks`（171-173）；`blocksLru` 是冻结块的缓存。另外单个 live fragment 超过 `LIVE_FRAGMENT_DEGRADE_CHARS=8000`（`StreamingMarkdownText.kt:583`）会降级为纯文本尾部，故"无上限"不成立，是"每 tick 的成本随 fragment 线性增长，且有 8K 硬顶"。native 内存堆积（12.6 万次分配/9GB）无法静态验证，但与 `BoundedText.kt` 记录的 ICU 复制机制一致，方向可信。 |

### 2.3 安全

| ID | 判定 | 复核证据与修正 |
|---|---|---|
| `SEC-1` | ⚠️ **机制成立，可利用性未验证** | 见 §3.2。`NativeOffload.kt:233-252` 接受任意连接，`handleClient` 只校验 magic/version，无 `getPeerCredentials`/token；注册的 handler 覆盖 calendar/contacts/notification/alarm/accessibility/browser/speak/photos/model-use，权限面确实高。但抽象 socket 的跨 UID 可达性取决于 SELinux 策略，报告未给出 PoC；"同设备其他进程能连接"是合理假设，不是已证事实。 |
| `SEC-2` | ✅ 成立，**Critical → High** | `SandboxHttpProxy.kt:69-70` 绑定 `127.0.0.1:17890`；`handle()`（108-157）不校验来源、不限目标，CONNECT 可到任意 `host:port`（含 127.0.0.1 本机服务、云 metadata）。仅当防火墙策略为 `LOG`/`DENY` 时启动（`SandboxFirewall.kt:66-67`），故是"开启联网审计时的开放代理"，不是常驻。修法：随机端口 + 启动令牌、`Host`/目标 allowlist、拒绝 loopback/私网/metadata、限制 CONNECT 端口。 |
| `SEC-3` | ✅ 成立，**High → Medium** | `SecurityGateImpl.kt:598-638`：每个 pattern 每次调用现编译 `Regex`，且 `patternMatches` 连编译两个 regex + `containsMatchIn` + `matches`。`*` 展开成 `.*`，多星号 pattern 对长命令有回溯放大。但 pattern 来自用户自己的安全规则，攻击者是规则作者本人；真正的成本是热路径反复编译。修法：预编译（规则变更时重建）+ 输入长度上限 + 用 glob 状态机替代正则。 |
| `SEC-4` | ✅ 成立，**条件成立** | `buildHttpResponse:316` 与 `corsPreflightResponse:341` 固定 `Access-Control-Allow-Origin: *`。认证在 `McpServerCore.kt:84-90`，但 `authToken` 为 null/blank 时**直接跳过**。即：只有"启用了 MCP server 且未配 token"时，恶意网页才能跨源调用；配了 token 时浏览器拿不到响应，但仍可发请求（CSRF 面）。修法：Origin/Host allowlist、token 强制、CORS 不回 `*`。 |
| `SEC-5` | ✅ 成立 | `ExecuteCodeTool.kt:82-144` 用 `ContextFactory` + `initStandardObjects`，没有 `ClassShutter`，`Packages`/`java` 可达，可 `java.lang.Runtime` 逃逸。注意威胁模型：agent 本身已有 `shell_execute`，所以该逃逸对"已被提示注入的 agent"并不增加权限；它的意义是 `execute_code` 宣称的脚本沙箱不成立。严重度取决于是否把 execute_code 当更严的边界，建议按 High 处理但说明前提。 |
| `S-14` | ✅ 成立，**High → Medium** | `ShellExecutor.kt:162`（`Log.d("Executing: $command")`）、292（超时带完整命令）、304（异常带完整命令）；`ExecutionCoordinator.kt:206` 截断 120 字符但仍含命令前缀。`Log.d` 在 release 仍编译，logcat 可被 adb/bugreport 读取。修法：只记 tool、hash、退出码、耗时；确需排查时走受控诊断开关。 |
| `SEC-N1` | ❌ **不成立** | 见 §4.1。`NativeOffload.kt:286` 记录的是 `envc=$envc`，是**数量**不是环境变量内容；全文件没有输出 `env` map 的日志。真实风险是同一行的 `argv=$argv`（`curl -H Authorization: ...` 之类会进日志），应并入 `S-14` 而不是单列。 |

### 2.4 长会话、数据库与内存

| ID | 判定 | 复核证据与修正 |
|---|---|---|
| `LONG-1` | ✅ 成立 | `AppDatabase.kt:469-493` 的 `MIGRATION_20_21`：`UPDATE messages SET sort_order = (SELECT COUNT(*) ... WHERE prior.session_id = messages.session_id AND ...)`，每行一次相关子查询 = O(N²)（按 session 分组）。10 万消息升级会长时间持写锁，ANR/迁移中断风险真实。 |
| `LONG-2` | ✅ 成立，证据需修正 | `searchSessions`（`ChatDao.kt:202-208`）用 `m.parts_json LIKE '%q%'`，全表 + 无法用索引；`SessionListViewModel.kt:377` 调用。第二处引用 `findFirstMessageSnippet:878-894` **不是 LIKE**，是 `instr(lower(parts_json), lower(:query))`——仍是全表函数扫描，但没有通配符回溯；且 `buildContentHits`（1178-1189）对每个结果会话再查一次，N+1 成立。修法：FTS5/规范化搜索列；至少先加 `session_id` 维度分页 + 缓存 snippet。 |
| `LONG-3` | ✅ 成立，**High → Medium** | `ResidentWindow.kt:110-139`：`byteCut` 对每个候选切点调 `isLegalBoundary`，后者从该点扫到结尾（114-127），再由 `firstLegalCut` 逐点重扫，最坏 O(n²)。已有 `suffix[]` 字节预计算（82-85）消除了序列化开销，剩下的配对扫描在 4000+ 消息仍明显。修法：从右往左维护 pending 配对的增量合法性。 |
| `LONG-4` | ✅ 成立 | `mergeStreamingOverlay`（`ChatFlatItems.kt:282-287`）在 `streaming` 非空时 `messages.map` 全量复制，4 个调用点（`ChatScreen:1634/3004/3629/4637`）都在重组热路径；长会话每 delta 重建整个列表。修法：只替换受影响的 message，保持前缀不可变。 |
| `UX-1` | ✅ 成立，**High → Medium** | `ChatScreen.kt` 实测 84 处 `collectAsState`、7609 行；`SessionListScreen` 同型。订阅过宽导致任一 Flow 变化都可能重组大范围。这是结构性性能债，不是功能缺陷；修法应渐进（拆 `ChatScreenState`、把只读叶子下沉），不要一次性重写。 |
| `LONG-6` | ✅ 成立 | `EvolutionStore.kt` 只有 `insertObservation`(28/45)、`insertProposal`(168/170)、`insert` tool_fails(228)、belief upsert(258)，全文件无 DELETE/prune/TTL。`observations`/`tool_fails` 无界增长。 |
| `LONG-7` | ✅ 成立 | `VoiceCorrectionDb.kt:372` 插入 `correction_events`，只有 `clearAll()`（389-406）手动清空；无 maxAge/上限。 |

### 2.5 文件保存与用户体验

| ID | 判定 | 复核证据与修正 |
|---|---|---|
| `SAVE-1` | ✅ 成立，**但属有意契约** | `FileSaveGuard.kt:30-34`：`dstLen == UNKNOWN_LENGTH -> true`；调用侧 `getOrDefault(UNKNOWN_LENGTH)`（`FileSaveActions.kt:44-47`）把"读长度失败"也折叠成"未知→成功"。KDoc 明说"providers legitimately report -1 — accepted rather than misjudged"，且 `FileSaveGuardTest.kt:24-27` 断言 `unknownDestinationLengthIsAccepted`。所以这是**被测试钉住的设计选择**，不能直接改成 false。正确修法见 §5.1：改为三态 `Intact/Unknown/Empty`，对 `Unknown` 做内容级验证（重开输入流读首块字节或比较 SHA-256），验证不了则提示"无法确认"，不发布成功。 |
| `SAVE-2` | ✅ 成立 | `FileSaveActions.kt:110-118` pre-Q 分支直接 `copyTo`，不校验 `dest.length()`、失败不删除。此路径 `File.length()` 可靠，补校验成本最低。 |
| `SAVE-3` | ✅ 成立 | 两处独立实现：`FullscreenImageViewer.kt:319-327`（`loadBitmap == null` 时**没有任何提示**，点击无反馈）、`487-544`（`bitmap.compress` 返回值被忽略；`dstLen != 0L` 让 -1 判成功；pre-Q 不校验）；`ImageGalleryViewer.kt:310-317` 复制同一逻辑。`FileSaveActions.saveImageToGallery`（64-125）已有正确版本，两处 view 未复用。 |
| `SAVE-4` | ✅ 成立 | `CrashFrequencyDetector.kt:842-859`：MediaStore / pre-Q 复制后无 `length`/内容校验，直接 Toast 成功。失败会走 catch，但"写 0 字节不抛异常"的 provider 仍会误报。 |
| `SCROLL-1` | ✅ 成立，范围需收窄 | 27 个 `tracedScrollToItem(` 调用点确认（`ChatScreen.kt`）；`ChatScrollController.kt:27-41` 只有 `Mutex` 串行，无 generation/取消；`SEND_FOLLOW_GRACE_MS=2000`（326 行）与 `sinceInterrupt < 1000L`（1683、3731 行）确实不一致。**但** `ChatScrollPolicy`（Pinned/Reading）已给 20+ 条路径加了 `isPinned` 闸门（如 1573、1588、3728），"27 条路径互相打架"是夸大。真正残留：发送后 100ms 的 settle 已入队不可取消；用户小幅上拖仍 `isNearBottom=true` → 保持 Pinned；1~2 秒窗口内 trailing pin 仍会拉回；session 切换的旧命令只在列表销毁时靠异常吞掉。修法仍是收编协调器 + generation + 手势优先，但改动面比报告说的小。 |
| `UX-3` | ✅ 成立 | `ChatAssistantMessageUI.kt:327-345`：`padding(horizontal=10.dp, vertical=3.dp)` + 10dp 图标 + 11sp 文本 ≈ 20-22dp 高，低于 48dp。视觉可不变，用 `Modifier.minimumInteractiveComponentSize()` 或增大外层 padding。 |
| `TTS-1` | ❌ **不成立** | 见 §4.2。`TextToSpeechManager.kt:118` 的 `Locale.US` 只是**初始化默认值**；`speak()` 的三条入口（197、237、337）都先调 `autoDetectAndSetLanguage(text)`（389-406），Han 字符走 `Locale.SIMPLIFIED_CHINESE`，并且支持 `preferredVoiceName` 指定音色。中文回复不会用英文音素朗读。 |

### 2.6 MCP、配置、终端及其他

| ID | 判定 | 复核证据与修正 |
|---|---|---|
| `MCP-STDIO-1` | ⚠️ **部分成立** | 见 §3.3。`McpStdioClient.kt:131` 用 `obj.optInt("id", Int.MIN_VALUE)`，JSON-RPC 合法的 string id 会被丢弃 → 请求等满 timeout。但 `rpc()` 有 `queue.poll(timeoutMs)`（181），**不存在"永久等待"**；reader 异常退出（143）确实没有 `failAllPending`，剩余请求会逐个超时（默认可能几十秒），表现为 MCP 工具长时间无响应，而非挂死。修法：id 用 `Any` 原值比较（或 `optString` 归一）、reader 退出时统一失败 pending、必要时重连。 |
| `CFG-1` | ✅ 成立 | `ConfigBridge.kt:279`（`writeFields`）与 `940`（audit revert）用 `runBlocking` 包 `performWriteBatch`，后者进入 `ConfigConfirmationGate`，超时 `TIMEOUT_MS=120_000`（`ConfigConfirmationGate.kt:45`）。注释也承认"bridge runs on the offload worker thread"。worker 被占满后所有 offload 工具级联阻塞。修法：确认门改 suspend + `withTimeout` + 取消传播，或每 session 独立队列。 |
| `MCPOAUTH-1` | ✅ 成立 | `MCPOAuthController.kt:260` 固定 `LOOPBACK_PORT=54546`；69 行只在 redirect URI 自带端口时用它，否则回落到常量，未传 `fallbackPorts`（`OAuthCallbackServer` 支持该参数但此处未用）。端口被占则登录失败。 |
| `MCP-FT-1` | ✅ 成立 | `FileTools.kt:152-166`：先 `file.readBytes()` 全量入堆，再 `Base64.encodeToString` 生成 4/3 倍字符串，8MB 上限下峰值约 8+10.7+JSON 包装 ≈ 20MB+，并发翻倍。修法：分块读 → 分块 base64 → 流式写响应；或直接回二进制 + 分片。 |
| `TERM-1` | ✅ 成立 | `TerminalScreen.kt:105` `delay(500)` 后发 `initCommand`；慢设备/首次冷启动时 shell 未就绪，首条命令丢失。修法：PTY 输出 ready marker（如提示符）或 session 状态事件，超时再报错。 |
| `SEND-DIAG-1` | ✅ 成立 | `ChatViewModelSendExt.kt:351` 每次发送 `println("[T-STALL-DIAG] send PRE-ACQUIRE ...")`；同项目的 `SessionConcurrencyManager`、`AgentForegroundService` 已改用 `AppLogger`（注释明确说"not System.out"），此处是遗漏。 |
| `OAUTH-FP-1` | ❌ **不成立** | 见 §4.3。`OAuthManager.kt:154` 比较的是 `tokenFingerprint(blob)`（SHA-256 十六进制串），不是密钥本身；摘要比较不需要常量时间，因为泄露"摘要不相等"不提供可利用信息。该条是安全规范的形式主义套用，改与不改都没有安全收益。 |

---

## 3. 三项"机制需修正"的详细说明

### 3.1 `ICU-1`：结论对，因果链错

报告说"`blocksLru` 以整段文本为 key，流式期每 tick 变化 → 必 MISS → 全量重解析"。实际：

- live 分支（`StreamingMarkdownBlockBody.kt:121-179`）**不读 `blocksLru`**，直接 `parseMarkdownBlocks(displayContent)`；缓存只在 >2000 字时写入，供 stream-end 后冻结分支命中（163-173）。
- 所以"缓存 key 设计导致 MISS"不是 live 成本的来源；来源是**块层根本没有增量结构**，而 inline/math 层已有（`StreamingMarkdownParseCaches.kt:130-181`）。
- 单 fragment 有 8K 降级上限（`LIVE_FRAGMENT_DEGRADE_CHARS=8000`），超过即只渲染尾部纯文本；因此"完全无上限"不成立。但一个长消息会被拆成多个 ≤8K fragment，每个 tick 仍要对当前 fragment 全量分类；表格/代码围栏尾部还会走 `prewarm` 的非增量路径。

**修订修法**（与报告方向一致，但优先级和验收不同）：

1. 先加度量：`StreamRenderProfiler` 已有 `recordParse`；补 native 分配/Java 堆/每 tick Matcher 次数，确认用户实测崩溃能复现。
2. 块层实现"安全切点 + 冻结前缀复用"：复用 `safeInlineSplitOffset` 的思路，在空行/围栏边界切分，冻结部分直接复用已解析 block 列表，只重扫尾部。
3. 对表格/围栏尾部单独限流（它们不可增量），或对 >N 行的表格降级为纯文本。
4. 8K 上限保留，作为兜底而不是主修法。

### 3.2 `SEC-1`：机制在，边界强度未证

- 事实：`NativeOffload` 无 peer 认证、无 token，socket 名固定为 `native-offload-linux`（抽象命名空间）。
- 抽象 socket 没有文件系统权限位，唯一的隔离是 SELinux 的 `unix_stream_socket connectto` 检查。同一 app 的 proot 子进程与主进程同 UID，天然可连；**其他 app 能否连，取决于设备 SELinux 策略与是否同 domain**，报告未给 PoC。
- 影响面确实高：handler 包括通讯录、日历、通知、闹钟、无障碍、浏览器、TTS、相册、model-use。
- 建议：不要因为"可能被 SELinux 挡住"就不修——加 `LocalSocket.getPeerCredentials().uid == Process.myUid()` + 每次启动随机 token 的成本很低，属于纵深防御。但在报告里应写成"未验证的越权面"，而不是"已确认可被同设备进程利用"。

### 3.3 `MCP-STDIO-1`：string id 成立，"永久等待"不成立

- `rpc()` 每次 `poll(timeoutMs)`，超时抛 `McpError(TIMEOUT)`，不会永久挂起。
- 真正的问题是：reader 线程 `catch (_: Exception) {}`（143）后静默退出，没有把 `pending` 里的请求统一失败，于是每个在途请求都要等满自己的 timeout；如果 timeout 配置得长（startup 可能 60s+），用户看到的是"MCP 工具卡住很久"。
- string id 兼容性：MCP 服务端用 string id 的比例不高，但 JSON-RPC 2.0 合法，应支持。修法：`id` 用 `Any`（Int/String）原值比较，或把两边都 `toString()` 归一。

---

## 4. 三项误诊

### 4.1 `SEC-N1`：环境变量没有进日志

`NativeOffload.kt:286` 原文：

```kotlin
Log.d(TAG, "recv pid=$pid name='$name' argc=$argc argv=$argv cwd=$cwd envc=$envc")
```

`envc` 是环境变量**个数**。全文件没有 `env=$env`、没有遍历 `env` 的日志。该条应撤销。

但同一行的 `argv=$argv` 是真实风险（命令行里可能带 `Authorization`、`--password`、URL token），应并入 `S-14` 一起脱敏（记录 `name` + argv 的 hash 或仅首参数）。

### 4.2 `TTS-1`：语言是逐条自动检测的

`TextToSpeechManager.kt`：

- 118 行 `setLanguage(Locale.US)` 只在 `onInit` 设默认。
- 197、237、337 三处 `speak` 入口都先调 `autoDetectAndSetLanguage(text)`。
- 389-406：优先用用户选的 `preferredVoiceName`；否则 `HAN_REGEX.containsMatchIn(text)` → `Locale.SIMPLIFIED_CHINESE`，否则 `Locale.US`。
- 355 行还有公开的 `setLanguage(locale)`。

结论：中文回复会切到中文 locale，不存在"中文用英文音素朗读"。若要改进，是把 `HAN_REGEX` 扩展为"含假名/韩文/西里尔"等更完整的语种探测，而不是"修 Locale.US 硬编码"。建议降为 Low 的体验优化项，或不列入。

### 4.3 `OAUTH-FP-1`：比较的是 SHA-256 摘要，不是密钥

```kotlin
if (tokenFingerprint(blob) != mark) return false
```

`tokenFingerprint` 是 SHA-256 的十六进制串。常量时间比较的意义是"不泄露原始秘密的前缀信息"；这里比较对象是摘要，攻击者即使测出时序也只能学到摘要的相等性，无法反推 token。该条没有安全收益，撤销。

---

## 5. 对 `SAVE-1` 的正确修法（避免撞测试）

现状契约：`unknown → true`，测试 `unknownDestinationLengthIsAccepted` 钉死。直接改成 `false` 会让所有 SAF provider（大量返回 -1）判失败，反而制造"保存失败"误报。

建议改成三态，并在调用侧分层处理：

```kotlin
enum class SaveVerdict { INTACT, EMPTY, UNKNOWN }

fun verify(srcLen: Long, dstLen: Long): SaveVerdict = when {
    dstLen == UNKNOWN_LENGTH -> SaveVerdict.UNKNOWN
    srcLen <= 0L -> if (dstLen == 0L) INTACT else EMPTY
    dstLen > 0L -> INTACT
    else -> EMPTY
}
```

- `EMPTY` → 删除目标、Toast 失败（现有行为）。
- `UNKNOWN` → 追加一次内容级验证：重新 `openInputStream(uri)` 读首 4KB，非空即 `INTACT`；读不到或仍为空 → 提示"已写入，但无法确认内容"，**不显示"已保存到"**，也不删除（避免删掉用户可见但暂未 flush 的文件）。
- MediaStore 的 `IS_PENDING` 只在 `INTACT` 后清；`UNKNOWN` 走内容验证后再决定。
- 同步更新 `FileSaveGuardTest`，把 `unknownDestinationLengthIsAccepted` 改为 `unknownIsNotTreatedAsSuccess`，并新增"内容验证通过→INTACT"的用例。

这样既解决"0 字节显示成功"，又不误伤合法 provider。

---

## 6. 修订后的执行顺序

原报告的阶段划分方向正确，但 P0 里漏了用户实测的 `ICU-1`，且把并发放在流式之前。建议如下（按"用户可见症状 → 正确性 → 性能 → 安全 → 卫生"）：

### P0：用户实测症状（先做，仍可分三支并行）

| 顺序 | 项 | 内容 | 完成门槛 |
|---|---|---|---|
| 1 | `ICU-1` | 先埋点量化，再做块层增量（冻结前缀 + 尾扫），表格/围栏尾部限流 | 长回复流式期间每 tick 解析耗时与 Matcher 次数不随已渲染长度线性增长；native/Java 内存曲线平坦 |
| 2 | `SAVE-1/2/3/4` | 统一 `FileSaveActions` + 三态守卫；图片保存复用同一路径；pre-Q 校验并失败删除 | provider 返回 -1 不误报成功；`loadBitmap` 失败有提示；0 字节不发布 |
| 3 | `SCROLL-1` | `ChatScrollController` 加 generation/取消；统一 1s/2s 常量；手势开始即作废旧命令 | 发送后立即上拖、stream-end 与 FAB 并发、session 切换，旧命令均不执行 |

### P1：正确性与稳定性

`CRASH-5`（未闭合 fence 降级）、`CRASH-7`（equals 用内容）、`PROV-1`（共享 SSE parser）、`PROV-2`（Anthropic/Gemini TTFB 超时）、`CRASH-1`（`.update {}` + 并发测试）、`CRASH-2`（安全转型/DI）、`CRASH-4`（深度上限 16）、`CRASH-8`（崩溃写入隔离）、`H-5`（CAS 消费）、`H-3`（onDestroy shutdown）。

> 顺序理由：`PROV-1/2` 与 `CRASH-5/7` 都改流式链路，先修它们再做 P0-1 的增量解析，否则测试基线不稳定。但 P0-1 的**埋点**可以先上。

### P2：性能与数据

`LONG-1`（迁移改批量/窗口函数）、`LONG-2`（FTS5 或搜索列；先去掉 N+1）、`LONG-3`（线性配对扫描）、`LONG-4`（按 id 增量替换）、`LONG-6/7`（prune/TTL）、`MCP-STDIO-1`（string id + failAllPending）、`UX-1`（拆分状态，渐进）。

### P3：安全边界

`SEC-2`（代理 allowlist/token）→ `SEC-1`（peer UID + token）→ `SEC-4`（CORS/Origin + 强制 token）→ `SEC-5`（ClassShutter）→ `S-14`（日志脱敏，合并 `SEC-N1` 的 argv）→ `SEC-3`（预编译 + 长度上限）。

> 先边界后日志：否则调试日志会泄露安全测试里的凭据。

### P4：低优先级

`UX-3`（48dp 触控）、`TERM-1`（ready handshake）、`SEND-DIAG-1`（println → AppLogger）、`MCPOAUTH-1`（动态端口）、`MCP-FT-1`（流式 base64）、`CFG-1`（suspend 确认门）。

### 撤销项

`SEC-N1`、`TTS-1`、`OAUTH-FP-1`：不改代码。`TTS-1` 若要保留，改写为"扩展语种探测"并降为 Low。

---

## 7. 建议先补的测试（避免改一处崩三处）

| 模块 | 测试 |
|---|---|
| 保存 | `FileSaveGuardTest` 改三态；新增 `saveAsToDocumentUri` 的 fake resolver 用例（-1、0 字节、延迟可见、删除失败）；`saveImageToGallery` 的 `compress=false` 用例 |
| 滚动 | `ChatScrollControllerTest` 增加 generation：旧命令在新 generation 下不执行；手势开始取消队列；session 切换淘汰旧命令 |
| 流式 | SSE parser 单测：多行 `data:`、`event:`、`id:`、`[DONE]`、空行边界；Anthropic/Gemini 用 MockWebServer 模拟"连接建立但无首字节" |
| 解析 | 未闭合 fence、>16 层 blockquote、等长替换（equals） |
| 并发 | `SessionActivityTracker` 并发 setActive/setInactive 各 100 次后集合一致；`SubAgentActivityTracker` 同型 |
| 数据库 | `MIGRATION_20_21` 用 1k/1w/10w 消息测耗时上限；搜索 N+1 的查询次数断言 |

---

## 8. 本次复核未覆盖 / 需要设备验证的部分

1. **`ICU-1` 的内存数据**：9GB/12.6 万次分配无法从源码验证，需要 per-tic native 分配采样 + 长流复现。
2. **`SEC-1` 的跨 UID 可达性**：需要一台设备上的 PoC（另一个 app 尝试 connect 抽象 socket），或查 SELinux 策略。
3. **`SEC-4` 的实际配置**：需确认发布版默认是否带 token；若默认强制 token，严重度应降为 Medium。
4. **`SCROLL-1` 的用户可见频率**：需要录屏/Perfetto + `tracedScrollToItem` 的 trace 对齐，确认 1~2s 窗口是主因。
5. **`SAVE-1` 的 OEM 行为**：需要 SAF provider 返回 -1 的实测设备（国产 ROM 常见）。
6. **`H-3` 的服务重建频率**：需 `onStartCommand`/`onDestroy` 计数日志。

---

## 9. 结论

- 报告整体可信，**不要重写**；按 §6 的顺序执行，撤销 §4 三项。
- 最大的认知修正：`ICU-1` 的根因是"块层缺增量"，不是"缓存 key 设计"；`SAVE-1` 是被测试钉住的契约，需要三态改造；`SCROLL-1` 的战场比"27 条路径"小，但 generation/取消确实缺失。
- 最大的风险不是漏修，而是**在没有回归测试的情况下同时改流式解析 + 滚动 + 保存**。建议每个 P0 分支先落测试，再动实现。
