# taixu 插件体系与底层中心分析：可参考/复用点

对标对象：`wkbin/taixu`（本地浅克隆 `/var/minis/workspace/taixu`）。
结论先行：**taixu 的 APK 只装"配方"（JSON 清单 + shell 脚本），不装"原料"（rootfs/SDK/二进制）。原料全部运行时从网络拉、在沙箱内自举。** 这套体系里最值得我们抄的是三样：Android SDK 配方化安装（-36MB APK + -36MB 每沙箱存储）、安装事务管理（begin/commit/recover）、声明式工具注册表。工具层面值得引入：llama.cpp 本地推理、guest→host ADB 桥、三个 Python MCP server。

## 1. taixu 的底层中心（runtime 层）

### 1.1 rootfs：OCI 镜像运行时拉取
- `RootfsInstaller.installOci()`（runtime/rootfs/RootfsInstaller.kt:51）：Ubuntu/Debian/Kali/Arch/Alpine 全部从 OCI registry 拉，路由耗尽回退 TUNA lxc-images（RootfsInstaller.kt:351）
- APK 里只有 jniLibs 324KB（libproot 240K + talloc 32K + shmem/pty/loader）+ assets ~1MB
- README:299 明说权衡："首次安装依赖网络：RootFS 不随 APK 打包"

### 1.2 资产同步：RuntimeAssetSynchronizer
- `runtime/scripts/RuntimeAssetSynchronizer.kt`：APK assets/scripts/ → 沙箱 /opt/taixu/scripts/，赋执行权限
- 关键细节：APK 升级后**已有 rootfs 不重装**，只重新同步脚本（LinuxRuntimeImpl.kt:401 注释："APK 升级可能新增或替换内置 MCP/构建脚本。已有 rootfs 不会再走 configureRootfs"）——增量同步，不是全量重装

### 1.3 安装事务：InstallTransactionManager
- `tools/InstallTransactionManager.kt`：begin（快照现存目录）→ commit（删快照）→ recover（进程崩溃后回收孤儿事务，按 lastModified 取最新）
- 设计原则（类注释）："目录清理属于尽力而为：残留文件由下次 begin/recover/cleanupOrphans 兜底，任何清理失败都只记录日志，绝不允许让安装/回滚流程崩溃"
- 工具数据在程序目录之外，app 升级不丢

### 1.4 Android SDK：全脚本安装
`app/src/main/assets/scripts/setup_android_core.sh`：
- lzhiyong/android-sdk-tools aarch64 静态工具（与我们同源！我们 RootfsManager.kt:773 也用它）+ Google build-tools（Java 工具 d8/r8）+ Gradle 8.14.2
- 每个 artifact 带 SHA256/SHA1 校验
- flock 互斥锁防并发安装（`/opt/taixu/locks/android-toolchain.lock`，等锁 1800s 超时）
- **五镜像回退链**：ghfast.top → ghproxy.net → gh.llkk.cc → gh-proxy.com → github 直连（国内可达性设计）

## 2. taixu 的插件/工具体系（tools 层）

### 2.1 声明式工具注册表
`app/src/main/assets/registry/tools.json`（3 个工具）：
- 每个工具 = 一个 manifest：依赖（`python>=3.11`）、installSteps（**shell 命令数组**，在沙箱内执行）、verifyCommand、commandLinks（软链到 PATH）、environment、architectures、permissions
- `GenericRecipeInstaller`（tools/runtime/tools/GenericRecipeInstaller.kt）：按 manifest 驱动安装/软链/校验/生命周期——加工具 = 加 JSON 条目，零 Kotlin 代码

### 2.2 工具管理器
`ToolManager`（3825 行的 tools 模块）：安装进度流（StateFlow）、互斥、后台任务注册、通知、SecretRedactor（日志脱敏）、本地插件 payload 管理

### 2.3 skills：GitHub 分发 + hash 锁定
- `skills-lock.json`：`{"material-3": {"source": "hamen/material-3-skill", "sourceType": "github", "computedHash": "..."}}`
- `SkillInstallationManager`/`SkillPackageParser`/`ClawHubClient`：从 GitHub 装 skill，hash 锁定防漂移

### 2.4 MCP servers：纯 Python 脚本
assets/scripts/ 里的 `git_mcp_server.py`、`sqlite_mcp_server.py`、`apktool_mcp_server.py`、`codegraph_mcp_server.py`——dependency-free Python（标准库实现 JSON-RPC），零二进制零体积

### 2.5 内置插件（BuiltinPlugins）
`core/model/AgentPlugin.kt`：PRoot 诊断探针、高危命令拦截器、web 检索增强、**host_bridge（内置 ADB）**——沙箱内通过 127.0.0.1:7980 访问宿主能力（APK 静默安装、特权 shell），优先内置无线调试 ADB，回退 Shizuku/Root

## 3. OpenMinis 现状对照

| 维度 | taixu | OpenMinis | 差距 |
|---|---|---|---|
| rootfs | OCI 运行时拉取 | APK 打包 30MB（ubuntu-base.tar.gz） | 产品决策差异（离线开箱 vs 体积） |
| Android SDK | setup_android_core.sh 脚本安装 | APK 打包 36MB，**rootfs 初始化无条件解压**（RootfsManager.kt:421-422 configureUbuntuGuest → installBundledAndroidSdkTools/installBundledCmdlineTools） | 我们双倍成本：APK 36MB + 每个沙箱 36MB 解压副本 |
| 工具安装 | tools.json 声明式配方 | minis-build-env / minis-dev-setup（内置 Kotlin 驱动） | 形态差异，功能同级 |
| 安装事务 | begin/commit/recover 三段式 + 孤儿回收 | installBundledAndroidSdkTools 有精确回滚（written 列表）但无崩溃恢复 | 可补 |
| MCP | Python 脚本进沙箱 | 宿主侧配置（mcp-servers/） | 形态差异 |
| skills | GitHub 安装 + lock | 本地目录手动放 | 可补分发能力 |
| 宿主桥 | guest→host（127.0.0.1:7980 ADB） | host→guest（android-* CLI 走 PRoot 边界） | 方向相反，各有优劣 |

## 4. 建议：按价值排序

### P0：Android SDK 配方化（-36MB APK，-36MB 每沙箱存储）
- **为什么能做**：`minis-android-sdk-setup` 本来就是显式命令，用户心智已是"要 Android 开发才装"；36MB 却在 rootfs 初始化时无条件解压——不用 Android 构建的用户也在付存储
- **怎么做**：RootfsManager 的 installBundledAndroidSdkTools 已有 marker 版本控制 + 精确回滚（written 列表），把"assets 解压"换成"URL 下载"即可；镜像回退链抄 taixu 五镜像（我们已有 android-sdk-mirrors skill 沉淀了国内镜像知识）；加 InstallTransactionManager 式的崩溃恢复
- **风险**：首次安装要网络（taixu 同款权衡）；CI 的 sandbox-assets 工作流要同步改

### P1：ubuntu-base 运行时拉取（-30MB APK）
- taixu 的 RootfsInstaller.installOci + LxcImagesClient（TUNA 回退）可直接参考
- **产品决策**：牺牲"零网络冷启动"卖点。可做混合：APK 保留最小 rootfs（bash+apt，~8MB），完整环境按需拉

### P1：声明式工具注册表 + 首批配方
- 抄 tools.json + GenericRecipeInstaller 形态，首批三个配方：
  1. **llama.cpp**（taixu 已有完整 installSteps 可抄）：沙箱内编译 llama-server，OpenAI 兼容本地推理——补上我们"本地推理"空白，离线/隐私场景
  2. **qemu-x86_64-compat**（taixu 已有）：ARM64 沙箱跑 x86_64 Linux 二进制
  3. **sqlite/codegraph MCP server**：纯 Python 脚本，从 taixu 抄（MIT/License 需确认），零体积

### P2：skills GitHub 分发
- SkillInstallationManager + skills-lock.json 模式，让 skill 生态可分发

### 不建议抄的
- host_bridge（guest→host ADB 桥）：架构优雅，但我们 android-su/Shizuku 已覆盖宿主能力，且 guest→host 反向桥要开 REQUEST_INSTALL_PACKAGES + 本地监听，安全面变大。记为观察项
- 多发行版支持：我们单 Ubuntu 深度优化（glibc 修复、CA 注入、apt 源切换都是 Ubuntu 专属），摊多发行版稀释质量

## 5. 体积账

| 改动 | APK | 用户存储 | 代价 |
|---|---|---|---|
| P0 Android SDK 配方化 | -36MB | -36MB/沙箱 | 首次装 SDK 要网络 |
| P1 ubuntu-base 拉取 | -30MB | 0 | 首次启动要网络 |
| 已完成（PR22） | -7.4MB | 0 | 无 |
| 合计 | **-73.4MB** | -36MB/沙箱 | — |

## 6. 未验证项

- taixu tools.json 的 installSteps 在其真机上的实际成功率（我们只验证了声明结构）
- llama.cpp 沙箱内编译时长（aarch64 沙箱 cmake build，估计 3-10 分钟，未实测）
- lzhiyong/android-sdk-tools 五镜像的当前可达性（未逐一探测）
