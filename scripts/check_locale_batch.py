#!/usr/bin/env python3
"""Validate one translation batch before it is spliced into the catalog.

Catches the failure modes that are invisible in review but break at runtime or
ship an untranslated string:

  1. coverage      - every source key present, no stray keys
  2. format specs  - %@ / %lld / %1$s preserved in count, order and spelling
  3. newline/tabs  - leading/trailing whitespace and \n structure preserved
  4. untranslated  - value identical to the English source (often a real miss;
                     legitimate for product names, so it reports rather than fails)

Usage: scripts/check_locale_batch.py <source_keys.json> <translations.json>
"""
import json
import re
import sys

# Deliberately does NOT allow the C space-flag ("% f"): no key in this catalog
# uses it, whereas literal "85% full" is real prose and would otherwise match.
SPEC = re.compile(r"%(?:\d+\$)?[-+#0]*[\d.*]*(?:lld|llu|ld|lu|zu|d|u|f|@|s)")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src = json.load(open(sys.argv[1], encoding="utf-8"))
    tr = json.load(open(sys.argv[2], encoding="utf-8"))
    src_set = set(src)

    problems, notes = [], []

    missing = src_set - set(tr)
    extra = set(tr) - src_set
    if missing:
        problems.append(f"{len(missing)} key(s) MISSING: {sorted(missing)[:5]}")
    if extra:
        problems.append(f"{len(extra)} key(s) NOT IN SOURCE: {sorted(extra)[:5]}")

    for k, v in tr.items():
        if not isinstance(v, str):
            problems.append(f"non-string value for {k!r}")
            continue
        a, b = SPEC.findall(k), SPEC.findall(v)
        if a != b:
            problems.append(f"format specs {a} -> {b} in {k[:60]!r}")
        if k.count("\n") != v.count("\n"):
            problems.append(f"newline count {k.count(chr(10))} -> {v.count(chr(10))} in {k[:60]!r}")
        if (k[:1].isspace() or k[-1:].isspace()) and (k[:1] != v[:1] or k[-1:] != v[-1:]):
            problems.append(f"edge whitespace changed in {k[:60]!r}")
        if v == k and len(k) > 3 and re.search(r"[A-Za-z]{4}", k):
            notes.append(k)

    for p in problems:
        print("FAIL " + p)
    if notes:
        print(f"\nnote: {len(notes)} value(s) identical to English "
              f"(fine for product/technical terms, check they are deliberate):")
        for n in notes[:20]:
            print("   = " + n[:70])

    print(f"\n{len(tr)}/{len(src_set)} keys, {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
