# OpenMinis Blink Engine (iOS 16 真实浏览器引擎)

在 OpenMinis 中嵌入真实 Chromium Blink+V8 渲染引擎，替换/补充 WKWebView，让 iOS 16.2 越狱设备（A12Z / TrollStore）上的现代网站正常渲染。

## 背景

- 设备：iPad Pro 2020 (A12Z, arm64e)，iOS 16.2，已越狱，TrollStore 侧载
- 问题：WKWebView 引擎随系统锁死（iOS 16.2 的 WebKit 太老），现代网站白屏、按钮无响应
- BrowserEngineKit 需要 iOS 17.4+，不可用
- 参考实现：**Blinker Fluid**（https://github.com/Ssabal/blinker-fluid）已成功把 Chromium Blink+V8 移植到 iOS 14–16 越狱设备，其产物为 `content_shell.app` + `content_shell_framework.framework`（263MB 单一 dylib，无签名，TrollStore 安装时签名）

## 方案优先级

| 级别 | 方案 | 状态 |
|------|------|------|
| P0 | 嵌入 Chromium Blink+V8，替换 WKWebView 渲染，agent 自动化接口不变 | 本分支实现（CI 构建） |
| P1 | 渲染失败自动降级服务器端渲染取内容（r.jina.ai + 原始 HTML + Wayback，curl 防 TLS 指纹反爬） | 本分支实现（SSR 引擎） |
| P2 | "用系统默认浏览器打开"选项 | 本分支实现（open_in_browser） |

## 架构

```
BrowserSheetView / BrowserTabPool
        │
        ▼
BrowserUseManager  (agent 动作执行器，现有 2640 行逻辑不动)
        │  engineKind: .webkit | .blink | .ssr
        ├── .webkit ──► WKWebView（现有路径，行为 100% 不变）
        ├── .blink  ──► BrowserEngineCoordinator ──► BlinkTabSession ──► BlinkEngineBridge
        │                                                    (dlopen + dlsym) │
        │                                                                    ▼
        │                                          content_shell_framework.framework
        │                                          （Chromium 149 + Blinker Fluid 补丁 + blink_bridge C API）
        └── .ssr    ──► BrowserEngineCoordinator ──► SSRManager
                                           (r.jina.ai 经 iSH curl / 原始 HTML / Wayback)
```

### 为什么用 dlopen 而不是静态链接

- `content_shell_framework.framework` 是单一 263MB dylib，由 CI 构建（macOS 上 xcodebuild 无法编译 Chromium）
- OpenMinis 主工程**零链接改动**：BlinkEngineBridge 在运行时 `dlopen` + `dlsym` 取 C API
- 同一个 OpenMinis IPA：默认 WebKit 路径照常工作；TrollStore 构建（嵌入 framework）自动启用 Blink
- 若 Blink 初始化失败 → 自动回退 WebKit（引擎选择器 + 状态提示）

### C API（blink_bridge，编译进 content_shell_framework）

```
BlinkBridgeInitialize(bundle_path, tmp_path)   // 一次性：CommandLine/ICU/资源/浏览器主循环
BlinkBridgeCreateView(x,y,w,h)                 // Shell::CreateNewWindow + GetContentView()
BlinkBridgeLoadURL / GoBack / GoForward / Reload / Stop
BlinkBridgeEvaluateJS(js, cb, ctx)             // ExecuteJavaScriptForTests (绕过 CanExecuteJavaScript CHECK)
BlinkBridgeCaptureSnapshot(cb, ctx)            // UIView drawViewHierarchyInRect → PNG base64
BlinkBridgeGetURL / GetTitle / IsLoading / CanGoBack / CanGoForward
BlinkBridgeSetUserAgent / SetViewFrame
BlinkBridgeSetEventCallback(cb, ctx)           // didFinish/didStartProvisional/titleChanged/loadFailed/windowOpenBlocked
BlinkBridgeDestroyView
```

关键点（来自 Blinker Fluid 的坑）：
- `RenderFrameHost::ExecuteJavaScript` 在真实 http/https 页面会 `CHECK(CanExecuteJavaScript())` 崩溃 —— 必须用 `ExecuteJavaScriptForTests`（content_shell 测试客户端已启用）或隔离世界
- V8 JITless（iOS VA 限制）、v8_enable_sandbox=false、cppgc_enable_caged_heap=false
- 进程内渲染（单进程），无 WebContent 子进程 —— `--blink-embed` 开关跳过 Blinker 的窗口/UI 创建

### P1 SSR 降级链（SSRManager）

1. **r.jina.ai**（渲染成 markdown）→ 经 OpenMinis 内嵌 iSH 执行 curl（r.jina.ai 按 TLS 指纹拦截 Python urllib，curl 可过；免费层偶发 Cloudflare 验证页则降级）
2. **原始 HTML**（URLSession 直连，不依赖设备渲染）→ 内置轻量提取器（title/meta/正文段落）
3. **Wayback 存档**（archive.org/wayback/available → 快照 HTML）

### P2 系统浏览器打开

- `browser_use` 新增动作 `open_in_browser`（agent 可用）
- 浏览器 UI 工具栏新增"在系统浏览器中打开"按钮

## 构建（GitHub Actions）

`.github/workflows/blink-build.yml` 两个 job：

1. **build-chromium**（macos-15，约 2–3 小时）：
   - depot_tools → gclient 浅克隆 Chromium @ `31dce68b925c2b8efc93df832a86a7c0d03e3fa2`
   - 叠加 blinker-fluid/src 补丁 → 应用 `chromium-blink/patches/embed_guard.patch`
   - 把 `chromium-blink/bridge/` 挂进 `content/shell/BUILD.gn`（`source_set("blink_bridge")` + framework deps）
   - `gn gen`（build_args.gn：use_blink=true + Blinker Fluid 全部参数 + is_component_build=true）
   - `autoninja content_shell` → 产出 `content_shell.app` / `content_shell_framework.framework`
   - 上传 framework + 资源（icudtl.dat / *.pak / net/data / 字体）为 artifact

2. **build-minis**（macos-14，需等待 job 1）：
   - xcodebuild 无签名构建 Minis（CODE_SIGNING_ALLOWED=NO，TrollStore 兼容）
   - 下载 framework artifact → `embed_into_app.sh` 拷入 Minis.app/Frameworks 与资源
   - 打包 `.ipa` + `.tipa` → 上传 artifact

## 安装（TrollStore）

1. Actions → 最新一次 `blink-build` 运行 → artifacts → 下载 `OpenMinis-Blink-TIPA.tipa`
2. 打开 TrollStore → 安装 .tipa
3. 首次使用：设置 → 浏览器引擎 选 Blink（或在浏览器工具栏引擎菜单切换）
4. 若某网站渲染仍异常 → 引擎菜单切 SSR 取内容，或点"系统浏览器打开"

## 风险与已知限制（v1）

- Blink 引擎在 iOS 16.2 + 进程内渲染下仍可能偶发崩溃（Blinker Fluid 本身标注 EXPERIMENTAL）——崩溃自动回退 WebKit/SSR
- Blink 模式下 cookies API 暂未桥接（get_cookies/set_cookies 返回明确错误，agent 降级）
- window.open 弹窗在 Blink 模式下被阻止并上报事件（agent 用 new_tab 显式开新标签）
- JITless 渲染 JS 密集型站点（地图/大型 SPA）较慢
- iSH curl 依赖内嵌 rootfs 可用；不可用时 SSR 自动走原始 HTML/Wayback
