//
//  ISHTerminalView.swift
//  MinisApp
//
//  Terminal view for iSH shell interaction
//

import SwiftUI
import UIKit

struct ISHTerminalView: View {
    var sessionId: String? = nil
    var showCloseButton: Bool = false
    var initCommand: String? = nil

    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel = ISHTerminalViewModel()
    @State private var showFileBrowser = false
    @State private var showRootfsManagement = false
    @State private var showRootfsImport = false
    @State private var ctrlActive = false
    @State private var keyboardActive = true
    /// Whether the software keyboard is currently visible (separate from first
    /// responder state). On Mac Catalyst or iPad with external keyboard, the
    /// software keyboard can be hidden while the input view remains first
    /// responder and continues to receive hardware key events.
    @State private var softwareKeyboardVisible = false
    /// URL captured from an OSC `MinisOpenURL` marker emitted by
    /// /usr/local/bin/minis-open — presented in an in-app WKWebView sheet.
    @State private var linkPreviewURL: URL?
    /// Track whether a sheet is presented so we can resign first responder
    /// and stop fighting with text fields inside the sheet.
    private var isSheetPresented: Bool { showFileBrowser || showRootfsManagement || showRootfsImport }

    var body: some View {
        ZStack {
            TerminalCanvasView(
                emulator: viewModel.emulator,
                onResize: { cols, rows in
                    viewModel.handleResize(cols: cols, rows: rows)
                },
                onPaste: { data in
                    viewModel.sendInput(data)
                },
                onTap: {
                    // Toggle the keyboard. Mirrors TerminalKeyboardAccessory's
                    // Show/Hide button: if visible, hide; if hidden but input
                    // view is still first responder (external keyboard / user
                    // swiped it away), toggle off-then-on to force a fresh
                    // becomeFirstResponder cycle; otherwise just activate.
                    if softwareKeyboardVisible {
                        keyboardActive = false
                    } else if keyboardActive {
                        keyboardActive = false
                        DispatchQueue.main.async { keyboardActive = true }
                    } else {
                        keyboardActive = true
                    }
                },
                onDoubleTap: {
                    viewModel.sendInput(Data([0x09]))
                }
            )

            // Invisible keyboard input capture
            TerminalInputView(
                onInput: { data in viewModel.sendInput(data) },
                applicationCursorKeys: viewModel.emulator.applicationCursorKeys,
                isActive: $keyboardActive,
                ctrlActive: $ctrlActive,
                isTerminalVisible: !isSheetPresented
            )
            .frame(width: 1, height: 1)
            .opacity(0)
        }
        // Terminal fills the full screen — keyboard floats on top.
        // This prevents bounds changes from triggering terminal resize (SIGWINCH).
        .ignoresSafeArea(.keyboard)
        // Accessory bar is pinned above the keyboard via safeAreaInset.
        // It moves with the keyboard but does NOT affect the terminal's frame.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            TerminalKeyboardAccessory(
                onInput: { data in viewModel.sendInput(data) },
                ctrlActive: $ctrlActive,
                onShowFileBrowser: { showFileBrowser = true },
                onShowRootfsManagement: { showRootfsManagement = true },
                keyboardActive: $keyboardActive,
                softwareKeyboardVisible: softwareKeyboardVisible,
                onPaste: {
                    guard let text = UIPasteboard.general.string, !text.isEmpty,
                          let data = text.data(using: .utf8) else { return }
                    viewModel.sendInput(data)
                }
            )
        }
        .background(Color.black)
        .navigationTitle("Minis Shell")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showCloseButton {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    viewModel.clearScreen()
                } label: {
                    Image(systemName: "paintbrush")
                }
            }
            // Rootfs / import control: view & switch installed rootfs profiles
            // or import a new tar.gz mini-rootfs.
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showRootfsImport = true
                } label: {
                    Image(systemName: "shippingbox")
                }
                .accessibilityLabel("Rootfs")
            }
        }
        .onAppear {
            viewModel.startShell(sessionId: sessionId, initCommand: initCommand)
            // Claim the broker so AIChatView (which sits beneath our
            // fullScreenCover) stops presenting web URLs on top — otherwise
            // its .sheet(item: $safariURL) tries to present while the
            // fullScreenCover is up, SwiftUI dismisses the terminal to make
            // room for the sheet, and the user sees "terminal closes,
            // browser opens, loading forever". Cleared in .onDisappear.
            MinisOpenURLBroker.shared.terminalVisible = true
        }
        .onDisappear {
            MinisOpenURLBroker.shared.terminalVisible = false
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            let end = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect) ?? .zero
            TerminalRedrawLog.log("keyboardWillShow endFrame=\(end) active=\(keyboardActive)")
            softwareKeyboardVisible = true
            if !isSheetPresented {
                keyboardActive = true
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            TerminalRedrawLog.log("keyboardWillHide active=\(keyboardActive)")
            softwareKeyboardVisible = false
            // Do NOT set keyboardActive = false here. On Mac Catalyst and
            // iPad with external keyboard, the software keyboard is hidden
            // but the TerminalKeyInputView must remain first responder to
            // receive hardware key events. Resigning first responder here
            // would break all keyboard input for interactive programs like
            // `gh auth login`, `read`, vim, etc.
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            if !isSheetPresented {
                keyboardActive = true
            }
        }
        .sheet(isPresented: $showFileBrowser) {
            NavigationStack {
                FileBrowserView()
            }
        }
        .sheet(isPresented: $showRootfsManagement) {
            NavigationStack {
                RootfsManagementView()
            }
        }
        .sheet(isPresented: $showRootfsImport) {
            NavigationStack {
                RootfsImportView()
            }
        }
        // In-app WKWebView preview for URLs emitted by `minis-open` via
        // the OSC 1337 MinisOpenURL marker. `TerminalEmulator` parses the
        // marker and forwards the URL through `MinisOpenURLBroker`.
        // `.dropFirst()` skips the broker's current value on first attach
        // so a stale URL from an earlier session isn't re-presented.
        .onReceive(MinisOpenURLBroker.shared.$pendingURL.dropFirst().compactMap { $0 }) { url in
            // Only web schemes are routed here — minis:// resource
            // previews need AIChatView's `handleMinisURLTap` and aren't
            // reachable from the standalone terminal. Consume either way
            // so the broker doesn't leak a stale pendingURL back to chat
            // on next attach.
            if MinisOpenURLBroker.isWebScheme(url.scheme),
               linkPreviewURL?.absoluteString != url.absoluteString {
                linkPreviewURL = url
            }
            MinisOpenURLBroker.shared.consume()
        }
        .sheet(item: $linkPreviewURL) { url in
            // Reuse the exact same preview the AIChat markdown-link tap
            // uses — `MinisLinkPreviewView` with its toolbar (reload /
            // stop / Safari / share / expand to fullscreen). The underlying
            // `WebViewHolder` reads the user's Browser Settings UA and
            // shares the global process pool, so cookies / HSTS state /
            // user agent match the rest of the app. `browserPool: nil`
            // is fine — the preview view doesn't actually use it.
            MinisLinkPreviewView(url: url, browserPool: nil)
        }
    }
}

// MARK: - View Model

class ISHTerminalViewModel: ObservableObject {
    let emulator = TerminalEmulator()
    private let logger = AppLogger(category: "ISHTerminalViewModel")

    private var isShellStarted = false

    /// Monotonic token used to detect whether the currently installed global
    /// outputCallback still belongs to this VM instance. See `installedCallbackGeneration`.
    private static var generationCounter: Int = 0
    private static let generationLock = NSLock()
    private static var currentOwnerGeneration: Int = 0
    /// The generation this VM installed when it last called setupOutputCallback.
    /// Used by deinit to avoid clobbering a newer VM's callback during async teardown.
    private var myGeneration: Int = 0

    init() {
        // NOTE: Do NOT install outputCallback here. On iOS 16, SwiftUI may init
        // a provisional @StateObject that it later discards (e.g. during a
        // fullScreenCover body re-evaluation). If we captured self in the C
        // callback here, the captured [weak self] goes nil once SwiftUI drops
        // the provisional VM, and all TTY output is silently discarded — the
        // terminal stays black forever. Install from startShell() (onAppear)
        // instead, by which point @StateObject has committed to storage.
    }

    deinit {
        // Only clear the global callback if WE are the current owner. A newer
        // VM may have taken ownership already — in that case clearing it would
        // strand the new VM with no callback and cause the "second open hangs"
        // bug. Swift ARC runs deinit asynchronously, so it is common for an
        // old VM's deinit to land AFTER a new VM has already installed its
        // own callback in startShell().
        Self.generationLock.lock()
        let owner = Self.currentOwnerGeneration
        Self.generationLock.unlock()
        if owner == myGeneration && myGeneration != 0 {
            ISHKernel.shared.outputCallback = nil
        }
    }

    private func setupOutputCallback() {
        // Capture the emulator strongly — NOT self. The closure stays valid
        // independent of VM lifetime; deinit clears it only if we still own
        // the global slot.
        let emulator = self.emulator
        Self.generationLock.lock()
        Self.generationCounter += 1
        let gen = Self.generationCounter
        Self.currentOwnerGeneration = gen
        Self.generationLock.unlock()
        myGeneration = gen

        // Coalesce TTY output bursts. ISHKernel fires outputCallback thousands
        // of times per millisecond with tiny (4–24 byte) chunks during commands
        // like `ls`. Dispatching each chunk to the main queue floods it and
        // starves touch/keyboard events. Instead: append to a pending buffer
        // under a lock, and schedule a single main.async only on the
        // empty→non-empty transition. The main block drains whatever has
        // accumulated by the time it runs — typically the entire burst.
        let pendingLock = NSLock()
        var pendingData = Data()
        ISHKernel.shared.outputCallback = { (data: Data) in
            pendingLock.lock()
            let wasEmpty = pendingData.isEmpty
            pendingData.append(data)
            pendingLock.unlock()
            guard wasEmpty else { return }
            DispatchQueue.main.async {
                pendingLock.lock()
                let drained = pendingData
                pendingData.removeAll(keepingCapacity: true)
                pendingLock.unlock()
                guard !drained.isEmpty else { return }
                emulator.feed(drained)
            }
        }
        // Wire up the terminal → TTY response path so the emulator can reply
        // to queries like DSR (ESC[6n → ESC[row;colR). Without this, programs
        // that query cursor position (e.g. Go's survey library used by gh) will
        // block forever waiting for the response.
        emulator.onResponse = { [weak self] data in
            self?.sendInput(data)
        }
    }

    func startShell(sessionId: String? = nil, initCommand: String? = nil) {
        // Always (re)install the output callback first. This is safe to call
        // multiple times — each call overwrites the previous global closure.
        // Doing it here instead of in init() avoids the iOS 16 @StateObject
        // churn problem where a provisional VM installs a callback that
        // immediately becomes orphaned when SwiftUI discards the VM.
        setupOutputCallback()

        guard !isShellStarted else { return }
        isShellStarted = true

        let totalStart = CFAbsoluteTimeGetCurrent()
        var stepStart = totalStart

        // [T-rootfs-reset-terminal-crash] If the rootfs was reset while the
        // kernel was already booted this session, the kernel's fakefs is mounted
        // against a deleted data/ + meta.db tree. It cannot be re-mounted or
        // un-booted in-process (become_first_process() is irreversible), so
        // reusing it here — the isBooted branch below skips install+boot — drives
        // executeCommand against freed/deleted state and crashes. Refuse to start
        // and tell the user to relaunch, which triggers a fresh install+boot.
        if ISHKernel.shared.isBooted && RootfsManager.shared.didResetWhileBooted {
            isShellStarted = false
            logger.warning("[StartShell] rootfs was reset this session; refusing to reuse stale kernel mount")
            let msg = "\r\n" + String(localized: "The Linux environment was reset. Please restart the app to reinstall it before using the terminal.") + "\r\n"
            if let data = msg.data(using: .utf8) {
                emulator.feed(data)
            }
            return
        }

        // Boot kernel if needed
        if !ISHKernel.shared.isBooted {
            do {
                stepStart = CFAbsoluteTimeGetCurrent()
                try RootfsManager.shared.installIfNeeded()
                logger.info("[StartShell] installIfNeeded: \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - stepStart) * 1000))ms")
            } catch {
                let msg = "Failed to install rootfs: \(error.localizedDescription)\r\n"
                if let data = msg.data(using: .utf8) {
                    emulator.feed(data)
                }
                return
            }

            stepStart = CFAbsoluteTimeGetCurrent()
            let rootPath = RootfsManager.shared.rootfsPath.path
            let err = ISHKernel.shared.boot(withRootPath: rootPath)
            logger.info("[StartShell] boot: \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - stepStart) * 1000))ms")
            if err < 0 {
                let msg = "Failed to boot kernel: \(err)\r\n"
                if let data = msg.data(using: .utf8) {
                    emulator.feed(data)
                }
                return
            }
        } else {
            logger.info("[StartShell] kernel already booted, skipping install+boot")
        }

        stepStart = CFAbsoluteTimeGetCurrent()
        RootfsManager.shared.applyDefaultMountOverlay()
        logger.info("[StartShell] applyDefaultMountOverlay: \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - stepStart) * 1000))ms")

        Task { @MainActor in MirrorSpeedTestViewModel.shared.autoDetectOnceIfNeeded() }

        // Mount /var/minis/* for this session BEFORE starting the shell.
        // We must do this synchronously before executeCommand so the shell sees the mounts.
        // Use Task.detached to avoid inheriting main actor isolation (which would deadlock
        // the semaphore since .onAppear runs on the main actor).
        if let sid = sessionId {
            stepStart = CFAbsoluteTimeGetCurrent()
            print("[ISHTerminal] Mounting /var/minis for session \(sid) before shell start")
            let semaphore = DispatchSemaphore(value: 0)
            Task.detached {
                await ISHExecutionCoordinator.shared.mountForSession(sid)
                semaphore.signal()
            }
            semaphore.wait()
            logger.info("[StartShell] mountForSession: \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - stepStart) * 1000))ms")
        }

        // Inject user-defined environment variables
        stepStart = CFAbsoluteTimeGetCurrent()
        let customEnv = EnvVarStore.shared.allAsDict()
        if !customEnv.isEmpty {
            ISHKernel.shared.customEnvironment = customEnv
        }
        logger.info("[StartShell] envVars: \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - stepStart) * 1000))ms (\(customEnv.count) vars)")

        // Start shell
        stepStart = CFAbsoluteTimeGetCurrent()
        // Start as login shell (-l) so /etc/profile and /etc/profile.d/*.sh
        // are sourced, enabling command history and line editing.
        let err = ISHKernel.shared.executeCommand(["/bin/sh", "-l"])
        logger.info("[StartShell] executeCommand(/bin/sh -l): \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - stepStart) * 1000))ms")
        if err < 0 {
            let msg = "Failed to start shell: \(err)\r\n"
            if let data = msg.data(using: .utf8) {
                emulator.feed(data)
            }
            return
        }

        logger.info("[StartShell] TOTAL: \(String(format: "%.1f", (CFAbsoluteTimeGetCurrent() - totalStart) * 1000))ms (sessionId=\(sessionId ?? "nil"))")

        // If opened from a session, cd to the session's shared directory
        if sessionId != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                if let data = "cd /var/minis && clear\n".data(using: .utf8) {
                    self?.sendInput(data)
                }
                // Pre-fill init command (without newline) so the user can review before pressing Enter
                if let cmd = initCommand, !cmd.isEmpty {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        if let data = cmd.data(using: .utf8) {
                            self?.sendInput(data)
                        }
                    }
                }
            }
        } else if let cmd = initCommand, !cmd.isEmpty {
            // Pre-fill init command (without newline) after shell starts
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                if let data = cmd.data(using: .utf8) {
                    self?.sendInput(data)
                }
            }
        }
    }

    /// Serial queue for sending input to the kernel TTY.
    /// tty_input() acquires a pthread mutex that can block when the kernel is busy,
    /// so we must never call it on the main thread.
    private let inputQueue = DispatchQueue(label: "com.openminis.terminal.input")

    func sendInput(_ data: Data) {
        let tEnq = TerminalRedrawLog.nowMs()
        let size = data.count
        inputQueue.async {
            let tDeq = TerminalRedrawLog.nowMs()
            ISHKernel.shared.sendInput(data)
            let tWrite = TerminalRedrawLog.nowMs()
            TerminalRedrawLog.log(String(format: "sendInput bytes=%d enq->deq=%.1fms write=%.1fms",
                size, tDeq - tEnq, tWrite - tDeq))
        }
    }

    func clearScreen() {
        // Send "clear" command to the shell so it redraws the prompt
        if let data = "clear\n".data(using: .utf8) {
            sendInput(data)
        }
        // Clear scrollback buffer for a completely fresh view
        emulator.activeBuffer.clearScrollback()
    }

    func handleResize(cols: Int, rows: Int) {
        emulator.resize(cols: cols, rows: rows)
        ISHKernel.shared.setTerminalSize(Int32(cols), rows: Int32(rows))
    }
}

// MARK: - Quick Command Button

struct QuickCommandButton: View {
    let label: String
    let icon: String
    var isActive: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.caption)
                Text(label)
                    .font(.caption)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(isActive ? Color.blue : Color(white: 0.25))
            .foregroundColor(isActive ? .white : .green)
            .cornerRadius(6)
        }
    }
}

#Preview {
    NavigationStack {
        ISHTerminalView()
    }
}
