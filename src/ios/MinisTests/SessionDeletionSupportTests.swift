import XCTest
@testable import Minis

// [T-child-delete-storage] Tree walking, root aggregation, and per-session
// directory measurement / removal — the helpers every delete and storage
// path relies on to cover hidden agent child sessions.
final class SessionDeletionSupportTests: XCTestCase {

    // parent → children, three levels deep plus an unrelated session.
    private let children: [String: [String]] = [
        "P": ["C1", "C2"],
        "C1": ["G1"],
        "G1": ["GG1"],
        "X": ["XC"],
    ]

    func testWithDescendantsWalksEveryLevelOnce() {
        let all = SessionTree.withDescendants(["P"]) { children[$0] ?? [] }
        XCTAssertEqual(Set(all), ["P", "C1", "C2", "G1", "GG1"])
        XCTAssertEqual(all.first, "P")
        XCTAssertEqual(all.count, 5, "no duplicates")
        XCTAssertFalse(all.contains("X"))
    }

    func testWithDescendantsIsCycleSafe() {
        let cyclic: [String: [String]] = ["A": ["B"], "B": ["A"]]
        let all = SessionTree.withDescendants(["A"]) { cyclic[$0] ?? [] }
        XCTAssertEqual(Set(all), ["A", "B"])
    }

    func testRootIdAndAggregation() {
        let parentOf = ["C1": "P", "C2": "P", "G1": "C1", "GG1": "G1", "XC": "X"]
        XCTAssertEqual(SessionTree.rootId(of: "GG1", parentOf: parentOf), "P")
        XCTAssertEqual(SessionTree.rootId(of: "P", parentOf: parentOf), "P")
        XCTAssertEqual(SessionTree.rootId(of: "Q", parentOf: ["Q": "R", "R": "Q"]), "R", "cycle resolves without hanging")

        let sizes: [String: Int64] = ["P": 100, "C1": 10, "C2": 20, "G1": 1, "GG1": 2, "X": 5, "XC": 7]
        let rolled = SessionTree.aggregateOntoRoots(sizes, parentOf: parentOf)
        XCTAssertEqual(rolled["P"], 133)
        XCTAssertEqual(rolled["X"], 12)
        XCTAssertNil(rolled["C1"], "children are folded, not listed")
        XCTAssertEqual(rolled.values.reduce(0, +), sizes.values.reduce(0, +), "totals never double-count")
    }

    func testMeasureAndRemoveCoverParentAndChildOnly() throws {
        let fm = FileManager.default
        let base = fm.temporaryDirectory.appendingPathComponent("minis-test-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: base, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: base) }

        let parent = UUID().uuidString, child = UUID().uuidString, other = UUID().uuidString
        func write(_ sid: String, _ rel: String, bytes: Int) throws {
            let url = base.appendingPathComponent(sid).appendingPathComponent(rel)
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(repeating: 0xAB, count: bytes).write(to: url)
        }
        try write(parent, "workspace/report.md", bytes: 1_000)
        try write(parent, "offloads/big.bin", bytes: 5_000)
        try write(child, "workspace/notes.txt", bytes: 300)
        try write(child, "browser/shot.jpg", bytes: 700)
        try write(other, "workspace/keep.txt", bytes: 42)
        try write("shared", "docs/global.md", bytes: 9)   // global bucket

        let both = SessionStorageCleanup.measure([parent, child], minisBase: base)
        XCTAssertEqual(both.fileCount, 4)
        XCTAssertEqual(both.bytes, 7_000)
        XCTAssertEqual(both.sampleNames.count, 3)

        XCTAssertTrue(SessionStorageCleanup.removeSessionDirectory(parent, minisBase: base))
        XCTAssertTrue(SessionStorageCleanup.removeSessionDirectory(child, minisBase: base))
        XCTAssertFalse(SessionStorageCleanup.removeSessionDirectory(child, minisBase: base), "idempotent")
        XCTAssertFalse(SessionStorageCleanup.removeSessionDirectory("shared", minisBase: base), "never a global bucket")
        XCTAssertFalse(SessionStorageCleanup.removeSessionDirectory("", minisBase: base))

        XCTAssertFalse(fm.fileExists(atPath: base.appendingPathComponent(parent).path))
        XCTAssertFalse(fm.fileExists(atPath: base.appendingPathComponent(child).path))
        XCTAssertTrue(fm.fileExists(atPath: base.appendingPathComponent(other).path))
        XCTAssertTrue(fm.fileExists(atPath: base.appendingPathComponent("shared/docs/global.md").path))
        XCTAssertEqual(SessionStorageCleanup.measure([parent, child], minisBase: base), .init())
    }
}
