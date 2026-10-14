# 构建 Minis Ultra

本仓库只构建 **Android arm64** 应用（启动器名 Minis Ultra，包名 `com.openminis.linux`）。没有 iOS 工程。第一次构建要先编 PRoot 并准备 Ubuntu rootfs，大约 30–60 分钟；之后产物会缓存在磁盘上。

## 环境

| 工具 | 版本 |
|---|---|
| JDK | **17** |
| Android SDK | compileSdk 36，targetSdk 35，minSdk 26 |
| Android NDK | **r29 `29.0.14206865`**，设置 `ANDROID_NDK_HOME`。已有 r28 不算可用 |
| CMake | **3.22.1 或更高**（DSL 写的是 `3.22.1+`）。aarch64 上 SDK Manager 未必装得到，`build_apk_aarch64.sh` 会自己解析并通过 `cmake.dir` 指定 |
| 其它 | `curl`、`tar`、`make`、`sed`，以及 **`gawk`**（不能只是任意 awk，见下）|

Gradle 使用仓库里的 wrapper（Gradle 8.11.1，AGP 8.7.3，Kotlin 2.1.0），不要另外安装。只打 `arm64-v8a`。

## 取得源码

```sh
git clone https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
git submodule update --init deps/proot
```

Android 沙箱只需要子模块 `deps/proot`。`deps/ish`（OpenMinis/ish-arm64）是历史遗留的 iOS 子模块，本仓库的安装包不编译它，`deps/build_ish.sh` 与 `deps/ISH_INTEGRATION.md` 也只在 iOS 分支上有意义——所以两条 CI workflow（`android-apk.yml`、`native-deps.yml`）都改成 `submodules: false` 加显式 `git submodule update --init deps/proot`，不再在每次 Android 构建里克隆一个不编译的仓库。子模块仍然注册在 `.gitmodules` 里，需要 iOS 那一侧的人自己 `git submodule update --init deps/ish`。

构建前复制一份可空的配置即可编译运行：

```sh
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

用 API Key 登录不需要填这项。只有走 Claude OAuth 时，才需要自己提供 `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`；本仓库不内置该值。

国内网络可以先选镜像，不要把某一个镜像地址提交进仓库：

```sh
python scripts/pick_build_mirrors.py
set MINIS_BUILD_MIRRORS=cn
```

## 编原生依赖和安装包

aarch64 宿主机直接跑一键脚本即可：

```sh
./scripts/build_apk_aarch64.sh
```

它会初始化 `deps/proot` 子模块、编 PRoot、准备沙箱资产（含完整性校验与自动修复）、
编 libunwind.a、解析 NDK/CMake/aapt2 并写入 `local.properties`，最后 `assembleRelease`
把产物复制到 `dist/openminis-aarch64.apk`。

也可以手工分步执行：

```sh
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
cd src/android
./gradlew :app:assembleRelease
```

- `build_proot.sh` 用 NDK 交叉编译 PRoot，并放入 `assets/` 和 `jniLibs/arm64-v8a/`。`libproot-loader.so` 与 `libproot-loader32.so` 必须在 APK 里，否则客户机命令会报 `[Shell not running]`。
- `build_proot.sh` **需要 gawk**：proot 的 `loader-info.awk` 用了 `strtonum()`，那是 gawk 扩展，而 Debian/Ubuntu 默认的 `/usr/bin/awk` 是 mawk。脚本会自己找一个支持 `strtonum` 的 awk 并在 make 期间遮蔽默认的。
- `prepare_android_sandbox.sh` 下载 Ubuntu 24.04 arm64 的 `ubuntu-base.tar.gz`（不进 git）并做 gzip 完整性校验。**Android SDK 宿主工具（aapt2 / sdkmanager）不再打包进 APK**：`18dff0c` 起改由客户机里的 `minis-android-sdk-setup` 按需下载，所以这个脚本不再产出 `android-sdk-tools-aarch64.zip` / `android-cmdline-tools.zip`，`slim_android_cmdline_tools.py` 也随之退休（文件已删除）。`repair_vendor_zip.py` 仅在重新引入第三方 zip 时才用得上（校验逻辑保留，`VendoredAssetIntegrityTest` 的护栏也一并留着）。
- 在 aarch64 宿主机上编译时，AGP 默认从 Maven 取 **x86_64** 的 aapt2，会以 `AAPT2 Daemon startup failed` 失败。`build_apk_aarch64.sh` 自动加 `-Pandroid.aapt2FromMavenOverride=` 指向一个能在本机跑的 aarch64 aapt2。同样地，在 aarch64 客户机里不要用 Google 的 x86_64 `build-tools` 覆盖 aapt2。镜像清单见内置技能 `android-sdk-mirrors`（`SkillRepository.ANDROID_SDK_MIRRORS_CONTENT`）。
- Release 签名：`src/android/release.keystore` 是本项目公开的默认上传证书（进 git，便于 CI 与本地共用同一张证书 —— 应用内更新按证书指纹比对，见 `UpdateChecker.verifyApkSignature`）。**口令文件 `signing.properties` 不再进 git**：私钥已经公开，口令再公开就等于任何人都能签发本项目的升级包。本地开发：`cp src/android/signing.properties.example src/android/signing.properties` 后填口令。CI：用 repository secrets（`MINIS_UPLOAD_KEYSTORE_BASE64` + 三个 `MINIS_UPLOAD_*`，或仅 `MINIS_UPLOAD_STORE_PASSWORD` 以复用仓库内证书）；两者都没有时 `Upload keystore` 步骤直接失败，**不会**退化成 debug 签名（那会让所有已安装用户失去升级路径）。

`src/android/app/src/main/cpp/` 里的 JNI 由 Gradle 的 CMake 一起编译，不用单独跑。

## 调试变体：整条 debug 链目前不可达

`src/android/app/build.gradle.kts` 的 `androidComponents.beforeVariants`（约 307–313 行）把 **所有 `debug` 变体**关掉了（`[T-ci-build-speed]`：避免 kspDebug / kspRelease 抢同一份 Room schema JSON），CI 也只跑 `assembleRelease` / `compileReleaseKotlin` / `testReleaseUnitTest`。因此下面这些 debug-only 的配置在默认配置下**永远不会触发**，属于已知矛盾，等 owner 决定是删掉还是接通：

- `debugImplementation("androidx.compose.ui:ui-tooling")`（约 394 行）：没有任何 debug APK 会被产出，这条依赖进不了任何构建产物。
- `stageDebugSkillAssets`（约 347–362 行）：只挂在名字里含 `Debug` 的 `merge*Assets` 任务上，那些任务已不存在，脚本一次也不会跑。
- `src/debug/assets` 这条 source set 连线（`.gitignore` 末尾也有 `src/android/app/src/debug/assets/debug-skill/` 对应规则）：仓库内没有 `src/debug` 的任何受跟踪文件，release APK 也永远不含它。

第二处矛盾：`build.gradle.kts`（约 349 行 `val skillDir = rootProject.file("../../.claude/skills/debug-server")`）和 `scripts/gen_debug_skill_android.sh`（约 30 行 `SKILL_DIR="$REPO_ROOT/.claude/skills/debug-server"`）都把 `.claude/skills/debug-server/` 写成 skill 的"唯一真源"，但**本仓库没有 `.claude/` 目录**，而且 `.gitignore` 第 98 行还专门忽略它。真要在本地跑起 debug 变体，`gen_debug_skill_android.sh` 的 `if [ -f "$SKILL_DIR/SKILL.md" ]` 分支会走 else，写出占位的 `# debug-server skill unavailable` SKILL.md —— 也就是调试服务器能开，`GET /skill` 只会返回那句占位文案。任务本身不会因此失败（`onlyIf { script.exists() }` 为真，且路径不存在时不声明 inputs），所以这是个**静默**的坑。

想恢复 debug 链路：在本地分支上把 `beforeVariants` 里的 `variantBuilder.enable = false` 去掉（或改成只关 `androidTest`），再自备 `.claude/skills/debug-server/SKILL.md`。要不要保留这一整套（还是连同 `ui-tooling` 一起删除）属于产品决定，见改动说明。

**重要澄清（上述"不可达"仅指 Gradle 的 debug *构建变体*，不等于 `debug` *包* 是死代码）**：`src/main/java/com/openminis/app/debug/`（`HeadlessChatRunner`、`DebugServer`、`DebugRPCHandler`、`LLMRequestLog` 等）位于 **main source set**，会照常编进 **release** APK，与变体开关无关。其中 `HeadlessChatRunner` **在生产路径上可达**：`src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt`（main）`import com.openminis.app.debug.HeadlessChatRunner` 并调用其 `prompt` / `retry` 来跑定时任务的 agent 循环。因此删除 `debug/**` 会直接破坏 release 的定时任务功能，绝非"清理死代码"。同理，散落在 main 里的 `BuildConfig.DEBUG` 分支（`MainActivity`、`MinisApp`、`AnthropicProvider`、`OpenAIRawStream`、`DebugOffloadHandler` 等）在 release 中恒为 `false`，属编译期裁剪的死分支，但源码仍随 release 打包。**要接通还是保留这一整套 debug 面（含 `ScheduledAgentRunner`→`HeadlessChatRunner` 这条真实链路），属 owner 产品决定，本次清理未改动 `debug/**` 的任何 Kotlin。**

## 排错

- `deps/proot` 是空的：执行 `git submodule update --init deps/proot`（`build_apk_aarch64.sh` 会自动做）。
- `awk: loader/loader-info.awk: line 9: function strtonum never defined`：默认 awk 是 mawk。装 gawk（`apt-get install -y gawk`）后重跑；`build_proot.sh` 会自己找到它。
- `AAPT2 Daemon startup failed` / `Bundled aapt2 not found`：在 aarch64 宿主机上十有八九是 AGP 取了 x86_64 的 aapt2。用 `build_apk_aarch64.sh`，或手工加 `-Pandroid.aapt2FromMavenOverride=<能跑的 aarch64 aapt2>`。
- `Android NDK not found`：把 `ANDROID_NDK_HOME` 指到 NDK r29（`29.0.14206865`）。不要指到 r28。
- CMake 相关的 `does not match ... required version`：DSL 要的是 `3.22.1+`，任何 ≥3.22.1 都行。`local.properties` 里的 `cmake.dir` 由脚本解析写入；手工构建时若 AGP 找不到 SDK 里的 CMake（手装的目录没有 `package.xml`，AGP 枚举不到），就自己写一行 `cmake.dir=`。不要在 `local.properties` 里写 `ndk.dir`，它已废弃（AGP 报 CXX5106），项目用 `ndkVersion` 定位。
- 客户机里没有 aapt2/zipalign：说明捆绑的 `android-sdk-tools-aarch64.zip` 没解出来。跑 `python3 scripts/repair_vendor_zip.py --verify src/android/app/src/main/assets/android-sdk-tools-aarch64.zip` 看它是否损坏，再用 `prepare_android_sandbox.sh` 修复。App 侧会在 `/opt/android-sdk/.minis-sdk-tools-error` 留下失败原因。
- 应用能开、终端不能跑：重跑 `./deps/build_proot.sh` 和 `./scripts/prepare_android_sandbox.sh` 后再编译。
- 每条命令都是 `[Shell not running] (exit code: -1)`：检查 `src/android/app/src/main/jniLibs/arm64-v8a/` 里是否有 `libproot-loader.so` 和 `libproot-loader32.so`。只看到沙箱启动日志不够，要实际跑一条命令并确认退出码为 0。
- 单元测试 `VendoredAssetIntegrityTest` 失败：说明 `assets/` 下有 blob 损坏或缺关键成员。这是故意的护栏——它拦住的正是"能打包但装到手机上跑不起来"那一类问题。

## 许可

本仓库以 **GPLv3** 分发，因为链接了 PRoot（GPLv2）。见 [LICENSE](LICENSE) 和 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
