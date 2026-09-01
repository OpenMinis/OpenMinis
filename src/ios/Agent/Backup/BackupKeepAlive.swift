import AVFoundation
import UIKit

private let logger = AppLogger(category: "Backup")

/// Keeps a running backup alive when the app is not in the foreground.
///
/// `BackupBackgroundAssertion` (beginBackgroundTask) buys roughly 30 seconds
/// after the user leaves — enough for a small export, nowhere near enough to
/// push a multi-hundred-megabyte package to a NAS. A backup interrupted that
/// way is not lost (staging is resumable), but it does mean a user who
/// switches apps mid-backup comes back to an unfinished job every time.
///
/// The app already declares the `audio` background mode, so an active audio
/// session keeps the process scheduled. This plays SILENCE on a loop — nothing
/// audible, no ducking of anything else — purely to hold that session open.
///
/// Two deliberate restrictions:
///
///   - `.mixWithOthers` so the user's music, podcast or call is untouched.
///     Without it, starting a backup would pause whatever they were listening
///     to, which is a far worse surprise than a backup that pauses.
///   - The session is torn down the moment the run ends. Holding an audio
///     session longer than the work needs it is what gets an app rejected, and
///     it also shows a stale "audio playing" indicator to the user.
@MainActor
enum BackupKeepAlive {

    private static var player: AVAudioPlayer?
    private static var activated = false

    /// Whether the keep-alive session is currently held.
    static var isActive: Bool { activated }

    /// Begin holding the app alive in the background. Idempotent.
    static func begin() {
        guard !activated else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            // .playback is the category that actually continues in the
            // background; .ambient stops when the screen locks, which is
            // exactly the case this exists to survive.
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            guard let data = silentLoopWAV() else {
                logger.warning("[Backup] keep-alive: could not build silent audio — background time not extended")
                return
            }
            let p = try AVAudioPlayer(data: data)
            p.numberOfLoops = -1        // forever, until the run ends
            p.volume = 0                // belt and braces: the samples are already silent
            p.play()
            player = p
            activated = true
            logger.info("[Backup] keep-alive: audio session active — backup continues in the background")
        } catch {
            // Not fatal. The backup still runs; it just loses its protection
            // if the user leaves the app.
            logger.warning("[Backup] keep-alive: could not start audio session: \(error.localizedDescription)")
        }
    }

    /// Release the session. Safe to call when not held.
    static func end() {
        guard activated else { return }
        player?.stop()
        player = nil
        activated = false
        // Non-fatal on failure, and deliberately notifying others so a paused
        // music app can resume.
        do {
            try AVAudioSession.sharedInstance().setActive(
                false, options: [.notifyOthersOnDeactivation])
        } catch {
            logger.warning("[Backup] keep-alive: could not deactivate audio session: \(error.localizedDescription)")
        }
        logger.info("[Backup] keep-alive: audio session released")
    }

    /// A minimal valid WAV carrying one second of silence.
    ///
    /// Generated rather than shipped as an asset: it is ~88KB of zeros that
    /// would otherwise sit in the bundle, and a file a future cleanup could
    /// delete without realising the backup path depends on it.
    private static func silentLoopWAV() -> Data? {
        let sampleRate = 44100
        let channels = 1
        let bitsPerSample = 16
        let seconds = 1
        let frameCount = sampleRate * seconds
        let dataBytes = frameCount * channels * bitsPerSample / 8
        let byteRate = sampleRate * channels * bitsPerSample / 8
        let blockAlign = channels * bitsPerSample / 8

        var d = Data()
        func u32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: Int) { withUnsafeBytes(of: UInt16(v).littleEndian) { d.append(contentsOf: $0) } }

        d.append(contentsOf: Array("RIFF".utf8))
        u32(36 + dataBytes)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8))
        u32(16)                 // PCM header size
        u16(1)                  // PCM
        u16(channels)
        u32(sampleRate)
        u32(byteRate)
        u16(blockAlign)
        u16(bitsPerSample)
        d.append(contentsOf: Array("data".utf8))
        u32(dataBytes)
        d.append(Data(count: dataBytes))   // silence
        return d
    }
}

/// "Keep the screen awake so the backup can finish."
///
/// Separate from the audio keep-alive on purpose: that one runs for every
/// backup and is invisible, while this is a user choice with a visible cost
/// (the screen stays lit, the battery drains). Default OFF.
///
/// Why offer it at all when the audio session already extends background time:
/// iOS can still suspend or jetsam a backgrounded app under memory pressure,
/// and a large export moving hundreds of megabytes is exactly when that
/// happens. Someone who wants a big backup to definitely finish can trade
/// screen-on time for it.
@MainActor
enum BackupScreenAwake {

    /// Reflects the live idle-timer state rather than a stored flag, so it
    /// cannot drift from what the system is actually doing.
    static var isEnabled: Bool { UIApplication.shared.isIdleTimerDisabled }

    static func set(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
        logger.info("[Backup] screen-awake \(on ? "ENABLED" : "disabled")")
    }
}
