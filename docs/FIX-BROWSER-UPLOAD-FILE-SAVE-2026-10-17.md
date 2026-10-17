# 内置浏览器上传 / 深链文件保存修复（2026-10-17）

## 1. 内置浏览器无法上传图片

根因：`BrowserUseManager` 的 `WebChromeClient` 没有覆写
`onShowFileChooser`。WebView 遇到 `<input type="file">` 时直接走默认 no-op，
页面收不到选择结果，所以用户点上传没有任何反应。

修复：

- `BrowserUseManager` 增加 host 回调，由 UI 层持有
  `ActivityResultLauncher`；
- `BrowserSheet` 与 `UrlPreviewSheet` 分别注册
  `StartActivityForResult`，把 `FileChooserParams.createIntent()` 交给系统
  文件选择器，再把 `parseResult(...)` 回传给 WebView；
- 新增 `browser/WebFileChooser.kt` 统一处理“第二个请求覆盖第一个”、“取消/
  异常必须回 null”等边界，避免页面 JS 线程永久等待；
- 取消、异常、tab 切换销毁时都会调用 `onReceiveValue(null)`。

覆盖两个内置浏览器入口：`BrowserSheet`（browser_use）和
`UrlPreviewSheet`（聊天链接预览）。

## 2. 深链文件下载 0 字节/空壳

根因在最近滚动包的保存校验改动，不在深链本身：

- 稳定版 `2.0.51-linux` 的 SAF 保存是
  `openOutputStream(uri)?.use { item.file.inputStream().copyTo(it) }`，复制成功
  就报成功，不依赖 provider 的读回长度；
- 10/15 的 `7cc77d56` 与 #62 的 `VerifiedCopy` 增加了
  `openAssetFileDescriptor(uri).length` / `probeBytes` 校验，并把 provider
  读回结果当作成功判据。部分 OEM SAF/DocumentsProvider 在写入完成后仍返回
  0/UNKNOWN（或拒绝 `openInputStream`），于是：
  - 校验把**正确写入的文件**判为失败；
  - 失败路径调用 `ContentResolver.delete`，部分 provider 删除失败，留下
    0 字节文档（用户看到的空壳）；
  - 另外 `FileSaveGuard.verify` 对 `srcLen <= 0 && dstLen == 0` 返回
    `INTACT`，真正的 0 字节源会被当成保存成功，发布一个空壳。

修复：

- `VerifiedCopy.copyFileToDocument` 改为**统计实际写入字节数**：
  - `copied > 0` 即视为内容已落地，不再让 provider 读回长度决定成败；
  - `copied == 0` 明确失败（真正的空源），删除目标并报错，不再“0==0 成功”；
  - 写入过程显式 `flush()`，输出流关闭后才返回；
- `deleteQuietly` 同时尝试 `DocumentsContract.deleteDocument` 和
  `ContentResolver.delete`，尽量避免失败后留下空壳；
- `copyFileToMediaStore` 对 0 字节源直接失败，避免发布空媒体项。

行为差异：

- 非空文件即使 provider 读回 0/UNKNOWN，现在也会保留并报保存成功；
- 真正的 0 字节源会明确失败，不再生成 0 字节文件；
- 若 provider 接受写入但实际丢弃字节，仍可能需要 provider 侧修复；本次
  不再因为“读回不可用”误删有效文件。

## 3. 验证

- `:app:testReleaseUnitTest`：**3329 tests, 0 failed, 4 skipped**
  - 新增/调整 `VerifiedCopyTest`：
    - 写入但不可读回 → 仍成功；
    - 0 字节源 → 失败；
    - provider 读回空 → 仍保留写入内容。
- 架构门禁：OK（`BrowserUseManager` 1912 ≤ 1914）
- CJK 门禁：OK
- 编译通过。

未在真机上做 UI 手动回归；建议在滚动包安装后验证：
1. 打开一个带 `<input type=file accept="image/*">` 的网页，点击选择图片，
   确认系统相册选择器出现、选择后网页收到文件；
2. 从模型回复的 `minis://` 文件链接进入预览，点击右上角下载，保存到
   Downloads，确认文件大小与源文件一致且可打开。
