// Tests for [T-soul-icon-sidecar] — the Soul avatar's bytes moved out of
// SOUL.md's frontmatter and into a `SOUL.icon.png` sidecar beside it.
//
// Standalone (`swift SoulIconSidecarTests.swift`) for the same reason as the
// neighbouring files: the MinisTests target has a pre-existing compile break,
// and the shipping types pull in AIChatViewModel and the whole app graph.
//
// The disk-boundary logic is reproduced here rather than imported. Two of the
// checks at the bottom re-read SoulStore.swift / the sync + backup files and
// fail if the shipping code stops doing what these tests assert, so the copy
// cannot silently drift.

import Foundation

// MARK: - Harness

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(expected)\n     actual:   \(actual)"); failures += 1 }
}

// MARK: - Reproduced logic (mirrors SoulStore.swift)

let prefix = "data:image/png;base64,"
let sidecarName = "SOUL.icon.png"
func isDataURI(_ s: String) -> Bool { s.hasPrefix(prefix) }
func isSidecarRef(_ s: String) -> Bool { s == sidecarName }
func pngData(from uri: String) -> Data? {
    guard isDataURI(uri) else { return nil }
    return Data(base64Encoded: String(uri.dropFirst(prefix.count)))
}
func dataURI(fromPNG d: Data) -> String { prefix + d.base64EncodedString() }

struct Meta: Equatable { var name = "Minis"; var icon = ""; var style = ""; var lang = "auto" }
struct SoulFileM: Equatable { var metadata = Meta(); var body = "" }

func parse(_ source: String) -> SoulFileM {
    let trimmed = source.drop(while: { $0 == "\n" || $0 == "\r" })
    guard trimmed.hasPrefix("---") else { return SoulFileM(metadata: Meta(), body: source) }
    let lines = String(trimmed).components(separatedBy: "\n")
    guard lines.first?.trimmingCharacters(in: .whitespaces) == "---",
          let close = lines.dropFirst().firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "---" })
    else { return SoulFileM(metadata: Meta(), body: source) }
    var meta = Meta()
    for raw in Array(lines[1..<close]) {
        let line = raw.trimmingCharacters(in: .whitespaces)
        guard let colon = line.firstIndex(of: ":") else { continue }
        let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
        var v = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
        if v.hasPrefix("\""), v.hasSuffix("\""), v.count >= 2 { v = String(v.dropFirst().dropLast()) }
        switch key {
        case "name":  if !v.isEmpty { meta.name = v }
        case "icon":  meta.icon = v
        case "style": meta.style = v
        case "lang":  if !v.isEmpty { meta.lang = v }
        default: break
        }
    }
    let body = Array(lines[(close + 1)...]).joined(separator: "\n").drop(while: { $0 == "\n" || $0 == "\r" })
    return SoulFileM(metadata: meta, body: String(body))
}

func serialize(_ f: SoulFileM) -> String {
    var out = "---\nname: \"\(f.metadata.name)\"\n"
    if !f.metadata.icon.isEmpty { out += "icon: \"\(f.metadata.icon)\"\n" }
    out += "style: \"\(f.metadata.style)\"\nlang: \"\(f.metadata.lang)\"\n---\n\n" + f.body
    if !out.hasSuffix("\n") { out += "\n" }
    return out
}

// Disk boundary, mirroring SoulStore.resolveIconForRead / persistIconSidecar.
let tmp = URL(fileURLWithPath: NSTemporaryDirectory())
    .appendingPathComponent("soul-sidecar-test-\(UUID().uuidString)")
let soulURL = tmp.appendingPathComponent("SOUL.md")
let iconURL = tmp.appendingPathComponent(sidecarName)
try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)

func resolveIconForRead(_ raw: String) -> String {
    guard isSidecarRef(raw) else { return raw }
    guard let d = try? Data(contentsOf: iconURL), !d.isEmpty else { return "" }
    return dataURI(fromPNG: d)
}
func persistIconSidecar(_ icon: String) -> String {
    guard isDataURI(icon) else {
        if !isSidecarRef(icon) { try? FileManager.default.removeItem(at: iconURL) }
        return icon
    }
    guard let png = pngData(from: icon) else { return icon }
    do { try png.write(to: iconURL, options: .atomic); return sidecarName }
    catch { return icon }
}
func save(_ f: SoulFileM) {
    var onDisk = f
    onDisk.metadata.icon = persistIconSidecar(f.metadata.icon)
    try? serialize(onDisk).data(using: .utf8)!.write(to: soulURL, options: .atomic)
}
func load() -> SoulFileM? {
    guard let d = try? Data(contentsOf: soulURL), let s = String(data: d, encoding: .utf8) else { return nil }
    var f = parse(s)
    f.metadata.icon = resolveIconForRead(f.metadata.icon)
    return f
}
func serializedForWire(_ f: SoulFileM) -> String {
    var c = f
    c.metadata.icon = resolveIconForRead(c.metadata.icon)
    return serialize(c)
}

// A tiny but real PNG (1x1, transparent).
let realPNG = Data(base64Encoded:
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")!
let realURI = dataURI(fromPNG: realPNG)

func reset() {
    try? FileManager.default.removeItem(at: soulURL)
    try? FileManager.default.removeItem(at: iconURL)
}

// MARK: - Tests

print("\n[1] Saving an image icon splits bytes out of SOUL.md")
reset()
save(SoulFileM(metadata: Meta(icon: realURI), body: "hello"))
let rawOnDisk = String(data: try! Data(contentsOf: soulURL), encoding: .utf8)!
check("SOUL.md no longer contains base64", rawOnDisk.contains("base64"), false)
check("SOUL.md names the sidecar", rawOnDisk.contains("icon: \"\(sidecarName)\""))
check("sidecar file exists", FileManager.default.fileExists(atPath: iconURL.path))
checkEq("sidecar holds the original PNG bytes", try! Data(contentsOf: iconURL), realPNG)
check("SOUL.md stays small", rawOnDisk.utf8.count < 200)

print("\n[2] Loading folds the sidecar back into a data URI")
checkEq("in-memory icon is the data URI again", load()!.metadata.icon, realURI)
// serialize() appends a trailing newline (shipping behaviour), so compare trimmed.
checkEq("body survives", load()!.body.trimmingCharacters(in: .newlines), "hello")

print("\n[3] Round-trip is stable (save -> load -> save)")
let firstDisk = rawOnDisk
save(load()!)
checkEq("second save produces identical SOUL.md",
        String(data: try! Data(contentsOf: soulURL), encoding: .utf8)!, firstDisk)
checkEq("sidecar unchanged", try! Data(contentsOf: iconURL), realPNG)

print("\n[4] Backward compat: an OLD file with inline base64 still loads")
reset()
let legacy = "---\nname: \"Minis\"\nicon: \"\(realURI)\"\nstyle: \"\"\nlang: \"auto\"\n---\n\nbody\n"
try! legacy.data(using: .utf8)!.write(to: soulURL)
checkEq("legacy inline icon resolves unchanged", load()!.metadata.icon, realURI)
check("no sidecar needed to read it", FileManager.default.fileExists(atPath: iconURL.path), false)
// ...and the next save migrates it.
save(load()!)
check("next save migrates legacy file to sidecar",
      String(data: try! Data(contentsOf: soulURL), encoding: .utf8)!.contains("base64"), false)
check("migration wrote the sidecar", FileManager.default.fileExists(atPath: iconURL.path))

print("\n[5] Clearing the icon removes the sidecar (no orphan file)")
save(SoulFileM(metadata: Meta(icon: ""), body: "body"))
check("sidecar deleted", FileManager.default.fileExists(atPath: iconURL.path), false)
checkEq("icon reads back empty", load()!.metadata.icon, "")

print("\n[6] An emoji icon never creates a sidecar")
reset()
save(SoulFileM(metadata: Meta(icon: "⚡"), body: ""))
check("no sidecar for emoji", FileManager.default.fileExists(atPath: iconURL.path), false)
checkEq("emoji round-trips", load()!.metadata.icon, "⚡")

print("\n[7] A dangling sidecar reference degrades to the default, not to text")
reset()
try! "---\nname: \"Minis\"\nicon: \"\(sidecarName)\"\nstyle: \"\"\nlang: \"auto\"\n---\n\n"
    .data(using: .utf8)!.write(to: soulURL)
checkEq("missing sidecar resolves to empty (falls back to sparkle)", load()!.metadata.icon, "")
check("does NOT leak the filename as the icon value", load()!.metadata.icon == sidecarName, false)

print("\n[8] Wire form re-inlines base64 for sync")
reset()
save(SoulFileM(metadata: Meta(icon: realURI), body: "b"))
let wire = serializedForWire(load()!)
check("wire form carries base64 (old peers still get an icon)", wire.contains("base64"))
check("wire form does NOT carry the bare filename", wire.contains("icon: \"\(sidecarName)\""), false)

print("\n[9] Sync echo is idempotent — the equality guard must compare wire forms")
// This is the regression that a naive implementation hits: disk holds the
// filename, the inbound record holds base64, so a byte comparison of the two
// never matches and every echo rewrites the file.
let rawDiskNow = String(data: try! Data(contentsOf: soulURL), encoding: .utf8)!
check("raw disk text != inbound wire text (why the naive check fails)", rawDiskNow == wire, false)
checkEq("but wire-form comparison matches, so the echo is skipped",
        serializedForWire(load()!), wire)

print("\n[10] Inbound sync content is normalized, not written verbatim")
// Simulate applyRemoteContent: a peer sends base64; disk must end up split.
reset()
var inbound = parse(legacy)
let resolved = resolveIconForRead(inbound.metadata.icon)
inbound.metadata.icon = persistIconSidecar(resolved)
try! serialize(inbound).data(using: .utf8)!.write(to: soulURL, options: .atomic)
check("inbound base64 did not land in SOUL.md",
      String(data: try! Data(contentsOf: soulURL), encoding: .utf8)!.contains("base64"), false)
check("inbound bytes landed in the sidecar", FileManager.default.fileExists(atPath: iconURL.path))
checkEq("resolved icon still available in memory", resolved, realURI)

// MARK: - Anti-drift: assert the shipping code still does these things

print("\n[11] Shipping source still matches these assumptions")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let soulSrc = sourceOf("Agent/Session/SoulStore.swift")
check("SoulStore declares the sidecar filename",
      soulSrc.contains("static let sidecarName = \"SOUL.icon.png\""))
check("load() resolves the sidecar", soulSrc.contains("file.metadata.icon = resolveIconForRead"))
check("save() persists the sidecar", soulSrc.contains("onDisk.metadata.icon = persistIconSidecar"))
check("applyRemoteContent normalizes inbound",
      soulSrc.contains("inbound.metadata.icon = persistIconSidecar(resolvedIcon)"))
check("equality guard compares wire form",
      soulSrc.contains("serializedForWire(localFile) == markdown"))

let exporterSrc = sourceOf("Agent/Backup/BackupExporter.swift")
check("backup EXPORT includes the sidecar",
      exporterSrc.contains("name == SoulIconImage.sidecarName"))
let importerSrc = sourceOf("Agent/Backup/BackupImporter+Categories.swift")
check("backup IMPORT includes the sidecar",
      importerSrc.contains("name == SoulIconImage.sidecarName"))
let syncSrc = sourceOf("Agent/Sync/V2/ChatStoreSyncHydrators.swift")
check("sync pushes the wire form, not the raw file",
      syncSrc.contains("SoulStore.serializedForWire(file)"))
check("sync LWW mtime considers the sidecar too",
      syncSrc.contains("SoulStore.iconFileURL"))

try? FileManager.default.removeItem(at: tmp)
print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
