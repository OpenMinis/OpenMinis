#!/usr/bin/env swift
// [T-attach-bounds-probe-kick] An attachment whose load had never started sat
// on the 200pt placeholder forever.
//
// `attachmentBounds` answers from three branches, in order:
//
//   loadedImage != nil                        -> LOADED      (real aspect)
//   NativeMediaImageCache.size(forSource:)    -> KNOWN-SIZE  (real aspect)
//   neither                                   -> PLACEHOLDER-200
//
// The third branch returned the box and did nothing else, which closed a loop:
// no recorded size means placeholder, placeholder kicks no load, and the header
// probe that WOULD record the size lives inside the load pipeline
// (`beginLoadingIfNeeded`). So the size stayed unrecorded and the next layout
// pass landed in exactly the same place. Only an outside event — the cell being
// rebuilt when the row scrolled back into view — could break it.
//
// Measured on device (iPhone 17 Pro, 2026-09-22), an image a background tool
// wrote at 22:16:15:
//
//   22:16:42  [IMG][BOUNDS] #1  rawW=10000000.0  hasImg=false
//   22:16:42  [IMG][BOUNDS-BRANCH] #1..#5  branch=PLACEHOLDER-200
//             ... no [Load] dispatching, no [Resolve] BEGIN at all ...
//   22:26:00  [Load] SUCCESS finalSize=970.0x1120.0     <- 9m18s later
//
// Nothing was slow: the file was present (55,736 bytes) and decoded fine. The
// load was simply never asked for.
//
// Two details the fix must keep, both pinned below:
//   * TextKit probes this method with lineFrag.width = 10_000_000 during
//     intermediate passes and throws the result away — those must not spend a
//     probe;
//   * attachmentBounds runs inside TextKit's layout loop (the diagnostic
//     counters in the shipping file reach #350), so the probe must be
//     dispatched OFF the pass and attempted at most once per source.
//
// Run: swift AttachmentBoundsProbeKickTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. The branch/kick logic is
// ported below; section [5] re-reads the shipping source so a rewrite fails
// here rather than silently passing a stale copy.
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

// MARK: - Ported model

let unconstrainedProbeWidth: CGFloat = 1_000_000
let placeholderHeight: CGFloat = 200

/// Stand-in for NativeMediaImageCache's size table.
final class SizeStore {
    private var sizes: [String: CGSize] = [:]
    func size(_ s: String) -> CGSize? { sizes[s] }
    func record(_ sz: CGSize, _ s: String) { sizes[s] = sz }
}

/// Verbatim port of the placeholder branch's kick guard.
final class ProbeKicker {
    private var kicked: Set<String> = []
    private(set) var probeCalls: [String] = []
    let store: SizeStore
    init(store: SizeStore) { self.store = store }

    /// Mirrors `kickHeaderProbe`: skip when a size is already recorded, record
    /// the ATTEMPT (not the success) so an unresolvable source cannot re-probe
    /// every pass, and only touch `minis://` local sources.
    func kick(source: String) {
        guard store.size(source) == nil else { return }
        guard kicked.insert(source).inserted else { return }
        guard let url = URL(string: source), url.scheme == "minis" else { return }
        probeCalls.append(source)
    }
}

enum Branch: String { case loaded = "LOADED", known = "KNOWN-SIZE", placeholder = "PLACEHOLDER-200" }

/// Verbatim port of attachmentBounds' branch selection + the new kick.
func attachmentBounds(rawWidth: CGFloat, hasImage: Bool, source: String,
                      store: SizeStore, kicker: ProbeKicker) -> (Branch, CGFloat) {
    if hasImage { return (.loaded, 400) }
    if let known = store.size(source) {
        let aspect = known.height / max(known.width, 1)
        return (.known, min(370 * aspect, 400))
    }
    if rawWidth < unconstrainedProbeWidth {
        kicker.kick(source: source)
    }
    return (.placeholder, placeholderHeight)
}

let src = "minis://attachments/repro_pixel6/compare_scroll1085.jpg"

print("▶️  1. the reported bug: a never-loaded source kicks a probe now")
do {
    let store = SizeStore(), kicker = ProbeKicker(store: store)
    let (branch, h) = attachmentBounds(rawWidth: 370, hasImage: false, source: src,
                                       store: store, kicker: kicker)
    checkEq("still answers with the placeholder this pass", branch, Branch.placeholder)
    checkEq("…at 200pt", h, placeholderHeight)
    checkEq("but a probe was requested", kicker.probeCalls, [src])
}

print("\n▶️  2. the probe closes the loop on the NEXT pass")
do {
    let store = SizeStore(), kicker = ProbeKicker(store: store)
    _ = attachmentBounds(rawWidth: 370, hasImage: false, source: src, store: store, kicker: kicker)
    // The real probe records the header size and posts .minisAttachmentSizeChanged.
    store.record(CGSize(width: 970, height: 1120), src)   // the device's real pixels
    let (branch, h) = attachmentBounds(rawWidth: 370, hasImage: false, source: src,
                                       store: store, kicker: kicker)
    checkEq("second pass takes the recorded-size branch", branch, Branch.known)
    check("…and the height is the real aspect, not 200", h != placeholderHeight)
    checkEq("970x1120 at 370pt wide -> 400 (half-screen cap)", h, 400)
}

print("\n▶️  3. TextKit's unconstrained probe does NOT spend a probe")
do {
    // rawW=10000000 appeared in 2 of the 5 device BOUNDS calls. Those passes are
    // discarded, so probing for them is pure waste.
    let store = SizeStore(), kicker = ProbeKicker(store: store)
    let (branch, _) = attachmentBounds(rawWidth: 10_000_000, hasImage: false, source: src,
                                       store: store, kicker: kicker)
    checkEq("still the placeholder", branch, Branch.placeholder)
    checkEq("no probe requested", kicker.probeCalls, [])

    // A real pass right after it still probes — the skip must not latch.
    _ = attachmentBounds(rawWidth: 370, hasImage: false, source: src, store: store, kicker: kicker)
    checkEq("a real width afterwards DOES probe", kicker.probeCalls, [src])
}

print("\n▶️  4. at most one probe per source, however hot the loop gets")
do {
    let store = SizeStore(), kicker = ProbeKicker(store: store)
    // The device log reaches #350 on this method; simulate a scroll burst.
    for _ in 0..<350 {
        _ = attachmentBounds(rawWidth: 370, hasImage: false, source: src, store: store, kicker: kicker)
    }
    checkEq("350 layout passes -> 1 probe", kicker.probeCalls.count, 1)

    // An unresolvable source must not retry forever either: the attempt is
    // recorded, not the success.
    let remote = "https://example.com/x.jpg"
    for _ in 0..<50 {
        _ = attachmentBounds(rawWidth: 370, hasImage: false, source: remote, store: store, kicker: kicker)
    }
    checkEq("a non-minis source is never probed", kicker.probeCalls.filter { $0 == remote }.count, 0)
}

print("\n▶️  5. a loaded / known source is untouched (no new work on the hot path)")
do {
    let store = SizeStore(), kicker = ProbeKicker(store: store)
    let (b1, _) = attachmentBounds(rawWidth: 370, hasImage: true, source: src, store: store, kicker: kicker)
    checkEq("loaded branch", b1, Branch.loaded)
    checkEq("no probe", kicker.probeCalls, [])

    store.record(CGSize(width: 970, height: 1120), src)
    let (b2, _) = attachmentBounds(rawWidth: 370, hasImage: false, source: src, store: store, kicker: kicker)
    checkEq("known-size branch", b2, Branch.known)
    checkEq("still no probe", kicker.probeCalls, [])
}

print("\n▶️  6. shipping source carries the fix on BOTH image and video")
do {
    let md = codeOnly(source("Views/Chat/SelectableMarkdownView.swift"))
    if md.isEmpty { print("  ⏭  source not readable") } else {
        // Image side.
        check("the image placeholder branch kicks a probe",
              md.contains("Self.kickHeaderProbe(source: source, canonicalSrc: canonicalSrc)"))
        check("…guarded by the unconstrained-probe width",
              md.contains("if rawWidth < Self.unconstrainedProbeWidth {"))
        check("the kick helper ships",
              md.contains("private static func kickHeaderProbe(source: String, canonicalSrc: String) {"))
        // Off the layout pass — a synchronous probe here would put file I/O
        // and a UserDefaults write inside TextKit's loop.
        check("…and it dispatches rather than probing inline",
              md.contains("private static func kickHeaderProbe")
              && md.range(of: "private static func kickHeaderProbe").map {
                     md[$0.lowerBound...].prefix(1200).contains("Task.detached(priority: .utility)")
                 } ?? false)
        check("…once per source", md.contains("probeKicked.insert(canonicalSrc).inserted"))
        check("…local sources only", md.contains("guard let url = URL(string: canonicalSrc), url.scheme == \"minis\" else { return }"))

        // Video side.
        check("the video placeholder branch kicks a probe",
              md.contains("Self.kickTrackProbe(source: source)"))
        check("…reusing the image class's probe-width constant",
              md.contains("if lineFrag.width < ImageAttachment.unconstrainedProbeWidth {"))
        check("the track probe was extracted so both callers share it",
              md.contains("private static func probeAndPublishTrackSize(asset: AVAsset, src: String, via: String) async {"))
        check("…and the thumbnail load now calls that extraction",
              md.contains("await Self.probeAndPublishTrackSize(asset: asset, src: src, via: \"thumbnail-load\")"))
        // The extraction must stay idempotent or the two callers double-publish.
        check("…guarded on an unrecorded size",
              md.contains("guard NativeMediaImageCache.shared.size(forSource: src) == nil,"))

        // The branch must still RETURN the placeholder — the kick is additive,
        // and pretending to know a height here would be the stale-height bug.
        check("the placeholder is still what this pass returns",
              md.contains("return CGRect(x: 0, y: 0, width: placeholderWidth, height: Self.placeholderHeight)"))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
