#!/bin/bash
set -euo pipefail
fixture_dir="$(cd "$(dirname "$0")" && pwd)"
team_source_dir="$(cd "$fixture_dir/.." && pwd)"
team_test_dir="$(mktemp -d "${TMPDIR:-/tmp}/minis-team-tests.XXXXXX")"
trap 'rm -rf "$team_test_dir"' EXIT
cp -R "$fixture_dir/Package.swift" "$fixture_dir/Sources" "$fixture_dir/Tests" "$team_test_dir/"
cp "$team_source_dir/MinisTeamStore.swift" "$team_source_dir/MinisTeamModels.swift" "$team_test_dir/Sources/TeamHarness/"
swift test --package-path "$team_test_dir"
