package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MountedFoldersStore
import java.io.File
import java.util.TimeZone
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PRoot configuration holder and command builder.
 * Corresponds to iOS ISHKernel — but since PRoot is process-per-command
 * (not a persistent kernel), this object holds configuration state
 * and builds proot command lines.
 */
object PRootKernel {

    private const val TAG = "PRootKernel"

    var isBooted: Boolean = false
        private set

    /** Path to native library directory (for LD_LIBRARY_PATH). */
    var nativeLibDir: String = ""
        private set

    /** Path to PRoot loader binary (64-bit). */
    var prootLoaderPath: String = ""
        private set

    /** Path to PRoot loader binary (32-bit). */
    var prootLoader32Path: String = ""
        private set

    private var rootfsManagerRef: RootfsManager? = null
    private val rootfsManager: RootfsManager get() = checkNotNull(rootfsManagerRef) { "PRootKernel.boot must be called first" }

    /** Custom environment variables injected into every proot command. */
    val customEnvironment: MutableMap<String, String> = mutableMapOf()

    /** Bind mounts: Linux path -> host filesystem path. */
    val bindMounts: MutableMap<String, String> = linkedMapOf()

    /**
     * Initialize the PRoot environment: install rootfs and proot binary.
     */
    suspend fun boot(context: Context) {
        if (isBooted) {
            Log.d(TAG, "Already booted")
            return
        }

        // [T-android-startup-splash-hang] The blocking boot body — refreshDns /
        // configureUbuntuGuest file writes, registerGlobalBindMounts().mkdirs(),
        // installHandlerStubs() writes, NativeOffloadServer.start()'s abstract-
        // socket bind + Thread.sleep backoff and listFiles() sweep, and
        // applyMountedFoldersSnapshot()'s ContentResolver queries +
        // File.canExecute() checks — had no dispatcher switch, so the first
        // interactive terminal open (and any ExecutionCoordinator auto-boot) ran
        // all of it on the caller's thread, including Dispatchers.Main. Force it
        // onto Dispatchers.IO. Body ORDER and the isBooted guarantee are
        // unchanged: isBooted is set at the very end of this block, which the
        // suspend caller sees only after withContext returns.
        withContext(Dispatchers.IO) {
        rootfsManagerRef = RootfsManager.getInstance(context)
        rootfsManager.installIfNeeded()
        rootfsManager.installProotIfNeeded()

        // Overlay assets/default_mount/ onto the rootfs on every boot so
        // updated profile scripts and URL-interception wrappers ship with
        // each app update. Mirrors iOS RootfsManager.applyDefaultMountOverlay.
        rootfsManager.applyDefaultMountOverlay()
        rootfsManager.applyHostTimezone()

        // Re-apply user mirror selections — the overlay above ships stock
        // config files (pip.conf, .npmrc, repositories) and would otherwise
        // revert user-chosen mirrors on every boot. Mirrors iOS ordering in
        // ISHTerminalView.startShell().
        com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel.applyAllActiveMirrors(context)
        com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel.autoDetectOnceIfNeeded(context)

        // Off the critical boot path: heal mirrors first so a dead apt
        // source cannot burn dpkg/pip retry strikes, then retry failed
        // restores, then snapshot. One IO job so these never interleave
        // (they also serialize on RootfsManager.aptMutex).
        (context.applicationContext as? com.openminis.app.MinisApp)
            ?.appCoroutineScopes?.io?.launch {
            runCatching { rootfsManager.runMinisMirrorAuto() }
                .onFailure { Log.e(TAG, "[boot] mirror auto failed", it) }
            runCatching { rootfsManager.seedNetworkTools() }
                .onFailure { Log.e(TAG, "[boot] seed network tools failed", it) }
            runCatching { rootfsManager.retryFailedDpkgWorld() }
                .onFailure { Log.e(TAG, "[boot] retry dpkg world failed", it) }
            runCatching { rootfsManager.retryFailedPipWorld() }
                .onFailure { Log.e(TAG, "[boot] retry pip world failed", it) }
            runCatching { rootfsManager.dumpDpkgWorld() }
                .onFailure { Log.e(TAG, "[boot] dump dpkg world failed", it) }
            runCatching { rootfsManager.dumpPipWorld() }
                .onFailure { Log.e(TAG, "[boot] dump pip world failed", it) }
        }

        // Refresh DNS from system (mirrors iOS ISHKernel.configureDns)
        rootfsManager.refreshDns()

        // LD_LIBRARY_PATH for the extracted native libs. talloc used to be
        // staged here under a versioned name; deps/build_proot.sh now links it
        // statically, so only the native lib dir is needed.
        nativeLibDir = rootfsManager.nativeLibDir.absolutePath

        // PROOT_LOADER / PROOT_LOADER_32 overrides. The loader is bundled into
        // the proot binary (extracted via /proc/self/fd at runtime), so these
        // files normally do not exist — kept only to honour a side-loaded
        // loader if one is present.
        val loaderPath = File(rootfsManager.nativeLibDir, "libproot-loader.so")
        val loader32Path = File(rootfsManager.nativeLibDir, "libproot-loader32.so")
        if (loaderPath.exists()) prootLoaderPath = loaderPath.absolutePath
        if (loader32Path.exists()) prootLoader32Path = loader32Path.absolutePath

        // Set default PATH for Ubuntu Linux
        customEnvironment.putIfAbsent(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin:" +
                "/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:" +
                "/opt/android-sdk/build-tools/35.0.2:/opt/android-sdk/cmake/3.22.1/bin:/opt/gradle/bin",
        )
        customEnvironment.putIfAbsent("DEBIAN_FRONTEND", "noninteractive")
        // pip/python block-buffer on a pipe. Without this, a package install
        // prints nothing until the process exits.
        customEnvironment.putIfAbsent("PYTHONUNBUFFERED", "1")
        customEnvironment.putIfAbsent("SHELL", "/bin/bash")
        customEnvironment.putIfAbsent("ANDROID_HOME", "/opt/android-sdk")
        customEnvironment.putIfAbsent("ANDROID_SDK_ROOT", "/opt/android-sdk")
        customEnvironment.putIfAbsent("HOME", "/root")

        // URL interception: seed $BROWSER directly into every process envp
        // so non-login shells (which never source /etc/profile.d/minis.sh)
        // still route webbrowser.open()/etc into the host OpenOffloadHandler.
        // Mirrors iOS ISHShellExecutor.m:333.
        customEnvironment["BROWSER"] = "/usr/local/bin/minis-open"   // T195: force override; user dotfile BROWSER= would otherwise win

        // ash-specific: ENV points at a file the shell sources on startup.
        // Our /etc/profile sources /etc/profile.d/*.sh, so non-login shells
        // (PersistentShell uses plain `/bin/sh`) still pick up HISTFILE,
        // aliases, and the rest of the shipped profile. Mirrors iOS
        // ISHShellExecutor.m:326.
        customEnvironment.putIfAbsent("ENV", "/etc/profile")

        // Character-set declaration for tools that probe $CHARSET instead of
        // $LANG (e.g. some ncurses builds, older Python scripts). iOS sets
        // this in ISHShellExecutor.m:325.
        customEnvironment.putIfAbsent("CHARSET", "UTF-8")

        // Tool-compat env — match iOS ISHShellExecutor.m:358-360 so scripts
        // that run identically in both sandboxes produce identical output.
        // NO_COLOR: suppress ANSI color in shell tools that honor the
        //   informal NO_COLOR standard (curl, ls --color=auto, etc.).
        // PYTHONDONTWRITEBYTECODE: don't litter the rootfs with .pyc files
        //   next to user scripts.
        // GOMAXPROCS=2: keep Go runtimes modest on mobile; iOS caps at 2
        //   for the iSH emulator, same cap is a sensible default under
        //   PRoot on Android where the sandbox is also IO-bound.
        customEnvironment.putIfAbsent("NO_COLOR", "1")
        customEnvironment.putIfAbsent("PYTHONDONTWRITEBYTECODE", "1")
        customEnvironment.putIfAbsent("GOMAXPROCS", "2")

        // T222: PRoot's link2symlink extension creates .l2s.* sentinel files
        // alongside every hardlinked file. uv's default `hardlink` mode tries
        // to re-link these sentinels when populating site-packages from cache,
        // and PRoot rejects the second link with EPERM. Force uv to symlink
        // package files instead so the sentinels are never used as link
        // sources. Reported as openminis/openminis#7.
        // Mirrored in default_mount/etc/profile.d/minis.sh for login shells.
        customEnvironment.putIfAbsent("UV_LINK_MODE", "symlink")

        // ProcessBuilder inherits Android's TMPDIR (typically
        // /data/user/0/<pkg>/cache). PRoot treats that as a *guest* path, so
        // it either does not exist or is the host cache Android may have
        // wiped. dpkg/apt then fail unpacking — first seen installing
        // ca-certificates. Always pin guest temp to /tmp. Mirrored in
        // default_mount/etc/profile.d/minis.sh and the setup scripts.
        customEnvironment["TMPDIR"] = "/tmp"
        customEnvironment["TMP"] = "/tmp"
        customEnvironment["TEMP"] = "/tmp"

        // Guest TLS: point every common client at the Android-injected bundle
        // so HTTPS works even when ubuntu-base CA hash-symlinks did not extract.
        customEnvironment["SSL_CERT_FILE"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["SSL_CERT_DIR"] = "/etc/ssl/certs"
        customEnvironment["CURL_CA_BUNDLE"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["REQUESTS_CA_BUNDLE"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["GIT_SSL_CAINFO"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["PIP_CERT"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["NODE_EXTRA_CA_CERTS"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["NODE_OPTIONS"] = GuestLimits.nodeOptions(customEnvironment["NODE_OPTIONS"])
        customEnvironment["BASH_ENV"] = "/etc/profile.d/minis-limits.sh"

        // Inject device timezone so Alpine userspace sees local time.
        // Mirrors iOS ISHShellExecutor.m:335-353 — uses POSIX TZ format with a
        // fixed name ("LCL") to avoid abbreviations like "GMT+8" which contain
        // +/- and confuse musl's TZ parser. POSIX sign is reversed from UTC
        // offset (UTC+8 → "LCL-8"), matching iOS exactly.
        customEnvironment["TZ"] = guestTz()

        // Propagate the Android system HTTP proxy into every sandboxed
        // process so curl/wget/pip/npm reuse the user's system or enterprise
        // proxy (and packet-capture tools like Charles / mitmproxy just work).
        // PROXY_CHANGE_ACTION is wired up in MinisApp so live shells pick up
        // proxy toggles without a restart.
        customEnvironment.putAll(systemProxyEnv(context))

        // Register global bind mounts so direct file I/O tools (file_read, file_edit)
        // can resolve /var/minis/{memory,skills,shared}/... (idempotent).
        registerGlobalBindMounts(context)

        // Start the native_offload server so the proot extension can reach it
        // over the abstract unix socket. Handlers must have been registered
        // via NativeOffloadServer.register() before this point.
        NativeOffloadServer.start(rootfsManager.rootfsDir)

        // Materialize stub binaries inside the rootfs for each handler so
        // /bin/sh's PATH search succeeds and triggers an execve the extension
        // can intercept. The stub's content is irrelevant — proot rewrites
        // the execve before it runs.
        installHandlerStubs(rootfsManager.rootfsDir)

        // T219-6: now that rootfs is on disk, materialize /var/minis/mounts/<name>
        // placeholder dirs that PRoot's `-b` needs as bind targets. The earlier
        // applyMountedFoldersSnapshot in MinisApp.onCreate ran before boot and
        // its mkdirs went nowhere; this re-run covers the mount entries that
        // were known at app launch.
        applyMountedFoldersSnapshot(context)

        isBooted = true
        HostStatusPublisher.start(context, rootfsManager.rootfsDir)
        Log.i(TAG, "PRoot kernel booted " +
            "rootfs=${rootfsManager.rootfsDir.absolutePath} " +
            "nativeLibDir=$nativeLibDir " +
            "loader=$prootLoaderPath " +
            "bindMounts=${bindMounts.size} " +
            "offloadHandlers=${NativeOffloadServer.registeredHandlers.sorted()}")
        }
    }

    fun addBindMount(linuxPath: String, hostPath: String) {
        bindMounts[linuxPath] = hostPath
    }

    /**
     * Register the global (session-independent) Minis bind mounts so direct
     * file I/O tools (file_read, file_edit) can resolve
     * `/var/minis/{skills,shared}/...` without needing PRoot to be
     * booted or any shell to have started. Memory is per-session.
     * Safe to call repeatedly.
     */
    fun registerGlobalBindMounts(context: Context) {
        val globalBase = File(context.filesDir, SessionWorkspace.GLOBAL_DIR)
        // [T-mcp-integration-android] mcp-servers is global (like skills):
        // binding it here makes the in-PRoot minis-mcp-cli read/write the SAME
        // servers.json the Android Settings UI does (host: minis-global/mcp-servers).
        SessionWorkspace.GLOBAL_BIND_SUBDIRS.forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
    }

    fun removeBindMount(linuxPath: String) {
        bindMounts.remove(linuxPath)
    }

    fun clearBindMounts() {
        bindMounts.clear()
    }

    // ── User-mounted external folders (T219) ──────────────────────────────
    //
    // Linux prefix every external mount lives under inside the rootfs.
    // PRoot's `-b host:linux` flag is per-invocation, so the diff-sync
    // applied via [applyMountedFoldersSnapshot] only affects subsequent
    // shell_execute calls — live processes won't observe a CRUD mid-flight.
    private const val MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"

    // Sentinel in the read-only write-guard wrapper scripts so we can recognize
    // and remove our own wrappers (vs a user/busybox binary of the same name).
    private const val GUARD_MARKER = "minis-mount-readonly-guard"

    /**
     * Reference to the user-mounted folders store, set by [MinisApp] at
     * launch (the file is owned by T219-1 / worker A — we cannot touch it
     * from here, so we receive the instance via this setter instead of a
     * top-level singleton). Nullable: tools and the file-mention index
     * tolerate `null` and treat it as "no mounts".
     */
    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    /**
     * Reconcile [bindMounts] keys under `/var/minis/mounts/` with the
     * current snapshot of [mountedFoldersStore]. Removes stale entries,
     * adds new ones, updates host paths in place when the user re-points
     * a mount. Idempotent — call from boot + after every CRUD on the
     * store.
     *
     * Mounts whose tree URI doesn't decode to a POSIX path readable by
     * us (cloud-only providers, `Android/data/<otherPkg>` under strict
     * scoped storage, etc.) are skipped silently — surfacing an error
     * is the responsibility of the picker UI in T219-2.
     */
    fun applyMountedFoldersSnapshot(context: Context) {
        val store = mountedFoldersStore
        val desired = mutableMapOf<String, String>()
        if (store != null) {
            for (entry in store.entries.value) {
                val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: continue
                desired["$MOUNTS_LINUX_PREFIX${entry.name}"] = host
            }
        }
        applySharedStorageBinds(context, desired)

        // Remove stale /var/minis/mounts/* keys not in desired.
        val stale = bindMounts.keys
            .filter { it.startsWith(MOUNTS_LINUX_PREFIX) }
            .filter { it !in desired }
        for (key in stale) bindMounts.remove(key)

        // Add or update.
        for ((linuxPath, hostPath) in desired) {
            bindMounts[linuxPath] = hostPath
        }

        // T219-6: PRoot's `-b host:linux` requires the linux target to exist
        // inside the rootfs as a real directory; otherwise PRoot silently skips
        // the bind. Materialize a placeholder dir for each /var/minis/mounts/<name>
        // (and clean up stale ones) so the bind actually takes effect.
        materializeMountTargets(context, desired.keys)

        // T219-4: raw shell writes (touch/cp/mv/tee/… and `>` redirects) bypass
        // the FileWriteTool/FileEditTool read-only guard because proot `-b` has no
        // read-only modifier. Install shell wrappers that reject writes whose
        // target lands under an effectively-read-only mount, so the agent gets a
        // clear "read-only mounted folder" error in the shell instead of a write
        // that silently no-ops (Android 10 shadow FUSE) or a confusing raw EACCES.
        val readOnlyLinuxPrefixes = store?.entries?.value.orEmpty()
            .filter { it.name in desired.keys.map { k -> k.removePrefix(MOUNTS_LINUX_PREFIX) } }
            .filterNot { it.effectiveWritable }
            .map { "$MOUNTS_LINUX_PREFIX${it.name}" }
        installMountWriteGuards(context, readOnlyLinuxPrefixes)

        val entryCount = store?.entries?.value?.size ?: 0
        Log.i(
            TAG,
            "applyMountedFoldersSnapshot: entries=$entryCount active=${desired.size} removed=${stale.size} " +
                storageAccessDiag(context),
        )
        if (entryCount > desired.size) {
            Log.w(
                TAG,
                "applyMountedFoldersSnapshot: ${entryCount - desired.size} mount(s) dropped — host path " +
                    "unresolved or unreadable (see resolveTreeUriToHostPath warnings above)",
            )
        }
    }

    /**
     * Bind host shared storage into the guest when All Files Access (or
     * legacy WRITE_EXTERNAL_STORAGE on API 29) is granted. PRoot `-b` then
     * gives POSIX read/write without going back through SAF DocumentFile.
     * Does not override a user mount already named `sdcard`.
     */
    private fun applySharedStorageBinds(context: Context, desired: MutableMap<String, String>) {
        val userNamed = desired.keys.any { it.removePrefix(MOUNTS_LINUX_PREFIX) == "sdcard" }
        val plan = sharedStoragePlan(context, userNamed)
        syncSharedStorageBindMounts(plan, userNamed)
        val mounted = plan[SharedStorageBindPlan.MOUNTS_SDCARD]
        if (mounted != null) desired[SharedStorageBindPlan.MOUNTS_SDCARD] = mounted
        if (plan.isNotEmpty()) materializeSharedStorageTargets(context, plan.keys)
    }

    /**
     * Binds [PersistentShell] must pass on `-b`. [bindMounts] alone is not
     * that argv, so a grant that only updated the kernel map left `/sdcard`
     * as an empty rootfs directory inside `shell_execute`.
     */
    fun shellSharedStorageBinds(context: Context, publish: Boolean = true): Map<String, String> {
        val userNamed = mountedFoldersStore?.entries?.value?.any { it.name == "sdcard" } == true
        val plan = sharedStoragePlan(context, userNamed)
        if (!publish) return plan
        syncSharedStorageBindMounts(plan, userNamed)
        if (plan.isNotEmpty()) materializeSharedStorageTargets(context, plan.keys)
        return plan
    }

    private fun sharedStoragePlan(context: Context, userNamedSdcard: Boolean): Map<String, String> {
        val host = Environment.getExternalStorageDirectory()?.absolutePath
        val granted = !host.isNullOrBlank() &&
            hasSharedStorageAccess(context) &&
            File(host).canRead()
        return SharedStorageBindPlan.plan(if (granted) host else null, userNamedSdcard)
    }

    private fun syncSharedStorageBindMounts(plan: Map<String, String>, userNamedSdcard: Boolean) {
        for (linux in listOf(SharedStorageBindPlan.SDCARD, SharedStorageBindPlan.EMULATED)) {
            val host = plan[linux]
            if (host == null) bindMounts.remove(linux) else bindMounts[linux] = host
        }
        val mounted = SharedStorageBindPlan.MOUNTS_SDCARD
        if (mounted in plan) bindMounts[mounted] = plan.getValue(mounted)
        else if (!userNamedSdcard) bindMounts.remove(mounted)
    }

    private fun materializeSharedStorageTargets(context: Context, linuxPaths: Collection<String>) {
        materializeAbsoluteTargets(
            context,
            linuxPaths.filter { !it.startsWith(MOUNTS_LINUX_PREFIX) },
        )
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeSharedStorageTargets: rootfs not yet available: ${t.message}")
            return
        }
        for (linux in linuxPaths) {
            if (!linux.startsWith(MOUNTS_LINUX_PREFIX)) continue
            File(rootfs, linux.removePrefix("/")).mkdirs()
        }
    }

    private fun hasSharedStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun materializeAbsoluteTargets(context: Context, linuxPaths: List<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeAbsoluteTargets: rootfs not yet available: ${t.message}")
            return
        }
        for (linuxPath in linuxPaths) {
            File(rootfs, linuxPath.removePrefix("/")).mkdirs()
        }
    }

    /**
     * Ensure each `/var/minis/mounts/<name>` has a real (empty) directory
     * inside the rootfs that PRoot can bind onto. Also removes stale
     * placeholder dirs whose mount entry was unmounted, so old names don't
     * keep appearing in `ls /var/minis/mounts/`.
     */
    private fun materializeMountTargets(context: Context, desiredLinuxPaths: Set<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeMountTargets: rootfs not yet available: ${t.message}")
            return
        }
        val mountsRoot = File(rootfs, "var/minis/mounts").also { it.mkdirs() }

        // Create placeholder dirs for desired names.
        for (linuxPath in desiredLinuxPaths) {
            val name = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
            File(mountsRoot, name).also { d ->
                if (!d.exists()) d.mkdirs()
            }
        }

        // Clean placeholder dirs that no longer correspond to a mount.
        val desiredNames = desiredLinuxPaths.map { it.removePrefix(MOUNTS_LINUX_PREFIX) }.toSet()
        mountsRoot.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name !in desiredNames) {
                f.deleteRecursively()
            }
        }
    }

    /**
     * True when [linuxPath] resolves under a `/var/minis/mounts/<name>`
     * mount whose effective writability is false. Used by [FileWriteTool]
     * and [FileEditTool] to short-circuit before touching disk; mirrors
     * iOS `MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount`.
     *
     * The shell can technically still write through a PRoot bind because
     * `-b` has no read-only modifier — that's why the mount-detail screen
     * uses honest "Locked / Unlocked" wording. Defense-in-depth via a
     * shell-level wrapper is tracked as T219-4 follow-up (optional).
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean {
        if (!linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) return false
        val store = mountedFoldersStore ?: return false
        val rest = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
        val name = rest.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    /**
     * Snapshot of mount roots for the @-mention index. Skips entries
     * whose tree URI doesn't resolve to a POSIX path on this device.
     */
    fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> {
        val store = mountedFoldersStore ?: return emptyList()
        return store.entries.value.mapNotNull { entry ->
            val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
            FileMentionIndex.MountEntry(name = entry.name, root = File(host))
        }
    }

    /**
     * Decode a SAF tree URI (the `content://com.android.externalstorage.documents/tree/<volume>:<rel>`
     * form returned by `ACTION_OPEN_DOCUMENT_TREE`) into an absolute POSIX
     * path on the host filesystem so PRoot's `-b` flag can bind it. This
     * is "Option A" from the T219 spec: the dominant Obsidian-vault /
     * Downloads / DCIM use cases all live under `/storage/emulated/0` or
     * a removable volume's mount directory and are openable directly by
     * our app uid.
     *
     * Returns null when:
     *   - the URI is from a non-`externalstorage` provider (Drive,
     *     Dropbox, OneDrive — those have no POSIX path),
     *   - the volume id can't be resolved (rare — likely a removed
     *     SD card),
     *   - the resolved path doesn't exist or isn't readable by us
     *     (e.g. another app's `Android/data/<pkg>` under strict scoped
     *     storage).
     *
     * Workers A's later `MountedFolderCoordinator` will likely hoist
     * this exact algorithm into the data layer with a `resolvedHostPath`
     * cache field on `MountedFoldersStore.Entry`. Kept inline here so
     * T219-3 can ship before A finishes.
     */
    private fun resolveTreeUriToHostPath(treeUriString: String, context: Context): String? = try {
        val treeUri = Uri.parse(treeUriString)
        if (treeUri.authority != "com.android.externalstorage.documents") {
            null
        } else {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            val volumeId = parts.getOrNull(0).orEmpty()
            val relPath = parts.getOrNull(1).orEmpty()
            val volumeRoot = resolveVolumeRoot(volumeId, context)
            if (volumeRoot == null) {
                Log.w(TAG, "resolveTreeUriToHostPath: unknown volume '$volumeId' for $treeUriString")
                null
            } else {
                val candidate = if (relPath.isEmpty()) File(volumeRoot) else File(volumeRoot, relPath)
                // Diagnose the common "mounted folder reads empty" failure: the
                // resolved host dir exists but the app process (raw POSIX, via
                // proot) can't readdir it because the ROM only grants the
                // /mnt/runtime/default FUSE view. list() returning null == EACCES.
                if (candidate.exists() && candidate.canRead()) {
                    val childCount = candidate.list()?.size
                    if (childCount == null) {
                        Log.w(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} canRead=true " +
                                "but list() returned null (readdir EACCES / scoped-storage filtered) — " +
                                "mount will appear empty. ${storageAccessDiag(context)}",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} readable childCount=$childCount",
                        )
                    }
                    candidate.absolutePath
                } else {
                    Log.w(
                        TAG,
                        "resolveTreeUriToHostPath: host=${candidate.absolutePath} " +
                            "exists=${candidate.exists()} canRead=${candidate.canRead()} — bind skipped",
                    )
                    null
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveTreeUriToHostPath failed for $treeUriString: ${t.message}")
        null
    }

    /**
     * One-line summary of the app's effective external-storage access, for the
     * mount log trail. Explains *why* a resolved host dir might readdir empty:
     * on Android 11+ it's MANAGE_EXTERNAL_STORAGE; on Android 10 base ROMs
     * (HarmonyOS/EMUI) it's whether legacy READ_EXTERNAL_STORAGE was granted.
     */
    private fun storageAccessDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.R ->
                "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
            else -> {
                val granted = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                // Issue #118: Environment.isExternalStorageLegacy() is API 29.
                // This branch covers everything below R (30), so on our minSdk
                // 26..28 floor the unguarded call was a hard NoSuchMethodError —
                // and because it sits on the boot path it killed the process in
                // Application.onCreate, ~250ms restart loop, before any user data
                // was touched (hence "clear app data" not helping). Below 29 the
                // scoped-storage opt-out doesn't exist as a concept: storage is
                // unconditionally legacy, so report that statically.
                val legacyOptIn = if (sdk >= Build.VERSION_CODES.Q) {
                    Environment.isExternalStorageLegacy().toString()
                } else {
                    "n/a(pre-Q)"
                }
                "sdk=$sdk legacyStorageGranted=$granted legacyExtStorageOptIn=$legacyOptIn"
            }
        }
    }

    private fun resolveVolumeRoot(volumeId: String, context: Context): String? {
        if (volumeId.equals("primary", ignoreCase = true) || volumeId.isEmpty()) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        // getStorageVolumes() is API 24; with minSdk 26 the old `>= N` gate was
        // always true, so it is unwrapped. `volume.directory` is API 30 (R) —
        // that gate stays, it is real.
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
        for (volume in sm.storageVolumes) {
            if (volume.uuid?.equals(volumeId, ignoreCase = true) == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return volume.directory?.absolutePath
                }
            }
        }
        return null
    }

    /**
     * Build a POSIX TZ string from the current system timezone.
     *
     * Matches iOS ISHShellExecutor.m:335-353 byte-for-byte:
     *   UTC+8:00 → "LCL-8"
     *   UTC+5:30 → "LCL-5:30"
     *   UTC-3:00 → "LCL+3"
     *
     * POSIX TZ sign is inverted relative to UTC offset, and we use the fixed
     * name "LCL" to avoid musl's confused parsing of abbreviations like
     * "GMT+8" (which contain an embedded sign).
     */
    /**
     * Zone id when the phone has one (`Asia/Shanghai`). POSIX `LCL-8` makes
     * `date +%Z` print LCL even after `/etc/localtime` is correct.
     */
    fun guestTz(): String {
        val id = TimeZone.getDefault().id
        return if (id.contains('/') && !id.startsWith("GMT")) id else posixTz()
    }

    fun posixTz(): String {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val secs = offsetMs / 1000L
        val hrs = (secs / 3600).toInt()
        val mins = ((abs(secs) % 3600) / 60).toInt()
        // POSIX sign is reversed: UTC+8 → "LCL-8"
        val posixHrs = -hrs
        val sign = if (posixHrs >= 0) "+" else "-"
        return if (mins != 0) {
            "LCL$sign${abs(posixHrs)}:${"%02d".format(mins)}"
        } else {
            "LCL$sign${abs(posixHrs)}"
        }
    }

    /**
     * Recompute TZ and update customEnvironment. Returns the new TZ string.
     * Callers should also propagate the new value to live shells via
     * [ExecutionCoordinator.broadcastTimezoneChange].
     */
    fun updateTimezone(): String {
        val tz = guestTz()
        customEnvironment["TZ"] = tz
        Log.i(TAG, "Updated TZ=$tz")
        return tz
    }

    /**
     * Rewrite guest `/etc/localtime` after a timezone change. [updateTimezone]
     * only updates the env var used by new shells; glibc `date` and Python
     * fall back to the symlink, which otherwise stays on the zone from boot.
     */
    suspend fun syncHostTimezoneFiles() {
        if (rootfsManagerRef == null) return
        rootfsManager.applyHostTimezone()
    }

    /**
     * Env-variable keys for HTTP proxy injection. All six are always set as a
     * block — even when no system proxy is configured the values are empty
     * strings, which (a) signals "no proxy" to curl/wget/pip/npm (they treat
     * empty as unset) and (b) lets a proxy-disabled transition overwrite any
     * previously exported value in a live shell without a separate `unset`.
     */
    private val PROXY_KEYS = listOf(
        "http_proxy", "https_proxy",
        "HTTP_PROXY", "HTTPS_PROXY",
        "no_proxy", "NO_PROXY",
    )

    /** Standard bypass list for localhost traffic — matches the `curl` convention. */
    private const val PROXY_NO_PROXY = "localhost,127.0.0.1,::1"

    /**
     * Read the system HTTP proxy configuration and produce the env-var block
     * to inject into every sandboxed process. Returns all six keys even when
     * no proxy is set (values are empty strings) so callers can unconditionally
     * `export` the block and get the right behavior on both transitions:
     *
     *   proxy set → values carry http://host:port
     *   proxy unset → values are empty, tools fall back to direct connection
     */
    fun systemProxyEnv(context: Context): Map<String, String> {
        val proxyUri = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val info = cm?.defaultProxy
            if (info != null && info.host.isNotBlank() && info.port > 0) {
                "http://${info.host}:${info.port}"
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read system proxy: ${t.message}")
            ""
        }

        val out = linkedMapOf<String, String>()
        for (key in PROXY_KEYS) {
            out[key] = when (key) {
                "no_proxy", "NO_PROXY" -> if (proxyUri.isNotEmpty()) PROXY_NO_PROXY else ""
                else -> proxyUri
            }
        }
        return out
    }

    /**
     * Recompute proxy env vars and update customEnvironment. Returns the map
     * that was written so callers can propagate to live shells via
     * [ExecutionCoordinator.broadcastProxyChange].
     */
    fun updateProxy(context: Context): Map<String, String> {
        val env = SandboxHttpProxy.envBlock() ?: systemProxyEnv(context)
        customEnvironment.putAll(env)
        val hasProxy = env["http_proxy"].orEmpty().isNotEmpty()
        Log.i(TAG, "Updated proxy — ${if (hasProxy) "active=${env["http_proxy"]}" else "cleared"}")
        return env
    }

    /**
     * Build the full proot command line for a given shell command.
     *
     * Produces:
     * ```
     * <proot> -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w /root
     *         [-b <host>:<linux> ...for each bind mount]
     *         /bin/sh -c "<command>"
     * ```
     */
    fun buildProotCommand(shellCommand: String): List<String> {
        check(isBooted) { "PRootKernel.boot() must be called before building commands" }

        val cmd = mutableListOf<String>()

        cmd.add(rootfsManager.prootBinary.absolutePath)

        // Run as fake root (uid 0)
        cmd.add("-0")

        // T141: Translate hardlink() syscalls to symlink(). Android's /data
        // (ext4/F2FS as mounted by zygote) refuses cross-directory hardlinks
        // for app uids, so PRoot would otherwise pass link() straight through
        // to the host kernel and the host kernel rejects with EPERM. Alpine's
        // apk installs busybox-applet packages (binutils, gcc deps, etc.) by
        // hardlinking ar/ld/nm/strip → busybox; without this flag every such
        // install fails with "Permission denied". Symlinks are functionally
        // equivalent for the apk consumer.
        cmd.add("--link2symlink")
        // One-shot proot must reap the guest when the host process is killed.
        cmd.add("--kill-on-exit")

        // Set rootfs
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)

        // Bind essential pseudo-filesystems
        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")

        // Working directory
        cmd.add("-w")
        cmd.add("/root")

        // User bind mounts
        for ((linuxPath, hostPath) in bindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
            // Log external-folder binds specifically — these are the ones that
            // read empty when the host view is filtered, and the proot child's
            // own EACCES on readdir is otherwise invisible in the log file.
            if (linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) {
                Log.i(TAG, "proot bind mount: $hostPath -> $linuxPath")
            }
        }

        // Native offload: route registered handler names to the host-side
        // NativeOffloadServer over the abstract unix socket.
        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // Shell command
        cmd.add("/bin/bash")
        cmd.add("-c")
        cmd.add(GuestLimits.wrap(shellCommand))

        Log.d(TAG, "proot cmd: ${cmd.take(cmd.size - 1).joinToString(" ")} <shellCommand ${shellCommand.length} bytes>")
        return cmd
    }

    /**
     * Resolve a `/var/minis/...` Linux path against the same host directory the
     * session shell bind-mounts. Use this when the caller knows the owning
     * session (file tools, chat link resolver, file preview). The global
     * [bindMounts] map is last-writer-wins and must not be used for per-session
     * paths — that split is what made `file_write` and `shell_execute` see two
     * different `/var/minis/workspace` trees.
     *
     * Falls back to [resolveHostPath] for paths this layout does not own
     * (rootfs files, external SAF mounts).
     */
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? {
        val resolved = SessionWorkspace.resolveGuestPath(context.filesDir, sessionId, linuxPath)
            ?: resolveHostPath(linuxPath)
            ?: return null
        return resolved.takeIf {
            SessionWorkspace.acceptsResolved(context.filesDir, sessionId, it)
        }
    }

    /**
     * Resolve a Linux path to a host filesystem File by checking bind mounts.
     * Returns null if no matching mount is found, or if `..` walks out of the
     * matched bind root / rootfs (that is how one session's file tool used to
     * read another's private tree).
     */
    fun resolveHostPath(linuxPath: String): File? {
        // Check bind mounts (longest prefix match)
        val sorted = bindMounts.keys.sortedByDescending { it.length }
        for (mountPoint in sorted) {
            if (linuxPath == mountPoint || linuxPath.startsWith("$mountPoint/")) {
                val hostBase = bindMounts[mountPoint]!!
                val relativePath = linuxPath.removePrefix(mountPoint).removePrefix("/")
                val file = if (relativePath.isEmpty()) File(hostBase) else File(hostBase, relativePath)
                return file.takeIf { SessionWorkspace.staysInside(File(hostBase), it) }
            }
        }

        // Fallback: resolve relative to rootfs
        if (rootfsManagerRef == null) return null
        val stripped = linuxPath.removePrefix("/")
        if (stripped.isEmpty()) return rootfsManager.rootfsDir
        val file = File(rootfsManager.rootfsDir, stripped)
        return file.takeIf { SessionWorkspace.staysInside(rootfsManager.rootfsDir, it) }
    }

    /**
     * Create a stub executable inside the rootfs for every registered
     * native_offload handler. The shell needs to find *something* on PATH
     * to trigger an execve — proot's native_offload extension intercepts
     * that execve before the stub actually runs.
     */
    private fun installHandlerStubs(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin").also { it.mkdirs() }
        val registered = NativeOffloadServer.registeredHandlers
        // The stub is fixed text, so a file matching it byte-for-byte was
        // written by THIS mechanism. Anything else in the dir (a real binary,
        // a guard/`top` wrapper) has different content and is never touched.
        val stubBody = "#!/bin/sh\nexit 0\n"
        var created = 0
        var existed = 0
        var removed = 0
        for (name in registered) {
            val stub = File(binDir, name)
            if (stub.exists()) { existed++; continue }
            stub.writeText(stubBody)
            stub.setExecutable(true, false)
            created++
        }
        // [T-stale-handler-stubs] A command that was registered in an earlier
        // app version and then renamed/retired kept its `exit 0` stub forever:
        // the guest invoked it and got a silent success with EMPTY output —
        // the worst possible dead end for an agent, because there is no error
        // to react to. Enumerate the dir and delete stubs (by exact content
        // signature) whose name is no longer registered.
        binDir.listFiles()?.forEach { f ->
            if (!f.isFile || f.name in registered) return@forEach
            val body = runCatching { f.readText() }.getOrNull() ?: return@forEach
            if (body == stubBody && f.delete()) removed++
        }
        Log.d(TAG, "installHandlerStubs dir=${binDir.absolutePath} created=$created existed=$existed removed=$removed")

        installShellWrappers(binDir)
    }

    /**
     * Install /usr/local/bin/ wrapper scripts that work around Android-specific
     * sandbox limitations. These take precedence over /bin/busybox in PATH.
     *
     * top: Android's hidepid=2 + SELinux blocks reads of /proc/<other_pid>/stat
     * for non-system UIDs, so plain `top` fails with "can't open 'stat': Permission
     * denied". Restrict it transparently to the PIDs we can actually read.
     *
     * Always rewritten so improvements ship with each app update.
     */
    private fun installShellWrappers(binDir: File) {
        // busybox `top` walks all of /proc and aborts on the first unreadable
        // /proc/<pid>/stat (Android sandbox blocks reads of other UIDs' procfs).
        // busybox in this rootfs doesn't accept `-p PIDS` either. Workaround:
        // simulate `top` with `ps` in a refresh loop, scoped to our session
        // (only PIDs whose /proc/<pid>/stat is actually readable by us).
        val topWrapper = File(binDir, "top")
        topWrapper.writeText(
            """#!/bin/sh
            |# Auto-installed by MinisApp PRootKernel.
            |# busybox top walks all of /proc and aborts on the first unreadable
            |# entry under Android's procfs hidepid restrictions, and this build
            |# of busybox doesn't accept `-p`. Emulate `top` with `ps` over the
            |# PIDs we can actually inspect.
            |
            |# Parse a few common top-style flags so existing muscle memory works.
            |delay=2
            |iters=-1
            |batch=0
            |while [ ${'$'}# -gt 0 ]; do
            |    case "${'$'}1" in
            |        -d) delay=${'$'}2; shift 2;;
            |        -n) iters=${'$'}2; shift 2;;
            |        -b) batch=1; shift;;
            |        -h|--help)
            |            echo "Usage: top [-b] [-n COUNT] [-d SECONDS]"
            |            exit 0;;
            |        *) shift;;
            |    esac
            |done
            |
            |saved_stty=""
            |on_exit() {
            |    [ ${'$'}batch -eq 0 ] && printf '\033[?25h'
            |    [ -n "${'$'}saved_stty" ] && stty "${'$'}saved_stty" 2>/dev/null
            |}
            |trap on_exit INT TERM EXIT
            |
            |list_readable_pids() {
            |    for d in /proc/[0-9]*; do
            |        [ -r "${'$'}d/stat" ] && printf '%s\n' "${'$'}{d##*/}"
            |    done
            |}
            |
            |render_once() {
            |    pids=${'$'}(list_readable_pids | tr '\n' ',' | sed 's/,${'$'}//')
            |    if [ -z "${'$'}pids" ]; then
            |        echo "top: no readable processes in /proc (Android sandbox)"
            |        return
            |    fi
            |    uptime_s=${'$'}(awk '{print int(${'$'}1)}' /proc/uptime 2>/dev/null)
            |    [ -z "${'$'}uptime_s" ] && uptime_s=0
            |    days=${'$'}((uptime_s / 86400))
            |    hours=${'$'}(((uptime_s % 86400) / 3600))
            |    mins=${'$'}(((uptime_s % 3600) / 60))
            |    visible=${'$'}(echo "${'$'}pids" | tr ',' '\n' | wc -l)
            |    printf 'top — up %dd %02d:%02d  visible processes: %d (own session only)\n' "${'$'}days" "${'$'}hours" "${'$'}mins" "${'$'}visible"
            |    printf '%s\n' '  PID USER     STAT  RSS  PPID COMMAND'
            |    /bin/busybox ps -o pid,user,stat,rss,ppid,comm 2>/dev/null \
            |        | awk -v pids=",${'$'}pids," 'NR==1 {next} {if (index(pids,","${'$'}1",")) print "  " ${'$'}0}'
            |}
            |
            |if [ ${'$'}batch -eq 1 ]; then
            |    count=0
            |    while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |        render_once
            |        count=${'$'}((count + 1))
            |        [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |        sleep "${'$'}delay"
            |    done
            |    exit 0
            |fi
            |
            |# Interactive mode: clear screen + redraw each interval. Exits on
            |# Ctrl+C OR when the user presses 'q' / 'Q'. We put the TTY into
            |# raw, no-echo mode so single keypresses are read without Enter.
            |if [ -t 0 ]; then
            |    saved_stty=${'$'}(stty -g 2>/dev/null)
            |    stty -echo -icanon min 0 time 0 2>/dev/null
            |fi
            |printf '\033[?25l'
            |count=0
            |while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |    printf '\033[H\033[2J'
            |    render_once
            |    count=${'$'}((count + 1))
            |    [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |    # Sleep in small slices so 'q' is responsive within ~0.1s.
            |    end=${'$'}((${'$'}(date +%s) + delay))
            |    while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
            |        if [ -t 0 ]; then
            |            ch=${'$'}(dd bs=1 count=1 2>/dev/null)
            |            case "${'$'}ch" in q|Q) exit 0;; esac
            |        fi
            |        sleep 0.1
            |    done
            |done
            |""".trimMargin()
        )
        topWrapper.setExecutable(true, false)
        Log.d(TAG, "installShellWrappers: /usr/local/bin/top installed")
    }

    /**
     * T219-4: install /usr/local/bin/ wrappers for the common file-writing
     * commands so a write whose target is under an effectively-read-only mount
     * fails loudly in the shell (mirrors how iOS `MountedFolderCoordinator`
     * throws `ReadOnlyMountError` before touching disk), instead of the write
     * silently no-op'ing on ROMs that shadow blocked writes.
     *
     * The list of read-only linux prefixes is written to a config file the
     * wrappers source at run time, so re-mounting / toggling writability just
     * rewrites the config — no rootfs churn. When there are no read-only mounts
     * the wrappers are removed entirely so they add zero overhead in the common
     * case (every argument would pass the check anyway).
     *
     * Coverage note ([T-mount-guard-bypass]): this catches the argv-target
     * commands agents actually use (touch/cp/mv/tee/mkdir/rm/dd/ln/…), and the
     * candidate write target is CANONICALISED with `realpath -m` before matching,
     * so `..` hops, relative paths, symlinks-out-of-the-jail and `dd of=…` no
     * longer slip past the old literal `"$prefix"`-prefixed glob match. `-T` /
     * `--target-directory` indirection is peeled, and when a real path argument
     * cannot be canonicalised the wrapper FAILS CLOSED (denies).
     *
     * Residual holes this wrapper structurally CANNOT close (argv-only,
     * installed per-command):
     *  - a program outside the guarded argv names writing the tree directly
     *    (an interpreter like `python -c`, or an unwrapped binary);
     *  - shell redirection / heredoc targets (`> /var/minis/mounts/<ro>/f`) —
     *    opened by the calling shell, never passed through this argv. proot
     *    forwards the open(2) to the host FS, which returns EACCES for a
     *    genuinely read-only SAF view, but the wrapper cannot see the path;
     *  - a file operand that begins with `-` (treated as a flag, skipped).
     * These are named here so the next reader does not mistake the guard for a
     * sandbox-wide invariant. The guarded trees are SAF-mounted user data, so the
     * defense is layered with the FileWriteTool/FileEditTool host-side guard.
     */
    private fun installMountWriteGuards(context: Context, readOnlyLinuxPrefixes: List<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            return
        }
        val binDir = File(rootfs, "usr/local/bin").also { it.mkdirs() }
        val guardedCmds = listOf("touch", "tee", "cp", "mv", "mkdir", "rm", "rmdir", "ln", "dd")
        val configFile = File(rootfs, "var/minis/.mount-readonly-prefixes")

        if (readOnlyLinuxPrefixes.isEmpty()) {
            // No read-only mounts — remove the config + wrappers so plain busybox
            // commands run unimpeded.
            runCatching { configFile.delete() }
            for (name in guardedCmds) {
                val w = File(binDir, name)
                if (w.exists() && w.readText().contains(GUARD_MARKER)) w.delete()
            }
            Log.i(TAG, "installMountWriteGuards: no read-only mounts, guards cleared")
            return
        }

        configFile.parentFile?.mkdirs()
        configFile.writeText(readOnlyLinuxPrefixes.joinToString("\n", postfix = "\n"))

        for (name in guardedCmds) {
            val wrapper = File(binDir, name)
            wrapper.writeText(
                """#!/bin/sh
                |# $GUARD_MARKER — reject writes into read-only mounted folders.
                |# Auto-installed by MinisApp PRootKernel (T219-4, T-mount-guard-bypass).
                |cfg=/var/minis/.mount-readonly-prefixes
                |if [ -f "${'$'}cfg" ]; then
                |    if command -v realpath >/dev/null 2>&1; then have_rp=1; else have_rp=0; fi
                |    # canonicalise a write target and match it against every
                |    # read-only prefix; realpath resolves '..'/symlinks and works
                |    # on not-yet-existing paths. Fail closed when a real path
                |    # argument cannot be canonicalised.
                |    check() {
                |        t="${'$'}1"
                |        [ -n "${'$'}t" ] || return 0
                |        if [ "${'$'}have_rp" = 1 ]; then
                |            c="${'$'}(realpath -m -- "${'$'}t" 2>/dev/null)"
                |            if [ -z "${'$'}c" ]; then
                |                echo "$name: ${'$'}t: read-only mounted folder — target could not be resolved (write blocked)" >&2
                |                exit 1
                |            fi
                |        else
                |            c="${'$'}t"
                |        fi
                |        while IFS= read -r prefix; do
                |            [ -z "${'$'}prefix" ] && continue
                |            case "${'$'}c" in
                |                "${'$'}prefix"|"${'$'}prefix"/*)
                |                    echo "$name: ${'$'}t: read-only mounted folder — writes are disabled (toggle in Settings → Mount External Folders)" >&2
                |                    exit 1;;
                |            esac
                |        done < "${'$'}cfg"
                |        return 0
                |    }
                |    expect=0
                |    for arg in "${'$'}@"; do
                |        if [ "${'$'}expect" = 1 ]; then expect=0; check "${'$'}arg"; continue; fi
                |        case "${'$'}arg" in
                |            -T|--target-directory) expect=1;;
                |            --target-directory=*) check "${'$'}{arg#--target-directory=}";;
                |            of=*) check "${'$'}{arg#of=}";;
                |            -*) : ;;
                |            *) check "${'$'}arg";;
                |        esac
                |    done
                |fi
                |exec /bin/busybox $name "${'$'}@"
                |""".trimMargin(),
            )
            wrapper.setExecutable(true, false)
        }
        Log.i(
            TAG,
            "installMountWriteGuards: installed for ${guardedCmds.size} cmds, " +
                "readOnlyPrefixes=${readOnlyLinuxPrefixes.joinToString(",")}",
        )
    }

    /**
     * Get the cache directory for PROOT_TMP_DIR.
     * Must be called after boot().
     */
    internal fun getProotTmpDir(context: Context): File {
        val tmpDir = File(context.cacheDir, "proot-tmp")
        tmpDir.mkdirs()
        return tmpDir
    }
}
