import XCTest
@testable import Minis

/// [GH#242] The permission gate must recognise an offload command by the same
/// rule the guest kernel dispatches on.
///
/// `native_offload_lookup()` → `offload_find()` matches the BASENAME of argv[0]
/// (`strrchr(guest_path, '/')`, deps/ish/kernel/native_offload.c:186-196).
/// `extractOffloadCommand` compared the raw first token, so
/// `/usr/local/bin/apple-healthkit` matched nothing, returned nil, and the
/// caller — which reads nil as "not an offload command" — ran it with no
/// prompt. The kernel dispatched it anyway. Absolute-path spellings therefore
/// reached HealthKit / HomeKit / Photos / Location regardless of the
/// configured level, including Not Allowed.
///
/// These tests pin BOTH directions: every spelling of a registered command is
/// recognised, and nothing else becomes one by accident — a basename rule
/// applied carelessly could match `/opt/evil/apple-photos`, which is correct
/// (the kernel would dispatch that too), but must not match unrelated commands
/// that merely live in a similar path.
@MainActor
final class OffloadPermissionBypassTests: XCTestCase {

    /// Registry names must be bare for the basename rule to be sufficient: if
    /// one ever contained a slash, normalising only the caller's side would
    /// stop matching it.
    func testRegisteredNamesContainNoPathSeparator() {
        for cmd in OffloadPermissionManager.allCommands {
            XCTAssertFalse(cmd.name.contains("/"),
                           "\(cmd.name) has a path separator; extractOffloadCommand's basename match would no longer line up with it")
            XCTAssertFalse(cmd.name.isEmpty)
        }
    }

    // MARK: - The bypass

    func testAbsolutePathIsRecognised() {
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "/usr/local/bin/apple-healthkit read steps"),
            "apple-healthkit")
    }

    func testDotSlashRelativePathIsRecognised() {
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "./apple-clipboard get"),
            "apple-clipboard")
    }

    func testDotDotTraversalPathIsRecognised() {
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "/bin/../usr/local/bin/apple-photos list"),
            "apple-photos")
    }

    func testArbitraryPrefixIsRecognised() {
        // The kernel matches on basename alone, so any directory prefix
        // dispatches. The gate has to agree, or the mismatch is the bypass.
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "/tmp/anything/apple-location current"),
            "apple-location")
    }

    /// The value handed back must be the REGISTERED name: `permissionLevel`,
    /// the session-grant set and the prompt's label all key off the registry,
    /// so returning the caller's spelling would look up nothing and silently
    /// fall back to the default level.
    func testReturnsRegisteredNameNotCallerSpelling() {
        let out = OffloadPermissionManager.extractOffloadCommand(from: "/usr/local/bin/apple-calendar list")
        XCTAssertEqual(out, "apple-calendar")
        XCTAssertNotNil(OffloadPermissionManager.allCommands.first { $0.name == out },
                        "the returned value must exist in the registry")
    }

    // MARK: - Unchanged behaviour

    func testPlainCommandStillRecognised() {
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "apple-healthkit read steps"),
            "apple-healthkit")
    }

    func testBareCommandWithNoArgumentsStillRecognised() {
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "apple-clipboard"),
            "apple-clipboard")
    }

    func testLeadingWhitespaceStillTolerated() {
        XCTAssertEqual(
            OffloadPermissionManager.extractOffloadCommand(from: "   apple-photos list  "),
            "apple-photos")
    }

    func testNonOffloadCommandStillReturnsNil() {
        for cmd in ["ls -la", "echo apple-healthkit", "/bin/ls", "", "   "] {
            XCTAssertNil(OffloadPermissionManager.extractOffloadCommand(from: cmd),
                         "\(cmd.debugDescription) must not be treated as an offload command")
        }
    }

    /// A basename that merely CONTAINS a registered name is a different
    /// program and must not inherit its permission decision — the kernel
    /// compares with strcmp, not a substring test.
    func testSimilarButDifferentNameIsNotRecognised() {
        for cmd in ["apple-healthkit-helper read",
                    "not-apple-photos list",
                    "/usr/bin/apple-photos-extra list"] {
            XCTAssertNil(OffloadPermissionManager.extractOffloadCommand(from: cmd),
                         "\(cmd) is not a registered command")
        }
    }

    // MARK: - Known remaining gap

    /// Indirection is explicitly NOT covered: the registered name is not
    /// argv[0] here, so no first-token rule can see it. Pinned as a test so
    /// the limitation is recorded rather than assumed fixed — closing it means
    /// moving the check to the dispatch site (see GH#242).
    func testIndirectInvocationRemainsUndetected() {
        for cmd in ["sh -c 'apple-healthkit read steps'",
                    "env apple-photos list"] {
            XCTAssertNil(OffloadPermissionManager.extractOffloadCommand(from: cmd),
                         "documented gap: first-token analysis cannot see through indirection")
        }
    }
}
