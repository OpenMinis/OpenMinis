# OpenMinis-Linux 2.0.60-linux

- versionCode **260**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

本版说明覆盖自 2.0.51 以来的全部变更（109 个提交）。

## New features / 新功能

- **AI process cards** — the AI process folds into one card per run: thinking lines and tool rows expand in place, in-flight tools count as steps, and the card settles once the reply text lands. A single-line tool activity pill, a sectioned top-bar menu and a compact session drawer complete the new chat chrome.
  AI 过程按轮次折叠成卡片：思考行与工具行就地展开、运行中的工具实时计入步数、回复正文落地后卡片定型；配套单行工具活动胶囊、分段顶栏菜单与紧凑会话抽屉。

- **Vendor logos on model rows** — the chat top bar and the provider page's model list show each model's real brand icon (lobe-icons, 358 brands, MIT); the top bar becomes a three-line layout (group / vendor / model). Unrecognized models fall back to the OpenAI mark.
  顶栏与供应商页的模型行显示真实厂商图标（lobe-icons 358 品牌，MIT）；顶栏改为「分组 / 厂商 / 模型名」三层布局，未识别模型兜底显示 OpenAI 图标。

- **Usage page time dimensions** — Today / 7 days / 30 days / All filters, a daily input-output bar chart and per-model input share bars, drawn with Canvas and aggregated off the main thread.
  用量页新增时间维度：今天 / 近 7 天 / 近 30 天 / 全部筛选、每日输入输出柱状图与模型输入占比条；Canvas 自绘，聚合在后台线程完成。

- **Dev environment suites** — five declarative toolchain bundles (android, code-search, python, nodejs, go) plus qemu-x86_64 and llama.cpp online tools. Android SDK components download on demand, shrinking the APK and every sandbox by ~36 MB.
  开发环境套件：android / code-search / python / nodejs / go 五套声明式工具链 + qemu / llama.cpp 在线工具；Android SDK 改为按需下载，APK 与每个沙箱各减约 36 MB。

- **Task anchoring** — a long autonomous run that has scrolled its own task description out of the window re-reads the session's original task in the system prompt every round, so it no longer drifts.
  任务锚定：长自治任务把任务描述滚出窗口后，系统提示词每轮重读会话原始任务，不再跑偏。

- **Connection warm-up** — the first request after a cold start or a model switch no longer pays for a full DNS + TCP + TLS handshake inline.
  连接预热：冷启动 / 切模型后的第一个请求不再内联付完整 DNS + TCP + TLS 握手。

- **Five zero-dependency built-in MCP servers** — git, sqlite, codegraph, apktool and websearch run as pure-Python stdio servers and enable with one tap from the MCP screen.
  五个零依赖内置 MCP 服务器：git / sqlite / codegraph / apktool / websearch，纯 Python stdio 实现，MCP 页面一键启用。

- **Questions reach you in the background** — when the agent asks a question while you are away, it arrives as a high-priority notification; 2–3 single-choice options become buttons you can tap to answer in place.
  后台提问通知：你不在聊天页时，模型的问题以高优先级通知送达；2–3 个单选选项直接变成通知按钮，点按即答。

- **Archive restore and version recovery** — restoring archived messages is now a menu action with a count that slots rows back by time and retires only what it restored; rows lost to the session-loss bug can be brought back from `message_versions`.
  归档恢复可用：带数量的菜单动作，按时间插回、只退役被恢复的行；误删的行可从版本历史幂等恢复。

- **Adjustable main-session turn cap** — the fixed 200-round ceiling is now a setting (50–1000), next to the sub-agent one.
  主会话轮次上限可调：从固定 200 轮改为设置项（50–1000），与子代理上限同页。

- **Binary preview in file_read** — a binary file now returns a magic-byte type guess and a hex preview of the first 256 bytes instead of a dead end.
  file_read 二进制预览：魔数识别类型 + 前 256 字节十六进制预览，不再直接报「无法显示」。

## Fixes / 修复

- **The one-tap-retry session-loss incident is fixed** — delete / retry / edit truncation now anchors on the tapped message's DB row id; the ordinal mismatch that archived and deleted a whole session tail (1,386 rows on 2026-10-14) is gone, and the lost rows can be restored.
  重试误删会话事故根治：删除 / 重试 / 编辑截断改按所点消息的 DB 行 ID 锚定；「一次重试丢失整段会话尾巴」的事故（2026-10-14，1386 行）不再发生，误删行可恢复。

- **Opening a long session no longer crashes** — the initial render window is capped at 500 messages (was 4,000), fixing a SKIA native-OOM on ~1,400-message sessions; older history stays one tap away.
  长会话打开不再崩溃：初始渲染窗口 4000 → 500 条，修复 1441 条会话打开即原生内存耗尽；更早历史仍可翻页。

- **Thinking blocks stop flickering** — the streaming signal now means "readable text follows", so multi-round tool loops no longer toggle the block every 500 ms; auto-follow while streaming is back.
  思考块不再闪烁：isStreaming 改为「其后有可读正文」，多轮工具循环不再 500ms 反复显隐；流式自动跟随恢复。

- **History paging works while streaming** — the "load older" gates are gone, so scrolling up during a long run loads earlier messages instead of doing nothing.
  流式期间历史翻页解封：长任务运行中向上翻页可以正常加载更早消息，不再静默无操作。

- **The install-time foreground-service crash is fixed** — stop/start cross-talk, a missing re-anchor and a prefs write storm, the three causes of `ForegroundServiceDidNotStartInTimeException`, are all addressed.
  安装期前台服务崩溃修复：停启交叉、锚定缺口与 prefs 写风暴三重根因（`ForegroundServiceDidNotStartInTimeException`）一并解决。

- **OAuth logins are no longer deleted on a rejected refresh** — the bundle is kept and marked "sign in again", with a SHA-256 fingerprint so a concurrent token rotation is not mis-flagged.
  OAuth 不再误删登录：refresh 被拒改为标记「需要重新登录」并保留凭据，指纹防误标。

- **Configs are no longer silently wiped** — MCP `servers.json` and environment variables enter a read-only state after a failed read instead of being overwritten empty; an encrypted backup without its MAC is refused on restore.
  配置不再被静默清空：MCP / 环境变量配置读失败进入脏读态、拒绝落盘；加密备份缺 MAC 拒绝还原。

- **Brand icons actually render now** — SvgDecoder is registered explicitly (Coil 2.x does not auto-discover it), fixing the blank icons everywhere.
  厂商图标真正渲染：显式注册 SvgDecoder（Coil 2.x 不自动发现解码器），修复图标全灭。

- **Entering a chat no longer pops the keyboard**, the swipe-in session drawer is gone, and the back arrow is back in one-pane layout.
  进入会话不再自动弹键盘；移除侧滑会话抽屉，单栏恢复返回箭头。

- **The in-app browser can upload files** (`onShowFileChooser`), and SAF saves count real bytes — a 0-byte result is reported as a failure and cleaned up instead of "saved".
  内置浏览器支持文件上传；SAF 保存统计实际写入字节，0 字节不再误报「已保存」。

- **Voice transcript editing** — double-tapping a word while editing selects it, so you can retype it right away.
  语音转写编辑：双击某个词即选中，可直接重打。

- **No more regex-bomb crashes on long sends** — the four regular expressions on the send path are replaced by a linear scanner, fixing native OOMs and stack overflows on pathological input.
  长消息不再因正则解析崩溃：发送路径 4 个正则改为单遍线性扫描器，根治原生内存 OOM 与回溯爆栈。

- **Context overflow compacts before retrying**, and progressive trimming (old thinking first, then large tool outputs) keeps long runs inside the window instead of one big summary.
  上下文溢出先压缩再重试；渐进裁剪（先剥旧轮思考、再截大工具输出）让长任务不再一步全量压缩。

- **A table nested under a list item renders as a table** instead of raw pipes.
  列表项下嵌套的表格正常渲染为表格，不再是原始管道符。

## Polish / 视觉打磨

- **A three-line top bar** (group / vendor / model) so long vendor names no longer squeeze the model out; the provider-color dots in the model picker become real vendor logos.
  顶栏三层布局（分组 / 厂商 / 模型名），长厂商名不再挤掉模型名；模型选择器的品牌色圆点换成真实厂商图标。

- **22 white-background icons became monochrome dual-theme** (black in light, white in night) so they no longer vanish on light surfaces; three single-white glyphs use a translucent neutral.
  22 个白底隐形图标改为单色双主题（浅色 #000 / 深色 #FFF），不再在浅色表面隐身；3 个纯白图形改用半透明中性色。

- **Process cards share one compact hairline style** — no bubbles or checkmarks, only the failed line is tinted; expand/collapse animates, and single-step thinking below the threshold no longer disappears.
  过程卡片统一紧凑发丝线样式：去气泡与 ✅，失败只染对应行；展开收起带动画，低于门槛的单步思考不再消失。

- **The usage chart and share bars are self-drawn** and never animate in an unbounded loop.
  用量图与占比条 Canvas 自绘，动画有界。

---

完整提交列表：`c15941f9..HEAD`（109 个提交）。
