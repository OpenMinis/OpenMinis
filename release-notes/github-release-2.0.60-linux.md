# OpenMinis-Linux 2.0.60-linux

- versionCode **260**
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## New features / 新功能

- **AI process cards** — the AI process folds into one card per run, with expandable thinking and tool rows.
  AI 过程按轮次折叠成卡片，思考与工具行可展开。
- **Vendor logos** — the top bar and provider page show each model's real brand icon (358 brands).
  顶栏与供应商页显示模型真实厂商图标（358 品牌）。
- **Usage page time dimensions** — Today / 7 days / 30 days / All filters with daily charts.
  用量页支持今天 / 近 7 天 / 近 30 天 / 全部筛选与每日图表。
- **Dev environment suites** — five toolchain bundles plus on-demand Android SDK (APK −36 MB).
  新增 5 套开发工具链套件，Android SDK 按需下载（APK 减 36 MB）。
- **Questions reach you in the background** — answered from the notification when you are away.
  后台提问通知：不在聊天页时可直接在通知里回答。
- **Archive restore** — archived messages restore as a menu action; lost rows come back from message versions.
  归档恢复可用，误删的行可从版本历史恢复。

## Fixes / 修复

- **Session-loss incident fixed** — delete / retry / edit now anchor by DB row id (1,386 rows were lost on 2026-10-14).
  重试误删会话事故根治：删除 / 重试 / 编辑改按 DB 行 ID 锚定。
- **Long sessions no longer crash** — render window capped at 500 messages; images decode with size limits.
  长会话打开不再崩溃：渲染窗口与图片解码封顶。
- **Thinking blocks stop flickering**; history paging works while streaming.
  思考块不再闪烁；流式期间历史翻页恢复。
- **OAuth logins are kept** on a rejected refresh (marked "sign in again" instead of deleted).
  OAuth 刷新被拒不再误删登录，改为标记重新登录。
- **Configs are no longer silently wiped**; encrypted backups without a MAC are refused.
  配置不再被静默清空；缺校验的加密备份拒绝还原。
- **Brand icons actually render** (SvgDecoder registered); no keyboard pop-up when entering a chat.
  厂商图标真正渲染；进入会话不再自动弹键盘。

## Polish / 视觉打磨

- **Three-line top bar** (group / vendor / model) so long names no longer get cut off.
  顶栏三层布局（分组 / 厂商 / 模型名），长名称不再被截断。
- **Monochrome dual-theme brand icons** no longer vanish on light surfaces.
  品牌图标改为单色双主题，浅色表面不再隐身。
- **Compact process cards** with hairline rows and animated expand/collapse.
  过程卡片统一紧凑样式，展开收起带动画。

---

完整提交列表：`c15941f9..4cc6e958`。
