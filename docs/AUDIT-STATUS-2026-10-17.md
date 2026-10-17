# 审计修复总状态（2026-10-17）

本文件汇总 `docs/CODE-AUDIT-2026-10-17-FINAL.md` 的修复进度。分阶段记录：

- `P0-FIXES-2026-10-17.md`
- `P1-FIXES-2026-10-17.md`
- `P2-FIXES-2026-10-17.md`
- `P3-FIXES-2026-10-17.md`
- `P4-FIXES-2026-10-17.md`

## 总览

| 阶段 | 项数 | 状态 |
|---|---:|---|
| P0（ICU-1、SAVE-1..4、SCROLL-1） | 7 | ✅ 全部完成 |
| P1（CRASH-5/7、PROV-1/2、CRASH-1/2/4/8、H-3/H-5） | 10 | ✅ 全部完成 |
| P2（LONG-1/2/3/4/6/7、MCP-STDIO-1、UX-1） | 8 | ✅ 7 项完成；`LONG-2` 为有明确取舍的改善；`UX-1` 完成重组隔离，物理拆分未做 |
| P3（SEC-1/2/3/4/5、S-14） | 6 | ✅ 4 项完成；`SEC-2`、`SEC-4` 经用户决定暂缓 |
| P4（UX-3、TERM-1、SEND-DIAG-1、MCPOAUTH-1、MCP-FT-1、CFG-1） | 6 | ✅ 全部完成 |

## 仍存在的明确缺口

1. **LONG-2 没有 FTS/索引**：搜索已改为 compact `preview` + `LIMIT 200` + 批量 snippet，但仍是扫描；中文子串使 `unicode61` 不可用，需要自定义分词/trigram 才能上 FTS。
2. **UX-1 物理拆分未做**：`ChatScreen` 的消息区/输入区已有独立重组 scope（7,579 行），但未搬成 `ChatMessagesPane`/`ChatComposerPane`；`SessionListScreen` 的宽订阅和顶层弹层订阅未动。
3. **SEC-2 SandboxHttpProxy**：未加 peer 认证/目标 allowlist；开启 `log`/`cut` 时同设备 App 仍可把它当本地代理。
4. **SEC-4 MCP CORS/Host**：`Access-Control-Allow-Origin: *` 仍在；生产 token 自动生成，但浏览器跨源面未收紧。
5. **CFG-1 超时语义**：20s handler watchdog 会取消等待并清弹窗，但确认门自身的 120s 窗口未缩短；用户需在 20s 内确认。
6. **MCP-FT-1 最终字符串**：已去掉整文件 byte[] 峰值，但 base64 最终字符串仍在内存；彻底流式化要改 HTTP 响应写出。

## 设备/仪器验证清单

- OEM SAF provider 返回 `-1`、MediaStore pending `SIZE` 语义、空文件保存。
- 发送后立即上拖、流式期间上拖、session 切换的滚动 trace。
- 长流式回复的 native/Java 内存曲线与 `[StreamRender][blockIncr]` 指标。
- 10 万消息升级耗时（instrumentation 只覆盖正确性）。
- Anthropic/Gemini 半开连接下的 120s TTFB 与取消释放。
- NativeOffload peer UID 在 API 26 / API 34 各跑一次。
- 动态 OAuth loopback 端口在目标 IdP 上是否被接受。
- Config 确认门：20s watchdog 取消后弹窗/队列清理。

## 回归基线

- 全量 `:app:testReleaseUnitTest`：**3361 tests, 0 failed, 4 skipped**。
- 真机 `TB365FC`（API 36）instrumentation：**49 tests, 0 failed**（迁移、ChatDao、纠错、分段器、bitmap、terminal sanitizer；重复的 ChatScroll UI 用例已移除，队列语义由 JVM `ChatScrollControllerTest` 覆盖）。
- 默认 minify 的 `:app:assembleRelease` 构建成功；minified APK 安装后 MainActivity 可启动、无 FATAL。
- `check_architecture_policy.py` OK（908 files）。
- 未新增 `strings.xml` 键，l10n gate 不受影响。
- 测试中修复的真实生产 bug：Jieba 词典 `.gz` / plain `.utf8` 资产名不一致导致中文分词为空。详见 `docs/TEST-VERIFICATION-2026-10-17.md`。
