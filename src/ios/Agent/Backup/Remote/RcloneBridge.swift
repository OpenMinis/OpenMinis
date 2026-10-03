import Foundation

private let logger = AppLogger(category: "Rclone")

/// Swift access to the bundled rclone library.
///
/// Everything rclone can do goes through ONE entry point — a JSON-RPC call:
///
///     RcloneBridge.rpc("operations/list", ["fs": "remote:", "remote": "dir"])
///
/// That is rclone's own librclone API (`librclone/librclone`), not an interface
/// invented here. Upstream marks it "experimental and may change", and says
/// "iOS has not been tested (but should probably work)" — hence the pinned
/// rclone version in deps/rclone-mobile/go.mod and the smoke test below.
///
/// Backends linked in are decided by deps/rclone-mobile/backends/backends.go
/// (10 of rclone's 70), which is also what keeps the binary at ~15.7 MB gz
/// instead of ~25.6 MB.
enum RcloneBridge {

    /// rclone's RPC returns an HTTP-style status; anything but 200 is a failure.
    struct RPCError: LocalizedError {
        let status: Int
        let payload: String
        var errorDescription: String? {
            // rclone puts a human-readable reason in `error` when it can.
            if let d = payload.data(using: .utf8),
               let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
               let msg = o["error"] as? String {
                return msg
            }
            return "rclone RPC failed (status \(status))"
        }
    }

    private static var initialised = false

    /// Idempotent. Must run before any RPC; safe to call from anywhere.
    static func initializeIfNeeded() {
        guard !initialised else { return }
        MinisRcloneInitialize()
        initialised = true
        applyGlobalOptions()
        logger.info("[Rclone] initialised")
    }

    /// rclone's defaults are tuned for a desktop batch job, not a phone.
    ///
    /// `MinisRcloneRPC` is a BLOCKING cgo call — nothing on the Swift side can
    /// interrupt it, so whatever rclone decides to wait for, the calling
    /// thread waits too. A server that accepts a connection and then stops
    /// responding must therefore be bounded by something, or a backup "appears
    /// frozen" rather than "failed, try again".
    ///
    /// `ConnectTimeout` is what bounds the dead-server case, and it stays
    /// short. `Timeout` does NOT mean what its old value assumed — see the
    /// note on `ioTimeout`.
    static func applyGlobalOptions() {
        let opts: [String: Any] = [
            // Nanoseconds — rclone's Duration options are int64 ns.
            "Timeout": Int(Self.ioTimeout * 1_000_000_000),
            "ConnectTimeout": Int(Self.connectTimeout * 1_000_000_000),
            // Retries MULTIPLY the wait: rclone applies Timeout per attempt.
            // The transfer layer above this reports a failed destination and
            // keeps the other ones, and the user can re-run — both better
            // outcomes than a long silence.
            "LowLevelRetries": 1,
            "Retries": 1,
        ]
        do {
            _ = try rpcUnchecked("options/set", ["main": opts])
            logger.info("[Rclone] timeouts set: connect=\(Int(Self.connectTimeout))s io=\(Int(Self.ioTimeout))s")
        } catch {
            logger.warning("[Rclone] couldn't set timeouts: \(error.localizedDescription)")
        }
    }

    /// How long to wait for a server to accept a connection.
    ///
    /// This — not `ioTimeout` — is what bounds a server that is down or
    /// black-holing packets, which is why it can stay short.
    static let connectTimeout: TimeInterval = 20

    /// [T-backup-webdav-response-deadline] rclone's `Timeout`, which becomes Go's
    /// `http.Transport.ResponseHeaderTimeout` (fs/fshttp/http.go:237).
    ///
    /// It is NOT a stall timeout. The timer starts once the request body has
    /// been fully written and is a FLAT deadline that never resets while the
    /// server is working (x/net/http2/transport.go ~1187, and net/http's own
    /// HTTP/1.1 path behaves the same way). For an unchunked WebDAV `PUT` that
    /// means one thing:
    ///
    ///     the server must produce response headers within this budget AFTER
    ///     receiving every byte.
    ///
    /// The old value was 45s, documented as "how long a transfer may stall".
    /// That reading is wrong, and it made a whole class of perfectly healthy
    /// servers fail deterministically: an AList/OpenList gateway that relays to
    /// a cloud drive must receive the entire file, re-upload it to the cloud
    /// API, and only then answer. For a 31MB backup that is routinely over a
    /// minute of silence with nothing wrong.
    ///
    /// Field report 2026-09-22 (openlist.fsin.fun, `/dav/189/` = a 189 cloud
    /// mount): every backup failed with
    ///     unchunked simple update failed: Put "…": http2: timeout awaiting
    ///     response headers
    /// while the UI showed 3.1 MB/s — the bytes crossed in ~10s; only the
    /// server's post-upload processing exceeded 45s.
    ///
    /// Reproduced and fixed under measurement (rclone v1.75.0 + these exact
    /// options, against a WebDAV server stalled 60s after the body landed):
    ///
    ///     Timeout=45s  → fails at 46.1s, byte-identical error
    ///     Timeout=300s → succeeds at 60.1s
    ///     Timeout=45s + DisableHTTP2 → still fails at 46.1s (only the message
    ///                   changes to "net/http:"), so HTTP/2 is NOT the cause
    ///                   and disabling it is not a fix.
    ///
    /// 300s is rclone's own default. A per-size deadline was considered and
    /// rejected: the delay tracks the BACKEND (a local-disk mount answers a
    /// 100MB PUT in ~5ms and does not grow with size; a cloud relay takes
    /// minutes), not the byte count, so there is no honest formula. The escape
    /// hatch for a genuinely wedged transfer is Cancel, which
    /// `RcloneTransfer.upload` now implements via `job/stop`.
    static let ioTimeout: TimeInterval = 300

    /// Whether to accept self-signed / untrusted TLS certificates.
    ///
    /// Applied GLOBALLY rather than per-remote on purpose: of the backends
    /// this app offers, only FTP exposes a `no_check_certificate` option —
    /// WebDAV and S3 have none, and their verification is governed by
    /// rclone's global `InsecureSkipVerify`. A per-remote toggle would
    /// therefore be a lie for exactly the backends most likely to need it
    /// (a NAS serving HTTPS WebDAV with its own certificate).
    static func setInsecureTLS(_ allow: Bool) {
        do {
            _ = try rpcUnchecked("options/set", ["main": ["InsecureSkipVerify": allow]])
            logger.info("[Rclone] TLS certificate verification \(allow ? "DISABLED" : "enabled")")
        } catch {
            logger.warning("[Rclone] couldn't set TLS option: \(error.localizedDescription)")
        }
    }

    /// `rpc` without the initialise guard — for calls made DURING
    /// initialisation, which would otherwise recurse.
    @discardableResult
    private static func rpcUnchecked(_ method: String,
                                     _ params: [String: Any] = [:]) throws -> [String: Any] {
        let input = String(data: try JSONSerialization.data(withJSONObject: params),
                           encoding: .utf8) ?? "{}"
        let cMethod = strdup(method)
        let cInput = strdup(input)
        defer { free(cMethod); free(cInput) }
        var status: Int32 = 0
        guard let raw = MinisRcloneRPC(cMethod, cInput, &status) else {
            throw RPCError(status: -1, payload: "")
        }
        defer { MinisRcloneFreeString(raw) }
        let out = String(cString: raw)
        guard status == 200 else { throw RPCError(status: Int(status), payload: out) }
        guard let d = out.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
        else { return [:] }
        return obj
    }

    /// One RPC call. `params` is encoded to JSON; the reply is decoded from it.
    @discardableResult
    static func rpc(_ method: String, _ params: [String: Any] = [:]) throws -> [String: Any] {
        initializeIfNeeded()

        let input = String(
            data: try JSONSerialization.data(withJSONObject: params),
            encoding: .utf8) ?? "{}"

        // cgo declares the parameters as `char *` (mutable), so Swift will not
        // accept a String or a `const char *` bridge. strdup gives us a
        // mutable copy we own; both are freed before returning.
        let cMethod = strdup(method)
        let cInput = strdup(input)
        defer { free(cMethod); free(cInput) }

        var status: Int32 = 0
        guard let raw = MinisRcloneRPC(cMethod, cInput, &status) else {
            throw RPCError(status: -1, payload: "")
        }
        // The C string is malloc'd by Go and owned by us — free it on every
        // path, including the throwing ones.
        defer { MinisRcloneFreeString(raw) }
        let out = String(cString: raw)

        guard status == 200 else { throw RPCError(status: Int(status), payload: out) }
        guard let d = out.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
        else { return [:] }
        return obj
    }

    /// Stage 0 smoke test: prove the Go runtime starts and answers on-device.
    ///
    /// `core/version` needs no config, no network and no credentials, so a
    /// successful reply isolates exactly one thing — that the linked-in Go
    /// runtime is alive inside this app, next to iSH.
    static func smokeTest() -> String {
        do {
            let v = try rpc("core/version")
            let version = v["version"] as? String ?? "?"
            let goVersion = v["goVersion"] as? String ?? "?"
            let arch = v["arch"] as? String ?? "?"
            logger.info("[Rclone] smoke OK version=\(version) go=\(goVersion) arch=\(arch)")
            return "rclone \(version) (\(goVersion), \(arch))"
        } catch {
            logger.error("[Rclone] smoke FAILED: \(error.localizedDescription)")
            return "FAILED: \(error.localizedDescription)"
        }
    }

    /// Backends actually compiled in — confirms the trim did what it claims.
    static func supportedBackends() -> [String] {
        guard let out = try? rpc("config/providers"),
              let providers = out["providers"] as? [[String: Any]] else { return [] }
        return providers.compactMap { $0["Name"] as? String }.sorted()
    }
}
