#!/usr/bin/env python3
"""Architecture policy ratchet — keeps oversized source files on a one-way
diet (taixu-style architecture-policy, adapted from the UI review report).

Rules (policy lives in scripts/ci/architecture-policy.json):
  1. NEW files may not exceed `newFileMaxLines` (800).
  2. Files already over the cap when the policy landed are registered in
     `baseline` with their line count at registration time. A baseline entry
     is a CEILING: the file may shrink freely but may never grow past it.
  3. The baseline itself only tightens:
       - file grew past its ceiling            -> FAIL, shrink it or extract
       - file shrank below its ceiling         -> FAIL, lower the entry
         (run `--update-baseline` — it can only lower or remove entries,
         never raise them, so it is always safe to run)
       - baseline entry with no file on disk   -> FAIL, delete the entry
  4. A baseline file that drops under `newFileMaxLines` graduates: remove its
     entry entirely (it is now an ordinary file governed by rule 1).

Exit code 0 = policy satisfied. Non-zero = violations listed on stdout.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
POLICY_PATH = pathlib.Path(__file__).resolve().parent / "architecture-policy.json"


def count_lines(path: pathlib.Path) -> int:
    """Editor-style line count (a final line without a newline still counts)."""
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        return len(fh.read().splitlines())


def watched_files(roots: list[str]) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for root in roots:
        base = REPO_ROOT / root
        if not base.is_dir():
            print(f"error: watched root missing on disk: {root}", file=sys.stderr)
            sys.exit(2)
        files.extend(p for p in base.rglob("*.kt") if p.is_file())
    return sorted(files)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--update-baseline",
        action="store_true",
        help="Tighten the baseline to current line counts. Only ever lowers "
        "entries or removes them (graduated/stale files); never raises one.",
    )
    args = ap.parse_args()

    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    cap: int = policy["newFileMaxLines"]
    roots: list[str] = policy["watchedRoots"]
    baseline: dict[str, int] = policy["baseline"]

    files = watched_files(roots)
    by_rel = {str(p.relative_to(REPO_ROOT)): count_lines(p) for p in files}

    # Tighten FIRST so the check below validates the post-update state
    # (otherwise a just-lowered entry would still be reported as stale).
    if args.update_baseline:
        changed = False
        for rel in sorted(baseline):
            lines = by_rel.get(rel)
            if lines is None:
                del baseline[rel]  # stale entry
                changed = True
            elif lines <= cap:
                del baseline[rel]  # graduated — ordinary file now
                changed = True
            elif lines < baseline[rel]:
                baseline[rel] = lines  # tighten to the new count
                changed = True
        if changed:
            policy["baseline"] = dict(sorted(baseline.items()))
            POLICY_PATH.write_text(
                json.dumps(policy, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            print("baseline tightened (see the diff for the lowered entries)")
        else:
            print("baseline already tight")

    violations: list[str] = []

    # Rule 3a: baseline file grew past its ceiling, or shrank without the
    # entry being lowered (a stale ceiling lets the file grow back for free).
    for rel, ceiling in sorted(baseline.items()):
        lines = by_rel.get(rel)
        if lines is None:
            violations.append(
                f"stale baseline entry (file no longer exists): {rel} — delete the entry"
            )
        elif lines > ceiling:
            violations.append(
                f"{rel}: {lines} lines > ceiling {ceiling} — extract a piece or "
                f"split the file (the ceiling never rises)"
            )
        elif lines < ceiling:
            violations.append(
                f"{rel}: shrank to {lines} lines but ceiling is {ceiling} — "
                f"run --update-baseline and commit the lowered entry"
            )

    # Rules 1+3b: unregistered files over the new-file cap.
    for rel, lines in sorted(by_rel.items()):
        if rel in baseline:
            continue
        if lines > cap:
            violations.append(
                f"{rel}: {lines} lines > new-file cap {cap} — split it before merging"
            )

    if violations:
        print(f"architecture policy: {len(violations)} violation(s)")
        for v in violations:
            print(f"  - {v}")
        print(
            "\nThe baseline only tightens. To re-snapshot after a legitimate "
            "extraction run:\n"
            "  python3 scripts/ci/check_architecture_policy.py --update-baseline\n"
            "and commit the lowered entries together with your change."
        )
        return 1

    registered = sum(1 for rel in by_rel if rel in baseline)
    print(
        f"architecture policy: OK "
        f"({len(by_rel)} files, cap {cap}, {registered} on baseline)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
