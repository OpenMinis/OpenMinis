import Foundation
import Speech
import AVFoundation

// MARK: - Built-in system voice provider
//
// Uses iOS SFSpeechRecognizer (ASR) and AVSpeechSynthesizer (TTS). Works
// offline with no API key. Exposed through the provider system as a built-in
// fallback so voice input/output is always available.

final class SystemVoiceProvider: NSObject, VoiceInputCapable, VoiceOutputCapable {

    static let builtinProviderId = "__builtin_system_speech__"
    static let shared = SystemVoiceProvider()

    /// Synthetic, LOCAL-ONLY ProviderInstance for the built-in System engine
    /// (Phase B of the System-Provider unification). It lets the factory / resolver
    /// / picker treat System like any other provider instance — WITHOUT ever being
    /// stored in ProviderConfigStore.config.instances, so it is never written to the
    /// DB and never uploaded to iCloud (the codebase has no per-instance sync
    /// filter, so the only safe exclusion is to stay out of the stored config).
    /// `instance(for:)` returns THIS for the sentinel id; no new ProviderType case
    /// is introduced (it never syncs, so a peer never decodes an unknown type).
    /// `createdAt` is a fixed epoch so the value is stable/deterministic.
    static let providerInstance = ProviderInstance(
        id: builtinProviderId,
        label: AppLocalized("System", comment: "Built-in Apple speech engine provider name"),
        providerType: .openAI,          // arbitrary — never used for auth/routing; System routes by id
        credentialType: .apiKey,        // arbitrary — System needs no credential
        isEnabled: true,
        createdAt: Date(timeIntervalSince1970: 0)
    )

    private let logger = AppLogger(category: "SystemVoice")
    private let synthesizer = AVSpeechSynthesizer()
    private let locale: Locale

    /// Cache of SFSpeechRecognizer by locale identifier. Recreating one per
    /// request resets its warm-up state, which is part of why the first request
    /// or two after entering voice mode produced no result. Reusing a cached,
    /// pre-warmed recognizer makes the first real request succeed.
    private var recognizerCache: [String: SFSpeechRecognizer] = [:]
    private let cacheLock = NSLock()

    init(locale: Locale = Locale(identifier: "zh-CN")) {
        self.locale = locale
        super.init()
    }

    var supportsVoiceInput: Bool  { SFSpeechRecognizer.authorizationStatus() == .authorized }
    var supportsVoiceOutput: Bool { true }

    // MARK: - Speech recognition authorization

    static var speechAuthStatus: SFSpeechRecognizerAuthorizationStatus {
        SFSpeechRecognizer.authorizationStatus()
    }

    /// Request Speech Recognition permission (no-op once decided). The offline
    /// System ASR can't run without it — the mic permission alone is not enough.
    @discardableResult
    static func ensureSpeechAuthorization() async -> Bool {
        if speechAuthStatus == .authorized { return true }
        return await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                VoiceLog.log("speech authorization result: \(status.rawValue)")
                cont.resume(returning: status == .authorized)
            }
        }
    }

    /// Resolve the best locale for a language code (e.g. "zh" → zh-CN), or the
    /// provider's default locale when no supported locale matches.
    private func resolveLocale(forLanguage code: String?) -> Locale {
        guard let code, !code.isEmpty else { return locale }
        let exact = Locale(identifier: code)
        if SFSpeechRecognizer.supportedLocales().contains(where: { $0.identifier.caseInsensitiveCompare(code) == .orderedSame }) {
            return exact
        }
        let lower = code.lowercased()
        if let match = SFSpeechRecognizer.supportedLocales().first(where: { $0.identifier.lowercased().hasPrefix(lower) }) {
            return match
        }
        return locale
    }

    /// Cached recognizer for a locale (created once, reused warm).
    private func cachedRecognizer(for loc: Locale) -> SFSpeechRecognizer? {
        cacheLock.lock(); defer { cacheLock.unlock() }
        if let r = recognizerCache[loc.identifier] { return r }
        guard let r = SFSpeechRecognizer(locale: loc) else { return nil }
        recognizerCache[loc.identifier] = r
        return r
    }

    /// Warm up the recognizer for `language` ahead of the first request so the
    /// on-device model is ready and the first utterance isn't silently dropped.
    /// Safe to call repeatedly; cheap once cached.
    func prewarm(language: String?) {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else { return }
        let loc = resolveLocale(forLanguage: language)
        _ = cachedRecognizer(for: loc)
        VoiceLog.log("prewarm recognizer for \(loc.identifier)")
    }

    // MARK: - Audio validation

    /// Smallest buffer that could plausibly hold decodable audio. A canonical
    /// WAV header is 44 bytes, so anything at or below that is header-only or
    /// truncated and has no samples at all.
    private static let minimumDecodableAudioBytes = 44

    /// Throw unless the file at `url` really contains a usable audio track.
    ///
    /// [T-ios-speech-no-audio-track] Guards the uncatchable
    /// AVAssetReaderAudioMixOutput exception described at the call site. Checks
    /// both halves of what that initializer needs: at least one audio track, and
    /// a finite, positive duration (a track can exist while the file is
    /// truncated to zero length).
    private func validateHasAudioTrack(at url: URL) async throws {
        let asset = AVURLAsset(url: url)
        do {
            let tracks = try await asset.loadTracks(withMediaType: .audio)
            guard !tracks.isEmpty else {
                VoiceLog.log("system ASR rejected: no audio track in \(url.lastPathComponent)")
                throw VoiceProviderError.noAudioData
            }
            let duration = try await asset.load(.duration)
            let seconds = CMTimeGetSeconds(duration)
            guard seconds.isFinite, seconds > 0 else {
                VoiceLog.log("system ASR rejected: non-positive duration (\(seconds)s) in \(url.lastPathComponent)")
                throw VoiceProviderError.noAudioData
            }
        } catch let error as VoiceProviderError {
            throw error
        } catch {
            // Loading the asset failed outright (unreadable, unknown container).
            // Treat that the same way: the file is not something we can safely
            // hand to Speech.
            VoiceLog.log("system ASR rejected: asset load failed for \(url.lastPathComponent): \(error.localizedDescription)")
            throw VoiceProviderError.noAudioData
        }
    }

    // MARK: - Voice input (offline SFSpeechRecognizer)

    func transcribe(_ request: VoiceInputRequest) async throws -> VoiceInputResponse {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            throw VoiceProviderError.unsupported("Speech recognition permission not granted")
        }
        // Reuse a cached (pre-warmed) recognizer for the requested language so
        // the first request after entering voice mode isn't cold.
        let loc = resolveLocale(forLanguage: request.language)
        guard let recognizer = cachedRecognizer(for: loc), recognizer.isAvailable else {
            throw VoiceProviderError.unsupported("System speech recognizer unavailable for this language")
        }

        // Decide on-device vs server. The Offline model forces on-device; Online
        // forces server; .auto (nil) prefers on-device when the recognizer supports
        // it for this locale. On-device that isn't supported here would fail, so we
        // only set the flag when the recognizer reports support — otherwise fall back
        // to server so recognition still works (Offline for an unsupported language
        // degrades to Online rather than erroring).
        let wantOnDevice = request.onDeviceRecognition ?? recognizer.supportsOnDeviceRecognition
        let useOnDevice = wantOnDevice && recognizer.supportsOnDeviceRecognition

        // [T-ios-speech-no-audio-track] Reject an empty/undersized buffer before
        // it ever becomes a file. A WAV header alone is 44 bytes and carries no
        // samples; anything at or below that cannot contain a decodable track,
        // and handing such a file to Speech aborts the process (see the asset
        // check below for why this cannot be caught).
        guard request.audioData.count > Self.minimumDecodableAudioBytes else {
            VoiceLog.log("system ASR rejected: audioData \(request.audioData.count) bytes is too small to contain audio")
            throw VoiceProviderError.noAudioData
        }

        // SFSpeechRecognizer needs a file URL, so spill the WAV to tmp.
        let tmpURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice_input_\(UUID().uuidString).wav")
        do {
            try request.audioData.write(to: tmpURL)
        } catch {
            throw VoiceProviderError.parseError("Failed to write temp audio file")
        }
        defer { try? FileManager.default.removeItem(at: tmpURL) }

        // [T-ios-speech-no-audio-track] The write above succeeding does NOT mean
        // the file is usable: a truncated or header-only buffer writes fine and
        // still has no decodable audio track. SFSpeechURLRecognitionRequest then
        // reaches -[AVAssetReaderAudioMixOutput initWithAudioTracks:], which
        // raises NSInvalidArgumentException on an empty track array — on Speech's
        // OWN dispatch queue, so it unwinds to _objc_terminate and aborts the
        // process. It is not catchable from here, which is why this has to be
        // checked up front rather than wrapped in a do/catch.
        try await validateHasAudioTrack(at: tmpURL)

        let recognitionRequest = SFSpeechURLRecognitionRequest(url: tmpURL)
        // Report partial results so we can salvage text if the recognizer never
        // emits a final result — which it can fail to do on the FIRST request or
        // two after a cold start (the on-device model isn't warm yet), leaving
        // the continuation hung and the utterance silently lost. With partials +
        // the watchdog below, we always resume with the best text we got.
        recognitionRequest.shouldReportPartialResults = true
        recognitionRequest.requiresOnDeviceRecognition = useOnDevice
        recognitionRequest.addsPunctuation = true          // iOS 16+ auto punctuation
        recognitionRequest.taskHint = .dictation           // optimize for free-form speech
        VoiceLog.log("system ASR loc=\(loc.identifier) onDevice=\(useOnDevice) (wanted=\(request.onDeviceRecognition.map(String.init(describing:)) ?? "auto"), supports=\(recognizer.supportsOnDeviceRecognition))")
        if let prompt = request.prompt {
            recognitionRequest.contextualStrings = [prompt]
        }

        return try await withCheckedThrowingContinuation { continuation in
            let lock = NSLock()
            var didResume = false
            var latestText = ""
            var task: SFSpeechRecognitionTask?

            func finish(_ result: Result<String, Error>) {
                lock.lock()
                if didResume { lock.unlock(); return }
                didResume = true
                lock.unlock()
                task?.cancel()
                switch result {
                case .success(let text):
                    continuation.resume(returning: VoiceInputResponse(text: text, language: nil, duration: nil))
                case .failure(let err):
                    continuation.resume(throwing: err)
                }
            }

            task = recognizer.recognitionTask(with: recognitionRequest) { result, error in
                if let result {
                    let text = result.bestTranscription.formattedString
                    lock.lock(); if !text.isEmpty { latestText = text }; lock.unlock()
                    if result.isFinal { finish(.success(text)); return }
                }
                if let error {
                    // If we already captured partial text, salvage it rather than
                    // failing — a cold-start recognizer often errors out after a
                    // partial without ever delivering a final.
                    lock.lock(); let salvage = latestText; lock.unlock()
                    if !salvage.isEmpty { finish(.success(salvage)) }
                    else { finish(.failure(VoiceProviderError.parseError(error.localizedDescription))) }
                }
            }

            // Watchdog: never hang. After 8s, finish with whatever partial text
            // we have (empty → treated as a no-op utterance upstream).
            DispatchQueue.global().asyncAfter(deadline: .now() + 8) {
                lock.lock(); let salvage = latestText; lock.unlock()
                finish(.success(salvage))
            }
        }
    }

    // MARK: - Voice output (AVSpeechSynthesizer)

    /// Render the utterance to WAV Data via the iOS 13+ write API. The
    /// completion is signalled by a final zero-length buffer.
    func synthesize(_ request: VoiceOutputRequest) async throws -> Data {
        let utterance = AVSpeechUtterance(string: request.input)
        utterance.rate  = mapSpeed(request.speed ?? 1.0)
        utterance.voice = Self.resolveVoice(request: request, fallbackLocale: locale)
        utterance.pitchMultiplier = SystemVoicePreferences.pitch
        utterance.volume = SystemVoicePreferences.volume

        // A dedicated synthesizer per write() call — the shared `synthesizer` is
        // used for live speak(); mixing write() onto it can drop callbacks.
        let writer = AVSpeechSynthesizer()
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            // Collect the raw synthesizer buffers, then serialize them to ONE WAV
            // (a per-buffer header produced several concatenated WAVs → AVAudioPlayer
            // played almost nothing — the old "Quick Test made no sound" bug).
            //
            // The write() callback is NOT guaranteed to be serialized on one thread,
            // and the zero-length end sentinel can race with a trailing data buffer,
            // so `didResume`/`buffers` are guarded by a lock — resuming the checked
            // continuation twice would hard-crash (mirrors transcribe()'s pattern).
            let lock = NSLock()
            var buffers: [AVAudioPCMBuffer] = []
            var didResume = false
            writer.write(utterance) { buffer in
                lock.lock()
                if didResume { lock.unlock(); return }
                guard let pcmBuffer = buffer as? AVAudioPCMBuffer else { lock.unlock(); return }
                if pcmBuffer.frameLength == 0 {
                    didResume = true
                    let collected = buffers
                    lock.unlock()
                    _ = writer   // keep the writer alive until the end sentinel
                    do {
                        continuation.resume(returning: try Self.encodeWAV(from: collected))
                    } catch {
                        continuation.resume(throwing: error)
                    }
                    return
                }
                buffers.append(pcmBuffer)
                lock.unlock()
            }
        }
    }

    /// Serialize synthesizer PCM buffers into ONE canonical 16-bit PCM WAV.
    /// Converts every buffer to interleaved Int16 at a fixed rate via AVAudioConverter,
    /// collects the raw sample bytes, and writes a single 44-byte header + data chunk.
    /// (AVAudioFile(forWriting:.wav) produced a container AVAudioPlayer read as
    /// 0-duration → silent; a hand-written canonical header plays reliably.)
    private static func encodeWAV(from buffers: [AVAudioPCMBuffer]) throws -> Data {
        guard let first = buffers.first else {
            throw VoiceProviderError.parseError("System TTS produced no audio")
        }
        let srcFormat = first.format
        let sampleRate = srcFormat.sampleRate > 0 ? Int(srcFormat.sampleRate) : 22050
        let channels = srcFormat.channelCount > 0 ? Int(srcFormat.channelCount) : 1
        guard let dstFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16, sampleRate: Double(sampleRate),
            channels: AVAudioChannelCount(channels), interleaved: true) else {
            throw VoiceProviderError.parseError("System TTS: bad audio format")
        }

        var pcm = Data()
        func appendInt16(_ buf: AVAudioPCMBuffer) {
            guard let ch = buf.int16ChannelData, buf.frameLength > 0 else { return }
            pcm.append(UnsafeBufferPointer(start: ch[0], count: Int(buf.frameLength) * channels))
        }

        // Fast path: buffers already in the target format (rare — AVSpeech emits
        // Float32) → copy directly.
        if srcFormat == dstFormat {
            for buf in buffers { appendInt16(buf) }
        } else if let converter = AVAudioConverter(from: srcFormat, to: dstFormat) {
            // Drive ONE converter over the whole buffer stream via an input block
            // that hands out each source buffer once, then signals end-of-stream —
            // so the resampler/format-converter's internally-buffered tail frames are
            // flushed instead of lost (feeding one buffer + immediate .noDataNow per
            // call dropped the tail). Pull output until the converter is drained.
            var idx = 0
            let inputBlock: AVAudioConverterInputBlock = { _, status in
                if idx < buffers.count {
                    let b = buffers[idx]; idx += 1
                    status.pointee = .haveData
                    return b
                }
                status.pointee = .endOfStream
                return nil
            }
            // Generous output capacity per pull (rate ratio + slack).
            let ratio = dstFormat.sampleRate / srcFormat.sampleRate
            let totalIn = buffers.reduce(0) { $0 + Int($1.frameLength) }
            let cap = AVAudioFrameCount(Double(max(totalIn, 1)) * ratio) + 4096
            while true {
                guard let outBuf = AVAudioPCMBuffer(pcmFormat: dstFormat, frameCapacity: cap) else { break }
                var convError: NSError?
                let statusOut = converter.convert(to: outBuf, error: &convError, withInputFrom: inputBlock)
                if let convError { throw convError }
                if outBuf.frameLength > 0 { appendInt16(outBuf) }
                if statusOut == .endOfStream || statusOut == .error { break }
                if outBuf.frameLength == 0 { break }  // safety: no progress
            }
        }

        let dataSize = UInt32(pcm.count)
        var wav = Data()
        wav.append(contentsOf: Array("RIFF".utf8))
        wav.appendUInt32LE(36 + dataSize)
        wav.append(contentsOf: Array("WAVE".utf8))
        wav.append(contentsOf: Array("fmt ".utf8))
        wav.appendUInt32LE(16)                                  // PCM fmt chunk size
        wav.appendUInt16LE(1)                                   // PCM
        wav.appendUInt16LE(UInt16(channels))
        wav.appendUInt32LE(UInt32(sampleRate))
        wav.appendUInt32LE(UInt32(sampleRate * channels * 2))   // byte rate
        wav.appendUInt16LE(UInt16(channels * 2))                // block align
        wav.appendUInt16LE(16)                                  // bits/sample
        wav.append(contentsOf: Array("data".utf8))
        wav.appendUInt32LE(dataSize)
        wav.append(pcm)
        return wav
    }

    /// Speak directly without producing Data (lowest-latency playback path).
    func speak(_ text: String, speed: Float = 1.0) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate  = mapSpeed(speed)
        utterance.voice = AVSpeechSynthesisVoice(language: locale.identifier)
            ?? AVSpeechSynthesisVoice(language: "zh-CN")
        synthesizer.speak(utterance)
    }

    /// A short sample phrase matching a voice's language, for Quick Test speech
    /// output — so a Chinese/Japanese/Korean voice speaks native text instead of
    /// an English string. Falls back to English for everything else.
    static func sampleText(forLanguage langCode: String?) -> String {
        let lc = (langCode ?? "en").lowercased()
        if lc.hasPrefix("zh") { return "你好，这是 Minis 的语音测试。" }
        if lc.hasPrefix("ja") { return "こんにちは、これは Minis の音声テストです。" }
        if lc.hasPrefix("ko") { return "안녕하세요, Minis 음성 테스트입니다." }
        return "Hi! This is Minis testing this voice."
    }

    func stopSpeaking() {
        synthesizer.stopSpeaking(at: .immediate)
    }

    /// Resolve the AVSpeechSynthesisVoice for a request. The `model` (or `voice`)
    /// field carries the selected voice identifier (see SystemVoiceCatalog) — an
    /// entry like "com.apple.voice.compact.zh-CN.Tingting". When present and
    /// valid, use exactly that voice. Otherwise fall back to the provider's
    /// language default (preserving the pre–Stage-1 hardcoded behavior), then a
    /// final zh-CN safety net. The sentinel/composite ids ("__builtin_system_speech__"
    /// or "…/system-tts") are NOT voice identifiers → they fall through to the
    /// language default.
    static func resolveVoice(request: VoiceOutputRequest, fallbackLocale: Locale) -> AVSpeechSynthesisVoice? {
        let candidate = request.model ?? request.voice
        if let id = candidate, id.contains("."),
           let voice = AVSpeechSynthesisVoice(identifier: id) {
            return voice
        }
        return AVSpeechSynthesisVoice(language: fallbackLocale.identifier)
            ?? AVSpeechSynthesisVoice(language: "zh-CN")
    }

    /// AVSpeechUtterance rate is 0.0...1.0; map from OpenAI-style speed 0.25...4.0.
    private func mapSpeed(_ speed: Float) -> Float {
        let normalized = max(0.0625, min(1.0, speed / 4.0))  // 0.25..4.0 -> ~0.06..1.0
        return normalized * AVSpeechUtteranceMaximumSpeechRate
    }
}

// MARK: - Little-endian WAV header helpers

private extension Data {
    mutating func appendUInt32LE(_ value: UInt32) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
    mutating func appendUInt16LE(_ value: UInt16) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
}

