import Foundation
import AVFoundation
import RealTimeCutVADLibrary

// MARK: - Voice Activity Detection
//
// Captures microphone audio through AVAudioEngine and feeds it to
// RealTimeCutVADLibrary (Silero VAD v5 + WebRTC APM). The library emits:
//   - voiceStarted               -> someone began speaking
//   - voiceEnded(withWavData:)   -> utterance finished; 16 kHz WAV ready
//   - voiceDidContinue(...)      -> live denoised PCM float (drives waveform)

// MARK: - Delegate

enum SegmentEndReason {
    case silenceDetected
    case maxLengthReached
    case manualFlush
}

protocol VoiceActivityDelegate: AnyObject {
    /// VAD detected the onset of speech.
    func voiceActivityDidStart()
    /// A speech segment ended; `audioData` is a complete 16 kHz WAV.
    /// `reason` distinguishes silence-triggered ends (mic should stop)
    /// from max-length forced flushes (mic should keep recording).
    func voiceActivityDidEnd(audioData: Data, reason: SegmentEndReason)
    /// Live PCM float during speech (optional; for waveform / preview).
    func voiceActivityDidUpdate(pcmData: Data)
    /// Capture was interrupted (call/Siri/route) and could NOT be auto-resumed —
    /// the detector has fully stopped; the UI should return to idle.
    func voiceActivityInterrupted()
}

extension VoiceActivityDelegate {
    func voiceActivityDidStart() {}
    func voiceActivityDidUpdate(pcmData: Data) {}
    func voiceActivityInterrupted() {}
}

// MARK: - VoiceActivityDetector

final class VoiceActivityDetector: NSObject {

    weak var delegate: VoiceActivityDelegate?

    private(set) var isRunning = false

    /// [T-voice-interrupt-cannot-restart] Whether audio is ACTUALLY being
    /// captured right now, as opposed to `isRunning`, which stays true across an
    /// interruption so the capture can be resumed.
    ///
    /// Callers deciding "should this tap start or stop?" must use this: a
    /// capture interrupted by a call whose `.ended` never arrived is `isRunning`
    /// but not capturing, and treating it as live made the mic button act as a
    /// stop for a recording that no longer existed.
    var isCapturing: Bool { isRunning && audioEngine.isRunning }
    private(set) var isSpeaking = false

    private let logger = AppLogger(category: "VAD")
    /// [T-voice-mic-stale-engine] `var`, not `let`: the engine has to be
    /// REPLACEABLE.
    ///
    /// `AVAudioEngine.inputNode` binds to the hardware input the first time it
    /// is touched, and that binding is cached on the engine for its whole life.
    /// When the app is backgrounded the session is deactivated (and the
    /// keep-alive track re-activates it as `.playback`, which has no input), so
    /// the cached binding goes stale. `stop()` + `removeTap` do NOT re-derive
    /// it, so every later `inputFormat(forBus: 0)` on this engine keeps
    /// answering 0ch / 0Hz even though the session is back on `.record` and
    /// activated — which is exactly what `start()` then reports as
    /// "Microphone input unavailable".
    ///
    /// Only a fresh AVAudioEngine re-binds. That is why the bug looked
    /// session-scoped: `VoiceInputPanel` owns the detector, so leaving and
    /// re-entering a chat built a new panel → new engine → working mic, while
    /// staying put reused the poisoned one forever.
    private var audioEngine = AVAudioEngine()
    private let vadQueue = DispatchQueue(label: "com.openminis.app.vad", qos: .userInteractive)
    private var vad: VADWrapper?

    // MARK: - Adaptive gain (noise-aware AGC)
    /// Smoothed gain carried frame-to-frame so it doesn't jump on transients.
    private var smoothedGain: Float = 1.0
    /// Tracked background-noise floor (slow-moving RMS estimate). Frames whose RMS
    /// sits near this floor are treated as noise/silence and NOT amplified — that
    /// stops the old `0.5/peak` AGC from boosting room noise up into the VAD's
    /// speech range and causing false speech-start triggers.
    private var noiseFloorRMS: Float = 0.003
    /// RMS must exceed `noiseFloorRMS * gateRatio` for a frame to be considered
    /// (possible) speech and eligible for gain. Below it → gain held at 1.
    /// 3.0 → 2.5 (2026-07-18, quiet-speech cutoff fix): soft speech sitting
    /// just under 3× floor never got AGC gain, which kept Silero probabilities
    /// at the start threshold and delayed/blocked segment starts.
    private static let gateRatio: Float = 2.5
    /// Target RMS the AGC aims speech frames toward; gain clamped to [1, maxGain].
    private static let agcTargetRMS: Float = 0.12
    private static let maxGain: Float = 6.0

    /// Rolling capture of the raw mic samples since the current segment began,
    /// used to (a) flush the in-progress audio to recognition when the user
    /// pauses manually, and (b) auto-emit a segment once it reaches the 60s cap.
    /// The VAD library has no flush API, so we keep our own copy. Guarded by
    /// `captureLock` since it's touched from both the audio tap and the main queue.
    private var captureSamples: [Float] = []
    private var captureSampleRate: Double = 48000
    private let captureLock = NSLock()
    /// Max single-segment length (seconds). 60s for System ASR (Apple's
    /// SFSpeechRecognizer limit per request). Cloud providers (Doubao, MiMo,
    /// etc.) support much longer audio — set to a very large value so segments
    /// accumulate freely until silence or the 5-minute total cap.
    var maxSegmentSeconds: Double = 60

    /// Max seconds of already-heard audio back-filled into a segment when the
    /// VAD confirms speech start. Start confirmation is inherently late —
    /// ≥10 frames (~0.32 s) for clear speech and OVER A SECOND for quiet
    /// speech whose per-frame probability hovers at the threshold — and any
    /// audio older than the back-fill window is lost to the segment. The old
    /// fixed 0.3 s pre-roll only covered the best case; device forensics
    /// (debug.voiceInputs) showed every capture starting mid-syllable with
    /// 0 ms of leading silence. Back-fill is bounded by this cap AND by how
    /// much audio arrived since the previous segment ended, so it can never
    /// swallow the prior segment's tail.
    private static let startBackfillSeconds: Double = 1.5
    /// Samples appended to `rawAudioBuffer` while NOT inside a speech segment
    /// since the last segment ended — the safe back-fill horizon.
    private var samplesSinceSegmentEnd: Int = 0
    /// Fallback buffer: ALL audio samples since start(), regardless of VAD state.
    /// Used when user taps stop but VAD never detected speech — we still have the audio.
    /// Capped at 5 minutes worth of samples.
    private var rawAudioBuffer: [Float] = []
    private var rawAudioStartTime: TimeInterval = 0
    /// Timestamp (seconds since boot) when the current segment's speech started.
    private var segmentStartTime: TimeInterval = 0

    #if DEBUG
    /// [T-debug-full-session-capture] Every sample seen between start() and
    /// stop(), with NO VAD trimming and no 5-minute rolling eviction — the
    /// un-cut reference recording you compare the ASR payloads against to judge
    /// whether the VAD's cut points are right. DEBUG only, so Release pays no
    /// memory for it. Guarded by `captureLock` like the other buffers.
    private var fullSessionSamples: [Float] = []
    /// Hard ceiling so a forgotten-open mic can't grow this without bound.
    /// 10 minutes @ 48 kHz ≈ 28.8M floats ≈ 115 MB; past it we stop appending
    /// (rather than evicting) so the recording's true START is always preserved
    /// — the start is exactly what this buffer exists to verify.
    private static let fullSessionMaxSeconds: Double = 600
    private var fullSessionTruncated = false
    /// Identifies one start()→stop() recording. Stamped onto every segment
    /// captured during the session so `debug.voiceInputs` can map the ASR
    /// payloads back to this session's un-cut audio.
    private(set) var currentSessionId: String?
    #endif

    struct Configuration {
        /// Frames to confirm speech start (default 10 ≈ 0.32 s).
        var voiceStartFrameCount: Int32 = 10
        /// Frames to confirm speech end (~32 ms/frame). 156 ≈ 5 s of trailing
        /// silence: the VAD closes a segment after a ~5 s pause. On segment
        /// delivery the panel auto-stops (equivalent to a manual tap-to-stop).
        var voiceEndFrameCount: Int32 = 156
        // Speech-start was too strict on device: at 0.7 probability + 0.8 true
        // ratio the VAD logged speechDetected=false across hundreds of frames
        // while the user was actually speaking, so no segment was ever emitted
        // and nothing got transcribed. Relax start detection to the library's
        // gentler defaults (0.5 prob / 0.5 ratio) so normal speech reliably
        // trips; keep end detection conservative so we don't cut words off.
        var startProbability: Float = 0.5
        var endProbability: Float = 0.5
        var voiceStartTrueRatio: Float = 0.5
        var voiceEndFalseRatio: Float = 0.95
        init() {}
    }

    private let configuration: Configuration

    /// Whether an AVAudioSession interruption (call/Siri/other app) stopped us and
    /// we should auto-resume when it ends. Distinguishes a system interruption from
    /// a deliberate user stop (which clears observers, so no resume fires).
    private var interruptedWhileRunning = false
    private var observersRegistered = false

    init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
        super.init()
    }

    deinit { unregisterSessionObservers() }

    // MARK: - Control

    /// Begin listening. Caller must hold microphone permission first.
    func start() throws {
        // [T-voice-interrupt-cannot-restart] `isRunning` alone is NOT proof that
        // capture is live, so this must not be a bare `guard !isRunning`.
        //
        // An interruption (FaceTime, a phone call, Siri) deliberately leaves
        // `isRunning == true` with the engine torn down, because that is what
        // lets `.ended` resume the same capture. But `.ended` is not guaranteed
        // to arrive: iOS does not deliver it to a suspended app, and answering
        // a FaceTime call backgrounds us — so the detector could sit wedged as
        // "running" with no engine forever. Every later tap then hit the old
        // `guard !isRunning else { return }` and returned SILENTLY: no capture,
        // no throw, no error for the panel to show. That is the reported
        // "after a call interrupts it, voice input never works again", and the
        // silence is why it looked like a dead button rather than a failure.
        //
        // The same wedge is reachable without backgrounding at all, via the
        // spurious-interruption branch in `handleInterruption`: if `.began`
        // arrives while `audioEngine.isRunning` is still true we ignore it, and
        // if the system then stops the engine we never get a second `.began`.
        //
        // So: trust the ENGINE, not the flag. If we believe we are running but
        // the engine is not, the previous capture is dead — reclaim it and
        // start fresh rather than refusing.
        if isRunning {
            guard !audioEngine.isRunning else { return }   // genuinely capturing
            VoiceLog.log("start(): stale isRunning with a stopped engine (missed interruption end?) — reclaiming")
            logger.warning("start() found stale running state — tearing down before restart")
            tearDown()
        }
        try configureSession()

        // [T-voice-vad-start-leak] configureSession() has now taken two pieces
        // of global state — it bumped BackgroundKeepAliveManager's
        // silentAudioSuspendCount and declared the `.capture` intent on
        // AudioSessionCoordinator. Every later step can throw, and the callers'
        // catch blocks only reset UI state, so any failure past this point used
        // to leave both permanently claimed.
        //
        // Observed with the mic held by another app (issue #283 logs): six
        // consecutive `suspendSilentAudioForMedia: count 2→…→8`, all
        // caller=VAD.capture, against a single resume — every failed retry
        // leaked one more suspend. If the user gave up before a retry
        // succeeded, the count never returned to 0, so the background
        // keep-alive track could not resume, and a stuck `.capture` intent
        // outranked later intents such as reply TTS.
        //
        // A `defer` keyed on `isRunning` covers EVERY exit — the throws below,
        // the ObjC-exception branch, and any future step added before the end
        // of this function — instead of relying on each new branch to remember
        // to clean up. `tearDown()` is safe here even though setup is only
        // half-done: `audioEngine.stop()` on a stopped engine is a no-op,
        // `removeTap` is wrapped in noff_try_objc, `vad` is optional, and both
        // rollback calls are idempotent (`end()` no-ops when the intent is not
        // held; `resumeSilentAudioForMedia` clamps at 0). On the success path
        // `isRunning` is true and the defer does nothing.
        var startedCleanly = false
        defer {
            if !startedCleanly {
                VoiceLog.log("start() failed — rolling back session state")
                tearDown()
            }
        }

        try setupEngineAndVAD()
        // `audioEngine.start()` can throw an Objective-C NSException (not a Swift
        // Error) when the engine/route is in a bad state — Swift `try` can't
        // catch it and it crashes via SIGABRT. Wrap in noff_try_objc.
        var engineError: Error?
        let started = noff_try_objc {
            do { try self.audioEngine.start() } catch { engineError = error }
        }
        if let engineError {
            VoiceLog.log("audioEngine start failed: \(engineError.localizedDescription)")
            throw engineError
        }
        guard started else {
            VoiceLog.log("audioEngine start threw ObjC exception")
            // Rollback handled by the defer above — calling tearDown() here too
            // would run it twice (harmless, both halves are idempotent, but it
            // double-logs and obscures which path actually cleaned up).
            throw VoiceProviderError.parseError("Audio engine failed to start")
        }
        VoiceLog.log("audioEngine started OK; isRunning=true")
        rawAudioBuffer.removeAll(keepingCapacity: true)
        samplesSinceSegmentEnd = 0
        rawAudioStartTime = ProcessInfo.processInfo.systemUptime
        #if DEBUG
        captureLock.lock()
        fullSessionSamples.removeAll(keepingCapacity: true)
        fullSessionTruncated = false
        captureLock.unlock()
        let sessionId = UUID().uuidString
        currentSessionId = sessionId
        VoiceInputCapture.shared.beginSession(id: sessionId)
        #endif
        isRunning = true
        startedCleanly = true   // [T-voice-vad-start-leak] disarm the rollback
        interruptedWhileRunning = false
        registerSessionObservers()
        logger.info("VAD started")
    }

    // MARK: - Interruption / route-change recovery (C2)

    private func registerSessionObservers() {
        guard !observersRegistered else { return }
        observersRegistered = true
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(handleInterruption(_:)),
                       name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(handleRouteChange(_:)),
                       name: AVAudioSession.routeChangeNotification, object: nil)
    }

    private func unregisterSessionObservers() {
        guard observersRegistered else { return }
        observersRegistered = false
        let nc = NotificationCenter.default
        nc.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        nc.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
    }

    @objc private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            if isRunning {
                // [T-voice-interrupt-cannot-restart] Arm the resume FIRST, even
                // when the engine still looks alive. iOS posts `.began` before
                // it has necessarily stopped our engine, so the old code read
                // "engine still running" as "spurious", returned, and left
                // `interruptedWhileRunning == false`. The matching `.ended` then
                // had nothing to resume, and no second `.began` ever came — the
                // detector stayed "running" with a dead engine and every later
                // tap no-oped. Arming is safe: `.ended` only resumes when the
                // engine is actually down, and `attemptResume` rebuilds from
                // scratch either way.
                interruptedWhileRunning = true
                if audioEngine.isRunning {
                    VoiceLog.log("audio interruption BEGAN — engine still running; armed resume and keeping capture for now")
                    return
                }
                VoiceLog.log("audio interruption BEGAN — engine stopped, pausing capture")
                tearDownEngineOnly()
            }
        case .ended:
            let opts = (info[AVAudioSessionInterruptionOptionKey] as? UInt).map {
                AVAudioSession.InterruptionOptions(rawValue: $0)
            } ?? []
            let shouldResume = opts.contains(.shouldResume)
            VoiceLog.log("audio interruption ENDED (shouldResume=\(shouldResume), wasRunning=\(interruptedWhileRunning))")
            if interruptedWhileRunning {
                interruptedWhileRunning = false
                // The engine can still be alive here — `.began` now arms the
                // resume even when the engine survived, so an interruption that
                // never actually stopped us needs no rebuild.
                if isRunning, audioEngine.isRunning {
                    VoiceLog.log("audio interruption ENDED — engine never stopped, nothing to rebuild")
                } else {
                    attemptResume()
                }
            }
        @unknown default:
            break
        }
    }

    @objc private func handleRouteChange(_ note: Notification) {
        // A route change (e.g. AirPods unplugged) can stop the engine. If we think
        // we're running but the engine actually stopped, rebuild it.
        guard isRunning, !audioEngine.isRunning else { return }
        VoiceLog.log("route change while running but engine stopped — resuming")
        attemptResume()
    }

    /// Rebuild the engine/tap/VAD after an interruption, preserving isRunning.
    private func attemptResume() {
        // Tear any partial state, then re-run the start internals.
        tearDownEngineOnly()
        do {
            // [T-voice-interrupt-cannot-restart] Release the session claims this
            // capture already holds before taking them again. `configureSession`
            // bumps BackgroundKeepAliveManager's suspend count and begins the
            // `.capture` intent; `tearDownEngineOnly` deliberately keeps both
            // (it is the resume path), so each interrupt→resume cycle used to
            // add one more unbalanced suspend — the same leak
            // [T-voice-vad-start-leak] fixed for the start path. Repeated
            // call/Siri interruptions during one recording could therefore pin
            // the count above zero for the rest of the app's life, keeping the
            // background keep-alive track from ever resuming.
            releaseSessionClaims()
            try configureSession()
            try setupEngineAndVAD()
            var engineError: Error?
            let started = noff_try_objc {
                do { try self.audioEngine.start() } catch { engineError = error }
            }
            if engineError != nil || !started {
                VoiceLog.log("resume failed to restart engine")
                fullStopFromInterruption()
                return
            }
            isRunning = true
            VoiceLog.log("capture resumed after interruption")
        } catch {
            VoiceLog.log("resume error: \(error.localizedDescription)")
            fullStopFromInterruption()
        }
    }

    /// Tear down ONLY the engine/tap/VAD (keeps observers + isRunning semantics for
    /// resume). Used on interruption-began and before a resume rebuild.
    /// Preserves captureSamples and isSpeaking so segment continuity survives
    /// across interrupt→resume cycles (the new VAD will re-detect speech and
    /// voiceStarted() will append to existing samples rather than resetting).
    private func tearDownEngineOnly() {
        audioEngine.stop()
        _ = noff_try_objc { self.audioEngine.inputNode.removeTap(onBus: 0) }
        vad?.delegate = nil
        vad = nil
        // Don't clear rawAudioBuffer here — it may be needed for fallback flush
        // Don't reset isSpeaking or captureSamples — preserve segment continuity
    }

    /// Could not recover — fully stop and notify the delegate so the UI can reflect
    /// that capture ended (button returns to idle instead of a frozen "Listening").
    private func fullStopFromInterruption() {
        tearDown()
        #if DEBUG
        publishFullSessionRecording()
        #endif
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityInterrupted()
        }
    }

    func stop() {
        guard isRunning else { return }
        tearDown()
        #if DEBUG
        publishFullSessionRecording()
        #endif
        logger.info("VAD stopped")
    }

    #if DEBUG
    /// [T-debug-full-session-capture] Encode the whole start()→stop() recording
    /// and hand it to `VoiceInputCapture`, which back-fills it onto every ASR
    /// payload captured under this session id. Called after `tearDown()` so the
    /// tap is already detached and the buffer is final. Encoding runs off the
    /// caller's thread — a 10-minute buffer is ~29M samples and the WAV pass is
    /// far too slow to sit on the stop path.
    private func publishFullSessionRecording() {
        guard let sessionId = currentSessionId else { return }
        currentSessionId = nil
        captureLock.lock()
        let samples = fullSessionSamples
        let rate = captureSampleRate
        let truncated = fullSessionTruncated
        fullSessionSamples.removeAll(keepingCapacity: false)
        captureLock.unlock()
        guard !samples.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async {
            guard let wav = Self.wavData(fromFloatSamples: samples, sampleRate: rate) else { return }
            let seconds = Double(samples.count) / rate
            VoiceLog.log(String(format: "full-session capture: %.2fs (%d samples) → %d bytes WAV truncated=%@",
                                seconds, samples.count, wav.count, truncated ? "yes" : "no"))
            VoiceInputCapture.shared.attachFullSessionAudio(sessionId: sessionId,
                                                           audioData: wav,
                                                           durationSeconds: seconds,
                                                           truncated: truncated)
        }
    }
    #endif

    /// Flush any in-progress speech to recognition NOW (e.g. the user tapped
    /// pause). Builds a WAV from the captured samples and delivers it through the
    /// same delegate path as a natural VAD segment end. No-op if nothing captured.
    func flush() {
        flushCapturedSegment(reason: .manualFlush)
    }

    /// Fallback flush: use the raw audio buffer when VAD never detected speech.
    /// Returns true if fallback audio was delivered, false if nothing to deliver.
    @discardableResult
    func flushRawFallback() -> Bool {
        captureLock.lock()
        let samples = rawAudioBuffer
        let rate = captureSampleRate
        rawAudioBuffer.removeAll(keepingCapacity: true)
        captureLock.unlock()
        let duration = Double(samples.count) / rate
        guard samples.count > Int(rate * 0.5) else {
            VoiceLog.log("flushRawFallback: too short (\(String(format: "%.2f", duration))s < 0.5s), skipping")
            return false
        }
        guard let wav = Self.wavData(fromFloatSamples: samples, sampleRate: rate) else {
            VoiceLog.log("flushRawFallback: WAV encoding failed")
            return false
        }
        VoiceLog.log("flushRawFallback: VAD missed speech — delivering \(samples.count) samples (\(String(format: "%.1f", duration))s) → \(wav.count) bytes WAV")
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityDidEnd(audioData: wav, reason: .manualFlush)
        }
        return true
    }

    /// How long the mic has been running since start()
    var runningDuration: TimeInterval {
        guard isRunning else { return 0 }
        return ProcessInfo.processInfo.systemUptime - rawAudioStartTime
    }

    /// Whether the raw fallback buffer has meaningful audio (> 0.5s)
    var hasRawFallbackAudio: Bool {
        captureLock.lock()
        let count = rawAudioBuffer.count
        let rate = captureSampleRate
        captureLock.unlock()
        return Double(count) / rate > 0.5
    }

    private func flushCapturedSegment(reason: SegmentEndReason) {
        // A manual flush (user tapped pause) is NOT routed through the VAD
        // library, so `voiceEnded()` never fires and `isSpeaking` stays true —
        // the audio tap then keeps appending frames into `captureSamples` the
        // instant this method releases `captureLock`, leaving ~0.1s of
        // post-flush audio behind. That residue made the NEXT recording's
        // `voiceStarted()` take the `alreadyHasSamples` (resume) branch and skip
        // the 1.5s start back-fill, clipping the opening syllables. Close the
        // speech state here so the tap stops contributing.
        // `.maxLengthReached` deliberately does NOT clear it: that flush cuts a
        // 60s chunk out of an utterance that is still in progress, and the tap
        // must keep filling the next chunk (the library won't re-fire
        // `voiceStarted()` until the next silence→speech transition).
        // `.silenceDetected` never reaches here — `voiceEnded()` flushes inline
        // and clears `isSpeaking` itself.
        if reason == .manualFlush { isSpeaking = false }
        captureLock.lock()
        let samples = captureSamples
        let rate = captureSampleRate
        captureSamples.removeAll(keepingCapacity: true)
        samplesSinceSegmentEnd = 0   // next back-fill horizon starts here
        captureLock.unlock()
        guard samples.count > Int(rate * 0.2) else { return }
        guard let wav = Self.wavData(fromFloatSamples: samples, sampleRate: rate) else { return }
        VoiceLog.log("flush segment (\(reason)): \(samples.count) samples → \(wav.count) bytes WAV")
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityDidEnd(audioData: wav, reason: reason)
        }
    }

    /// Encode mono Float32 samples to a 16-bit PCM WAV at the given sample rate.
    private static func wavData(fromFloatSamples samples: [Float], sampleRate: Double) -> Data? {
        guard !samples.isEmpty else { return nil }
        var pcm16 = [Int16](repeating: 0, count: samples.count)
        for i in 0..<samples.count {
            let clamped = max(-1.0, min(1.0, samples[i]))
            pcm16[i] = Int16(clamped * 32767)
        }
        let pcmData = pcm16.withUnsafeBytes { Data($0) }
        let dataSize = UInt32(pcmData.count)
        let sr = UInt32(sampleRate)
        let byteRate = UInt32(sampleRate) * 2          // mono, 2 bytes/sample
        let blockAlign: UInt16 = 2

        var wav = Data()
        func appendLE32(_ v: UInt32) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 4)) }
        func appendLE16(_ v: UInt16) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 2)) }
        wav.append("RIFF".data(using: .ascii)!)
        appendLE32(36 + dataSize)
        wav.append("WAVE".data(using: .ascii)!)
        wav.append("fmt ".data(using: .ascii)!)
        appendLE32(16)            // subchunk1 size
        appendLE16(1)             // PCM
        appendLE16(1)             // mono
        appendLE32(sr)
        appendLE32(byteRate)
        appendLE16(blockAlign)
        appendLE16(16)            // bits/sample
        wav.append("data".data(using: .ascii)!)
        appendLE32(dataSize)
        wav.append(pcmData)
        return wav
    }

    /// Tear the engine/tap/VAD down. Safe to call whether or not we're running.
    private func tearDown() {
        unregisterSessionObservers()
        interruptedWhileRunning = false
        audioEngine.stop()
        _ = noff_try_objc { self.audioEngine.inputNode.removeTap(onBus: 0) }
        vad?.delegate = nil
        vad = nil
        isRunning = false
        isSpeaking = false
        // Belt-and-braces: drop any segment residue so the NEXT start() begins
        // from a clean slate. Even with the manual-flush fix above, an in-flight
        // tap callback can land between the flush and the engine actually
        // stopping; whatever it appended belongs to the finished recording, not
        // the next one. Leaving it behind is what made `voiceStarted()` mistake a
        // brand-new recording for an interruption resume and skip the back-fill.
        // (`tearDownEngineOnly()` intentionally does NOT do this — interruption
        // resume relies on the samples surviving.)
        captureLock.lock()
        captureSamples.removeAll(keepingCapacity: true)
        samplesSinceSegmentEnd = 0
        captureLock.unlock()
        releaseSessionClaims()
    }

    /// Drop the audio-session claims `configureSession()` took: the coordinator
    /// drops `.capture` and either re-applies a lower-priority intent's profile
    /// (e.g. reply TTS) or deactivates the session, letting other audio resume;
    /// and the keep-alive suspend count is given back.
    ///
    /// Both calls are idempotent (`end` no-ops when the intent is not held,
    /// `resumeSilentAudioForMedia` clamps at 0), which is what lets the resume
    /// path call this before re-claiming without risking a negative count.
    private func releaseSessionClaims() {
        MainActor.assumeIsolated {
            AudioSessionCoordinator.shared.end(.capture)
            BackgroundKeepAliveManager.shared.resumeSilentAudioForMedia(caller: "VAD.capture")
        }
        VoiceLog.log("AVAudioSession: end(.capture)")
    }

    // MARK: - Setup

    private func configureSession() throws {
        // The shared AVAudioSession is owned by AudioSessionCoordinator. Declaring
        // the `.capture` intent makes it apply `.record/.measurement` + activate;
        // we no longer call setCategory/setActive directly (that caused last-wins
        // races with TTS / keep-alive). All call sites run on the main actor.
        //
        // [T-voice-input-double-tap] MUST be beginAndWait, not begin: the profile
        // switch is applied on a background queue, and `setupEngineAndVAD` reads
        // `inputNode.inputFormat` immediately after. Reading it while the session
        // still carries TTS's `.playback` profile yields 0 channels / 0 Hz and the
        // start throws "Microphone input unavailable" — the first mic tap then
        // stopped the speech but never began recording, and only the second tap
        // (by which time the queue had drained) worked.
        let t0 = CFAbsoluteTimeGetCurrent()
        var applied = false
        MainActor.assumeIsolated {
            BackgroundKeepAliveManager.shared.suspendSilentAudioForMedia(caller: "VAD.capture")
            applied = AudioSessionCoordinator.shared.beginAndWait(.capture)
        }
        let ms = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        VoiceLog.log("[VoiceInputDebug] AVAudioSession: begin(.capture) applied=\(applied) waited=\(String(format: "%.0f", ms))ms")
    }

    private func setupEngineAndVAD() throws {
        // Reset adaptive-gain state for a fresh capture so the noise floor is
        // re-estimated from this environment.
        smoothedGain = 1.0
        noiseFloorRMS = 0.003
        var inputNode = audioEngine.inputNode
        // Use the node's INPUT bus format for the tap. Using outputFormat(forBus:)
        // can disagree with the tap-able bus format when the hardware route runs
        // at a non-48k rate (e.g. 24 kHz after a Bluetooth / TTS route change),
        // and installTap then throws "Failed to create tap due to format
        // mismatch". The input-bus format is what installTap actually accepts.
        var inputFormat = inputNode.inputFormat(forBus: 0)
        VoiceLog.log("installTap: format=\(inputFormat.channelCount)ch \(Int(inputFormat.sampleRate))Hz")

        // [T-voice-mic-stale-engine] A 0-channel format here does NOT prove the
        // mic is unavailable — far more often this engine's cached input
        // binding went stale across a background trip (see `audioEngine`). The
        // session is already `.record` and activated at this point, so rebuild
        // the engine once and re-read before believing the failure. A fresh
        // engine re-binds to the current route; if the mic is genuinely taken
        // (a call, another app), the retry reads 0ch too and we fall through to
        // the same error as before, so this cannot mask a real preemption.
        if inputFormat.channelCount == 0 || inputFormat.sampleRate == 0 {
            VoiceLog.log("[VoiceInputDebug] input format invalid — rebuilding AVAudioEngine and retrying")
            audioEngine.stop()
            _ = noff_try_objc { self.audioEngine.inputNode.removeTap(onBus: 0) }
            audioEngine = AVAudioEngine()
            inputNode = audioEngine.inputNode
            inputFormat = inputNode.inputFormat(forBus: 0)
            VoiceLog.log("[VoiceInputDebug] after engine rebuild: format=\(inputFormat.channelCount)ch \(Int(inputFormat.sampleRate))Hz")
        }

        let sampleRate = inputFormat.sampleRate
        captureSampleRate = sampleRate
        // A 0-channel format means the input node isn't ready (session not
        // active / route mid-change). Installing a tap with it crashes.
        guard inputFormat.channelCount > 0, sampleRate > 0 else {
            // [T-voice-mic-preempted] A 0-channel format has two very different
            // causes. If the session never activated, another app owns the mic
            // (FaceTime, a call) — that is the common case and it deserves an
            // actionable message. If activation SUCCEEDED and we still got 0
            // channels, the cause is unknown (route mid-change, hardware), so
            // keep the generic error rather than blaming another app.
            if let applyError = AudioSessionCoordinator.lastApplyError {
                VoiceLog.log("ERROR: microphone unavailable — audio session activation failed: \(applyError.localizedDescription)")
                throw VoiceProviderError.audioSessionPreempted
            }
            VoiceLog.log("ERROR: microphone input unavailable (0 channels / 0 Hz), session activated OK")
            throw VoiceProviderError.parseError("Microphone input unavailable")
        }

        guard let wrapper = VADWrapper() else {
            throw VoiceProviderError.parseError("Failed to initialize VAD")
        }
        wrapper.setSileroModel(.v5)
        wrapper.setSamplerate(Self.sampleRateEnum(for: sampleRate))
        wrapper.setThresholdWithVadStartDetectionProbability(
            configuration.startProbability,
            vadEndDetectionProbability: configuration.endProbability,
            voiceStartVadTrueRatio: configuration.voiceStartTrueRatio,
            voiceEndVadFalseRatio: configuration.voiceEndFalseRatio,
            voiceStartFrameCount: Int32(configuration.voiceStartFrameCount),
            voiceEndFrameCount: Int32(configuration.voiceEndFrameCount)
        )
        wrapper.delegate = self
        self.vad = wrapper

        // 512-frame buffer (~10 ms @ 48 kHz) matches the VAD frame size.
        // installTap can also throw an ObjC NSException — wrap it.
        let installed = noff_try_objc {
            inputNode.installTap(onBus: 0, bufferSize: 512, format: inputFormat) { [weak self] buffer, _ in
                self?.processAudioBuffer(buffer)
            }
        }
        if !installed {
            // Fallback: let CoreAudio pick the node's native format (nil) — this
            // sidesteps a residual "format mismatch" when the explicit format and
            // the bus disagree mid route-change.
            VoiceLog.log("installTap explicit format failed; retrying with nil (native) format")
            let retried = noff_try_objc {
                inputNode.installTap(onBus: 0, bufferSize: 512, format: nil) { [weak self] buffer, _ in
                    self?.processAudioBuffer(buffer)
                }
            }
            guard retried else {
                self.vad = nil
                throw VoiceProviderError.parseError("Failed to attach microphone tap")
            }
        }
        audioEngine.prepare()
    }

    private static func sampleRateEnum(for sampleRate: Double) -> SL {
        switch Int(sampleRate) {
        case 48000: return .SAMPLERATE_48
        case 24000: return .SAMPLERATE_24
        case 16000: return .SAMPLERATE_16
        case 8000:  return .SAMPLERATE_8
        default:    return .SAMPLERATE_48  // engine input is typically 48 kHz
        }
    }

    private var tapFrameCounter = 0

    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData else { return }
        let frameCount = UInt(buffer.frameLength)
        tapFrameCounter += 1
        if tapFrameCounter % 50 == 0 {
            let elapsed = ProcessInfo.processInfo.systemUptime - rawAudioStartTime
            VoiceLog.log("VAD frame \(tapFrameCounter) t=\(String(format: "%.1f", elapsed))s speechDetected=\(isSpeaking) rawBuf=\(rawAudioBuffer.count)")
        }
        // Copy the mono channel off the audio thread before hopping queues —
        // the tap buffer is only valid for the duration of the callback.
        let mono = channelData[0]
        let raw = UnsafeBufferPointer(start: mono, count: Int(frameCount))

        // Noise-aware AGC. The old "gain = 0.5/peak" boosted EVERYTHING — including
        // room noise during silence — up to 6×, which pushed background noise into
        // the VAD's speech band and caused false triggers. Instead:
        //  1) Measure the frame RMS (energy), not a single peak (transient-robust).
        //  2) A noise GATE: if RMS is near the tracked noise floor, it's noise/
        //     silence → gain held at 1 (don't amplify noise), and we slowly adapt
        //     the noise-floor estimate toward it.
        //  3) Only frames clearly above the floor (likely speech) get AGC gain,
        //     smoothed across frames so it never jumps on a transient.
        var sumSq: Float = 0
        for s in raw { sumSq += s * s }
        let rms = (raw.count > 0) ? sqrt(sumSq / Float(raw.count)) : 0

        let gateThreshold = noiseFloorRMS * Self.gateRatio
        let targetGain: Float
        if rms < gateThreshold {
            // Noise / silence: don't amplify. Adapt the floor toward this level
            // — but ASYMMETRICALLY and never during a detected segment. The old
            // symmetric 0.05 rate (~0.2 s time constant at ~93 fps) let QUIET
            // SPEECH itself drag the floor upward: soft speech lands under the
            // gate, gets classified as noise, raises the floor, raises the
            // gate, and each further frame is even less likely to be amplified
            // — a runaway that starved the VAD of exactly the low-volume
            // speech users complained about. Downward stays fast (real silence
            // should re-arm sensitivity quickly); upward is 10× slower; the
            // ceiling drops 0.05 → 0.02 (0.05 RMS ≈ normal speech level — a
            // floor that high gates almost everything).
            targetGain = 1.0
            if !isSpeaking {
                let rate: Float = rms < noiseFloorRMS ? 0.05 : 0.005
                noiseFloorRMS += (rms - noiseFloorRMS) * rate
                noiseFloorRMS = max(0.0005, min(0.02, noiseFloorRMS))
            }
        } else {
            // Likely speech: aim RMS toward the target, clamp to [1, maxGain].
            targetGain = min(Self.maxGain, max(1.0, Self.agcTargetRMS / max(rms, 0.0001)))
        }
        // Smooth gain across frames to avoid pumping/jitter.
        smoothedGain += (targetGain - smoothedGain) * 0.3

        var samples = [Float](repeating: 0, count: raw.count)
        if smoothedGain > 1.01 {
            for i in 0..<raw.count {
                samples[i] = tanhf(raw[i] * smoothedGain)  // soft-clip
            }
        } else {
            for i in 0..<raw.count { samples[i] = raw[i] }
        }

        // Drive the waveform from the (boosted) tap so it's live the moment the
        // mic is on — the library's voiceDidContinue only fires DURING a detected
        // speech segment, so relying on it leaves the waveform flat until VAD
        // trips speech-start.
        let waveformData = samples.withUnsafeBytes { Data($0) }
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityDidUpdate(pcmData: waveformData)
        }

        captureLock.lock()
        #if DEBUG
        // [T-debug-full-session-capture] Un-cut reference copy: no VAD gating,
        // no rolling eviction, so the recording's real start survives.
        if !fullSessionTruncated {
            if Double(fullSessionSamples.count) / captureSampleRate >= Self.fullSessionMaxSeconds {
                fullSessionTruncated = true
            } else {
                fullSessionSamples.append(contentsOf: samples)
            }
        }
        #endif
        // Fallback: always accumulate raw audio (capped at 5 min = 300s)
        rawAudioBuffer.append(contentsOf: samples)
        // Track how much of it arrived outside a speech segment — this bounds
        // the start back-fill so it never reaches into the previous segment.
        if !isSpeaking {
            samplesSinceSegmentEnd += samples.count
        }
        let rawCap = Int(captureSampleRate * 300)
        if rawAudioBuffer.count > rawCap {
            rawAudioBuffer.removeFirst(rawAudioBuffer.count - rawCap)
        }
        // While speech is active, accumulate the segment.
        var total = 0
        if isSpeaking {
            captureSamples.append(contentsOf: samples)
            total = captureSamples.count
        }
        captureLock.unlock()
        if isSpeaking, Double(total) / captureSampleRate >= maxSegmentSeconds {
            VoiceLog.log("segment hit \(Int(maxSegmentSeconds))s cap — auto-flushing")
            flushCapturedSegment(reason: .maxLengthReached)
        }

        vadQueue.async { [weak self] in
            samples.withUnsafeBufferPointer { ptr in
                guard let base = ptr.baseAddress else { return }
                self?.vad?.processAudioData(withBuffer: base, count: UInt(samples.count))
            }
        }
    }
}

// MARK: - VADDelegate

extension VoiceActivityDetector: VADDelegate {
    func voiceStarted() {
        let now = ProcessInfo.processInfo.systemUptime
        captureLock.lock()
        let alreadyHasSamples = !captureSamples.isEmpty
        if alreadyHasSamples {
            // Resuming mid-speech after an interruption — keep accumulated samples
            let existingCount = captureSamples.count
            captureLock.unlock()
            let existingSec = Double(existingCount) / captureSampleRate
            VoiceLog.log(String(format: "VAD speech start (resume) | ts=%.3f | preserving %.3fs (%d samples)", now, existingSec, existingCount))
        } else {
            segmentStartTime = now
            // Fresh start — back-fill from the raw buffer to recover the audio
            // that played out while start detection was still confirming.
            // Bounded by the back-fill cap AND by what arrived since the last
            // segment ended (so we never re-consume the previous segment).
            let cap = Int(captureSampleRate * Self.startBackfillSeconds)
            let backfill = min(min(cap, samplesSinceSegmentEnd), rawAudioBuffer.count)
            captureSamples = backfill > 0 ? Array(rawAudioBuffer.suffix(backfill)) : []
            captureLock.unlock()
            let backfillSec = Double(backfill) / captureSampleRate
            VoiceLog.log(String(format: "VAD speech start | ts=%.3f | backfill=%.3fs", now, backfillSec))
        }
        isSpeaking = true
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityDidStart()
        }
    }

    func voiceEnded(withWavData wavData: Data!) {
        isSpeaking = false
        let endTime = ProcessInfo.processInfo.systemUptime
        captureLock.lock()
        let samples = captureSamples
        let rate = captureSampleRate
        captureSamples.removeAll(keepingCapacity: true)
        samplesSinceSegmentEnd = 0   // next back-fill horizon starts here
        captureLock.unlock()

        // Build the segment WAV from our own capture (pre-roll + speech) rather
        // than the library's wavData, so the 0.3s lead-in is included.
        let collectedSec = Double(samples.count) / rate
        let wav = Self.wavData(fromFloatSamples: samples, sampleRate: rate) ?? (wavData ?? Data())
        VoiceLog.log(String(format:
            "VAD speech end | collected: ts=%.3f dur=%.3fs (%d samples @ %.0fHz) → feeding recognizer: ts=%.3f wavBytes=%d wavDur=%.3fs",
            segmentStartTime, collectedSec, samples.count, rate,
            endTime, wav.count, collectedSec))
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityDidEnd(audioData: wav, reason: .silenceDetected)
        }
    }

    func voiceDidContinue(withPCMFloat pcmFloatData: Data!) {
        let data = pcmFloatData ?? Data()
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.voiceActivityDidUpdate(pcmData: data)
        }
    }
}

// MARK: - Microphone permission

extension VoiceActivityDetector {
    enum MicrophonePermission { case granted, denied, undetermined }

    static var microphonePermission: MicrophonePermission {
        let raw: AVAudioSession.RecordPermission
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted:      return .granted
            case .denied:       return .denied
            case .undetermined: return .undetermined
            @unknown default:   return .undetermined
            }
        } else {
            raw = AVAudioSession.sharedInstance().recordPermission
        }
        switch raw {
        case .granted:      return .granted
        case .denied:       return .denied
        case .undetermined: return .undetermined
        @unknown default:   return .undetermined
        }
    }

    static func requestMicrophonePermission() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        }
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}
