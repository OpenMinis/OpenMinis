# Minis Ultra（OpenMinis-Linux 分支）

**Minis Ultra** 是本仓库的 Android 应用：Agent 加 Ubuntu 24.04 PRoot 沙箱，带 Linux 工具链、主机 `su` 直通和 POSIX 共享存储挂载。包名独立，可以和官方 OpenMinis **并排安装**。

启动器名称是 **Minis Ultra**，`applicationId` 为 `com.openminis.linux`。当前版本 **1.36.24-linux**（versionCode 77）。

滚动 APK：GitHub Releases 标签 `android-latest`，文件名 `minis-ultra-com.openminis.linux.apk`。关于页 / 检查更新走 fork `tall-1997/OpenMinis-Linux`；滚动包用 release body 里的 `versionCode` / `versionName`（以及 APK `updated_at`）判断是否比本机新。

正式发行包：[Releases `1.36.24-linux`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/1.36.24-linux)。1.36.24：手机时区变化时同步客户机 `/etc/localtime`，不再只改正在运行的 shell 的 `TZ`。完整变更记录见 [Releases](https://github.com/tall-1997/OpenMinis-Linux/releases)。

## 沙箱当服务器

客户机里可以直接驱动主机：

```
minis-toast 备份完成
minis-clipboard get
minis-clipboard set --text 'hello'
minis-open https://example.com            # 无 TTY 也走应用内预览
minis-open --system https://example.com   # 系统浏览器
minis-firewall status
minis-firewall set wifi-only              # 可选 --strict（整进程绑 Wi-Fi，含 LLM）
minis-firewall log                        # 沙箱 http_proxy 记 CONNECT，无 VpnService
minis-firewall cut                        # 一键切断沙箱出站
minis-notify post --title 完成 --body ok --action-label 重试 --action-command /var/minis/hooks/retry.sh
minis-on-event register battery_low /var/minis/hooks/pause.sh
minis-doze status
minis-doze request
minis-ps
cat /run/minis-host-status.json
cat /run/android-events.jsonl
cat /run/minis-netlog.jsonl
cat /run/minis-proc.json
```

任务完成通知上的「重试 / 备份 / 清理」会在对应会话沙箱执行预设命令（上次 shell、打包 workspace、清 `/tmp`）。

## 客户机系统

Android 客户机是 Canonical **Ubuntu 24.04 (noble) arm64** 的 `ubuntu-base`，跑在 **PRoot** 里（不是 KVM，也不依赖内核 user namespace 的 chroot）。

| | |
|---|---|
| libc | glibc（`aarch64-linux-gnu`） |
| shell | GNU bash（`/bin/bash`；`/bin/sh` 是 dash） |
| 软件包 | `apt-get` / `apt`。`yum`/`dnf` 只是 apt 的兼容包装，不是 RPM |
| 架构 | arm64。软件源走 **ports.ubuntu.com**，不是 archive.ubuntu.com |
| init | 无。ubuntu-base 在 PRoot 下没有 systemd |

PRoot 会在客户机里**假装** uid 0。那不是主机 root。主机 root 只来自 `su` / `android-su` 卸载通道（Magisk / KernelSU）。

本仓库没有 iOS 工程。Ubuntu 客户机只用于 Android。

## 工具链

```
apt-get update && apt-get install -y python3
minis-dev-setup              # bash, gcc, python3, git, ffmpeg, openjdk-21, gradle, golang-go
minis-android-sdk-setup      # aarch64 aapt2 + platforms 36/35 + CMake 3.31.6 (fallback 3.22.1) + NDK r29 (29.0.14206865)
yum install python3          # → apt-get install -y python3
```

`aapt2` / `zipalign` / `adb` 以 **aarch64** 静态二进制打进 APK（AOSP，来自 lzhiyong/android-sdk-tools 35.0.2），解压到 `/opt/android-sdk`。
`sdkmanager`（Google cmdline-tools 12.0，Java）也捆绑了——精简到 sdkmanager 的 classpath（约 20MB，丢掉 lint/R8/kotlin-compiler）。它跑在 aarch64 OpenJDK 上，**只用来拉** `platforms;android-36` 和 `platforms;android-35`。CMake 默认 3.31.6（失败则 3.22.1）。NDK 只装 r29（`29.0.14206865`，lzhiyong/termux-ndk 的 `android-ndk-r29-aarch64.tar.xz`）。已有 r28 会留下，但不算安装成功，失败也不回退 r28。不要用 sdkmanager 装 Google 的 linux x86_64 宿主包。

**严禁**在客户机里安装 Google 的 linux build-tools / cmake / ndk：那是 x86_64，会把 aapt2 覆盖成无法执行的 ELF，或留下不能跑的 clang。完整镜像清单见内置技能 `android-sdk-mirrors`。

客户机继承 Android 的 `TMPDIR`（应用 cache 目录）时，dpkg/apt 会解包失败。PRoot 启动时把 `TMPDIR`/`TMP`/`TEMP` 钉成 `/tmp`；`minis-dev-setup` 会先修破损依赖并安装 `ca-certificates`。

可选：把完整 SDK bind-mount 到 `/var/minis/mounts/android-sdk`。

## 多智能体

设置 → Agent Runtime → **多智能体**（深链 `minis://settings/multi-agent`）：

- 主会话模型是**任务协调者**：拆解、分派、验收、汇总，而不是独自做完所有活。
- 工具是 `run_subagent`。同一回合里多条独立调用会并行，上限 1–8（默认 3），并与「团队模型」池的勾选数量联动。
- 有依赖的阶段必须验收通过后再进入下一阶段。
- 子代理看不到主会话，prompt 必须自包含；子代理禁止再开子代理。
- 团队模型留空则沿用主会话模型；否则在池中轮询，也可在调用里指定。
- 会话菜单 **计划讨论**：打开后每条用户消息先走「主会话提案 → 团队模型独立立场、共享全文、最多 3 轮同意/反驳（可用工具）→ 主会话合成」；用户自行判断方案。关掉开关后才自动实现。

## 与官方 OpenMinis 共存

| | 官方 | 本分支 |
|---|---|---|
| `applicationId` | `com.openminis.app` | `com.openminis.linux` |
| 启动器名称 | Minis | Minis Ultra |
| 抽象套接字 | `native-offload` | `native-offload-linux` |
| Debug JSON-RPC | `127.0.0.1:5321` | `127.0.0.1:5322` |
| 客户机 | Alpine musl | Ubuntu 24.04 glibc |
| Rootfs 目录 | `files/alpine-rootfs` | `files/ubuntu-rootfs` |

首次启动会解压 Ubuntu 并删除残留的 `alpine-rootfs`。重建资源：

```
./scripts/prepare_android_sandbox.sh
```

## 主机 `su`（可与 Shizuku 共存）

优先 Magisk / KernelSU。若没有 `su` 或 Magisk 拒绝提权，同一条命令会在 binder 就绪时经 Shizuku 重试。设置 → 权限里，**Host su** 卡片就在 Shizuku 旁边。

```
su -c id
android-su status
```

深链：`minis://settings/host-su`。

## 共享存储

SAF 目录绑定在 `/var/minis/mounts/<name>/`。若有「所有文件访问」：`/sdcard`、`/storage/emulated/0`、`/var/minis/mounts/sdcard`。

## 为何首次编译可能缺 Go / rclone.aar

`src/android/app/libs/rclone.aar` 是 gomobile 产物（约 15MB），已 gitignore，**不是**编译 APK 的硬依赖。

- 没有 AAR 时，Gradle 会编译 `src/rcloneStub`，APK 能打出来；SMB/WebDAV/SFTP/S3/FTP 备份会在 RPC 时返回 501 说明。
- 只有要完整远程备份时，才需要主机 Go 1.26+（云端钉 1.26.8）与 gomobile，然后运行 `./deps/build_rclone_android.sh`，把 AAR 放到 `src/android/app/libs/rclone.aar`。
- 客户机里 `minis-dev-setup` 会装 `golang-go`，方便在沙箱里重建绑定，但首次从 Windows/macOS 编译 APK 不必装 Go。
