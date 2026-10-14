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
git clone --recurse-submodules https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
git submodule update --init --recursive
```

Android 沙箱用的是子模块 `deps/proot`。`deps/ish` 是历史子模块，本仓库的安装包不编译它。

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
- `prepare_android_sandbox.sh` 下载 Ubuntu 24.04 arm64 的 `ubuntu-base.tar.gz`（不进 git）并做 gzip 完整性校验。**Android SDK 宿主工具（aapt2 / sdkmanager）不再打包进 APK**：`18dff0c` 起改由客户机里的 `minis-android-sdk-setup` 按需下载，所以这个脚本不再产出 `android-sdk-tools-aarch64.zip` / `android-cmdline-tools.zip`，`slim_android_cmdline_tools.py` 也随之退休。`repair_vendor_zip.py` 仅在重新引入第三方 zip 时才用得上（校验逻辑保留）。
- 在 aarch64 宿主机上编译时，AGP 默认从 Maven 取 **x86_64** 的 aapt2，会以 `AAPT2 Daemon startup failed` 失败。`build_apk_aarch64.sh` 自动加 `-Pandroid.aapt2FromMavenOverride=` 指向一个能在本机跑的 aarch64 aapt2。同样地，在 aarch64 客户机里不要用 Google 的 x86_64 `build-tools` 覆盖 aapt2。镜像清单见内置技能 `android-sdk-mirrors`（`SkillRepository.ANDROID_SDK_MIRRORS_CONTENT`）。
- Release 签名：`src/android/release.keystore` 是本项目公开的默认上传证书（进 git，便于 CI 与本地共用同一张证书 —— 应用内更新按证书指纹比对，见 `UpdateChecker.verifyApkSignature`）。**口令文件 `signing.properties` 不再进 git**：私钥已经公开，口令再公开就等于任何人都能签发本项目的升级包。本地开发：`cp src/android/signing.properties.example src/android/signing.properties` 后填口令。CI：用 repository secrets（`MINIS_UPLOAD_KEYSTORE_BASE64` + 三个 `MINIS_UPLOAD_*`，或仅 `MINIS_UPLOAD_STORE_PASSWORD` 以复用仓库内证书）；两者都没有时 `Upload keystore` 步骤直接失败，**不会**退化成 debug 签名（那会让所有已安装用户失去升级路径）。

`src/android/app/src/main/cpp/` 里的 JNI 由 Gradle 的 CMake 一起编译，不用单独跑。

## 排错

- `deps/proot` 是空的：执行 `git submodule update --init --recursive`（`build_apk_aarch64.sh` 会自动做）。
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
