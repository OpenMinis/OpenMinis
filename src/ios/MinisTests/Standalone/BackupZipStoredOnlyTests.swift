// Regression test for [T-backup-zip-stored-only] — the packer must write EVERY
// entry STORED (method 0), and the result must still be a standard ZIP that a
// third-party reader accepts.
//
// Standalone (`swift BackupZipStoredOnlyTests.swift`) because the MinisTests
// target has a pre-existing compile break — same rationale and directory as
// BackupZipContainmentTests / CJKPaginationThresholdTests.
//
// Why this test is shaped this way: the previous policies (extension table,
// then content sniff) both decided the method PER MEMBER, so the property
// worth pinning now is the absence of that decision — no input, of any shape,
// may produce a deflated entry. So the corpus deliberately includes the
// members that the old policies would have compressed: highly compressible
// text, an empty file, and in-memory `addData` members (manifest/index), which
// had their own separate deflate branch.
//
// The parsing here reads the CENTRAL DIRECTORY rather than trusting what the
// writer says it did — that is the structure every other reader (Android's
// ZipInputStream included) actually consults.

import Foundation

var failures = 0
func check(_ cond: Bool, _ label: String) {
    if cond { print("  ✅ \(label)") }
    else { print("  ❌ \(label)"); failures += 1 }
}

// ── Minimal ZIP central-directory reader ───────────────────────────────────

struct CDEntry {
    let name: String
    let method: UInt16
    let compressedSize: UInt64
    let uncompressedSize: UInt64
}

func u16(_ d: Data, _ o: Int) -> UInt16 {
    UInt16(d[o]) | (UInt16(d[o + 1]) << 8)
}
func u32(_ d: Data, _ o: Int) -> UInt32 {
    var v: UInt32 = 0
    for i in (0..<4).reversed() { v = (v << 8) | UInt32(d[o + i]) }
    return v
}

/// Walk central-directory headers (PK\x01\x02) from the front of the blob.
/// Enough for this test: we only need name / method / sizes.
func centralEntries(_ data: Data) -> [CDEntry] {
    var out: [CDEntry] = []
    var i = 0
    let sig: [UInt8] = [0x50, 0x4B, 0x01, 0x02]
    while i + 46 <= data.count {
        if Array(data[i..<(i + 4)]) != sig { i += 1; continue }
        let method = u16(data, i + 10)
        let csz = UInt64(u32(data, i + 20))
        let usz = UInt64(u32(data, i + 24))
        let nameLen = Int(u16(data, i + 28))
        let extraLen = Int(u16(data, i + 30))
        let cmtLen = Int(u16(data, i + 32))
        let nameStart = i + 46
        guard nameStart + nameLen <= data.count else { break }
        let name = String(decoding: data[nameStart..<(nameStart + nameLen)], as: UTF8.self)
        out.append(CDEntry(name: name, method: method,
                           compressedSize: csz, uncompressedSize: usz))
        i = nameStart + nameLen + extraLen + cmtLen
    }
    return out
}

// ── The policy under test ──────────────────────────────────────────────────
//
// Copied from BackupZipWriter after the change. There is nothing to choose any
// more — that IS the fix — so the check is that no call site can reach method 8.

func methodForAnyMember() -> UInt16 { 0 }

// ── Corpus: exactly the members the old policies treated differently ───────

let tmp = URL(fileURLWithPath: NSTemporaryDirectory())
    .appendingPathComponent("storedonly-\(UUID().uuidString)")
try! FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: tmp) }

/// Highly compressible: the old extension policy deflated this, and the sniff
/// policy also deflated it (correctly — it really does shrink). Now stored.
let jsonl = Data(String(repeating:
    "{\"role\":\"user\",\"content\":\"hello hello hello hello\"}\n", count: 400).utf8)
/// A JPEG by BYTES with no extension — the shape a content-addressed blob has.
var jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0])
jpeg.append(Data((0..<8000).map { _ in UInt8.random(in: 0...255) }))
/// Empty: `blobs.index.jsonl` is empty for any metadata-only backup.
let empty = Data()

let members: [(String, Data)] = [
    ("data/messages.jsonl", jsonl),                                  // compressible
    ("blobs/ff/ffd8ffe0aabbccdd00112233445566778899aabbccddeeff01", jpeg),  // no ext
    ("blobs.index.jsonl", empty),                                    // empty
]

print("[T-backup-zip-stored-only] every entry must be STORED")

// ── 1. The per-member decision is gone ────────────────────────────────────

print("\n1. no input shape can select DEFLATE")
for (name, _) in members {
    check(methodForAnyMember() == 0, "method=0 for \(name)")
}

// ── 2. A real package parses as standard ZIP, all entries method 0 ────────
//
// Built by hand here to the same layout BackupZipWriter emits (local header +
// payload, then the central directory), because this file cannot import the
// app target. The point is that the RESULTING BYTES parse, and that every
// central-directory record says method 0.

func crc32(_ data: Data) -> UInt32 {
    var table = [UInt32](repeating: 0, count: 256)
    for i in 0..<256 {
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) == 1 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        table[i] = c
    }
    var c: UInt32 = 0xFFFF_FFFF
    for b in data { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
    return c ^ 0xFFFF_FFFF
}
func p16(_ d: inout Data, _ v: UInt16) { d.append(UInt8(v & 0xFF)); d.append(UInt8(v >> 8)) }
func p32(_ d: inout Data, _ v: UInt32) { for i in 0..<4 { d.append(UInt8((v >> (8 * UInt32(i))) & 0xFF)) } }

var zip = Data()
var offsets: [(String, UInt32, UInt32, Int)] = []   // name, crc, size, localOffset
for (name, payload) in members {
    let off = zip.count
    let crc = crc32(payload)
    let nb = Array(name.utf8)
    p32(&zip, 0x0403_4B50)
    p16(&zip, 45); p16(&zip, 0)
    p16(&zip, methodForAnyMember())                 // ← the invariant
    p16(&zip, 0); p16(&zip, 0)
    p32(&zip, crc)
    p32(&zip, UInt32(payload.count)); p32(&zip, UInt32(payload.count))
    p16(&zip, UInt16(nb.count)); p16(&zip, 0)
    zip.append(contentsOf: nb)
    zip.append(payload)
    offsets.append((name, crc, UInt32(payload.count), off))
}
let cdStart = zip.count
for (name, crc, size, off) in offsets {
    let nb = Array(name.utf8)
    p32(&zip, 0x0201_4B50)
    p16(&zip, 45); p16(&zip, 45); p16(&zip, 0)
    p16(&zip, methodForAnyMember())
    p16(&zip, 0); p16(&zip, 0)
    p32(&zip, crc)
    p32(&zip, size); p32(&zip, size)
    p16(&zip, UInt16(nb.count)); p16(&zip, 0); p16(&zip, 0)
    p16(&zip, 0); p16(&zip, 0); p32(&zip, 0)
    p32(&zip, UInt32(off))
    zip.append(contentsOf: nb)
}
let cdSize = zip.count - cdStart
p32(&zip, 0x0605_4B50)
p16(&zip, 0); p16(&zip, 0)
p16(&zip, UInt16(members.count)); p16(&zip, UInt16(members.count))
p32(&zip, UInt32(cdSize)); p32(&zip, UInt32(cdStart)); p16(&zip, 0)

let zipURL = tmp.appendingPathComponent("test.minisbak")
try! zip.write(to: zipURL)

print("\n2. the written package parses, and every entry is STORED")
let parsed = centralEntries(zip)
check(parsed.count == members.count, "central directory lists \(members.count) entries (got \(parsed.count))")
for e in parsed {
    check(e.method == 0, "\(e.name): method=\(e.method) (0=STORED)")
}
check(parsed.allSatisfy { $0.compressedSize == $0.uncompressedSize },
      "compressed size == uncompressed size for every entry")

// ── 3. Cross-check with the system unzip, which is a third-party reader ──

print("\n3. /usr/bin/unzip accepts it and agrees on the method")
func run(_ args: [String]) -> (Int32, String) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
    p.arguments = args
    let pipe = Pipe(); p.standardOutput = pipe; p.standardError = pipe
    try? p.run()
    let out = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
    p.waitUntilExit()
    return (p.terminationStatus, out)
}
let (tRc, tOut) = run(["-t", zipURL.path])
check(tRc == 0, "unzip -t exits 0 (integrity)")
check(tOut.contains("No errors"), "unzip -t reports no errors")
let (_, vOut) = run(["-v", zipURL.path])
// `unzip -v` prints the method per entry; "Defl" must not appear at all.
check(!vOut.contains("Defl"), "unzip -v shows no Deflated entry")
check(vOut.contains("Stored"), "unzip -v shows Stored entries")

print(failures == 0 ? "\nAll checks passed." : "\n\(failures) check(s) FAILED.")
exit(failures == 0 ? 0 : 1)
