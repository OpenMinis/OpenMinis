import XCTest
@testable import Minis

/// Packaging-side tests: the §3.4 size cap and the hand-rolled ZIP reader.
///
/// The size cap is a boundary the design states precisely — a file *equal* to
/// the cap is kept, only a file *over* it is skipped (`>`, not `>=`). One
/// character decides whether a user's exactly-4MiB file silently vanishes from
/// their backup, and no device test would notice, because nobody happens to
/// have a file exactly on the line.
final class BackupPackagingTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("packaging-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        try super.tearDownWithError()
    }

    private func store(cap: Int64?) -> BackupBlobStore {
        BackupBlobStore(stagingRoot: dir, maxFileBytes: cap)
    }

    private func file(_ name: String, bytes: Int) throws -> URL {
        let url = dir.appendingPathComponent(name)
        try Data(repeating: 0x5A, count: bytes).write(to: url)
        return url
    }

    // MARK: - §3.4 size cap boundary

    /// **The boundary.** Exactly at the cap is KEPT; one byte over is skipped.
    /// A `>=` here would drop the equal case.
    func testFileExactlyAtTheCapIsKept() throws {
        let s = store(cap: 1000)
        let outcome = try s.addFile(at: try file("exact", bytes: 1000),
                                    logicalPath: "shared/exact")
        guard case .stored(_, let size) = outcome else {
            return XCTFail("a file exactly at the cap must be stored, got \(outcome)")
        }
        XCTAssertEqual(size, 1000)
        XCTAssertEqual(s.skippedFiles, 0)
        XCTAssertEqual(s.skippedBytes, 0)
    }

    func testFileOneByteOverTheCapIsSkipped() throws {
        let s = store(cap: 1000)
        let outcome = try s.addFile(at: try file("over", bytes: 1001),
                                    logicalPath: "shared/over")
        guard case .skippedTooLarge(let size) = outcome else {
            return XCTFail("a file over the cap must be skipped, got \(outcome)")
        }
        XCTAssertEqual(size, 1001)
        XCTAssertEqual(s.skippedFiles, 1)
        XCTAssertEqual(s.skippedBytes, 1001)
        // The path is recorded so the completion screen can tell the user which
        // files are missing — the whole point of the tombstone (§3.4).
        XCTAssertEqual(s.skippedPaths.first?.path, "shared/over")
    }

    func testFileOneByteUnderTheCapIsKept() throws {
        let s = store(cap: 1000)
        guard case .stored = try s.addFile(at: try file("under", bytes: 999),
                                           logicalPath: "shared/under") else {
            return XCTFail("a file under the cap must be stored")
        }
        XCTAssertEqual(s.skippedFiles, 0)
    }

    /// The same boundary on the in-memory path, which has its own copy of the
    /// comparison and could drift from the on-disk one.
    func testAddDataHonoursTheSameBoundary() throws {
        let s = store(cap: 100)
        guard case .stored = try s.addData(Data(repeating: 1, count: 100),
                                           logicalPath: "gen/exact") else {
            return XCTFail("data exactly at the cap must be stored")
        }
        guard case .skippedTooLarge = try s.addData(Data(repeating: 1, count: 101),
                                                   logicalPath: "gen/over") else {
            return XCTFail("data over the cap must be skipped")
        }
        XCTAssertEqual(s.skippedFiles, 1)
    }

    /// nil = unlimited is the §3.4 DEFAULT, and it matters: a silent cap would
    /// create exactly the "thought it was backed up, it wasn't" gap a backup
    /// must never have.
    func testNilCapMeansUnlimited() throws {
        let s = store(cap: nil)
        guard case .stored = try s.addFile(at: try file("big", bytes: 300_000),
                                           logicalPath: "shared/big") else {
            return XCTFail("with no cap, nothing may be skipped")
        }
        XCTAssertEqual(s.skippedFiles, 0)
        XCTAssertNil(s.maxFileBytesForManifest)
    }

    /// A zero cap is degenerate but must behave consistently with `>`: an empty
    /// file is 0 bytes, which is not > 0, so it is kept.
    func testZeroCapKeepsEmptyFilesAndSkipsEverythingElse() throws {
        let s = store(cap: 0)
        guard case .stored = try s.addFile(at: try file("empty", bytes: 0),
                                           logicalPath: "shared/empty") else {
            return XCTFail("an empty file is not over a zero cap")
        }
        guard case .skippedTooLarge = try s.addFile(at: try file("one", bytes: 1),
                                                   logicalPath: "shared/one") else {
            return XCTFail("one byte is over a zero cap")
        }
    }

    /// Skips accumulate across files rather than only recording the last one.
    func testSkippedCountersAccumulate() throws {
        let s = store(cap: 10)
        _ = try s.addFile(at: try file("a", bytes: 50), logicalPath: "shared/a")
        _ = try s.addFile(at: try file("b", bytes: 70), logicalPath: "shared/b")
        XCTAssertEqual(s.skippedFiles, 2)
        XCTAssertEqual(s.skippedBytes, 120)
        XCTAssertEqual(s.skippedPaths.map(\.path), ["shared/a", "shared/b"])
    }

    /// The configured cap is reported even when nothing was skipped, so a
    /// reader can tell "unlimited" apart from "capped, nothing exceeded it".
    func testConfiguredCapIsReportedForTheManifest() {
        XCTAssertEqual(store(cap: 4096).maxFileBytesForManifest, 4096)
    }

    // MARK: - Content addressing

    /// Identical content must be stored once. Dedup is what keeps a package
    /// with many copies of one attachment from multiplying its size.
    func testIdenticalContentIsDeduplicated() throws {
        let s = store(cap: nil)
        let a = try file("dup-a", bytes: 2048)
        let b = try file("dup-b", bytes: 2048)   // same bytes, different name

        guard case .stored(let hash1, _) = try s.addFile(at: a, logicalPath: "shared/a") else {
            return XCTFail("first copy must be stored")
        }
        guard case .duplicate(let hash2, _) = try s.addFile(at: b, logicalPath: "shared/b") else {
            return XCTFail("second identical copy must be reported as a duplicate")
        }
        XCTAssertEqual(hash1, hash2)
        // Only the first copy contributes bytes.
        XCTAssertEqual(s.totalBytesStored, 2048)
    }

    func testDifferentContentGetsDifferentHashes() throws {
        let s = store(cap: nil)
        guard case .stored(let h1, _) = try s.addData(Data("one".utf8), logicalPath: "g/1"),
              case .stored(let h2, _) = try s.addData(Data("two".utf8), logicalPath: "g/2") else {
            return XCTFail("both must be stored")
        }
        XCTAssertNotEqual(h1, h2)
    }

    // MARK: - ZIP central directory

    /// Build a real ZIP with the system zipper, then read it back with our own
    /// parser. Testing against a hand-built byte blob would only prove the
    /// parser agrees with the test's idea of a ZIP.
    private func makeZip(files: [String: Data]) throws -> URL {
        let src = dir.appendingPathComponent("zipsrc-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: src, withIntermediateDirectories: true)
        for (name, data) in files {
            let out = src.appendingPathComponent(name)
            try FileManager.default.createDirectory(at: out.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try data.write(to: out)
        }
        var zipURL: URL!
        var coordError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: src, options: .forUploading,
                                       error: &coordError) { readURL in
            let dest = dir.appendingPathComponent("made-\(UUID().uuidString).zip")
            do {
                try FileManager.default.copyItem(at: readURL, to: dest)
                zipURL = dest
            } catch { copyError = error }
        }
        if let coordError { throw coordError }
        if let copyError { throw copyError }
        return zipURL
    }

    private func entries(of zip: URL) throws -> [BackupZipExtractor.Entry] {
        let handle = try FileHandle(forReadingFrom: zip)
        defer { try? handle.close() }
        return try BackupZipExtractor.centralDirectory(handle: handle)
    }

    func testCentralDirectoryListsEveryEntry() throws {
        let zip = try makeZip(files: [
            "manifest.json": Data(#"{"format":"minisbak/1"}"#.utf8),
            "data/sessions.jsonl": Data("{\"id\":1}\n{\"id\":2}\n".utf8),
            "blobs/aa/hash": Data(repeating: 7, count: 5000),
        ])
        let names = Set(try entries(of: zip).map(\.name))
        for expected in ["manifest.json", "data/sessions.jsonl", "blobs/aa/hash"] {
            XCTAssertTrue(names.contains { $0.hasSuffix(expected) },
                          "central directory should list \(expected); got \(names)")
        }
    }

    /// Round-trip through our own extractor: every file must come back byte-identical.
    func testExtractRestoresContentExactly() throws {
        let payloads: [String: Data] = [
            "manifest.json": Data(#"{"format":"minisbak/1","device_name":"x"}"#.utf8),
            "data/a.jsonl": Data((0..<200).map { "line \($0)\n" }.joined().utf8),
            "blobs/bb/big": Data(repeating: 0x33, count: 60_000),
        ]
        let zip = try makeZip(files: payloads)
        let out = dir.appendingPathComponent("extracted", isDirectory: true)
        try BackupZipExtractor.extract(zip, to: out)

        for (name, expected) in payloads {
            // The system zipper may nest everything under a top folder; find
            // the file by suffix rather than assuming the layout.
            let found = FileManager.default
                .enumerator(at: out, includingPropertiesForKeys: nil)?
                .compactMap { $0 as? URL }
                .first { $0.path.hasSuffix(name) }
            let url = try XCTUnwrap(found, "extracted archive should contain \(name)")
            XCTAssertEqual(try Data(contentsOf: url), expected,
                           "\(name) must round-trip byte-identically")
        }
    }

    /// An empty file inside the archive is a real case (a category with no
    /// rows writes an empty .jsonl) and a classic off-by-one for a ZIP reader.
    func testEmptyMemberRoundTrips() throws {
        let zip = try makeZip(files: ["data/empty.jsonl": Data(),
                                      "data/nonempty.jsonl": Data("x".utf8)])
        let out = dir.appendingPathComponent("extracted-empty", isDirectory: true)
        try BackupZipExtractor.extract(zip, to: out)

        let found = FileManager.default
            .enumerator(at: out, includingPropertiesForKeys: nil)?
            .compactMap { $0 as? URL }
            .first { $0.path.hasSuffix("data/empty.jsonl") }
        let url = try XCTUnwrap(found, "the empty member must be extracted")
        XCTAssertEqual(try Data(contentsOf: url).count, 0)
    }

    /// Not a ZIP at all must be refused rather than misparsed.
    func testNonZipIsRejected() throws {
        let bogus = dir.appendingPathComponent("bogus.zip")
        try Data("this is definitely not a zip archive".utf8).write(to: bogus)
        XCTAssertThrowsError(try entries(of: bogus)) { error in
            guard case BackupZipExtractor.ExtractError.notAZip = error else {
                return XCTFail("expected notAZip, got \(error)")
            }
        }
    }

    func testEmptyFileIsRejectedAsNotAZip() throws {
        let empty = dir.appendingPathComponent("empty.zip")
        try Data().write(to: empty)
        XCTAssertThrowsError(try entries(of: empty))
    }
}
