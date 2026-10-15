# 为什么 taixu / OmniBot 的 APK 小一倍还功能齐全、多沙箱

> 结论先行：**他们的"重资源"全部运行时下载，APK 只装引擎不装燃料**。OpenMinis 把 Ubuntu rootfs（~30MB）和 Android SDK 工具链（36MB）直接打进 APK，换取"零网络冷启动"——这是产品决策差异，不是技术差距。但 OpenMinis 也有 ~4MB 纯冗余（未压缩的 models-dev-api.json）可以免费砍掉。

## 一、三方 APK 构成实测（源码级证据）

### OpenMinis-Linux（我们）

CI（android-apk.yml）在构建时往 assets 塞入：

| 项 | 体积 | 来源 | 性质 |
|---|---|---|---|
| ubuntu-base.tar.gz | ~30MB | CI 从 cdimage.ubuntu.com 下载（prepare_android_sandbox.sh:22） | **打进 APK** |
| android-cmdline-tools.zip | 21MB | 本地 assets 实测 | **打进 APK** |
| android-sdk-tools-aarch64.zip | 15MB | 本地 assets 实测 | **打进 APK** |
| models-dev-api.json（未压缩） | 4.1MB | 本地 assets 实测 | **纯冗余**（见下） |
| jieba 分词词典 | 5.4MB | 本地 assets 实测 | 打进 APK |
| katex | 887K | 本地 assets 实测 | 打进 APK |
| proot-aarch64 + libproot.so | ~2MB | CI native-deps | 打进 APK |
| rclone.aar | ~15MB | CI（存在时） | 打进 APK |
| mlkit 中文 OCR / ONNX VAD / rhino | 数十MB级 | gradle 依赖 | 打进 APK |

仅 assets 就 **76MB+**（46MB 本地 + 30MB CI 注入），再加 native 库和 dex，APK 100MB+ 顺理成章。

### taixu

- jniLibs 总共 **324KB**：libproot.so 240K + libtalloc 32K + shmem/pty/loader 各 16-20K（app/src/main/jniLibs/arm64-v8a 实测）
- assets 总共 **~1MB**（webchat 575K、scripts 248K、certs 136K）
- **rootfs 不打包**。README 第 299 行原话："首次安装依赖网络：RootFS 不随 APK 打包，安装时需要下载 OCI 镜像并占用额外存储空间"
- Kotlin 文件 745 个（和我们 857 个同级——代码量不是差异来源）

### OmniBot

- jniLibs 只有 **8KB** 的 libproot-loader（proot 本体也是运行时下载）
- assets 总共 **153K**
- rootfs 运行时从 Ubuntu 官方 cdimage 下载（EmbeddedRuntimeInstaller.kt:92，ubuntu-base 29.8MB 压缩）；Alpine 走官方/清华镜像（AlpineRepositoryManager.kt:16-17）
- 内置大模型由 `ADD_BUILTIN` 构建属性控制，默认**不内置**（split_assets.gradle:8）

## 二、为什么"多沙箱"反而更小

这是最反直觉的一点，答案是：**多沙箱 ≠ 打包多个环境**。

taixu 的"多沙箱"是三种**执行模式**（ExecutionMode.kt 枚举）：

- PRoot（免 root 用户态沙箱）
- Shizuku（ADB 提权）
- Root

三种模式共享同一个 240KB 的 libproot.so。而发行版多样性（Ubuntu/Debian/Kali/Arch/Alpine，RuntimeInstallRequest.kt:34-35 的 DistributionSpec）全部走 **OCI 镜像运行时拉取**（RootfsInstaller.installOci → OciRegistryClient，带清华 TUNA lxc-images 回退路由）。

也就是说：**支持 5 个发行版和支持 1 个的 APK 一样大**——加一个发行版 = 加一个镜像 URL，体积零增长。OmniBot 同理（Alpine + Ubuntu 都是下载）。

"按需下载"架构下，沙箱数量与 APK 体积**解耦**了。我们的架构是"数量与体积耦合"：每多一个环境就多一份打包资产。

## 三、OpenMinis 大在哪——逐项定性

| 差异项 | 定性 | 说明 |
|---|---|---|
| ubuntu-base 30MB | **产品决策** | 我们换"装完即用、零网络冷启动"；他们换"APK 小、首次要网"。各有道理 |
| Android SDK 36MB | **产品决策** | 开箱即用 Android 开发环境是 OpenMinis 独有卖点，taixu/OmniBot 根本没这功能。不是浪费 |
| mlkit OCR / ONNX VAD / rclone / rhino | **功能面差异** | 我们功能更宽（语音 VAD、中文 OCR、远程备份、JS 引擎），每个都带 native/模型体积 |
| models-dev-api.json 未压缩版 4.1MB | **纯冗余** | 代码首选读 gzip 版（ModelsDevApi.kt:736），未压缩版只是 gzip 失败的回退（:740）。本地资产解压失败概率趋零，4MB 白背 |
| jieba 4.9MB 词典 | **可优化** | 文本词典 gzip 压缩率通常 60%+，估计 -3MB |

## 四、可落地的瘦身清单

**零代价（建议直接做）：**
1. 删未压缩 models-dev-api.json（-4.1MB）：回退链改为直接走网络刷新
2. jieba 词典 gzip 化（估计 -3MB）：JiebaEngine.kt:112 的 assets.open 包一层 GZIPInputStream

**产品决策（需要拍板）：**
3. ubuntu-base 改首次运行下载（-30MB）：代价是首次进沙箱要网络。可做混合：Wi-Fi 下提示下载，或保留"完整离线包"双渠道发布
4. Android SDK 工具链改按需（-36MB）：`minis-android-sdk-setup` 本来就是显式命令，改成触发时下载完全成立——这是**最不伤体验**的大头，因为不用 SDK 的用户永远不碰它
5. mlkit OCR 改动态分发：注意国内无 GMS 设备会失效，需谨慎

**理论极限**：3+4 落地 = APK 缩 ~70MB，直接追平 taixu 量级，且功能零损失（SDK 用户首次 setup 多一次下载）。

## 五、一句话总结

他们不是"功能齐全还小"，是**把功能的重量从安装时挪到了首次使用时**。多沙箱恰恰是这种架构的副产品——引擎（proot 240KB）很小，燃料（rootfs）很大，他们只随 APK 发引擎。我们连燃料带备用油箱（Android SDK）全塞进了后备箱。后备箱里还有一桶永远用不到的 4MB 冗余油（未压缩 JSON）。

---
*分析基于三方源码实测（2026-10-14）：taixu 44MB 仓库、OmniBot 236MB 仓库、OpenMinis-Linux 本地 + CI workflow。APK 体积数字为源码资产推算，未实际解包 release APK。*
