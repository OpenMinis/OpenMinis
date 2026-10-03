import Foundation

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Command Execution (ISHShellExecutor)

extension AIChatViewModel {

    // MARK: - Command Execution (ISHShellExecutor)

    /// Result of command execution containing output text and exit code.
    struct CommandResult {
        let output: String
        let exitCode: Int
    }

    /// Execute a command via ISHExecutionCoordinator (serialized, mount-safe).
    /// Returns clean stdout+stderr output without any TTY artifacts, plus the exit code.
    func executeCommand(_ command: String, timeout: TimeInterval? = nil, lineCallback: @escaping (String) -> Void) async throws -> CommandResult {
        let effectiveTimeout = timeout ?? defaultCommandTimeout
        logger.info("Executing command via coordinator (timeout: \(Int(effectiveTimeout))s): \(command)")

        // [T-p2-shared-workspace] A helper's shell runs in its parent's bucket.
        guard let sid = fsSessionId else {
            return CommandResult(output: "Error: no session", exitCode: -1)
        }

        // [T-bash-on-demand] Detect busybox-ash-incompatible bash syntax and, if
        // found, transparently install + switch to bash. Install time is NOT
        // charged against `effectiveTimeout` (it has its own budget inside
        // OnDemandBash). Only the shell_execute path reaches here; the
        // interactive terminal uses a different path and is untouched.
        let bashism = BashismDetector.detect(command)
        var bashReminder: String? = nil
        if bashism.needsBash {
            let outcome = await OnDemandBash.shared.ensureBash(executor: ishExecutor(sessionId: sid))
            switch outcome {
            case .available:
                if bashism.mustSwitchInterpreter {
                    // §3.2 M3: run the script via bash by self-writing it in the
                    // guest (base64, single line, no host→fakefs write, self-cleaning).
                    return try await runViaBash(command, sessionId: sid, timeout: effectiveTimeout,
                                                lineCallback: lineCallback)
                }
                // T1 only (script already invokes bash itself) — run as-is under sh.
            case .unavailable(let reason):
                // Fall back to sh; attach a reminder if the command then fails,
                // or immediately for silent-error (S) hits whose failure mode is
                // "looks fine, wrong result" (design §4.2 default-on exception).
                bashReminder = BashismReminder.build(hits: bashism.hits, installFailure: reason)
            }
        }
        return try await runWithReminder(command, sessionId: sid, timeout: effectiveTimeout,
                                         reminder: bashReminder, silentClass: bashism.hasSilent,
                                         lineCallback: lineCallback)
    }

    /// Executor adapter handing OnDemandBash a way to run guest commands.
    private func ishExecutor(sessionId sid: String) -> OnDemandBash.Executor {
        OnDemandBash.Executor(run: { command, timeout in
            let r = try? await ISHExecutionCoordinator.shared.execute(
                sessionId: sid, command: command, timeout: timeout,
                lineCallback: { _ in }, pidCallback: { _ in })
            return r?.exitCode ?? -1
        })
    }

    /// Sentinel exit code the bash wrapper returns when bash is missing at run
    /// time (distinguishes "bash gone" from a script that legitimately exits
    /// 127). 119 is otherwise unused by our commands.
    private static let bashMissingSentinel = 119

    /// Run `script` under bash by self-writing it in the guest (M3): base64 →
    /// decode to a per-pid temp file → `bash file` → capture rc → delete. No
    /// host→fakefs write, no heredoc, self-cleaning on the same command line.
    /// The wrapper guards on `command -v bash` first so a bash that vanished
    /// between our cache check and now (user `apk del bash`) is detected
    /// precisely (M5) and self-healed inline rather than failing the command.
    private func runViaBash(_ script: String, sessionId sid: String,
                            timeout: TimeInterval,
                            lineCallback: @escaping (String) -> Void,
                            allowReinstall: Bool = true) async throws -> CommandResult {
        // [T-heredoc-trailing-newline] Same heredoc-terminator rule applies to
        // the decoded script bash runs: a heredoc that ends the file with no
        // trailing newline fails with "unexpected end of file". Guarantee one.
        let normalized = script.hasSuffix("\n") ? script : script + "\n"
        let b64 = Data(normalized.utf8).base64EncodedString()
        let f = "/tmp/.minis-exec-$$.sh"
        let wrapped = "command -v bash >/dev/null 2>&1 || exit \(Self.bashMissingSentinel); "
            + "printf %s '\(b64)' | base64 -d > \(f) && bash \(f); rc=$?; rm -f \(f); exit $rc"
        let result = try await runRaw(wrapped, sessionId: sid, timeout: timeout, lineCallback: lineCallback)

        // M5 self-heal: bash disappeared after we cached it available. Re-probe
        // and, once only, try to reinstall + rerun under bash inline so THIS
        // command still succeeds instead of the next one.
        // The coordinator can surface either the raw exit code (119) or the
        // wait(2)-encoded status (119 << 8 = 30464), so accept both.
        if result.exitCode == Self.bashMissingSentinel
            || result.exitCode == (Self.bashMissingSentinel << 8) {
            await OnDemandBash.shared.markDisappeared()
            if allowReinstall {
                let outcome = await OnDemandBash.shared.ensureBash(executor: ishExecutor(sessionId: sid))
                if case .available = outcome {
                    return try await runViaBash(script, sessionId: sid, timeout: timeout,
                                                lineCallback: lineCallback, allowReinstall: false)
                }
            }
            // Reinstall unavailable → degrade to sh so the script at least runs.
            return try await runRaw(script, sessionId: sid, timeout: timeout, lineCallback: lineCallback)
        }
        return result
    }

    /// Run `command` and, when a bash reminder applies, append it to the output
    /// per the §4.2 trigger rules (non-zero exit, OR any silent-class hit).
    private func runWithReminder(_ command: String, sessionId sid: String,
                                 timeout: TimeInterval, reminder: String?, silentClass: Bool,
                                 lineCallback: @escaping (String) -> Void) async throws -> CommandResult {
        let result = try await runRaw(command, sessionId: sid, timeout: timeout, lineCallback: lineCallback)
        guard let reminder else { return result }
        let shouldAppend = result.exitCode != 0 || silentClass
        guard shouldAppend else { return result }
        return CommandResult(output: result.output + "\n\n" + reminder, exitCode: result.exitCode)
    }

    /// The original coordinator call + output sanitation/truncation, factored
    /// out so the bash/sh/reminder wrappers share one implementation.
    private func runRaw(_ command: String, sessionId sid: String, timeout: TimeInterval,
                        lineCallback: @escaping (String) -> Void) async throws -> CommandResult {
        let effectiveTimeout = timeout

        let cmdIdx = await ShellCommandRingBuffer.shared.didStart(command: command, sessionId: sid)

        // [T-ios-shellring-counter-leak] Balance didStart on EVERY exit path.
        // The old code called didExit only on the normal-return path below, so a
        // throw (CancellationError / kernelNotBooted) or a cancelled enclosing
        // task leaked `_runningCount` permanently — leaving the entry rendered as
        // "RUNNING" forever in crash reports and making `hasRunningCommand` a
        // one-way latch that pinned keep-alive on for the rest of the process.
        //
        // Deliberately do-catch rather than `defer { Task { … } }`: a detached
        // Task spawned from a defer during CANCELLATION is exactly the case that
        // may never be scheduled, which is the main path we need to cover. An
        // `await` on the error path is structured and always runs. `exitCode`
        // stays nil so the entry records an abort, not a fake exit status.
        let result: ISHCommandResult
        do {
            result = try await ISHExecutionCoordinator.shared.execute(
                sessionId: sid,
                command: command,
                timeout: effectiveTimeout,
                // ISHShellExecutor dispatches every line on the main queue
                // already (ISHShellExecutor.m:780/812), so we're guaranteed to
                // run on the main thread here. Calling the MainActor-isolated
                // closure synchronously via `assumeIsolated` avoids spawning a
                // fresh `Task { @MainActor in ... }` per line — that wrapper
                // used to pile up behind high-volume output (one MainActor job
                // per line) and starve other MainActor work such as
                // BrowserUseOffloadBridge's semaphore signal, causing
                // execute_js/navigate to hang inside a Python subprocess.
                lineCallback: { line in
                    MainActor.assumeIsolated { lineCallback(line) }
                },
                pidCallback: { [weak self] pid in
                    // Unlike lineCallback, pidCallback is invoked from the
                    // coordinator actor context (not the main queue), so we
                    // can't MainActor.assumeIsolated here. It only fires 1-2
                    // times per command so a Task hop is fine.
                    Task { @MainActor in
                        self?.runningCommandPid = pid
                        self?.commandStartTime = pid > 0 ? Date() : nil
                    }
                }
            )
        } catch {
            await ShellCommandRingBuffer.shared.didAbort(index: cmdIdx)
            throw error
        }

        await ShellCommandRingBuffer.shared.didExit(index: cmdIdx, exitCode: result.exitCode)

        // Fold carriage-return sequences: simulate terminal line-overwrite behaviour.
        // Tools like yt-dlp emit "\r[download] X%" to overwrite the current line; without a TTY
        // every update is captured verbatim, ballooning the output with hundreds of redundant lines.
        // We replay each \r as a real terminal would: later text on the same line overwrites earlier text,
        // so only the final state of each line is kept — matching what you would actually see on screen.
        var output = Self.sanitizeTerminalOutput(result.output)

        // Apply truncation — keep head + tail so the model sees both the beginning and end
        if output.count > Self.kMaxToolResultChars {
            let totalChars = output.count
            let totalLines = output.components(separatedBy: "\n").count
            let halfLen = Self.kMaxToolResultChars / 2
            let head = String(output.prefix(halfLen))
            let tail = String(output.suffix(halfLen))
            output = head
                + "\n\n...\n\n"
                + tail
                + "\n\n[OUTPUT TRUNCATED] Showing first & last \(halfLen) of \(totalChars) chars (\(totalLines) lines total)."
                + "\nUse file_read tool to read specific sections."
        }

        return CommandResult(output: output, exitCode: result.exitCode)
    }

    /// Sanitize raw shell output so it matches what a user would actually see on a terminal.
    ///
    /// Two passes are applied in order:
    ///
    /// **Pass 1 — carriage-return folding**
    /// A real TTY processes `\r` by moving the cursor to column 0, causing subsequent
    /// characters to overwrite what was already on that line.  Pipe-captured output has
    /// no TTY, so every `\r`-update is stored verbatim.  Tools like yt-dlp, curl, wget,
    /// and rsync can emit hundreds of progress lines that are pure noise for the model.
    /// We replay the same cursor logic: within each newline-delimited chunk, split on `\r`
    /// and keep only the last non-empty segment — the text a terminal would finally display.
    ///
    /// **Pass 2 — ANSI / VT escape sequence stripping**
    /// After CR-folding the remaining text may still contain ANSI SGR colour/style codes
    /// (e.g. `\033[1;32m`, `\033[0m`), cursor-movement sequences (`\033[A`, `\033[2K`,
    /// `\033[G`), and other CSI/OSC/ST control sequences.  None of these are meaningful
    /// as plain text; stripping them produces clean output the model can reason about.
    static func sanitizeTerminalOutput(_ raw: String) -> String {
        // ── Pass 1: carriage-return folding ──────────────────────────────────────
        let crFolded: String
        if !raw.contains("\r") {
            crFolded = raw
        } else {
            var lines: [String] = []
            for chunk in raw.components(separatedBy: "\n") {
                if !chunk.contains("\r") {
                    lines.append(chunk)
                } else {
                    // Split on \r; the last non-empty segment is what the terminal shows.
                    let segments = chunk.components(separatedBy: "\r")
                    let final = segments.last(where: { !$0.isEmpty }) ?? segments.last ?? ""
                    lines.append(final)
                }
            }
            // Collapse consecutive blank lines left by folding.
            var collapsed: [String] = []
            var blankRun = 0
            for line in lines {
                if line.isEmpty {
                    blankRun += 1
                    if blankRun <= 1 { collapsed.append(line) }
                } else {
                    blankRun = 0
                    collapsed.append(line)
                }
            }
            crFolded = collapsed.joined(separator: "\n")
        }

        // ── Pass 2: ANSI / VT escape sequence stripping ──────────────────────────
        // Covers:
        //   CSI sequences  \033[ … <final byte 0x40-0x7E>  (colours, cursor movement, erase)
        //   OSC sequences  \033] … \007  or  \033] … \033\\  (window title, hyperlinks)
        //   Single-char Fe  \033[A-Z@\[\\\]^_]  (e.g. \033M reverse index)
        //   Literal ESC     \033  (bare, not followed by anything matched above)
        guard crFolded.contains("\u{1B}") else { return crFolded }

        // Single NSRegularExpression is cheap to apply; pattern is anchored to ESC.
        let pattern = "\u{1B}(?:\\[[0-9;]*[A-Za-z@`]|\\][^\u{07}\u{1B}]*(?:\u{07}|\u{1B}\\\\)|[@-Z\\\\-_]|\u{1B})"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return crFolded }
        let range = NSRange(crFolded.startIndex..., in: crFolded)
        return regex.stringByReplacingMatches(in: crFolded, range: range, withTemplate: "")
    }

}
