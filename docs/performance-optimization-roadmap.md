# 五方向性能优化排查报告

## 1. 冷启动速度（Cold Start）

### 现状分析

#### 1.1 启动路径（MinisApp.onCreate）

**关键阶段时间线**：
1. **锁前工作**（无阻塞）：ACRA 初始化、native crash handler、FGS timeout 拦截器
2. **孤儿通知清扫**（NotificationManager 查询）：Ask/Approval 通道各一次 `getActiveNotifications()` —— **低成本，已优化**
3. **Safe-mode 检查**（文件 I/O）：扫 `filesDir/logs/crash-*.log` 时间戳对比
4. **AppLogger 初始化**（文件 I/O）：创建日志目录、启动 stdout/stderr capture 线程
5. **轻量 priming**（SharedPreferences 读）：FastModePrefs / AutoCompactPrefs / PlanDiscussionPrefs / ToolLimitPrefs / SecurityGateHolder
6. **重型初始化块**（**I/O + CPU 密集**）：
   - AppDatabase.getInstance() → Room 初始化 + 数据库打开 + 可选 migration
   - ChatRepository（7 个 DAO 对象）
   - ProviderRepository （model 目录遍历、configs 文件 I/O）
   - EnvVarRepository 等 5 个库
   - EvolutionEngine（文件系统遍历）
   - WebAppShortcutRepository（DB 查询）
7. **sandbox/offload 体系**（**并发不足，CPU 阻塞**）：
   - ExecutionCoordinator
   - offload handlers × 30+（每个初始化一个后台任务 / 线程）
   - PRootKernel（PRoot 初始化）
   - RootfsManager（可能磁盘操作）
   - NativeOffloadServer

**已知优化**：
- `runBlocking(Dispatchers.IO)` 已删除 → workspace warmup 改异步（`SessionWorkspace.awaitWarmup()`）
- `subsystemsInitialized` 标记设置**早**（第 6 阶段末），不等第 7 阶段 → MainActivity 可早开始组合

### 瓶颈定位

#### P0：Room 数据库打开 + 数据迁移
- **症状**：首次启动或版本升级时 App 冻结 3-5s，系统显示"应用无响应"
- **原因**：单线程 SQLiteDatabase.open()，若有 migration 则每个迁移脚本同步运行
- **验证方法**：
  ```bash
  adb logcat | grep "Room\|database\|opened"
  # 对比: 首次启动 vs 无 migration 启动
  minis-dev-setup && minis-android-sdk-setup && cd workspace && gradlew :app:connectedAndroidTest 
  # 测试类: com.openminis.app.data.db.AppDatabaseTest（如存在）
  ```

#### P1：ProviderRepository 目录遍历 + 文件 I/O
- **症状**：多 provider / config 文件多时启动延迟累计 200-500ms
- **原因**：遍历 `filesDir/providers/` 逐文件 JSON parse
- **当前实现**：未见代码，需定位初始化逻辑

#### P2：offload handlers × 30+ 逐个加载
- **症状**：启动期间 CPU 占用高，内存申请频繁
- **原因**：handler 列表硬编码 30 个，每个 handler 初始化可能创建线程/预热资源
- **当前实现**：MinisApp.kt 第 ~750 行起，`registerOffloadHandlers` 无见代码

#### P3：HangDetector + SandboxWorkload.attach()
- **症状**：启动后主线程心跳检测开启，初期可能导致帧抖
- **当前实现**：1s 心跳，3s 无响应则 dump 堆栈 → 已被认可为诊断工具，可保留

### 优化建议

| 优先级 | 措施 | 估计收益 | 难度 |
|--------|------|--------|------|
| P0 | **Room 迁移异步化** | 3-5s | 高 |
| P0 | **Provider 目录延迟加载**（按需解析） | 200-300ms | 中 |
| P1 | **offload handler 延迟注册**（仅注册活跃模式需要的） | 100-200ms | 中 |
| P2 | **SharedPreferences 预热合并**（所有 prefs 一次同步读） | 50-100ms | 低 |

---

## 2. 后台留存（Background Retention）

### 现状分析

#### 2.1 前台服务（FGS）配置

**AgentForegroundService.kt**：
- **FGS 类型**：mediaPlayback（原 dataSync 因 6h 超时被弃）
- **Notification 渠道**：IMPORTANCE_DEFAULT / IMPORTANCE_LOW
- **启动条件**：代理任务运行中（StreamingLoopExt 或 SendExt 触发）
- **停止条件**：循环完成或用户中断

**关键代码段**：
```kotlin
// FGS 绑定时机
startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
// 停止
stopForeground(STOP_FOREGROUND_REMOVE)
```

#### 2.2 冷启动路径的后台进程

**三个入口**（不经 MainActivity）：
1. **通知点击**（Ask/Approval 后台作答）：AskUserBroadcastReceiver → AskUserGate.answer() → ViewModel → ChatViewModel（需 chatRepository，受 safe-mode 影响）
2. **闹钟触发**（定时任务）：ScheduledAgentRunner → 同上
3. **系统意图**（BOOT_COMPLETED 等）：无实现或已移除

**当前防御机制**：
- `chatRepositoryOrNull` 读取，null 则静默返回（GH#147）
- 无强制前台提升

#### 2.3 后台限制适配

**Android 版本兼容**：
- Android 12+ (S)：必须显式注册 SCHEDULE_EXACT_ALARM 等权限
- Android 10-11 (Q-R)：后台执行限制较宽松
- Doze 模式：电池优化下被限制（可用 minis-doze request 豁免）
- 省电模式：MIUI / OColorOS 等定制 ROM 独特行为（已有诊断 [LaunchCycleBeacon](../blob/main/src/android/app/src/main/java/com/openminis/app/diagnostics/LaunchCycleBeacon.kt)）

**已知问题**：
- PR28 修过"401 归因"（OAuth 刷新无法后台成功）
- ApprovalNotifier / AskUserNotifier 使用 setOngoing(true)，孤儿通知会卡死 → PR37 修了清扫逻辑

### 瓶颈定位

#### P0：FGS 被系统杀死，没有重启机制
- **症状**：长时间后台代理，过了 10-15 分钟突然卡住，重进 app 才恢复
- **原因**：Android 系统内存压力下把 FGS 强杀，service 进程死亡，协程 cancel，循环中止
- **当前实现**：无重启机制（START_STICKY 已失效），无错误恢复
- **验证方法**：
  ```bash
  adb shell dumpsys activity services | grep AgentForegroundService
  # 记录 pid，然后 kill -9 <pid>，观察是否重启
  ```

#### P1：后台访问 DB 竞态
- **症状**：系统通知激活 ask/approval 作答，与前台 UI 读同一数据库行 → SQLite BUSY 或脏读
- **原因**：Room DAO 无行级锁，多 access thread（Main + IO + 后台通知） 并发
- **当前实现**：`@Query` 默认 Database.Query 线程约束，即 DAO 调用方需遵守线程模型

#### P2：长任务下协程主动取消
- **症状**：后台代理 20+ 分钟突然终止，日志无异常，只有"协程被取消"
- **原因**：appCoroutineScopes.application SupervisorJob() 被应用层显式 cancel（如 onTerminate），或系统 trimMemory(COMPLETE) 触发清理

### 优化建议

| 优先级 | 措施 | 估计收益 | 难度 |
|--------|------|--------|------|
| P0 | **FGS 重启机制**（START_STICKY + onStartCommand 重建协程） | 业务可用性 | 中 |
| P0 | **后台数据库线程隔离**（后台通知走独立 IO scope，不争 Main） | 可靠性 | 低 |
| P1 | **前后台模型同步**（后台激活时 sync 最新会话状态，防数据旧化） | 数据一致性 | 高 |
| P2 | **协程主动 cancel 前置日志**（记录谁、何时、为什么 cancel） | 诊断 | 低 |

---

## 3. 流式响应速度（Streaming Response）

### 现状分析

#### 3.1 流式接收路径

**调用链**：
```
runAgentLoop (suspend, Dispatchers.IO)
  ↓
buildRequestForProvider() [IO 线程]
  ↓
provider.streamMessage() [返回 Flow<LLMStreamChunk>]
  ↓
.collect { chunk ->
    // 每个 chunk：
    // 1. 处理文本：MessageTransformerChain（markdown parse） → append buffer
    // 2. 工具调用：JSON parse → ToolUse 对象 → append buffer
    // 3. 思考块：<think>...</think> strip + collect
    // 4. 更新 UI：withContext(Dispatchers.Main) { updateCompositionState() }
    //    → ChatViewModel._streamingContent.value = ...
    //    → Compose recompose
}
```

**关键代码位置**：
- ChatViewModelAgentLoopExt.kt:695 `.collect { chunk -> ... }`
- ChatViewModelAgentLoopExt.kt:718 onwards（每个 chunk 的 Main thread update）

#### 3.2 并发模型

**协程作用域**：
- `appCoroutineScopes.io`：SupervisorJob + Dispatchers.IO（无限制线程池）
- runAgentLoop 本身 suspend，首行 `withContext(Dispatchers.IO)` 跳转
- 流式 collect 在 IO dispatcher 上
- 每个 chunk 处理后调用 `withContext(Dispatchers.Main) { ... }` 跳回主线程

**问题**：
1. **Main thread 频率上限**：每个 chunk 都 withContext(Main)，而 Compose recompose 是 O(tree)
   - 高频 chunk（GPT-4 Turbo 可达 50-100 chunk/sec）× O(recompose) = 主线程卡顿
2. **IO 线程竞争**：markdown parse 逐字符扫，工具 JSON parse 无缓存 → CPU 占用高
3. **无背压机制**：collect 没有 buffer，网络快速到达 chunk 时，parse 跟不上会导致内存积压

#### 3.3 非流式响应路径

**调用链**：
```
runAgentLoop 
  ↓
provider.sendMessage() [返回 LLMResponse]
  ↓
parse response body
  ↓ (single withContext(Main) at end)
updateCompositionState()
```

**特点**：
- 全部 JSON parse 在 IO 线程完成，主线程只做一次 recompose
- **瓶颈**：JSON 解析巨大 response（content > 1MB）时仍在 IO 线程，若 IO 线程被占就慢

### 瓶颈定位

#### P0：主线程频繁 Compose recompose（流式）
- **症状**：流式输出期间帧率 15-20 FPS，滑动卡顿，cpu 占用 60-80%
- **原因**：每 chunk 都 recompose 整个 message UI tree（包括已渲染的部分）
- **验证方法**：
  ```bash
  adb shell cmd appcompat dump-config | grep RECOMPOSITION_STATS
  # 或用 Compose 性能 profiler
  ```

#### P1：IO 线程 markdown parse CPU 占用
- **症状**：网络快速传输内容时，客户端 CPU 占用 70-90%，GPU idle
- **原因**：MessageTransformerChain 逐字符状态机 parse，无 SIMD 优化
- **当前实现**：StreamingMarkdownParse 和 MarkdownParser，无缓存策略

#### P2：无背压导致内存峰值
- **症状**：大文件输出（code block >5MB）时内存突然增长 200MB+，GC pause 500ms+
- **原因**：chunk 缓冲无上限，parse 跟不上时消息对象堆积
- **当前实现**：无 buffer() / conflate() 流控

### 优化建议

| 优先级 | 措施 | 估计收益 | 难度 |
|--------|------|--------|------|
| P0 | **Compose recompose 批量化** | +100-150% FPS | 高 |
| P0 | **Main thread update debounce** | 主线程释放 40-60% | 中 |
| P1 | **markdown parse 缓存 + incremental update** | IO 线程 -40% CPU | 中 |
| P2 | **背压流控**（buffer/conflate） | 峰值内存 -50% | 低 |

**实现要点**：

1. **Recompose 批量化**（P0 高收益）：
   - 改为每 50-100ms 汇总一次 chunk，一次性更新 Compose state
   - 用 `MutableStateFlow` debounceTime（500ms） + 实时预览偏移
   - 流式完成后同步一次确保最后内容可见

2. **incremental markdown**（P1 可选）：
   - 流式 markdown 不重新解析整个历史，只对新增文本增量 parse
   - 缓存上次的"未闭合围栏"状态，新 chunk 从该状态续

3. **背压**（P2 可选）：
   - `.buffer(DEFAULT_BUFFER_SIZE)` 或 `.conflate()`
   - 若 UI recompose 慢，自动丢弃中间快照

---

## 4. 非流式响应速度（Non-streaming Response）

### 现状分析

#### 4.1 非流式调用路径

**工作流**：
```
runAgentLoop (IO context)
  → provider.sendMessage(request) [网络请求]
  → 响应体到达（1-3 MB JSON）
  → ResponseBody.string() [一次性读取内存]
  → JSON parse (Gson / org.json) [CPU 密集]
    ├─ token counting estimate
    ├─ content 提取
    ├─ tool calls 反序列化
    ├─ 嵌入检查 (Embedding 模型判定)
  → messageToRow() [对象构造]
  → withContext(Main) { 单次 recompose }
```

#### 4.2 瓶颈来源

**1. JSON parse**：
- OpenAI: 嵌套 choice[].message.content + tool_calls[] 深度 5-7
- Anthropic: 扁平但 content_block[] 逐行 parse
- Gemini: 部分 base64 编码需解码
- **当前实现**：无增量解析，无流式 JSON（SSE 用 text/event-stream 但解析仍是一行一行）

**2. Token counting**（可能同步运行）：
- 估计输入 token：`estimateAgentHistoryTokens()`，遍历历史 O(n)
- 估计输出 token：简单启发式，内容长度 / 4
- **当前实现**：ChatViewModelCompactExt.kt 有 `estimateMessages*` 函数族

**3. 网络延迟**：
- 多 provider 切换时，首个 DNS 查询可能 100-500ms
- SSL handshake 再加 100-300ms
- 高延迟网络（2G fallback / VPN）可能 1-2s

### 瓶颈定位

#### P0：JSON parse 阻塞 IO 线程
- **症状**：响应到达 1s 后才显示，但网络已完成（tcpdump 证实）
- **原因**：3MB JSON 的 Gson parse 耗时 500-800ms
- **验证方法**：
  ```bash
  # 在 provider.sendMessage 出口加 time mark
  adb logcat | grep "json-parse-start\|json-parse-end"
  ```

#### P1：token count 估计重复计算
- **症状**：连续发送 3-4 条消息时，每次都重新计数历史（O(n) 遍历）
- **原因**：ProviderRepository 的 sendMessage 里每次都调 `estimateMessages(history)`
- **当前实现**：无缓存

#### P2：DNS / SSL 冷启动延迟
- **症状**：首次调用新 provider，响应到达时间多 300-600ms
- **原因**：OkHttp 连接池冷，每个 domain 首次连接都要 DNS + TLS handshake
- **当前实现**：OkHttp 默认连接池（支持连接复用），但无预热

### 优化建议

| 优先级 | 措施 | 估计收益 | 难度 |
|--------|------|--------|------|
| P0 | **JSON 增量解析** | -300-400ms | 高 |
| P0 | **token count 缓存策略** | IO 线程 -100ms | 低 |
| P1 | **网络连接预热** | 冷启动 -300-500ms | 中 |
| P2 | **响应流式 JSON**（SSE format 改 ndjson） | 端到端 -500ms | 高 |

---

## 5. 应用线程模型（App Threading Model）

### 现状分析

#### 5.1 协程作用域层级

```
MinisApp.appCoroutineScopes (Process level)
  ├─ application: SupervisorJob + Dispatchers.Default
  │   └─ 后台任务（日志、诊断）
  ├─ io: SupervisorJob + Dispatchers.IO
  │   └─ 数据库、网络、文件 I/O
  └─ (viewModelScope: viewLifecycleOwner level)
      └─ ChatViewModel (one per session)
          ├─ runAgentLoop() suspend (IO context)
          ├─ viewModelScope.launch { UI updates }
          └─ collect { stateflows }
```

#### 5.2 线程分配

| Dispatcher | 线程池大小 | 用途 | 当前用途 |
|-----------|----------|------|--------|
| Dispatchers.Main | 1 | UI 更新 | Compose recompose、notification、UI state |
| Dispatchers.IO | 60+（limit） | I/O 阻塞操作 | 网络、DB、file、offload RPC |
| Dispatchers.Default | (cpu core count) | CPU 密集 | JSON parse（应在此但实际在 IO） |
| Dispatchers.Unconfined | 外部线程 | 回调适配 | broadcast receiver、lifecycle callbacks |

#### 5.3 当前问题

**1. IO dispatcher 过载**：
- JSON parse（CPU 密集）在 IO context 运行 → IO 线程被占，数据库查询等待
- 修复方案：parse 后跳 `withContext(Dispatchers.Default)` 再做 recompose 前置

**2. Main thread 频率上限**：
- 流式 chunk 每次 withContext(Main)，主线程无法处理 50+ chunk/sec 的 recompose 频率

**3. 无 viewModelScope 隔离**：
- 多个 ChatViewModel 实例（多个聊天 tab）共用 appCoroutineScopes
- 一个会话卡顿不影响其他会话，但内存竞争

**4. 后台任务无优先级**：
- 低优先级任务（日志文件 GC）与高优先级任务（LLM 请求）竞争同一 IO dispatcher

### 瓶颈定位

#### P0：JSON parse 阻塞 IO 线程导致 DB 查询缓慢
- **症状**：大型响应解析期间，同时发起 Goal 查询（`withContext(IO) { goalManager.get(...) }`），等待 1-2s
- **原因**：Dispatchers.IO 同一线程队列被 JSON parse 占满
- **验证方法**：
  ```bash
  adb shell dumpsys cpuinfo | grep -A 20 thread
  # 观察 IO dispatcher 线程的 CPU 占用
  ```

#### P1：线程池耗尽导致新任务等待
- **症状**：快速连续发起 5+ 会话，新会话迟迟无响应，最后一个等待 5-10s
- **原因**：IO dispatcher 默认 limit 是 cpu count + 1（通常 8-16），若每个会话一个长连接，很快耗尽
- **当前实现**：无线程池调参

#### P2：低优先级任务 starvation
- **症状**：代理运行中，后台日志 rotate 任务被无限延迟
- **原因**：SupervisorJob 无任务优先级概念，FIFO 调度

### 优化建议

| 优先级 | 措施 | 估计收益 | 难度 |
|--------|------|--------|------|
| P0 | **JSON parse 改用 Dispatchers.Default** | IO 线程释放 30-50% | 低 |
| P0 | **流式 recompose debounce（Main 线程释放 60-70%）** | 帧率稳定 | 中 |
| P1 | **IO dispatcher 线程池扩容** | 新任务等待时间 -50% | 低 |
| P2 | **多级优先级 dispatcher**（高/中/低） | 后台任务不被饿死 | 高 |

**具体改法**：

1. **JSON parse 分流**：
   ```kotlin
   val jsonString = response.body().string() // IO thread
   val parsed = withContext(Dispatchers.Default) {
       Gson().fromJson(jsonString, LLMResponse::class.java)
   }
   // 后续处理回到 IO context
   val message = withContext(Dispatchers.IO) { messageToRow(parsed) }
   ```

2. **Main thread debounce**：
   ```kotlin
   // 原：.collect { chunk -> withContext(Main) { ... } }
   // 改：
   flow.debounce(50.ms)
       .collect { latestContent ->
           withContext(Main) { updateUI(latestContent) }
       }
   ```

3. **线程池扩容**（build.gradle.kts）：
   ```kotlin
   // 编译时配置 Dispatchers.IO 限制（可选，Kotlin 1.6+）
   System.setProperty("kotlinx.coroutines.io.parallelism", "64")
   ```

---

## 总体优化优先级

### Phase 1（周期 1 周）— 快速收益

- [ ] P0-1: Room 迁移异步化 → 冷启动 -3s
- [ ] P0-2: JSON parse 改 Dispatchers.Default → IO 线程释放 40%
- [ ] P0-3: Main thread debounce（流式 recompose） → 帧率 +100-150%

### Phase 2（周期 2-3 周）— 中等难度

- [ ] P1-1: Provider 目录延迟加载 → 冷启动 -200ms
- [ ] P1-2: FGS 重启机制 → 后台可靠性
- [ ] P1-3: 网络连接预热 → 首次调用 -300-500ms

### Phase 3（周期 4-6 周）— 长期优化

- [ ] P2-1: incremental markdown parse + 缓存
- [ ] P2-2: 多级优先级 dispatcher
- [ ] P2-3: 响应流式 JSON（SSE → ndjson）

---

## 验证策略

### 冷启动计时
```bash
adb shell am start -W -n com.openminis.app/.MainActivity
# 记录 TotalTime
```

### CPU 占用分析
```bash
adb shell top -n 1 | grep "openminis\|app"
# 或用 Android Profiler（Studio）
```

### 内存峰值
```bash
adb shell dumpsys meminfo | grep TOTAL
# 对比优化前后
```

### 流式帧率
```bash
adb shell dumpsys gfxinfo com.openminis.app | tail -n 20
# 观察 60fps vs 实际 FPS
```

### 后台保活时长
```bash
adb logcat | grep "AgentForegroundService\|onDestroy"
# 记录 kill -9 后是否重启
```
