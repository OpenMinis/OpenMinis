import XCTest
@testable import Minis

/// [T-sftp-absolute-path] SFTP paths are filesystem-absolute; every other
/// backend's are relative to the folder baked into its fs spec.
///
/// Both halves matter and pull in opposite directions:
///   * Stripping a leading `/` on SFTP rewrote the user's `/srv/backup` into a
///     home-relative `srv/backup`, which usually does not exist — the "can't
///     pick the root directory" report.
///   * NOT stripping it on WebDAV lets the path escape the configured folder
///     and resolve against the server root — a package written to
///     `http://host/backup-….minisbak` instead of `…/backups/…`, which is the
///     shipped bug commit 1dec9e650 fixed.
/// So these tests pin the split, not just the new behaviour.
@MainActor
final class SFTPAbsolutePathTests: XCTestCase {

    private func remote(backend: String, path: String) -> RcloneRemoteStore.Remote {
        RcloneRemoteStore.Remote(name: "t", backend: backend, params: [:],
                                 path: path, createdAt: Date())
    }

    // MARK: - Backend capability

    func testOnlySFTPUsesAbsolutePaths() {
        XCTAssertTrue(RcloneBackendCatalog.usesAbsolutePaths("sftp"))
        for other in ["webdav", "smb", "s3", "ftp", "drive", ""] {
            XCTAssertFalse(RcloneBackendCatalog.usesAbsolutePaths(other),
                           "\(other) must keep the relative-path semantics")
        }
    }

    // MARK: - join()

    func testSFTPJoinKeepsLeadingSlash() {
        XCTAssertEqual(remote(backend: "sftp", path: "/srv/backup").join("pkg.minisbak"),
                       "/srv/backup/pkg.minisbak")
    }

    func testSFTPJoinHandlesRootAndHomeRelative() {
        XCTAssertEqual(remote(backend: "sftp", path: "/").join("p"), "/p")
        // Empty path = the login user's home; a bare name is correct there.
        XCTAssertEqual(remote(backend: "sftp", path: "").join("p"), "p")
        XCTAssertEqual(remote(backend: "sftp", path: "backups").join("p"), "backups/p")
        // A trailing slash must not double up.
        XCTAssertEqual(remote(backend: "sftp", path: "/srv/").join("p"), "/srv/p")
    }

    /// The 1dec9e650 regression guard: WebDAV must still be trimmed on BOTH
    /// sides, so a server-root destination cannot escape the fs spec's folder.
    func testWebDAVJoinStillStripsLeadingSlash() {
        XCTAssertEqual(remote(backend: "webdav", path: "/backups").join("p"), "backups/p")
        XCTAssertEqual(remote(backend: "webdav", path: "").join("p"), "p")
        XCTAssertEqual(remote(backend: "webdav", path: "/").join("p"), "p")
        XCTAssertEqual(remote(backend: "s3", path: "/bucketdir").join("p"), "bucketdir/p")
    }

    // MARK: - Typed-path normalisation

    func testSFTPInputKeepsAbsoluteFormAndTranslatesHome() {
        let f = { RcloneAddServerView.normalizedInputPath($0, isSFTP: true) }
        XCTAssertEqual(f("/etc"), "/etc")
        XCTAssertEqual(f("/srv/backup/"), "/srv/backup")   // trailing slash dropped
        XCTAssertEqual(f("/"), "/")                         // real filesystem root
        XCTAssertEqual(f("  /srv/backup  "), "/srv/backup") // whitespace tolerated
        // `~` is how the header spells home; rclone expresses that as empty.
        XCTAssertEqual(f("~"), "")
        XCTAssertEqual(f("~/backups"), "backups")
        XCTAssertEqual(f("backups"), "backups")             // stays home-relative
    }

    func testNonSFTPInputIsStrippedAsBefore() {
        let f = { RcloneAddServerView.normalizedInputPath($0, isSFTP: false) }
        XCTAssertEqual(f("/backups"), "backups")
        XCTAssertEqual(f("/"), "")
        XCTAssertEqual(f("backups/"), "backups")
    }

    // MARK: - Storage round trip

    /// The reported failure was a save-then-reopen losing the leading slash.
    func testSFTPPathSurvivesUpdateRoundTrip() {
        let name = "unit-test-sftp-\(UUID().uuidString.prefix(8))"
        try? RcloneRemoteStore.add(name: name, backend: "sftp",
                                   params: ["host": "h", "user": "u"],
                                   secret: nil, path: "")
        defer { RcloneRemoteStore.remove(name: name) }

        try? RcloneRemoteStore.update(name: name, newPath: "/srv/backup")
        XCTAssertEqual(RcloneRemoteStore.remote(named: name)?.path, "/srv/backup",
                       "SFTP must keep the absolute form across a save")
    }

    func testWebDAVPathIsStillStrippedOnSave() {
        let name = "unit-test-dav-\(UUID().uuidString.prefix(8))"
        try? RcloneRemoteStore.add(name: name, backend: "webdav",
                                   params: ["url": "http://h/dav"],
                                   secret: nil, path: "")
        defer { RcloneRemoteStore.remove(name: name) }

        try? RcloneRemoteStore.update(name: name, newPath: "/backups")
        XCTAssertEqual(RcloneRemoteStore.remote(named: name)?.path, "backups",
                       "WebDAV must keep being stripped — 1dec9e650")
    }
}
