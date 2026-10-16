package com.openminis.app.plugins

/**
 * [T-dev-suite] Declarative dev-environment bundles for the guest sandbox.
 *
 * Declarative recipe model (PluginBundle/PluginComponent):
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
    /**
     * Conservative free-space reservation for this component: downloaded payload
     * PLUS build output, in bytes. apt-backed components carry a real estimate
     * too — [DevSuiteInstaller] refuses an assembly whose total exceeds what the
     * device can actually free, and a 0 here meant an apt-heavy suite (go, node,
     * python) was never gated at all. The number is the unpacked footprint, not
     * the download size, because dpkg holds both halves on disk while installing.
     * [DevSuiteInstaller] adds a bounded reserve for the resumable `.part` cache.
     */
    val estimatedBytes: Long = 0L,
)

data class DevBundle(
    val id: String,
    @androidx.annotation.StringRes val nameRes: Int,
    @androidx.annotation.StringRes val summaryRes: Int,
    val components: List<DevComponent>,
) {
    fun component(id: String): DevComponent? = components.firstOrNull { it.id == id }
}

object DevSuiteCatalog {

    /** Bundles shown in the "开发环境套件" section. */
    val bundles: List<DevBundle> = listOf(
        DevBundle(
            id = "android-suite",
            nameRes = com.openminis.app.R.string.dev_suite_android_name,
            summaryRes = com.openminis.app.R.string.dev_suite_android_summary,
            components = listOf(
                DevComponent(
                    id = "android-core",
                    name = "Android 核心基础环境",
                    description = "OpenJDK 17 + aapt2/adb/sdkmanager + android.jar(35/36) + CMake（NDK r29 由「C/C++ & NDK 原生构建链」单独安装）",
                    isRequired = true,
                    aptPackages = listOf("openjdk-17-jdk-headless", "ca-certificates-java", "curl", "ca-certificates", "unzip"),
                    // [T-devsuite-ndk-split] minis-android-sdk-setup installs the
                    // ~1 GB NDK unless MINIS_SKIP_NDK=1 and exits 1 when its NDK
                    // tail check fails — running it unflagged here meant (a) a
                    // "core only" assembly still pulled the NDK, so the optional
                    // component was fictional, and (b) an NDK hiccup marked core
                    // FAILED although aapt2/sdkmanager had landed (its probe
                    // below only checks those two). The NDK leg belongs to
                    // android-ndk, which runs the same script without the flag.
                    postInstallSteps = listOf("MINIS_SKIP_NDK=1 /usr/local/bin/minis-android-sdk-setup"),
                    checkCommand = "test -x /opt/android-sdk/build-tools/35.0.2/aapt2 && test -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager",
                    purgePaths = listOf("/opt/android-sdk"),
                    estimatedBytes = 2_200_000_000L,
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
                    estimatedBytes = 2_500_000_000L,
                ),
                DevComponent(
                    id = "android-ndk",
                    name = "C/C++ & NDK 原生构建链",
                    description = "cmake/ninja/clang + NDK r29 aarch64（minis-android-sdk-setup 幂等补齐）",
                    aptPackages = listOf("cmake", "ninja-build", "gcc", "g++", "clang", "make", "pkg-config"),
                    // The NDK leg of the shared setup script — android-core ran
                    // the same script with MINIS_SKIP_NDK=1 (see above), so this
                    // is the only place the ~1 GB NDK is fetched.
                    postInstallSteps = listOf("/usr/local/bin/minis-android-sdk-setup"),
                    checkCommand = "test -x /opt/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-aarch64/bin/clang",
                    dependsOn = listOf("android-core"),
                    estimatedBytes = 1_500_000_000L,
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
                    estimatedBytes = 400_000_000L,
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
                    estimatedBytes = 1_500_000_000L,
                ),
            ),
        ),
        DevBundle(
            id = "code-search-suite",
            nameRes = com.openminis.app.R.string.dev_suite_code_search_name,
            summaryRes = com.openminis.app.R.string.dev_suite_code_search_summary,
            components = listOf(
                DevComponent(
                    id = "code-search-toolkit",
                    name = "代码检索四件套",
                    description = "ripgrep + fd-find + fzf + bat",
                    isRequired = true,
                    aptPackages = listOf("ripgrep", "fd-find", "fzf", "bat"),
                    checkCommand = "command -v rg && command -v fzf && command -v batcat",
                    estimatedBytes = 120_000_000L,
                ),
            ),
        ),
        DevBundle(
            id = "python-suite",
            nameRes = com.openminis.app.R.string.dev_suite_python_name,
            summaryRes = com.openminis.app.R.string.dev_suite_python_summary,
            components = listOf(
                DevComponent(
                    id = "python-core",
                    name = "Python 3 核心运行时",
                    description = "python3 + pip + venv",
                    isRequired = true,
                    aptPackages = listOf("python3", "python3-pip", "python3-venv"),
                    checkCommand = "command -v python3 && command -v pip3",
                    estimatedBytes = 250_000_000L,
                ),
                DevComponent(
                    id = "python-ai-dev",
                    name = "AI 科学计算与 C 扩展编译基础",
                    description = "python3-dev + build-essential + pkg-config + libffi（numpy/pandas 等编译安装所需）",
                    aptPackages = listOf("python3-dev", "build-essential", "pkg-config", "libffi-dev"),
                    checkCommand = "test -f /usr/include/Python.h || ls /usr/include/python3*/Python.h >/dev/null 2>&1",
                    dependsOn = listOf("python-core"),
                    estimatedBytes = 200_000_000L,
                ),
            ),
        ),
        DevBundle(
            id = "nodejs-suite",
            nameRes = com.openminis.app.R.string.dev_suite_nodejs_name,
            summaryRes = com.openminis.app.R.string.dev_suite_nodejs_summary,
            components = listOf(
                DevComponent(
                    id = "nodejs-core",
                    name = "Node.js 核心运行时",
                    description = "node + npm",
                    isRequired = true,
                    aptPackages = listOf("nodejs", "npm"),
                    checkCommand = "command -v node && command -v npm",
                    estimatedBytes = 250_000_000L,
                ),
                DevComponent(
                    id = "nodejs-pkg",
                    name = "现代包管理与构建加速",
                    description = "pnpm + yarn（npmmirror 回退）",
                    postInstallSteps = listOf("/usr/local/bin/minis-setup-pnpm"),
                    checkCommand = "command -v pnpm",
                    estimatedBytes = 80_000_000L,
                    dependsOn = listOf("nodejs-core"),
                    purgeLinks = listOf("pnpm", "yarn"),
                ),
            ),
        ),
        DevBundle(
            id = "go-suite",
            nameRes = com.openminis.app.R.string.dev_suite_go_name,
            summaryRes = com.openminis.app.R.string.dev_suite_go_summary,
            components = listOf(
                DevComponent(
                    id = "go-toolchain",
                    name = "Go 工具链",
                    description = "golang-go（apt）",
                    isRequired = true,
                    aptPackages = listOf("golang-go"),
                    checkCommand = "command -v go",
                    estimatedBytes = 700_000_000L,
                ),
            ),
        ),
    )

    /**
     * Standalone online tools (tools.json style): not part of a bundle,
     * installed one-shot from the "智能体生态" area of the same screen.
     */
    val onlineTools: List<DevComponent> = listOf(
        DevComponent(
            id = "qemu-x86-64-compat",
            name = "QEMU x86_64 兼容层",
            description = "qemu-user-static：在 ARM64 沙箱里显式运行 x86_64 Linux 二进制（PRoot 无 binfmt，需显式调用 qemu-x86_64 <bin>）",
            aptPackages = listOf("qemu-user-static"),
            // [T-devsuite-qemu-probe] Ubuntu 24.04 arm64 的 qemu-user-static
            // 只装 *-static 命名（/usr/bin/qemu-x86_64-static），不产生裸
            // qemu-x86_64；Debian 老版本则相反。探测必须接受两种命名，而不是
            // 把包名钉死在某一种——probeBinaryCandidates 就是这个判定的纯函数。
            checkCommand = "command -v qemu-x86_64 || command -v qemu-x86_64-static",
            estimatedBytes = 60_000_000L,
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
            estimatedBytes = 2_000_000_000L,
        ),
    )

    fun bundleById(id: String): DevBundle? = bundles.firstOrNull { it.id == id }
    fun onlineToolById(id: String): DevComponent? = onlineTools.firstOrNull { it.id == id }

    /**
     * [T-devsuite-qemu-probe] Pure binary-name candidate decision: a package
     * may provide the bare tool name (older Debian qemu-user-static) or a
     * `-static`-suffixed one (Ubuntu 24.04 arm64 ships ONLY
     * /usr/bin/qemu-x86_64-static, no bare qemu-x86_64). Probing a single
     * hardcoded name mismatched the package's actual artifact and failed a
     * finished install; the accepted set must be derived from the tool name
     * instead. [name] is the canonical invocation name (e.g. "qemu-x86_64");
     * returns every name whose presence should count as ready, first entry
     * first. Pure so the probe contract is unit-testable without a shell.
     */
    internal fun probeBinaryCandidates(name: String): List<String> {
        val base = name.substringBeforeLast(".")
        return if (base.endsWith("-static")) {
            val bare = base.removeSuffix("-static")
            listOf(base, bare)
        } else {
            listOf(base, "$base-static")
        }
    }

    /**
     * [T-devsuite-uncheck-converge] Whether the assembly dialog's checkbox
     * can actually change this component's outcome. The checkbox is the
     * TARGET state: checking installs, unchecking removes. It is only
     * honest to offer the toggle when the assembly can deliver both
     * directions —
     *  * required components are locked on (the bundle needs them);
     *  * an installed component is only removable when its recipe declares
     *    purge targets ([DevComponent.purgePaths]/[purgeLinks]). apt-only
     *    components (rg, fd, qemu) share packages with the whole rootfs,
     *    so "uncheck = remove" cannot be delivered and the checkbox stays
     *    locked — the same rule that gates the dialog's 移除 button.
     * Before this rule the checkbox was `!isRequired || checked`, which is
     * always true: required rows bounced back on tap and unchecking an
     * installed optional component was a visual no-op (the assembly never
     * uninstalled, and the next dialog open pre-checked it again).
     */
    internal fun checkboxEditable(isRequired: Boolean, installed: Boolean, purgeable: Boolean): Boolean =
        !isRequired && (!installed || purgeable)

    /**
     * [T-devsuite-tool-link] What `minis://devsuite/install/<id>` points at:
     * a bundle OR a standalone online tool. Resolving only bundles made an
     * online-tool link (the prompt advertises `/install/llama-cpp`) a silent
     * no-op — the market opened, the pending link was consumed, nothing
     * installed and no error was shown. A tool is wrapped in a one-component
     * bundle so the link still lands on the SAME explicit confirmation dialog;
     * the tool is pre-checked because tapping the link is already a choice,
     * but 装配 remains the only thing that starts a download.
     */
    fun installTargetById(id: String): DevBundle? = bundleById(id) ?: onlineToolById(id)?.let {
        DevBundle(
            id = it.id,
            // [T-devsuite-online-res] Wrapper bundle reuses the online-tools
            // header/footer strings; the component row below shows the tool's
            // own name/description, so the dialog still lands coherently.
            nameRes = com.openminis.app.R.string.dev_suite_online_header,
            summaryRes = com.openminis.app.R.string.dev_suite_online_footer,
            components = listOf(it),
        )
    }

    /** True when [id] names a standalone online tool rather than a bundle. */
    fun isOnlineToolId(id: String): Boolean = onlineToolById(id) != null

    /** All components across bundles + online tools, for id uniqueness checks. */
    fun allComponents(): List<DevComponent> =
        bundles.flatMap { it.components } + onlineTools

    /**
     * Deep link target for the agent: minis://devsuite/install/<bundleId>.
     * Also used for online tools (see [installTargetById]); every bundle and
     * tool id is `[a-z0-9-]+` so it is deep-link safe by construction.
     */
    fun installLink(bundleId: String): String = "minis://devsuite/install/$bundleId"
}
