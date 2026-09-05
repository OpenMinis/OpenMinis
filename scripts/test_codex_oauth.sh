#!/usr/bin/env bash
# Runs the actual Foundation-only production helpers without booting iOS.
set -euo pipefail
task_repo_root="$(cd "$(dirname "$0")/.." && pwd)"
task_test_dir="$(mktemp -d)"
trap 'rm -rf "$task_test_dir"' EXIT
mkdir -p "$task_test_dir/Sources/CodexOAuthLogic" "$task_test_dir/Tests/CodexOAuthLogicTests"
cp "$task_repo_root/src/ios/Providers/OpenAI/OAuth/CodexOAuthToken.swift" "$task_test_dir/Sources/CodexOAuthLogic/"
cp "$task_repo_root/src/ios/Providers/OpenAI/OAuth/CodexModelCatalog.swift" "$task_test_dir/Sources/CodexOAuthLogic/"
cat > "$task_test_dir/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "CodexOAuthLogic", targets: [
    .target(name: "CodexOAuthLogic"),
    .testTarget(name: "CodexOAuthLogicTests", dependencies: ["CodexOAuthLogic"]),
])
SWIFT
{
    echo '@testable import CodexOAuthLogic'
    cat "$task_repo_root/src/ios/MinisTests/CodexOAuthTests.swift"
} > "$task_test_dir/Tests/CodexOAuthLogicTests/CodexOAuthTests.swift"
swift test --package-path "$task_test_dir"
