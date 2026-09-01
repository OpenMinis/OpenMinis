import Foundation
import SwiftAnthropic
import os.log

// Category names the STREAM TRANSPORT, not an auth/token-refresh subsystem.
// `OAuthURLProtocol` below is what carries the live Anthropic /v1/messages SSE
// (it swaps x-api-key→Bearer inline and proxies the stream). A connection drop
// here is the stream's own socket dying — historically this logged under
// "OAuthHTTP" and was misread as a background token-refresh failure leaking
// into streamMessage. They are the same request. [diag-0800 Q2]
private let logger = AppLogger(category: "AnthropicStreamTransport")

/// Replaces `x-api-key` with `Authorization: Bearer` via a custom URLProtocol.
///
/// Since `HTTPRequest` properties are module-internal in SwiftAnthropic,
/// we can't mutate headers directly. Instead we route the default
/// `URLSessionHTTPClientAdapter` through a URLSession whose configuration
/// includes `OAuthURLProtocol`, which intercepts every request to swap
/// auth headers before it hits the network.
final class OAuthHTTPClient: HTTPClient {

    private let underlying: URLSessionHTTPClientAdapter
    private let registryId: String

    init(tokenProvider: @escaping @Sendable () async throws -> String) {
        let box = TokenBox()
        box.set(tokenProvider)
        let rid = UUID().uuidString
        self.registryId = rid
        TokenBoxRegistry.shared.register(id: rid, box: box)

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 600  // 10 min — SSE streams can be idle during large tool generation
        config.protocolClasses = [OAuthURLProtocol.self]
        config.httpAdditionalHeaders = (config.httpAdditionalHeaders ?? [:]).merging(
            ["X-Minis-OAuth-UUID": rid]) { _, new in new }
        let session = URLSession(configuration: config)
        self.underlying = URLSessionHTTPClientAdapter(urlSession: session)
    }

    deinit {
        TokenBoxRegistry.shared.deregister(id: registryId)
    }

    func data(for request: HTTPRequest) async throws -> (Data, HTTPResponse) {
        try await underlying.data(for: request)
    }

    func bytes(for request: HTTPRequest) async throws -> (HTTPByteStream, HTTPResponse) {
        try await underlying.bytes(for: request)
    }
}

// MARK: - Last Error Body Capture

/// Thread-safe storage for the last HTTP error body from the Anthropic API.
/// The URLProtocol captures it; `AnthropicProvider.mapError()` reads it.
final class LastAPIErrorBody: @unchecked Sendable {
    static let shared = LastAPIErrorBody()
    private let lock = NSLock()
    private var _body: String?

    func set(_ body: String?) {
        lock.lock()
        _body = body
        lock.unlock()
    }

    /// Atomically read and clear the last error body.
    func take() -> String? {
        lock.lock()
        defer { lock.unlock() }
        let val = _body
        _body = nil
        return val
    }
}

// MARK: - Last Request Body Capture (Debug)

#if DEBUG

/// Read request body from httpBody or httpBodyStream.
/// After reading from a stream, re-assigns httpBody so the request can still be sent.
private func readRequestBodyData(from request: NSMutableURLRequest) -> Data? {
    if let body = request.httpBody, !body.isEmpty {
        return body
    }
    if let stream = request.httpBodyStream {
        stream.open()
        defer { stream.close() }
        var data = Data()
        let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: 65_536)
        defer { buf.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buf, maxLength: 65_536)
            if read <= 0 { break }
            data.append(buf, count: read)
        }
        if !data.isEmpty {
            // Re-assign so downstream consumers still have a body
            request.httpBody = data
            return data
        }
    }
    return nil
}

/// Token usage captured from a completed LLM response.
struct CapturedUsage {
    var inputTokens: Int = 0
    var outputTokens: Int = 0
    var cacheCreationTokens: Int = 0
    var cacheReadTokens: Int = 0
}

/// A single captured API round-trip for debug inspection.
final class CapturedAPIRequest {
    let provider: String   // e.g. "Anthropic", "Gemini"
    let timestamp: Date
    let requestBody: String
    var requestHeaders: [String: String]?
    var requestURL: String?
    var requestMethod: String?
    var usage: CapturedUsage?
    var durationMs: Int?
    var responseStatusCode: Int?
    var responseHeaders: [String: String]?
    var responseBody: String?
    /// Optional usage label (e.g. "compact", "title"). Set when the entry
    /// was registered as a sticky LastAPIRequestBody slot — surfaces the
    /// purpose of out-of-band LLM calls in debug.llmRequests output.
    var tag: String?
    private let maxResponseBodySize = 65_536 // 64KB cap for accumulated SSE

    init(provider: String, timestamp: Date, requestBody: String) {
        self.provider = provider
        self.timestamp = timestamp
        self.requestBody = requestBody
    }

    /// Append a chunk of response data (SSE events arrive incrementally).
    func appendResponseBody(_ chunk: String) {
        if let existing = responseBody {
            guard existing.count < maxResponseBodySize else { return }
            responseBody = existing + chunk
        } else {
            responseBody = chunk
        }
    }

    /// Format as copyable text.
    func formatted(index: Int) -> String {
        let tf = DateFormatter()
        tf.dateFormat = "HH:mm:ss"
        var header = "// --- #\(index) \(provider) \(tf.string(from: timestamp))"
        if let d = durationMs {
            header += " \(d)ms"
        }
        if let s = responseStatusCode {
            header += " HTTP \(s)"
        }
        // [T-ios-llmcapture-entry-crosstalk] Prefer usage parsed from THIS
        // entry's own response body — it's inseparable from the request it
        // belongs to and can't be scribbled over by another request's
        // `updateLatest`. Fall back to the pushed `usage` only when the
        // response wasn't captured (e.g. non-streaming / error paths).
        if let u = usageFromResponseBody() ?? usage {
            header += " | in:\(u.inputTokens) out:\(u.outputTokens)"
            if u.cacheCreationTokens > 0 { header += " cache_create:\(u.cacheCreationTokens)" }
            if u.cacheReadTokens > 0 { header += " cache_read:\(u.cacheReadTokens)" }
        }
        header += " ---"

        var sections = [header]

        // Request
        if let method = requestMethod, let url = requestURL {
            sections.append("// \(method) \(url)")
        }
        if let rh = requestHeaders, !rh.isEmpty {
            let filtered = rh.filter { !["authorization", "x-api-key"].contains($0.key.lowercased()) }
            if !filtered.isEmpty {
                sections.append("// Request Headers: " + filtered.sorted(by: { $0.key < $1.key }).map { "\($0.key): \($0.value)" }.joined(separator: " | "))
            }
        }
        sections.append(requestBody)

        // Response
        if let rb = responseBody, !rb.isEmpty {
            sections.append("// --- Response (\(rb.count) chars) ---")
            sections.append(rb)
        }

        return sections.joined(separator: "\n")
    }

    /// [T-ios-llmcapture-entry-crosstalk] Reconstruct the round-trip's usage
    /// from its own captured Anthropic SSE stream. `message_start` carries the
    /// input / cache tokens; `message_delta` carries the final `output_tokens`.
    /// Returns nil when the body isn't a recognizable Anthropic stream (other
    /// providers / errors), so `formatted()` falls back to the pushed usage.
    private func usageFromResponseBody() -> CapturedUsage? {
        guard let body = responseBody else { return nil }

        func intField(_ key: String, in text: String) -> Int? {
            // Match `"key":<digits>` — tolerant of whitespace after the colon.
            guard let r = text.range(of: "\"\(key)\"\\s*:\\s*(\\d+)", options: .regularExpression) else { return nil }
            let digits = text[r].drop { $0 != ":" }.dropFirst().filter { $0.isNumber }
            return Int(digits)
        }

        // input / cache from the message_start usage block.
        let startUsage = body.range(of: "\"type\"\\s*:\\s*\"message_start\"", options: .regularExpression)
            .map { String(body[$0.lowerBound...].prefix(600)) }
        // output_tokens from the LAST message_delta usage block (final count).
        let lastDelta: String? = {
            guard let last = body.range(of: "message_delta", options: .backwards) else { return nil }
            return String(body[last.lowerBound...].prefix(400))
        }()

        let input = startUsage.flatMap { intField("input_tokens", in: $0) }
        let cacheCreate = startUsage.flatMap { intField("cache_creation_input_tokens", in: $0) }
        let cacheRead = startUsage.flatMap { intField("cache_read_input_tokens", in: $0) }
        let output = lastDelta.flatMap { intField("output_tokens", in: $0) }

        // Require at least an output count to consider this a real Anthropic
        // round-trip; otherwise let the caller fall back.
        guard output != nil || input != nil else { return nil }
        return CapturedUsage(
            inputTokens: input ?? 0,
            outputTokens: output ?? 0,
            cacheCreationTokens: cacheCreate ?? 0,
            cacheReadTokens: cacheRead ?? 0
        )
    }
}

/// Thread-safe ring buffer of the last N API round-trips, for debug copying.
///
/// In addition to the rolling N-entry buffer there's a tagged "sticky" slot
/// keyed by usage label (e.g. "compact", "title") — special-purpose calls
/// that the user almost never wants flushed by subsequent chat traffic.
/// Sticky slots are scoped per-tag and only ever hold the LATEST entry for
/// that tag, so they never grow unbounded. They surface alongside ring
/// entries via `getAll()` (with `tag` set).
final class LastAPIRequestBody: @unchecked Sendable {
    static let shared = LastAPIRequestBody()
    private let lock = NSLock()
    // [T-ios-llmcapture-giant-body] 5 is plenty for debugging the recent
    // exchange; 20 entries of multi-MB bodies pinned hundreds of MB.
    private let maxEntries = 5
    private var _entries: [CapturedAPIRequest] = []
    /// Latest captured request per tag — survives ring eviction. The
    /// caller marks an entry sticky via `set(..., tag:)`; subsequent
    /// `setRequestMeta` / `setHTTPResponse` / `appendResponseBody`
    /// updates flow into BOTH the ring entry and the sticky slot, so a
    /// sticky entry stays consistent with the wire result even as it
    /// gets pushed off the ring.
    private var _sticky: [String: CapturedAPIRequest] = [:]
    /// [T-debug-trace-pause-clear] When true, `set(...)` stops capturing new
    /// requests. `set` is the single entry point for every provider (the
    /// truncation choke point noted below), so gating it here covers all ~10
    /// call sites at once. Returning nil also disables the follow-up writes:
    /// the `on:`-targeted variants take the returned token, so with no entry
    /// there is nothing for the response body / usage to accumulate onto.
    private var _paused = false

    /// Stop/resume capturing new LLM requests. Idempotent.
    func setPaused(_ paused: Bool) {
        lock.lock()
        _paused = paused
        lock.unlock()
    }

    var isPaused: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _paused
    }

    /// Append a new request. Keeps only the most recent `maxEntries`.
    /// Pass `tag` to also pin a copy in the per-tag sticky slot.
    /// [T-ios-llmcapture-giant-body] Bodies are truncated to `maxStoredBodySize`
    /// HERE (single choke point) so none of the ~10 provider call sites can pin
    /// a multi-MB request body in the ring — image-bearing agent histories
    /// reached 27MB per body and dominated the heap.
    private static let maxStoredBodySize = 512 * 1024
    /// Append a new request and RETURN the created entry as a token. Callers
    /// (per-request URLProtocol instances) hold this token and pass it to the
    /// `on:` variants below so a request's response/usage always lands on ITS
    /// OWN entry — not `_entries.last`, which another concurrent request (e.g. a
    /// cache keep-alive warmup) may have shifted in the interim.
    /// [T-ios-llmcapture-entry-crosstalk]
    @discardableResult
    func set(_ body: String?, provider: String = "Anthropic", tag: String? = nil) -> CapturedAPIRequest? {
        guard var body else { return nil }
        if body.utf8.count > Self.maxStoredBodySize {
            body = String(body.prefix(Self.maxStoredBodySize))
                + "\n... [truncated: \(body.utf8.count) bytes total]"
        }
        lock.lock()
        // [T-debug-trace-pause-clear] Checked INSIDE the lock so a concurrent
        // setPaused can't be straddled by an in-flight capture.
        guard !_paused else {
            lock.unlock()
            return nil
        }
        let entry = CapturedAPIRequest(provider: provider, timestamp: Date(), requestBody: body)
        entry.tag = tag
        _entries.append(entry)
        if _entries.count > maxEntries {
            _entries.removeFirst(_entries.count - maxEntries)
        }
        if let tag {
            _sticky[tag] = entry
        }
        lock.unlock()
        return entry
    }

    /// Update the most recent entry with response usage and duration.
    func updateLatest(usage: CapturedUsage, durationMs: Int? = nil) {
        lock.lock()
        // [T-debug-trace-pause-clear] While paused, don't mutate the last
        // PRE-pause entry — that snapshot is what the user paused to capture.
        if !_paused, let last = _entries.last {
            last.usage = usage
            last.durationMs = durationMs
        }
        lock.unlock()
    }

    // [T-ios-llmcapture-entry-crosstalk] `on:`-targeted variants. The
    // `CapturedAPIRequest` token identifies THIS request's entry, so response
    // metadata / body / usage never bleed onto a different (e.g. warmup)
    // request that happened to become `_entries.last`. The lock still guards
    // the shared buffer's mutation; writing the class's fields under it keeps
    // the same memory-visibility guarantees as the `.last`-based writers.
    func updateLatest(usage: CapturedUsage, durationMs: Int? = nil, on entry: CapturedAPIRequest?) {
        guard let entry else { updateLatest(usage: usage, durationMs: durationMs); return }
        lock.lock()
        entry.usage = usage
        entry.durationMs = durationMs
        lock.unlock()
    }

    /// Record HTTP response metadata on the most recent entry (called from URLProtocol).
    func setHTTPResponse(statusCode: Int, headers: [String: String]?) {
        lock.lock()
        // [T-debug-trace-pause-clear] See updateLatest.
        if !_paused, let last = _entries.last {
            last.responseStatusCode = statusCode
            last.responseHeaders = headers
        }
        lock.unlock()
    }

    func setHTTPResponse(statusCode: Int, headers: [String: String]?, on entry: CapturedAPIRequest?) {
        guard let entry else { setHTTPResponse(statusCode: statusCode, headers: headers); return }
        lock.lock()
        entry.responseStatusCode = statusCode
        entry.responseHeaders = headers
        lock.unlock()
    }

    /// Record request metadata on the most recent entry (called from URLProtocol).
    func setRequestMeta(url: String?, method: String?, headers: [String: String]?) {
        lock.lock()
        // [T-debug-trace-pause-clear] See updateLatest.
        if !_paused, let last = _entries.last {
            last.requestURL = url
            last.requestMethod = method
            last.requestHeaders = headers
        }
        lock.unlock()
    }

    func setRequestMeta(url: String?, method: String?, headers: [String: String]?, on entry: CapturedAPIRequest?) {
        guard let entry else { setRequestMeta(url: url, method: method, headers: headers); return }
        lock.lock()
        entry.requestURL = url
        entry.requestMethod = method
        entry.requestHeaders = headers
        lock.unlock()
    }

    /// Append a chunk of response body to the most recent entry (SSE streaming).
    func appendResponseBody(_ chunk: String) {
        lock.lock()
        // [T-debug-trace-pause-clear] Most important of the untargeted writers
        // to gate: a streamed SSE body accumulates chunk by chunk, so leaving
        // this open would keep growing the last pre-pause entry for the whole
        // duration of an in-flight response.
        if !_paused { _entries.last?.appendResponseBody(chunk) }
        lock.unlock()
    }

    func appendResponseBody(_ chunk: String, on entry: CapturedAPIRequest?) {
        guard let entry else { appendResponseBody(chunk); return }
        lock.lock()
        entry.appendResponseBody(chunk)
        lock.unlock()
    }

    /// Get the most recent request body (backward-compatible).
    func get() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return _entries.last?.requestBody
    }

    /// Promote the most recent ring entry to a sticky slot under `tag`.
    /// Use this when the caller knows the just-issued request is a
    /// special-purpose call (compact / title) that should survive
    /// subsequent chat-driven ring eviction. No-op if the ring is empty.
    func tagLatest(_ tag: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let last = _entries.last else { return }
        last.tag = tag
        _sticky[tag] = last
    }

    /// Get all captured requests, newest last. Includes sticky entries
    /// that have aged out of the ring (deduplicated by object identity);
    /// sticky-only entries are inserted in chronological order.
    func getAll() -> [CapturedAPIRequest] {
        lock.lock()
        defer { lock.unlock() }
        // Same identity (===) means the sticky still happens to be in the
        // ring — dedupe so we don't double-list. Sticky-only entries
        // (already evicted from ring) get prepended/inserted by timestamp.
        let ringSet = Set(_entries.map { ObjectIdentifier($0) })
        let stickyOnly = _sticky.values.filter { !ringSet.contains(ObjectIdentifier($0)) }
        if stickyOnly.isEmpty { return _entries }
        let merged = (_entries + stickyOnly).sorted { $0.timestamp < $1.timestamp }
        return merged
    }

    func clear() {
        lock.lock()
        _entries.removeAll()
        _sticky.removeAll()
        lock.unlock()
    }
}
#endif

// MARK: - Thread-safe token provider container

private final class TokenBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _provider: (@Sendable () async throws -> String)?

    func set(_ provider: @escaping @Sendable () async throws -> String) {
        lock.lock()
        _provider = provider
        lock.unlock()
    }

    func get() -> (@Sendable () async throws -> String)? {
        lock.lock()
        defer { lock.unlock() }
        return _provider
    }

    /// Fetch token asynchronously. Returns the token or an error.
    /// This avoids blocking the calling thread with a semaphore.
    func fetchToken(completion: @escaping (Result<String, Error>) -> Void) {
        guard let provider = get() else {
            completion(.failure(NSError(domain: "OAuthHTTPClient", code: -1,
                                       userInfo: [NSLocalizedDescriptionKey: "No token provider"])))
            return
        }
        Task.detached {
            do {
                let token = try await provider()
                completion(.success(token))
            } catch {
                completion(.failure(error))
            }
        }
    }
}

// MARK: - Token Box Registry

/// Thread-safe global registry mapping UUID → TokenBox.
/// Each OAuthHTTPClient registers its own TokenBox so that the URLProtocol
/// can look up the correct token provider per-request.
private final class TokenBoxRegistry: @unchecked Sendable {
    static let shared = TokenBoxRegistry()
    private let lock = NSLock()
    private var boxes: [String: TokenBox] = [:]

    func register(id: String, box: TokenBox) {
        lock.lock()
        boxes[id] = box
        lock.unlock()
    }

    func deregister(id: String) {
        lock.lock()
        boxes.removeValue(forKey: id)
        lock.unlock()
    }

    func box(for id: String) -> TokenBox? {
        lock.lock()
        defer { lock.unlock() }
        return boxes[id]
    }
}

// MARK: - URLProtocol that swaps auth headers

/// Intercepts outgoing requests that contain `x-api-key`, removes it,
/// and injects `Authorization: Bearer <token>` + the OAuth beta header.
/// Streams response data incrementally so both `data(for:)` and
/// `bytes(for:)` on URLSession work correctly.
private final class OAuthURLProtocol: URLProtocol, URLSessionDataDelegate {

    private lazy var innerSession: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }()
    private var innerTask: URLSessionDataTask?
    #if DEBUG
    /// [T-ios-llmcapture-entry-crosstalk] This request instance's capture
    /// entry, so response/usage updates target it and not `_entries.last`.
    private var captureToken: CapturedAPIRequest?
    #endif
    private var isStopped = false
    private let stateLock = NSLock()

    // Do NOT invalidate innerSession in deinit.
    // URLSession holds a strong ref to its delegate (self). Calling
    // invalidateAndCancel during dealloc triggers an async delegate release
    // that accesses freed memory → EXC_BAD_ACCESS.
    // Cleanup is done in stopLoading() instead.

    // MARK: - URLProtocol overrides

    override class func canInit(with request: URLRequest) -> Bool {
        guard request.value(forHTTPHeaderField: "x-api-key") != nil else { return false }
        return URLProtocol.property(forKey: "OAuthHandled", in: request) == nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let mutable = (request as NSURLRequest).mutableCopy() as! NSMutableURLRequest
        URLProtocol.setProperty(true, forKey: "OAuthHandled", in: mutable)

        logger.info("[URLProtocol] Intercepted: \(mutable.httpMethod ?? "?") \(mutable.url?.absoluteString ?? "?")")

        // Look up the correct TokenBox for this request via the UUID header
        let oauthUUID = mutable.value(forHTTPHeaderField: "X-Minis-OAuth-UUID") ?? ""
        mutable.setValue(nil, forHTTPHeaderField: "X-Minis-OAuth-UUID")  // strip before sending

        guard let tokenBox = TokenBoxRegistry.shared.box(for: oauthUUID) else {
            logger.error("[URLProtocol] No TokenBox found for UUID \(oauthUUID)")
            client?.urlProtocol(self, didFailWithError: NSError(domain: "OAuthHTTPClient", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No token provider for this request"]))
            return
        }

        // Remove API key
        mutable.setValue(nil, forHTTPHeaderField: "x-api-key")

        // Mimic the real Claude Code CLI as closely as possible. Anthropic's
        // backend uses the *combination* of `anthropic-beta`, `User-Agent`,
        // and `X-Stainless-*` headers to decide whether an OAuth-scoped
        // (Claude Code) credential is being used by the official CLI or by a
        // third-party client; non-CLI requests get downgraded (e.g. extra-
        // usage billing, silently-disabled `thinking`/`adaptive`).
        // The full beta set + Stainless headers below align with sub2api's
        // FullClaudeCodeMimicryBetas / DefaultHeaders (see
        // Wei-Shaw/sub2api backend/internal/pkg/claude/constants.go).
        let existing = mutable.value(forHTTPHeaderField: "anthropic-beta") ?? ""
        // [T-anthropic-redact-thinking] `redact-thinking-2026-02-12` is
        // intentionally OMITTED. With it, the server returns thinking content
        // blocks whose `thinking` text is blanked ("") — only `signature` is
        // populated — so Claude 4.6+ adaptive-thinking models (sonnet-5,
        // opus-4-8, …) reason (usage.thinking_tokens > 0) but the plaintext is
        // never shown in the UI. Claude Code's official CLI only pushes this beta
        // when `showThinkingSummaries !== true` (de-obfuscated; anthropics/
        // claude-code#31326; code.claude.com/docs model-config notes interactive
        // API sessions get redacted thinking by default unless
        // showThinkingSummaries: true). Dropping it = always the
        // showThinkingSummaries:true behavior, so thinking text is visible. The
        // other 7 mimicry betas are unchanged.
        let mimicryBetas = [
            "claude-code-20250219",
            "oauth-2025-04-20",
            "interleaved-thinking-2025-05-14",
            "prompt-caching-scope-2026-01-05",
            "effort-2025-11-24",
            "context-management-2025-06-27",
            "extended-cache-ttl-2025-04-11",
        ]
        var flags = existing.isEmpty ? [] : existing.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        for flag in mimicryBetas where !flags.contains(flag) {
            flags.append(flag)
        }
        mutable.setValue(flags.joined(separator: ","), forHTTPHeaderField: "anthropic-beta")

        // Stainless / CLI fingerprint headers. Versions intentionally pinned
        // to claude-cli/2.1.195 — bump in lockstep with sub2api when the real
        // CLI version moves.
        mutable.setValue("claude-cli/2.1.195 (external, cli)", forHTTPHeaderField: "User-Agent")
        mutable.setValue("js", forHTTPHeaderField: "X-Stainless-Lang")
        mutable.setValue("0.106.0", forHTTPHeaderField: "X-Stainless-Package-Version")
        mutable.setValue("Linux", forHTTPHeaderField: "X-Stainless-OS")
        mutable.setValue("arm64", forHTTPHeaderField: "X-Stainless-Arch")
        mutable.setValue("node", forHTTPHeaderField: "X-Stainless-Runtime")
        mutable.setValue("v24.18.0", forHTTPHeaderField: "X-Stainless-Runtime-Version")
        mutable.setValue("0", forHTTPHeaderField: "X-Stainless-Retry-Count")
        mutable.setValue("600", forHTTPHeaderField: "X-Stainless-Timeout")
        mutable.setValue("cli", forHTTPHeaderField: "X-App")
        mutable.setValue("true", forHTTPHeaderField: "Anthropic-Dangerous-Direct-Browser-Access")

        // Materialize httpBodyStream → httpBody so patchers and debug capture can read it.
        #if DEBUG
        AgentRequestTrace.shared.step("urlprotocol.intercept", detail: "httpBody=\(mutable.httpBody?.count ?? -1) httpBodyStream=\(mutable.httpBodyStream != nil)")
        #endif
        RequestBodyPatcher.materializeBodyStream(mutable)
        #if DEBUG
        AgentRequestTrace.shared.step("urlprotocol.afterMaterialize", detail: "httpBody=\(mutable.httpBody?.count ?? -1) httpBodyStream=\(mutable.httpBodyStream != nil)")
        #endif

        // Normalize key ordering first so all patchers produce deterministic output
        // (critical for Anthropic prompt cache prefix matching)
        RequestBodyPatcher.normalizeKeyOrder(in: mutable)
        // Cache: mark last tool definition for caching (stable prefix)
        RequestBodyPatcher.injectToolsCacheControl(into: mutable)
        // Inject eager_input_streaming into tool definitions
        RequestBodyPatcher.injectEagerInputStreaming(into: mutable)
        // Rewrite tool results that have associated images
        RequestBodyPatcher.patchToolResultsWithImages(into: mutable)
        // Inject 1h TTL into cache_control objects when enhanced caching is enabled
        RequestBodyPatcher.injectCacheTTL(into: mutable)
        // Inject thinking config (budget_tokens + beta header) when enabled
        RequestBodyPatcher.injectThinkingConfig(into: mutable)
        RequestBodyPatcher.injectThinkingBlocksForCompatProxy(into: mutable)
        #if DEBUG
        AgentRequestTrace.shared.step("urlprotocol.afterPatchers", detail: "httpBody=\(mutable.httpBody?.count ?? -1)")
        #endif

        // Capture body data NOW — NSMutableURLRequest can lose httpBody across async boundaries
        // (the underlying stream gets invalidated by the framework before the callback fires).
        let capturedBody = mutable.httpBody ?? Data()
        mutable.httpBody = nil  // no longer needed on the request object
        #if DEBUG
        AgentRequestTrace.shared.step("urlprotocol.capturedBody", detail: "bytes=\(capturedBody.count)")
        #endif

        // Remove stale Content-Length — URLSession will recalculate from uploadTask data
        mutable.setValue(nil, forHTTPHeaderField: "Content-Length")

        // Fetch token asynchronously — no semaphore to avoid deadlock with @MainActor token refresh.
        tokenBox.fetchToken { [weak self] result in
            guard let self else { return }
            self.stateLock.lock()
            let stopped = self.isStopped
            self.stateLock.unlock()
            guard !stopped else { return }

            switch result {
            case .failure(let error):
                logger.error("[URLProtocol] Token fetch error: \(error.localizedDescription)")
                self.client?.urlProtocol(self, didFailWithError: error)
                return
            case .success(let token):
                mutable.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                #if DEBUG
                logger.info("[URLProtocol] Set Bearer token (prefix: \(token.prefix(16))...)")
                #else
                logger.info("[URLProtocol] Set Bearer token")
                #endif
            }

            #if DEBUG
            let headers = mutable.allHTTPHeaderFields ?? [:]
            var headerLines: [String] = ["[URLProtocol] Final Headers:"]
            for key in headers.keys.sorted() {
                let value = headers[key] ?? ""
                if key.lowercased() == "authorization" {
                    headerLines.append("  \(key): Bearer \(value.dropFirst(min(7, value.count)).prefix(16))...")
                } else {
                    headerLines.append("  \(key): \(value)")
                }
            }
            logger.debug("\(headerLines.joined(separator: "\n"))")

            do {
                // [T-ios-llmcapture-giant-body] Cap what this debug capture
                // costs. Long sessions with base64 images produce 20MB+ request
                // bodies; the old path JSONSerialization-parsed + pretty-printed
                // EVERY body (CPU per agent-loop round) and stored the full
                // pretty string in the ring — a memgraph showed 8 strings of
                // 24-27MB each (~200MB) all allocated here. Bodies over the cap
                // skip pretty-printing entirely and store a truncated prefix;
                // debug.llmRequests still shows the request head + true size.
                let prettyCap = 256 * 1024
                let formatted: String
                if capturedBody.count > prettyCap {
                    // The cut may land mid-way through a UTF-8 multi-byte
                    // character; back off a few bytes until decoding succeeds.
                    var head: String?
                    for backoff in 0..<4 {
                        head = String(data: capturedBody.prefix(prettyCap - backoff), encoding: .utf8)
                        if head != nil { break }
                    }
                    formatted = (head ?? "(binary \(capturedBody.count) bytes)")
                        + "\n... [truncated: \(capturedBody.count) bytes total]"
                } else if let json = try? JSONSerialization.jsonObject(with: capturedBody),
                   let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
                   let prettyStr = String(data: pretty, encoding: .utf8) {
                    formatted = prettyStr
                } else {
                    formatted = String(data: capturedBody, encoding: .utf8) ?? "(binary \(capturedBody.count) bytes)"
                }
                captureToken = LastAPIRequestBody.shared.set(formatted)
                LastAPIRequestBody.shared.setRequestMeta(
                    url: mutable.url?.absoluteString,
                    method: mutable.httpMethod,
                    headers: headers,
                    on: captureToken
                )
                let truncated = formatted.prefix(2000)
                let suffix = formatted.count > 2000 ? "\n  ... (\(formatted.count) chars total)" : ""
                logger.debug("[URLProtocol] Body (\(capturedBody.count) bytes):\n\(truncated)\(suffix)")
            }
            #else
            let headers = mutable.allHTTPHeaderFields ?? [:]
            logger.info("[URLProtocol] Headers: \(headers.keys.sorted().joined(separator: ", "))")
            #endif

            // Atomically check isStopped and create the upload task under the same lock.
            // stopLoading() can fire on another thread between the token callback and here,
            // invalidating innerSession. Creating a task on an invalidated session is a fatal NSException.
            self.stateLock.lock()
            guard !self.isStopped else {
                self.stateLock.unlock()
                return
            }
            #if DEBUG
            AgentRequestTrace.shared.step("urlprotocol.sendUploadTask", detail: "bodyBytes=\(capturedBody.count)")
            #endif
            let task = self.innerSession.uploadTask(with: mutable as URLRequest, from: capturedBody)
            self.innerTask = task
            self.stateLock.unlock()
            task.resume()
        }
    }

    override func stopLoading() {
        stateLock.lock()
        isStopped = true
        let task = innerTask
        innerTask = nil
        stateLock.unlock()
        task?.cancel()
        innerSession.invalidateAndCancel()
    }

    // MARK: - URLSessionDataDelegate (stream data incrementally)

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse {
            logger.info("[URLProtocol] Response status: \(http.statusCode)")
            #if DEBUG
            AgentRequestTrace.shared.step("urlprotocol.response", detail: "status=\(http.statusCode)")
            AgentRequestTrace.shared.setHTTPResponse(status: http.statusCode, body: nil)
            let respHeaders = (http.allHeaderFields as? [String: String])
                ?? Dictionary(uniqueKeysWithValues: http.allHeaderFields.map { ("\($0.key)", "\($0.value)") })
            LastAPIRequestBody.shared.setHTTPResponse(statusCode: http.statusCode, headers: respHeaders, on: captureToken)
            #endif
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if let http = dataTask.response as? HTTPURLResponse {
            if http.statusCode >= 400 {
                // Log and capture error response bodies for UI display
                if let body = String(data: data, encoding: .utf8) {
                    #if DEBUG
                    logger.error("[URLProtocol] Error body (\(http.statusCode)): \(body.prefix(500))")
                    #else
                    logger.error("[URLProtocol] Error body (\(http.statusCode))")
                    #endif
                    LastAPIErrorBody.shared.set(body)
                    #if DEBUG
                    AgentRequestTrace.shared.setHTTPResponse(status: http.statusCode, body: body)
                    AgentRequestTrace.shared.step("urlprotocol.errorBody", detail: String(body.prefix(500)))
                    AgentRequestTrace.shared.finish(error: "HTTP \(http.statusCode): \(body.prefix(200))")
                    #endif
                }
            }
            #if DEBUG
            if let body = String(data: data, encoding: .utf8), !body.isEmpty {
                LastAPIRequestBody.shared.appendResponseBody(body, on: captureToken)
                if http.statusCode < 400 {
                    let preview = String(body.prefix(300))
                    let suffix = body.count > 300 ? "..." : ""
                    logger.debug("[URLProtocol] Response data (\(data.count) bytes): \(preview)\(suffix)")
                }
            }
            #endif
        }
        // Filter out "data: [DONE]" from Anthropic-compatible proxies (OpenAI SSE convention)
        if let str = String(data: data, encoding: .utf8) {
            if str.trimmingCharacters(in: .whitespacesAndNewlines) == "data: [DONE]" { return }
            if str.contains("data: [DONE]") {
                let filtered = str.replacingOccurrences(of: "data: [DONE]", with: "")
                if let fd = filtered.data(using: .utf8), !fd.isEmpty {
                    client?.urlProtocol(self, didLoad: fd)
                }
                return
            }
        }
        client?.urlProtocol(self, didLoad: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            logger.error("[URLProtocol] Completed with error: \(error.localizedDescription)")
            client?.urlProtocol(self, didFailWithError: error)
        } else {
            client?.urlProtocolDidFinishLoading(self)
        }
        // Don't call finishTasksAndInvalidate() here — the session holds a strong
        // reference to its delegate (self), and invalidation releases it asynchronously.
        // If the URLProtocol is deallocated before the invalidation callback fires,
        // the session's delegate wrapper accesses freed memory (EXC_BAD_ACCESS).
        // deinit already calls invalidateAndCancel() to clean up the session.
    }
}

// MARK: - Request Body Patcher (shared utility)

/// Injects `eager_input_streaming: true` into each tool definition in the request body.
/// This enables fine-grained tool streaming (GA feature, no beta header required).
enum RequestBodyPatcher {
    /// If the request uses httpBodyStream instead of httpBody, read the stream
    /// and assign the result to httpBody so subsequent patchers can access it.
    static func materializeBodyStream(_ request: NSMutableURLRequest) {
        if let body = request.httpBody, !body.isEmpty { return }
        guard let stream = request.httpBodyStream else { return }
        stream.open()
        var data = Data()
        let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: 65_536)
        defer { buf.deallocate() }
        // Read until EOF — don't rely on hasBytesAvailable which can be false
        // before the first read on some stream types.
        while true {
            let n = stream.read(buf, maxLength: 65_536)
            if n > 0 {
                data.append(buf, count: n)
            } else {
                break  // 0 = EOF, -1 = error
            }
        }
        stream.close()
        // Always set httpBody (even if empty) since the stream is now consumed
        request.httpBody = data
    }

    /// Re-serialize the JSON body with `.sortedKeys` to ensure deterministic key
    /// ordering across requests. This is critical for Anthropic prompt caching:
    /// the SDK encodes with `JSONEncoder` (which has its own key order), but the
    /// patchers below round-trip through `JSONSerialization`. Without `.sortedKeys`,
    /// the key ordering is non-deterministic and can change between requests even
    /// for identical logical content, breaking cache prefix matching.
    static func normalizeKeyOrder(in request: NSMutableURLRequest) {
        guard let body = request.httpBody,
              let json = try? JSONSerialization.jsonObject(with: body),
              let normalized = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) else { return }
        request.httpBody = normalized
    }

    /// Inject cache_control on the last tool definition so that tools + system
    /// form a stable cached prefix (matching Claude Code's strategy).
    static func injectToolsCacheControl(into request: NSMutableURLRequest) {
        guard let body = request.httpBody,
              var json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              var tools = json["tools"] as? [[String: Any]],
              !tools.isEmpty else { return }

        let lastIdx = tools.count - 1
        if tools[lastIdx]["cache_control"] == nil {
            tools[lastIdx]["cache_control"] = ["type": "ephemeral"]
            json["tools"] = tools
            if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
                request.httpBody = newBody
            }
        }
    }

    static func injectEagerInputStreaming(into request: NSMutableURLRequest) {
        guard let body = request.httpBody,
              var json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              var tools = json["tools"] as? [[String: Any]] else { return }

        var modified = false
        for i in tools.indices {
            if tools[i]["eager_input_streaming"] == nil {
                tools[i]["eager_input_streaming"] = true
                modified = true
            }
        }

        guard modified else { return }
        json["tools"] = tools

        if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
            request.httpBody = newBody
        }
    }

    // MARK: - Structured tool result images

    private static let imageLock = NSLock()
    private static var _toolResultImages: [String: (data: Data, mimeType: String)] = [:]

    /// Set pending tool result images (called from AnthropicAgentProvider before streaming).
    static func setToolResultImages(_ images: [String: (data: Data, mimeType: String)]) {
        imageLock.lock()
        _toolResultImages = images
        imageLock.unlock()
    }

    /// Atomically take and clear the pending images.
    private static func takeToolResultImages() -> [String: (data: Data, mimeType: String)] {
        imageLock.lock()
        defer { imageLock.unlock() }
        let images = _toolResultImages
        _toolResultImages.removeAll()
        return images
    }

    /// Rewrites tool_result blocks that have associated images (looked up by tool_use_id)
    /// into multi-part content arrays with image + text blocks.
    ///
    /// SwiftAnthropic encodes `.toolResult(id, content)` as a string, but the API
    /// accepts an array of content blocks for vision. This replaces the string content
    /// using structured image data passed via `setToolResultImages()`.
    static func patchToolResultsWithImages(into request: NSMutableURLRequest) {
        let images = takeToolResultImages()
        guard !images.isEmpty else { return }

        guard let body = request.httpBody,
              var json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              var messages = json["messages"] as? [[String: Any]] else { return }

        var modified = false

        for mi in messages.indices {
            guard var content = messages[mi]["content"] as? [[String: Any]] else { continue }
            for ci in content.indices {
                guard content[ci]["type"] as? String == "tool_result",
                      let toolUseId = content[ci]["tool_use_id"] as? String,
                      let imageInfo = images[toolUseId] else { continue }

                let textContent = content[ci]["content"] as? String ?? ""
                let base64 = imageInfo.data.base64EncodedString()

                // Build multi-part content array
                var contentArray: [[String: Any]] = [
                    [
                        "type": "image",
                        "source": [
                            "type": "base64",
                            "media_type": imageInfo.mimeType,
                            "data": base64
                        ]
                    ]
                ]
                if !textContent.isEmpty {
                    contentArray.append(["type": "text", "text": textContent])
                }

                content[ci]["content"] = contentArray
                modified = true
            }
            messages[mi]["content"] = content
        }

        guard modified else { return }
        json["messages"] = messages

        if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
            request.httpBody = newBody
            logger.info("[RequestBodyPatcher] Patched tool results with image content blocks")
        }
    }

    // MARK: - Extended cache TTL

    private static let ttlLock = NSLock()
    private static var _extendedCacheTTL = false

    /// Enable or disable 1-hour cache TTL injection for subsequent requests.
    static func setExtendedCacheTTL(_ enabled: Bool) {
        ttlLock.lock()
        _extendedCacheTTL = enabled
        ttlLock.unlock()
    }

    private static var extendedCacheTTLEnabled: Bool {
        ttlLock.lock()
        defer { ttlLock.unlock() }
        return _extendedCacheTTL
    }

    // MARK: - Thinking config

    private static let thinkingLock = NSLock()
    private static var _thinkingBudget: Int = 0
    private static var _thinkingEffort: String? = nil
    private static var _thinkingDisabled: Bool = false
    /// [T-ios-thinking-flag-cross-request] Model the pending `_thinkingDisabled`
    /// intent was computed for; "" means unstamped (legacy callers).
    private static var _thinkingDisabledModelId: String = ""

    /// Set thinking budget tokens for the next request (0 = disabled).
    /// Used for legacy Claude models (<= 4.5) that take `thinking.type="enabled"`
    /// + `budget_tokens`.
    static func setThinkingBudget(_ budget: Int) {
        thinkingLock.lock()
        _thinkingBudget = budget
        thinkingLock.unlock()
    }

    /// Set adaptive thinking effort for the next request (nil = unset).
    /// Used for Claude 4.6+ which take `thinking.type="adaptive"` +
    /// `output_config.effort = low|medium|high|xhigh|max` and ignore the
    /// older budget-based form.
    static func setThinkingEffort(_ effort: String?) {
        thinkingLock.lock()
        _thinkingEffort = effort
        thinkingLock.unlock()
    }

    /// Explicitly disable thinking for the next request. Needed for adaptive
    /// models (Claude 4.6+/5) whose SERVER default is thinking-on when the
    /// request has no thinking field — omission is not "off" there.
    ///
    /// [T-ios-thinking-flag-cross-request] `modelId` is the model the intent was
    /// computed FOR. These three flags are process-global and are consumed by
    /// whichever request reaches `URLProtocol.startLoading` next — and the set
    /// (in AnthropicAgentProvider, before the SDK builds the body) is separated
    /// from the take (when the request actually goes out) by a wide window.
    /// Anything else that fires an Anthropic request in between — title
    /// generation, which calls `streamAgentMessage(thinkingLevel: .off)` on a
    /// possibly DIFFERENT model — can consume an intent that was never meant
    /// for it, or leave one behind for the next turn. Stamping the model lets
    /// the consumer drop a mismatched intent instead of applying it blindly.
    static func setThinkingDisabled(modelId: String = "") {
        thinkingLock.lock()
        _thinkingDisabled = true
        _thinkingDisabledModelId = modelId
        thinkingLock.unlock()
    }

    /// Returns (disabled, modelIdItWasSetFor).
    private static func takeThinkingDisabled() -> (Bool, String) {
        thinkingLock.lock()
        defer { thinkingLock.unlock() }
        let d = _thinkingDisabled
        let m = _thinkingDisabledModelId
        _thinkingDisabled = false
        _thinkingDisabledModelId = ""
        return (d, m)
    }

    private static func takeThinkingBudget() -> Int {
        thinkingLock.lock()
        defer { thinkingLock.unlock() }
        let b = _thinkingBudget
        _thinkingBudget = 0
        return b
    }

    private static func takeThinkingEffort() -> String? {
        thinkingLock.lock()
        defer { thinkingLock.unlock() }
        let e = _thinkingEffort
        _thinkingEffort = nil
        return e
    }

    // MARK: - Compat-proxy reasoning echo

    private static let reasoningEchoLock = NSLock()
    /// One entry per assistant message in chronological order. Non-nil values
    /// are echoed back as a synthesized `{"type":"thinking","thinking":...}`
    /// content block at the head of the corresponding assistant message.
    /// Drained by the next request (single-shot like the other patcher state).
    private static var _reasoningHistory: [String?]? = nil
    /// When true, assistant turns with nil/empty reasoning still get an empty
    /// `{type:"thinking", thinking:""}` block prepended. Required by
    /// compat-proxies (e.g. DeepSeek V4) that demand the field be present on
    /// every assistant turn once any thinking has occurred — interrupted
    /// streams or cross-provider sessions otherwise produce 400s.
    private static var _reasoningInjectPlaceholder: Bool = false

    /// Stash the captured `reasoningContent` for each assistant turn (in
    /// order). Called by AnthropicAgentProvider just before kicking off the
    /// SDK request when the configured endpoint isn't the official one and
    /// thinking is enabled — see AnthropicProvider.isOfficialAnthropicEndpoint
    /// for why this is gated.
    static func setReasoningHistory(_ history: [String?]?, injectPlaceholder: Bool = false) {
        reasoningEchoLock.lock()
        _reasoningHistory = history
        _reasoningInjectPlaceholder = injectPlaceholder
        reasoningEchoLock.unlock()
    }

    private static func takeReasoningHistory() -> (history: [String?]?, injectPlaceholder: Bool) {
        reasoningEchoLock.lock()
        defer { reasoningEchoLock.unlock() }
        let h = _reasoningHistory
        let p = _reasoningInjectPlaceholder
        _reasoningHistory = nil
        _reasoningInjectPlaceholder = false
        return (h, p)
    }

    /// Anthropic-compat proxies (DeepSeek's deepseek-v4-pro etc.) reject
    /// history requests where a previous assistant turn produced a thinking
    /// block but the field isn't echoed back. Real Anthropic enforces this
    /// too via signed `thinking` blocks, which we don't carry over the wire,
    /// so this patch only runs for non-official endpoints (gated upstream).
    /// Synthesizes a `{type:"thinking", thinking:"..."}` block at the head
    /// of each assistant message that had captured reasoning content,
    /// satisfying the compat-proxy requirement without forging signatures.
    static func injectThinkingBlocksForCompatProxy(into request: NSMutableURLRequest) {
        let (history, injectPlaceholder) = takeReasoningHistory()
        guard history != nil || injectPlaceholder else { return }
        let h = history ?? []

        guard let body = request.httpBody,
              var json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              var messages = json["messages"] as? [[String: Any]] else { return }

        var assistantIdx = 0
        var injectedReal = 0
        var injectedPlaceholder = 0
        for mi in messages.indices {
            guard (messages[mi]["role"] as? String) == "assistant" else { continue }
            defer { assistantIdx += 1 }

            let reasoning: String? = (assistantIdx < h.count) ? h[assistantIdx] : nil
            let hasReal = reasoning?.isEmpty == false
            // Skip when there's nothing to inject for this turn and placeholder
            // is off — keeps no-op turns untouched.
            if !hasReal && !injectPlaceholder { continue }

            // Materialize content into the array form (the API accepts both
            // a string and an array of blocks; we need an array to prepend).
            var contentArray: [[String: Any]]
            if let arr = messages[mi]["content"] as? [[String: Any]] {
                contentArray = arr
            } else if let str = messages[mi]["content"] as? String {
                contentArray = str.isEmpty ? [] : [["type": "text", "text": str]]
            } else {
                contentArray = []
            }

            // Skip if the message already starts with a thinking block (defensive
            // — shouldn't happen given our convertMessages doesn't emit them).
            if let first = contentArray.first, first["type"] as? String == "thinking" { continue }

            let thinkingText = hasReal ? reasoning! : ""
            contentArray.insert([
                "type": "thinking",
                "thinking": thinkingText,
            ], at: 0)
            messages[mi]["content"] = contentArray
            if hasReal { injectedReal += 1 } else { injectedPlaceholder += 1 }
        }

        guard injectedReal + injectedPlaceholder > 0 else { return }
        json["messages"] = messages
        if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
            request.httpBody = newBody
            logger.info("[RequestBodyPatcher] Injected thinking blocks for compat-proxy endpoint: real=\(injectedReal) placeholder=\(injectedPlaceholder)")
        }
    }

    /// Inject the appropriate thinking config + beta headers based on the model.
    ///
    /// Two protocol shapes:
    ///   - Adaptive (Claude 4.6+): `thinking.type="adaptive"` + `output_config.effort`,
    ///     plus the `effort-2025-11-24` beta. The older budget form is silently
    ///     ignored by these models so the user sees "thinking enabled but no
    ///     thinking output". Confirmed against CLIProxyAPI / sub2api.
    ///   - Legacy budget (Claude <= 4.5): `thinking.type="enabled"` +
    ///     `budget_tokens=N`, plus the `interleaved-thinking-2025-05-14` beta.
    ///
    /// In both cases temperature handling follows AnthropicProvider.modelRejectsTemperature
    /// — Claude 4.6+ rejects temperature entirely, so we drop it.
    static func injectThinkingConfig(into request: NSMutableURLRequest) {
        let budget = takeThinkingBudget()
        let effort = takeThinkingEffort()
        let (disabledRaw, disabledForModel) = takeThinkingDisabled()
        // Nothing to do if the caller didn't set any thinking intent for this request.
        guard budget > 0 || effort != nil || disabledRaw else { return }

        guard let body = request.httpBody,
              var json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return }

        let modelId = (json["model"] as? String) ?? ""
        let useAdaptive = AnthropicProvider.modelUsesAdaptiveThinking(modelId)

        // [T-ios-thinking-flag-cross-request] Honour the disable intent only if
        // it was computed for THIS model. The flags are process-global and are
        // claimed by whichever request reaches startLoading first, so an intent
        // set for model A can otherwise be applied to a request for model B
        // (title generation fires its own `.off` Anthropic request on a
        // possibly different model between the set and the take). An unstamped
        // intent ("" — no caller passed a model) is honoured as before so this
        // cannot silently drop a legitimate disable.
        let disabled = disabledRaw
            && (disabledForModel.isEmpty || disabledForModel == modelId)
        if disabledRaw && !disabled {
            logger.info("[Thinking] dropped cross-request disable intent set for \(disabledForModel) — this request is \(modelId)")
        }

        if disabled {
            // Explicit off. Only 4.6-4.x needs it on the wire (their server
            // default is thinking-on); legacy models are off by omission, and
            // enable-intent always wins if both were set.
            //
            // [T-ios-claude5-thinking-disabled-400] Claude 5+ is excluded even
            // though it is "adaptive": it rejects the value with
            // `"thinking.type.disabled" is not supported for this model` and
            // defaults to adaptive when the field is absent. This is the OAuth
            // wire path (fable 5 / Opus 5 login), i.e. exactly where the 400
            // was reported — it re-derives the decision from the on-wire model
            // id rather than reusing the resolver's shape, so it needs the same
            // gate independently.
            if useAdaptive,
               AnthropicProvider.modelAcceptsExplicitThinkingDisabled(modelId),
               budget <= 0, effort == nil {
                json["thinking"] = ["type": "disabled"]
                if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
                    request.httpBody = newBody
                }
            }
            if budget <= 0, effort == nil { return }
        }

        // Beta headers: each protocol needs its own beta token. Append without
        // disturbing any tokens the SDK / OAuth path already set.
        var betas: [String] = []
        if let existing = request.value(forHTTPHeaderField: "anthropic-beta"), !existing.isEmpty {
            betas = existing.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        }
        if useAdaptive {
            if !betas.contains("effort-2025-11-24") { betas.append("effort-2025-11-24") }
        } else {
            if !betas.contains("interleaved-thinking-2025-05-14") { betas.append("interleaved-thinking-2025-05-14") }
        }
        request.setValue(betas.joined(separator: ","), forHTTPHeaderField: "anthropic-beta")

        if useAdaptive {
            // Adaptive: thinking.type=adaptive, no budget_tokens; effort goes
            // under output_config.effort. If the caller only set a budget
            // (legacy code path), default to "medium" so we still get thinking.
            //
            // [T-anthropic-thinking-display] Explicitly request
            // `display: "summarized"`. Adaptive thinking's `display` field
            // controls whether the server returns readable thinking-summary text
            // ("summarized") or blanks the thinking block ("omitted", thinking:""
            // + signature only — indistinguishable from redacted). The DEFAULT
            // flipped from "summarized" (Sonnet/Opus 4.6) to "omitted" on Opus
            // 4.7+ / newer tiers, so relying on the default left claude-sonnet-5
            // etc. with empty thinking even after we dropped the redact-thinking
            // beta (958ee16c). Setting it explicitly makes thinking text visible
            // regardless of the model's default. Independent mechanism from the
            // beta header — both were needed.
            json["thinking"] = ["type": "adaptive", "display": "summarized"]
            var oc = (json["output_config"] as? [String: Any]) ?? [:]
            oc["effort"] = effort ?? "medium"
            json["output_config"] = oc
            // Strip any legacy budget that may have been left around.
            (json["thinking"] as? [String: Any]).map { _ in
                if var t = json["thinking"] as? [String: Any] {
                    t.removeValue(forKey: "budget_tokens")
                    json["thinking"] = t
                }
            }
        } else {
            // Legacy: thinking.type=enabled with budget_tokens.
            // Defensive: if the caller only set effort (newer code path) but
            // we hit a pre-4.6 model, fall back to a reasonable budget.
            let effectiveBudget = budget > 0 ? budget : 16384
            json["thinking"] = ["type": "enabled", "budget_tokens": effectiveBudget]
        }

        // Anthropic requires temperature=1 when legacy thinking is enabled,
        // but Claude >= 4.6 rejects the temperature parameter entirely
        // (see AnthropicProvider.modelRejectsTemperature). Adaptive thinking
        // models all fall under that rejection rule.
        if AnthropicProvider.modelRejectsTemperature(modelId) {
            json.removeValue(forKey: "temperature")
        } else {
            json["temperature"] = 1
        }

        if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
            request.httpBody = newBody
        }
    }

    /// Injects `"ttl": "1h"` into every `cache_control` object in messages and system prompt.
    static func injectCacheTTL(into request: NSMutableURLRequest) {
        guard extendedCacheTTLEnabled else { return }
        guard let body = request.httpBody,
              var json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return }

        var modified = false

        // Patch messages
        if var messages = json["messages"] as? [[String: Any]] {
            for mi in messages.indices {
                guard var content = messages[mi]["content"] as? [[String: Any]] else { continue }
                for ci in content.indices {
                    if var cc = content[ci]["cache_control"] as? [String: Any], cc["ttl"] == nil {
                        cc["ttl"] = "1h"
                        content[ci]["cache_control"] = cc
                        modified = true
                    }
                }
                messages[mi]["content"] = content
            }
            json["messages"] = messages
        }

        // Patch system prompt blocks
        if var system = json["system"] as? [[String: Any]] {
            for si in system.indices {
                if var cc = system[si]["cache_control"] as? [String: Any], cc["ttl"] == nil {
                    cc["ttl"] = "1h"
                    system[si]["cache_control"] = cc
                    modified = true
                }
            }
            json["system"] = system
        }

        guard modified else { return }
        if let newBody = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
            request.httpBody = newBody
        }
    }
}

// MARK: - Eager Streaming URLProtocol (for API key path)

/// Lightweight URLProtocol that only patches the request body to add
/// `eager_input_streaming` — no auth changes. Used for API key mode.
private final class EagerStreamingURLProtocol: URLProtocol, URLSessionDataDelegate {

    private lazy var innerSession: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }()
    private var innerTask: URLSessionDataTask?
    #if DEBUG
    /// [T-ios-llmcapture-entry-crosstalk] This request instance's capture
    /// entry, so response/usage updates target it and not `_entries.last`.
    private var captureToken: CapturedAPIRequest?
    #endif

    // Do NOT invalidate innerSession in deinit — same reason as OAuthURLProtocol.

    override class func canInit(with request: URLRequest) -> Bool {
        // Intercept ALL requests from this session (custom base URLs need patching too)
        return URLProtocol.property(forKey: "EagerHandled", in: request) == nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let mutable = (request as NSURLRequest).mutableCopy() as! NSMutableURLRequest
        URLProtocol.setProperty(true, forKey: "EagerHandled", in: mutable)
        RequestBodyPatcher.materializeBodyStream(mutable)
        RequestBodyPatcher.normalizeKeyOrder(in: mutable)
        RequestBodyPatcher.injectToolsCacheControl(into: mutable)
        RequestBodyPatcher.injectEagerInputStreaming(into: mutable)
        RequestBodyPatcher.patchToolResultsWithImages(into: mutable)
        RequestBodyPatcher.injectCacheTTL(into: mutable)
        RequestBodyPatcher.injectThinkingConfig(into: mutable)
        RequestBodyPatcher.injectThinkingBlocksForCompatProxy(into: mutable)

        // Remove stale Content-Length — URLSession will recalculate from httpBody
        mutable.setValue(nil, forHTTPHeaderField: "Content-Length")

        #if DEBUG
        if let body = readRequestBodyData(from: mutable) {
            if let json = try? JSONSerialization.jsonObject(with: body),
               let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
               let prettyStr = String(data: pretty, encoding: .utf8) {
                captureToken = LastAPIRequestBody.shared.set(prettyStr)
            } else if let bodyStr = String(data: body, encoding: .utf8) {
                captureToken = LastAPIRequestBody.shared.set(bodyStr)
            }
            LastAPIRequestBody.shared.setRequestMeta(
                url: mutable.url?.absoluteString,
                method: mutable.httpMethod,
                headers: mutable.allHTTPHeaderFields,
                on: captureToken
            )
        }
        #endif

        // Use uploadTask to guarantee the body is sent — dataTask can lose httpBody
        let bodyData = mutable.httpBody ?? Data()
        mutable.httpBody = nil
        let task = innerSession.uploadTask(with: mutable as URLRequest, from: bodyData)
        innerTask = task
        task.resume()
    }

    override func stopLoading() {
        innerTask?.cancel()
        innerTask = nil
        innerSession.invalidateAndCancel()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        #if DEBUG
        if let http = response as? HTTPURLResponse {
            let respHeaders = (http.allHeaderFields as? [String: String])
                ?? Dictionary(uniqueKeysWithValues: http.allHeaderFields.map { ("\($0.key)", "\($0.value)") })
            LastAPIRequestBody.shared.setHTTPResponse(statusCode: http.statusCode, headers: respHeaders, on: captureToken)
        }
        #endif
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if let http = dataTask.response as? HTTPURLResponse, http.statusCode >= 400 {
            if let body = String(data: data, encoding: .utf8) {
                LastAPIErrorBody.shared.set(body)
            }
        }
        #if DEBUG
        if let chunk = String(data: data, encoding: .utf8), !chunk.isEmpty {
            LastAPIRequestBody.shared.appendResponseBody(chunk, on: captureToken)
        }
        #endif
        // Filter out "data: [DONE]" lines that some Anthropic-compatible proxies append
        // (OpenAI SSE convention). The SwiftAnthropic SDK doesn't expect this and will
        // throw a DecodingError trying to parse "[DONE]" as JSON.
        if let str = String(data: data, encoding: .utf8),
           str.trimmingCharacters(in: .whitespacesAndNewlines) == "data: [DONE]" {
            // Swallow — don't forward to the SDK
            return
        }
        // Also handle chunks that contain [DONE] mixed with other data
        if let str = String(data: data, encoding: .utf8), str.contains("data: [DONE]") {
            let filtered = str.replacingOccurrences(of: "data: [DONE]", with: "")
            if let filteredData = filtered.data(using: .utf8), !filteredData.isEmpty {
                client?.urlProtocol(self, didLoad: filteredData)
            }
            return
        }
        client?.urlProtocol(self, didLoad: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
        } else {
            client?.urlProtocolDidFinishLoading(self)
        }
        // Don't invalidate here — deinit handles it. See OAuthURLProtocol for details.
    }
}

// MARK: - Eager Streaming HTTP Client (for API key path)

/// HTTPClient wrapper that injects `eager_input_streaming` into tool definitions
/// for the API key authentication path.
final class EagerStreamingHTTPClient: HTTPClient {
    private let underlying: URLSessionHTTPClientAdapter

    /// - Parameter customUserAgent: when non-nil, overrides the default URLSession
    ///   `User-Agent` on every request through `httpAdditionalHeaders` (proxies that
    ///   gate on client UA). The SDK never sets User-Agent itself, so this takes effect.
    init(customUserAgent: String? = nil) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 600  // 10 min — SSE streams can be idle during large tool generation
        config.protocolClasses = [EagerStreamingURLProtocol.self]
        if let ua = customUserAgent {
            config.httpAdditionalHeaders = (config.httpAdditionalHeaders ?? [:]).merging(
                ["User-Agent": ua]) { _, new in new }
        }
        let session = URLSession(configuration: config)
        self.underlying = URLSessionHTTPClientAdapter(urlSession: session)
    }

    func data(for request: HTTPRequest) async throws -> (Data, HTTPResponse) {
        try await underlying.data(for: request)
    }

    func bytes(for request: HTTPRequest) async throws -> (HTTPByteStream, HTTPResponse) {
        try await underlying.bytes(for: request)
    }
}

/// HTTPClient that sends both `x-api-key` and `Authorization: Bearer` headers simultaneously.
/// Used for manual OAuth tokens on Anthropic-compatible proxies that may expect either header.
final class DualAuthHTTPClient: HTTPClient {
    private let underlying: URLSessionHTTPClientAdapter

    /// - Parameter customUserAgent: see `EagerStreamingHTTPClient.init`.
    init(customUserAgent: String? = nil) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 600
        config.protocolClasses = [DualAuthURLProtocol.self]
        if let ua = customUserAgent {
            config.httpAdditionalHeaders = (config.httpAdditionalHeaders ?? [:]).merging(
                ["User-Agent": ua]) { _, new in new }
        }
        let session = URLSession(configuration: config)
        self.underlying = URLSessionHTTPClientAdapter(urlSession: session)
    }

    func data(for request: HTTPRequest) async throws -> (Data, HTTPResponse) {
        try await underlying.data(for: request)
    }

    func bytes(for request: HTTPRequest) async throws -> (HTTPByteStream, HTTPResponse) {
        try await underlying.bytes(for: request)
    }
}

// MARK: - Dual Auth URLProtocol

/// URLProtocol that keeps `x-api-key` AND adds `Authorization: Bearer` from the same value.
/// Applies all the same request body patching as EagerStreamingURLProtocol.
private final class DualAuthURLProtocol: URLProtocol, URLSessionDataDelegate {

    private lazy var innerSession: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }()
    private var innerTask: URLSessionDataTask?
    #if DEBUG
    /// [T-ios-llmcapture-entry-crosstalk] This request instance's capture
    /// entry, so response/usage updates target it and not `_entries.last`.
    private var captureToken: CapturedAPIRequest?
    #endif

    override class func canInit(with request: URLRequest) -> Bool {
        return URLProtocol.property(forKey: "DualAuthHandled", in: request) == nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let mutable = (request as NSURLRequest).mutableCopy() as! NSMutableURLRequest
        URLProtocol.setProperty(true, forKey: "DualAuthHandled", in: mutable)

        // Send both x-api-key AND Authorization: Bearer for maximum proxy compatibility.
        // MiniMax Anthropic endpoint requires x-api-key; other proxies require Authorization: Bearer.
        if let apiKey = mutable.value(forHTTPHeaderField: "x-api-key") {
            mutable.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
            // Keep x-api-key as well — proxy will use whichever it recognizes
        }

        // Apply same patching as EagerStreamingURLProtocol
        RequestBodyPatcher.materializeBodyStream(mutable)
        RequestBodyPatcher.normalizeKeyOrder(in: mutable)
        RequestBodyPatcher.injectToolsCacheControl(into: mutable)
        RequestBodyPatcher.injectEagerInputStreaming(into: mutable)
        RequestBodyPatcher.patchToolResultsWithImages(into: mutable)
        RequestBodyPatcher.injectCacheTTL(into: mutable)
        RequestBodyPatcher.injectThinkingConfig(into: mutable)
        RequestBodyPatcher.injectThinkingBlocksForCompatProxy(into: mutable)
        mutable.setValue(nil, forHTTPHeaderField: "Content-Length")

        #if DEBUG
        if let body = readRequestBodyData(from: mutable) {
            if let json = try? JSONSerialization.jsonObject(with: body),
               let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
               let prettyStr = String(data: pretty, encoding: .utf8) {
                captureToken = LastAPIRequestBody.shared.set(prettyStr)
            } else if let bodyStr = String(data: body, encoding: .utf8) {
                captureToken = LastAPIRequestBody.shared.set(bodyStr)
            }
            LastAPIRequestBody.shared.setRequestMeta(
                url: mutable.url?.absoluteString,
                method: mutable.httpMethod,
                headers: mutable.allHTTPHeaderFields,
                on: captureToken
            )
        }
        #endif

        let bodyData = mutable.httpBody ?? Data()
        mutable.httpBody = nil
        let task = innerSession.uploadTask(with: mutable as URLRequest, from: bodyData)
        innerTask = task
        task.resume()
    }

    override func stopLoading() {
        innerTask?.cancel()
        innerTask = nil
        innerSession.invalidateAndCancel()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        #if DEBUG
        if let http = response as? HTTPURLResponse {
            let respHeaders = (http.allHeaderFields as? [String: String])
                ?? Dictionary(uniqueKeysWithValues: http.allHeaderFields.map { ("\($0.key)", "\($0.value)") })
            LastAPIRequestBody.shared.setHTTPResponse(statusCode: http.statusCode, headers: respHeaders, on: captureToken)
        }
        #endif
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if let http = dataTask.response as? HTTPURLResponse, http.statusCode >= 400 {
            if let body = String(data: data, encoding: .utf8) {
                LastAPIErrorBody.shared.set(body)
            }
        }
        #if DEBUG
        if let chunk = String(data: data, encoding: .utf8), !chunk.isEmpty {
            LastAPIRequestBody.shared.appendResponseBody(chunk, on: captureToken)
        }
        #endif
        // Filter out "data: [DONE]" (OpenAI SSE convention some proxies append)
        if let str = String(data: data, encoding: .utf8),
           str.trimmingCharacters(in: .whitespacesAndNewlines) == "data: [DONE]" {
            return
        }
        if let str = String(data: data, encoding: .utf8), str.contains("data: [DONE]") {
            let filtered = str.replacingOccurrences(of: "data: [DONE]", with: "")
            if let filteredData = filtered.data(using: .utf8), !filteredData.isEmpty {
                client?.urlProtocol(self, didLoad: filteredData)
            }
            return
        }
        client?.urlProtocol(self, didLoad: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
        } else {
            client?.urlProtocolDidFinishLoading(self)
        }
    }
}
