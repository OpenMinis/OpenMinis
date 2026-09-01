#!/usr/bin/env python3
"""Add a whole NEW LOCALE to every existing entry in Localizable.xcstrings.

Companion to `add_localization.py`, which deliberately refuses this job: that
script only inserts keys that do not exist yet and asserts `modified=0`, but
adding a locale means touching all ~2.2k existing entries. This one does that
one job, under the same core rule:

  **Never reserialise the catalog.** Xcode writes `"key" : {` and `json.dump`
  writes `"key": {`, so a round-trip reflows all ~80k lines and collides with
  every other session working in this shared tree. We splice text and re-parse
  to verify.

Guarantees, checked after the edit and rolled back on any violation:
  - the key set is unchanged (none lost, none added)
  - every PRE-EXISTING locale value is byte-identical (no other locale touched)
  - the new locale is present exactly on the keys we were given

Usage:
    scripts/add_locale.py <locale> <translations.json> [--dry-run]

`translations.json` is `{source_key: translated_string}`. Keys absent from the
file are left without the new locale (they fall back to English at runtime,
which is the honest state for an untranslated string).
"""
import argparse
import json
import os
import re
import sys

CATALOG = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "src", "ios", "Localizable.xcstrings")


def esc(s):
    """Escape exactly the way Xcode does for xcstrings values."""
    return json.dumps(s, ensure_ascii=False)


def snapshot(doc):
    """(key -> {locale -> value}) used to prove nothing else moved."""
    out = {}
    for key, ent in doc["strings"].items():
        locs = {}
        for loc, l in (ent.get("localizations") or {}).items():
            su = l.get("stringUnit")
            locs[loc] = su.get("value") if su else json.dumps(l, sort_keys=True)
        out[key] = locs
    return out


def build_block(locale, value, indent):
    """Render one `"<locale>" : { "stringUnit" : {...} },` block."""
    i = " " * indent
    return (
        f'{i}"{locale}" : {{\n'
        f'{i}  "stringUnit" : {{\n'
        f'{i}    "state" : "translated",\n'
        f'{i}    "value" : {esc(value)}\n'
        f'{i}  }}\n'
        f'{i}}}'
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("locale")
    ap.add_argument("translations")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    locale = args.locale
    path = os.path.normpath(CATALOG)

    with open(args.translations, encoding="utf-8") as f:
        tr = json.load(f)
    with open(path, encoding="utf-8") as f:
        text = f.read()

    before_doc = json.loads(text)
    before = snapshot(before_doc)
    n_keys_before = len(before_doc["strings"])

    missing = [k for k in tr if k not in before_doc["strings"]]
    if missing:
        print(f"ERROR: {len(missing)} key(s) not in catalog, e.g. {missing[:3]}")
        return 1

    already = [k for k in tr if locale in before.get(k, {})]
    todo = [k for k in tr if k not in already]
    print(f"catalog  : {path}")
    print(f"locale   : {locale}")
    print(f"requested: {len(tr)}  ->  to add: {len(todo)}, already present: {len(already)}")
    if not todo:
        print("nothing to do.")
        return 0

    # Walk the raw text entry by entry. Anchoring on the exact `    "<key>" : {`
    # line that json also sees keeps us honest about which entry we are in.
    lines = text.split("\n")
    key_line = re.compile(r'^    ((?:"(?:[^"\\]|\\.)*")) : \{')

    # Map each catalog key to the line index where its entry starts.
    starts = {}
    for i, ln in enumerate(lines):
        m = key_line.match(ln)
        if m:
            try:
                k = json.loads(m.group(1))
            except ValueError:
                continue
            if k in before_doc["strings"] and k not in starts:
                starts[k] = i

    unresolved = [k for k in todo if k not in starts]
    if unresolved:
        print(f"ERROR: could not locate {len(unresolved)} entr(ies) in raw text,"
              f" e.g. {unresolved[:3]}")
        return 1

    # Edit from the bottom up so earlier line numbers stay valid.
    edits = 0
    for key in sorted(todo, key=lambda k: starts[k], reverse=True):
        start = starts[key]
        # Find this entry's closing line (4-space `},` or `}`), which bounds it.
        end = start + 1
        while end < len(lines) and not re.match(r"^    \},?$", lines[end]):
            end += 1
        body = lines[start:end + 1]

        loc_hdr = None
        for j, ln in enumerate(body):
            if ln.strip() == '"localizations" : {':
                loc_hdr = j
                break

        if loc_hdr is None:
            # Bare `{}` or metadata-only entry: give it a localizations block.
            # Only the 14 punctuation/number keys are bare, and we never
            # translate those, so this path is defensive rather than routine.
            print(f"  ! skipping entry with no localizations block: {key[:50]}")
            continue

        # Collect the existing locale sub-blocks at the 8-space level so we can
        # insert alphabetically -- Xcode keeps them sorted and a stray order
        # would show up as noise in every future diff.
        indent = 8
        sub = re.compile(r'^ {8}"([A-Za-z0-9_\-]+)" : \{')
        entries = []  # (locale, first_line, last_line) relative to body
        j = loc_hdr + 1
        while j < len(body):
            m = sub.match(body[j])
            if m:
                depth = 0
                k2 = j
                while k2 < len(body):
                    depth += body[k2].count("{") - body[k2].count("}")
                    if depth == 0:
                        break
                    k2 += 1
                entries.append((m.group(1), j, k2))
                j = k2 + 1
                continue
            if re.match(r"^ {6}\},?$", body[j]):
                break
            j += 1

        if not entries:
            print(f"  ! skipping entry with empty localizations: {key[:50]}")
            continue

        block = build_block(locale, tr[key], indent)
        after = [e for e in entries if e[0] > locale]
        if after:
            at = after[0][1]                      # insert before first later locale
            body.insert(at, block + ",")
        else:
            last = entries[-1]
            body[last[2]] = body[last[2]] + ","   # comma onto previous last
            body.insert(last[2] + 1, block)

        lines[start:end + 1] = body
        edits += 1

    new_text = "\n".join(lines)

    # ---- verify before writing -------------------------------------------
    try:
        after_doc = json.loads(new_text)
    except ValueError as e:
        print(f"ABORT: result is not valid JSON: {e}")
        return 1

    after = snapshot(after_doc)
    problems = []

    lost = sorted(set(before) - set(after))
    added_keys = sorted(set(after) - set(before))
    if lost:
        problems.append(f"{len(lost)} key(s) LOST: {lost[:5]}")
    if added_keys:
        problems.append(f"{len(added_keys)} key(s) ADDED: {added_keys[:5]}")

    touched = []
    for k, locs in before.items():
        if k not in after:
            continue
        for loc, val in locs.items():
            if loc == locale:
                continue
            if after[k].get(loc) != val:
                touched.append((k, loc))
    if touched:
        problems.append(f"{len(touched)} PRE-EXISTING locale value(s) changed: {touched[:5]}")

    got = sum(1 for k in tr if locale in after.get(k, {}))
    if got != len(tr):
        problems.append(f"expected {locale} on {len(tr)} keys, found {got}")

    if problems:
        print("\nABORT -- not written:")
        for p in problems:
            print("  * " + p)
        return 1

    if args.dry_run:
        print(f"\n--dry-run: {edits} entr(ies) would change; all invariants hold.")
        return 0

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_text)

    print(f"\nOK  keys {n_keys_before} -> {len(after_doc['strings'])} (unchanged), "
          f"other locales untouched, {locale} now on {got} keys.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
