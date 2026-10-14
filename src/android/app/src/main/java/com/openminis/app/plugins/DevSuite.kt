package com.openminis.app.plugins

/**
 * [T-dev-suite] Declarative dev-environment bundles for the guest sandbox.
 *
 * Modeled after taixu's PluginBundle/PluginComponent (DevEnvironmentSuite.kt):
 * the APK ships only the *recipe* — apt package names, a setup script name,
 * a readiness probe — never the payload. Heavy ingredients (SDK toolchains,
 * Flutter, llama.cpp) download on demand inside the sandbox, which is what
 * keeps the APK ~36MB lighter while giving users the same one-tap assembly.
 *
 * Execution lives in [DevSuiteInstaller]; state in [DevSuiteStore]; the
 * management surface is the "开发环境套件" section of PluginMarketScreen.
 * The agent-facing surface is the `minis://devsuite/install/<bundleId>`
 * deep link (see ChatViewModelPromptExt).
 */
data class DevComponent(
    val id: String,
    val name: String,
    val description: String,
    /** Required inside its bundle: pre-checked and not deselectable. */
    val isRequired: Boolean = false,
    /** apt packages installed before [postInstallSteps] run. */
    val aptPackages: List<String> = emptyList(),
    /** Shell commands run inside the guest after apt (script names or inline). */
    val postInstallSteps: List<String> = emptyList(),
    /** Readiness probe: exit 0 = ready. Run through the guest shell. */
    val checkCommand: String,
    /** Component ids that must be ready before this one installs. */
    val dependsOn: List<String> = emptyList(),
    /** Directories removed on uninstall (must be absolute, guest-side). */
    val purgePaths: List<String> = emptyList(),
    /** Command symlinks removed on uninstall (names, resolved on PATH). */
    val purgeLinks: List<String> = emptyList(),
)

data class DevBundle(
    val id: String,
    val name: String,
    val summary: String,
    val components: List<DevComponent>,
) {
    fun component(id: String): DevComponent? = components.firstOrNull { it.id == id }
}

object DevSuiteCatalog {

    /** Bundles shown in the "开发环境套件" section. */
    val bundles: List<DevBundle> = listOf(
        DevBundle(
            id = "android-suite",
            name = "Android & 移动全栈开发套件",
            summary = "JDK17 · aarch64 SDK 工具链 · NDK r29 · 可选 Flutter/逆向/Rust",
            components = listOf(
                DevComponent(
                    id = "android-core",
                    name = "Android 核心基础环境",
                    description = "OpenJDK 17 + aapt2/adb/sdkmanager + android.jar(35/36) + CMake + NDK r29，全部按需下载（aarch64）",
                    isRequired = true,
                    aptPackages = listOf("openjdk-17-jdk-headless", "ca-certificates-java", "curl", "ca-certificates", "unzip"),
                    postInstallSteps = listOf("/usr/local/bin/minis-android-sdk-setup"),
                    checkCommand = "test -x /opt/android-sdk/build-tools/35.0.2/aapt2 && test -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager",
                    purgePaths = listOf("/opt/android-sdk"),
                ),
                DevComponent(
                    id = "flutter",
                    name = "Flutter 跨平台开发环境",
                    description = "Flutter stable SDK（上海交大/清华镜像回退），flutter/dart 挂到 PATH",
                    aptPackages = listOf("git", "curl", "xz-utils"),
                    postInstallSteps = listOf("/usr/local/bin/minis-setup-flutter"),
                    checkCommand = "test -x /opt/flutter/bin/flutter",
                    purgePaths = listOf("/opt/flutter"),
                    purgeLinks = listOf("flutter", "dart"),
                ),
                DevComponent(
                    id = "android-ndk",
                    name = "C/C++ & NDK 原生构建链",
                    description = "cmake/ninja/clang + NDK r29 aarch64（minis-android-sdk-setup 幂等补齐）",
                    aptPackages = listOf("cmake", "ninja-build", "gcc", "g++", "clang", "make", "pkg-config"),
                    postInstallSteps = listOf("/usr/local/bin/minis-android-sdk-setup"),
                    checkCommand = "test -x /opt/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-aarch64/bin/clang",
                    dependsOn = listOf("android-core"),
                ),
                DevComponent(
                    id = "android-re",
                    name = "Android 逆向分析与代码审计",
                    description = "jadx 反编译器 + apktool（GitHub 镜像回退）",
                    aptPackages = listOf("openjdk-17-jdk-headless", "curl", "apktool", "unzip"),
                    postInstallSteps = listOf("/usr/local/bin/minis-setup-jadx"),
                    checkCommand = "command -v jadx",
                    purgePaths = listOf("/opt/jadx"),
                    purgeLinks = listOf("jadx", "jadx-gui"),
                ),
                DevComponent(
                    id = "rust-dev",
                    name = "Rust 系统与 Android JNI 交叉编译链",
                    description = "rustup（rsproxy 镜像）+ stable 工具链",
                    aptPackages = listOf("curl", "ca-certificates", "build-essential"),
                    postInstallSteps = listOf("/usr/local/bin/minis-setup-rust"),
                    checkCommand = "command -v rustc && command -v cargo",
                    purgePaths = listOf("/root/.rustup", "/root/.cargo"),
                    purgeLinks = listOf("rustc", "cargo", "rustup", "rustfmt"),
                ),
            ),
        ),
        DevBundle(
            id = "code-search-suite",
            name = "代码检索与终端效率套件",
            summary = "rg / fd / fzf / bat 四件套",
            components = listOf(
                DevComponent(
                    id = "code-search-toolkit",
                    name = "代码检索四件套",
                    description = "ripgrep + fd-find + fzf + bat",
                    isRequired = true,
                    aptPackages = listOf("ripgrep", "fd-find", "fzf", "bat"),
                    checkCommand = "command -v rg && command -v fzf && command -v batcat",
                ),
            ),
        ),
        DevBundle(
            id = "python-suite",
            name = "Python & AI 开发者套件",
            summary = "Python 3 运行时 · pip/venv · AI 科学计算编译依赖",
            components = listOf(
                DevComponent(
                    id = "python-core",
                    name = "Python 3 核心运行时",
                    description = "python3 + pip + venv",
                    isRequired = true,
                    aptPackages = listOf("python3", "python3-pip", "python3-venv"),
                    checkCommand = "command -v python3 && command -v pip3",
                ),
                DevComponent(
                    id = "python-ai-dev",
                    name = "AI 科学计算与 C 扩展编译基础",
                    description = "python3-dev + build-essential + pkg-config + libffi（numpy/pandas 等编译安装所需）",
                    aptPackages = listOf("python3-dev", "build-essential", "pkg-config", "libffi-dev"),
                    checkCommand = "test -f /usr/include/Python.h || ls /usr/include/python3*/Python.h >/dev/null 2>&1",
                    dependsOn = listOf("python-core"),
                ),
            ),
        ),
        DevBundle(
            id = "nodejs-suite",
            name = "Node.js & Web 全栈套件",
            summary = "Node.js 运行时 · npm · pnpm/yarn",
            components = listOf(
                DevComponent(
                    id = "nodejs-core",
                    name = "Node.js 核心运行时",
                    description = "node + npm",
                    isRequired = true,
                    aptPackages = listOf("nodejs", "npm"),
                    checkCommand = "command -v node && command -v npm",
                ),
                DevComponent(
                    id = "nodejs-pkg",
                    name = "现代包管理与构建加速",
                    description = "pnpm + yarn（npmmirror 回退）",
                    postInstallSteps = listOf("/usr/local/bin/minis-setup-pnpm"),
                    checkCommand = "command -v pnpm",
                    dependsOn = listOf("nodejs-core"),
                    purgeLinks = listOf("pnpm", "yarn"),
                ),
            ),
        ),
        DevBundle(
            id = "go-suite",
            name = "Go 开发套件",
            summary = "golang-go 工具链（OpenMinis 独有，taixu 无此套件）",
            components = listOf(
                DevComponent(
                    id = "go-toolchain",
                    name = "Go 工具链",
                    description = "golang-go（apt）",
                    isRequired = true,
                    aptPackages = listOf("golang-go"),
                    checkCommand = "command -v go",
                ),
            ),
        ),
    )

    /**
     * Standalone online tools (taixu tools.json style): not part of a bundle,
     * installed one-shot from the "智能体生态" area of the same screen.
     */
    val onlineTools: List<DevComponent> = listOf(
        DevComponent(
            id = "qemu-x86-64-compat",
            name = "QEMU x86_64 兼容层",
            description = "qemu-user-static：在 ARM64 沙箱里显式运行 x86_64 Linux 二进制（PRoot 无 binfmt，需显式调用 qemu-x86_64 <bin>）",
            aptPackages = listOf("qemu-user-static"),
            checkCommand = "command -v qemu-x86_64",
        ),
        DevComponent(
            id = "llama-cpp",
            name = "llama.cpp 本地推理",
            description = "源码编译 llama-server（OpenAI 兼容本地推理 API）。PRoot 下编译约 10-30 分钟",
            aptPackages = listOf("build-essential", "cmake", "git", "ca-certificates", "curl"),
            postInstallSteps = listOf("/usr/local/bin/minis-setup-llama-cpp"),
            checkCommand = "command -v llama-server",
            purgePaths = listOf("/opt/llama.cpp"),
            purgeLinks = listOf("llama-server"),
        ),
    )

    fun bundleById(id: String): DevBundle? = bundles.firstOrNull { it.id == id }
    fun onlineToolById(id: String): DevComponent? = onlineTools.firstOrNull { it.id == id }

    /** All components across bundles + online tools, for id uniqueness checks. */
    fun allComponents(): List<DevComponent> =
        bundles.flatMap { it.components } + onlineTools

    /** Deep link target for the agent: minis://devsuite/install/<bundleId> */
    fun installLink(bundleId: String): String = "minis://devsuite/install/$bundleId"
}
