# 追加问题核查与修复（2026-10-17）

针对“记忆面板主线程 IO / UI 硬编码中文门禁 / 流式 Markdown ICU 内存”三条逐项核对。

## 1. 记忆面板主线程 IO — 存在，已修复

核对结果（修复前）：

- `ui/chat/SessionMemorySheet.kt:74-75` 确实是
  `remember(memoryRepository, context, sources) { buildAutoInjectedItems(...) }`；
  `buildAutoInjectedItems` 会读取 GLOBAL.md、daily log、persona 文件，等于在
  composition（主线程）里做文件 IO，首帧被阻塞。
- `ui/settings/MemoryManagementScreen.kt:82` 的 `LaunchedEffect` 里直接
  `memoryRepository.listAllFiles()`；`LaunchedEffect` 默认跑在 Main，仓库实现
  `MemoryRepository.listAllFiles()` 会 `readText()` 每一个 daily log 做预览。
- 同文件 `:165` 删除确认回调里同步 `deleteFile()` + `listAllFiles()`，也在主线程。

修复：

- `SessionMemorySheet` 改为 `produceState` + `withContext(Dispatchers.IO)`，
  文件读取离开主线程，完成后发布列表；
- `MemoryManagementScreen` 首次加载和删除后的刷新都改为
  `withContext(Dispatchers.IO)`；删除刷新通过 `rememberCoroutineScope()` 启动，
  不再在点击回调里阻塞。

没有新增用户可见字符串，也没有改 repository 的同步 API（其它调用方仍可复用）。

## 2. UI 硬编码中文 + 缺失门禁 — 存在，已补门禁

核对结果：

- `scripts/ci/` 确实只有 `check_architecture_policy.py`、
  `check_new_untranslated.py` 和 baseline JSON，没有 CJK 棘轮；
  `check_new_untranslated.py` 只看 `strings.xml` 新增 key，看不到 composable
  里直接写的中文。
- 统计口径差异：
  - 精确扫描 **Kotlin 字符串字面量**（忽略注释/字符字面量）：
    `ui/` 下 **25 个文件、100 处**；
  - 任意 CJK 字符（含注释、KDoc）：**53 个文件**；
  - 用简单引号正则粗扫：44 个文件。
  你提到的 47 个文件应在后两种口径之间；精确门禁采用第一种。

修复：

- 新增 `scripts/ci/check_cjk_literals.py`：扫描
  `app/src/main/java/com/openminis/app/ui/**/*.kt` 的字符串字面量，
  注释/KDoc 不计；
- 新增 `scripts/ci/cjk-baseline.json`：冻结当前 25 文件 / 100 处的历史债；
- 规则：baseline 文件不得增长；新文件不得出现 CJK 字面量；减少后必须
  `--update-baseline`（只降不升）；
- `.github/workflows/pr-check.yml` 增加 “No new hardcoded CJK in UI” 步骤。

当前门禁通过：

```
CJK hardcoded-string ratchet: OK (25 files, 100 literals, 25 baselined)
```

## 3. 流式 Markdown 的 ICU 正则/native 内存 — 部分修复，仍有残余

### 已经改变的部分

聊天实际渲染路径是 `ChatScreen -> MarkdownBlock -> MarkdownBlockBody`：

- 多 block 的 live fragment 走
  `parseMarkdownBlocksIncremental`：只重解析最后一个 block，之前 block 复用；
- live fragment 超过 `LIVE_FRAGMENT_DEGRADE_CHARS = 8_000` 时直接降级为
  有界 plain-text tail，不再逐 tick 跑块级解析；
- 解析在 `Dispatchers.Default`；块级正则在文件级 hoist（不再每次
  `Pattern.compile`）；
- `BoundedText.MAX_ICU_INPUT_CHARS = 16_384` 限制单行 ICU 输入；
- **本次新增**：单个 growing paragraph 的纯文本同段追加走 fast path，
  不再每个 tick 重跑块级正则；只有出现换行或媒体语法时才回退完整解析。

因此“每收到一小段就把整条消息从头到尾重新解析”的旧路径，在当前聊天渲染链路里
已经不成立；原来的 12.6 万份/9GB 级别的整段复制不应再按同样速率发生。

### 仍然存在的残余

- fast path 只覆盖“最后一个 block 是 Paragraph，且追加内容不含换行/媒体语法”；
  其它情况仍会重解析最后一个 block（最大 8KB 的 live fragment）；
- 在这些回退路径里，每个块级正则仍会为每行创建 `Matcher`，ICU 仍会复制输入到
  native；目前没有 native 内存预算/强制回收，也没有本次设备上的 native 内存曲线；
- `StreamingMarkdownTextBody.kt` 里仍保留一条 `parseMarkdownBlocks(displayContent)`
  的整段解析路径，但当前没有调用方（`StreamingMarkdownText` /
  `StreamingMarkdownTextBody` 只在声明处出现），属于遗留死路径；若以后接回，
  需要同样改成增量。

结论：**机制上已从“整条消息重解析”降为“最后一个 block / 纯文本追加”，但
ICU native 复制没有被彻底消除，不能用“9GB 崩溃已根除”来表述；需要再做一次
真机 native 内存压测才能给最终结论。**

## 4. 验证

- `:app:testReleaseUnitTest`：**3361 tests, 0 failed, 4 skipped**
  （新增/调整了增量解析测试）；
- 架构门禁：OK（908 files；SessionMemorySheet 由 840 收紧到 838）；
- CJK 门禁：OK；
- 代码编译通过。未提交、未推送。
