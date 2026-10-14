# 参与本仓库

本仓库是 [tall-1997/OpenMinis-Linux](https://github.com/tall-1997/OpenMinis-Linux)，产品名 **Minis Ultra**。问题和改动都在这里处理，不要提到其它仓库。

## 反馈

- 缺陷、崩溃、使用问题：开 [Issue](https://github.com/tall-1997/OpenMinis-Linux/issues)。
- 请写明：系统版本、应用版本（设置 → 关于）、期望行为、实际行为、复现步骤、所用模型和供应商。有日志时附上设置 → 日志。

## 改源码

代码以 GPLv3 分发。可以 fork、修改并自行构建，见 [BUILDING.md](BUILDING.md)。分发修改版时必须公开对应源码，并继续使用 GPLv3。

构建只针对 Android arm64。本仓库没有 iOS 工程。

## 加/改文案（本地化）

Android 侧的文案就是资源文件，没有 Xcode 目录（`.xcstrings` / `.lproj`）那一套：

- 源语言：`src/android/app/src/main/res/values/strings.xml`（英文）。
- 每种已发布语言一个目录：`res/values-zh/`、`res/values-es/`、`res/values-pt-rBR/` …，文件名同样是 `strings.xml`，`name` 必须和英文一致。
- 新增一条用户可见文案 = 同时在**所有**已发布 locale 里补上翻译，否则 PR 会被门禁拦掉。

门禁由 CI 自动执行（`pr-check.yml` 的 `No new untranslated strings` 步骤）：

```sh
python3 scripts/ci/check_new_untranslated.py <base-sha> <head-sha>
# 本地预检同一件事，避免推上去才发现被拦：
python3 scripts/ci/check_new_untranslated.py "$(git merge-base origin/main HEAD)" HEAD
```

它比对 diff 里新增的 `strings.xml` key 与格式占位符（`%1$s`、`%d` 之类）在各语言里是否齐全、数量是否一致；另有 `src/android/app/src/test/java/com/openminis/app/i18n/StringFormatArgSafetyTest.kt` 在单测里校验每条字符串的占位符数量在各 locale 间一致。这两件是本地化工作的真工具，改文案前后各跑一次即可。

> 注：iOS 时代遗留的 `scripts/add_locale.py`、`scripts/add_localization.py`、`scripts/register_locale.py`、`scripts/check_locale_batch.py` 已在本次清理中删除。它们操作的对象是 `src/ios/Localizable.xcstrings`、`.lproj`、`project.pbxproj` 的 `knownRegions` 与 `Info.plist`——这些路径在本仓库（仅 Android）里都不存在，也没有任何 workflow / Gradle / 文档调用过它们，因此与上面的 Android 本地化流程无关。
