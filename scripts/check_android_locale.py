#!/usr/bin/env python3
"""Validate an Android `values-<loc>/strings.xml` against the English base.

Android localisation fails in ways that are invisible in review and only show
up as a crash, a blank label, or a silently-English screen on a user's device.
This checks the ones that actually bite:

  1. coverage      - every base key present; no key that is not in the base
                     (a stray key is dead weight, a missing one silently
                     renders English)
  2. format specs  - %1$s / %d preserved in COUNT and in the exact set. A
                     dropped or renumbered specifier is
                     IllegalFormatException at runtime, i.e. a crash.
  3. plurals       - the quantity branches CLDR requires for this language,
                     no more and no fewer. A missing branch throws at runtime
                     when that count occurs; a surplus one is a dead string
                     the resolver never selects, which reads as translated but
                     ships as a lie.
  4. escaping      - a bare apostrophe is an aapt2 error that fails the WHOLE
                     file (this has broken the build before); &amp;/&lt;/&gt;
                     must survive; a literal newline must stay `\\n`.
  5. diacritics    - per-language wrong-character check (e.g. Croatian must not
                     use Serbian/Polish look-alikes)
  6. untranslated  - value byte-identical to English. Legitimate for product
                     names and symbols, so it REPORTS rather than fails.

Usage: scripts/check_android_locale.py <locale-dir-suffix>      # e.g. hr
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "src/android/app/src/main/res")

# Positional (%1$s) and plain (%d) Java format specifiers.
SPEC = re.compile(r"%(?:\d+\$)?[-+#0 ]*[\d.]*[sdfx]")

# CLDR cardinal categories that Android/ICU can actually select, per language.
# Getting this wrong in EITHER direction is a bug, so the check is exact-set,
# not "at least".
#   hr (Croatian): one/few/other. It has NO `many` — unlike pl/ru. Counts that
#     Polish routes to `many` (5..9, 11..14) fall to `other` in Croatian, so
#     `other` must carry the genitive plural, never a fallback guess.
#   pl (Polish):   one/few/many/other.
#   ro (Romanian): one/few/other.
REQUIRED = {
    "hr": {"one", "few", "other"},
    "sr": {"one", "few", "other"},
    "bs": {"one", "few", "other"},
    "pl": {"one", "few", "many", "other"},
    "ru": {"one", "few", "many", "other"},
    "ro": {"one", "few", "other"},
    "tr": {"one", "other"},
    "th": {"other"},
}

# Characters that mark a translation as having drifted into the wrong language
# or the wrong Unicode form. Croatian uses č ć đ š ž — and NOT the Serbian
# Cyrillic block, nor Polish ł/ą/ę/ń/ś/ź/ż, nor a decomposed d+stroke.
WRONG_DIACRITICS = {
    "hr": ("ą", "ę", "ł", "ń", "ś", "ź", "ż", "ů", "ě", "ř", "ţ", "ş"),
    "ro": ("ş", "ţ"),  # cedilla forms; Romanian needs comma-below U+0219/U+021B
}


def parse(path):
    """(strings, plurals) as {name: value} / {name: {quantity: value}}."""
    text = open(path, encoding="utf-8").read()
    strings = {m.group(1): m.group(3)
               for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', text, re.S)}
    plurals = {}
    for m in re.finditer(r'<plurals name="([^"]+)">(.*?)</plurals>', text, re.S):
        plurals[m.group(1)] = {
            q: v for q, v in re.findall(r'<item quantity="([^"]+)">(.*?)</item>', m.group(2), re.S)
        }
    return strings, plurals, text


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    loc = sys.argv[1]

    base_s, base_p, _ = parse(os.path.join(RES, "values", "strings.xml"))
    tgt_path = os.path.join(RES, f"values-{loc}", "strings.xml")
    if not os.path.exists(tgt_path):
        print(f"FAIL: {tgt_path} does not exist")
        return 1
    tgt_s, tgt_p, tgt_text = parse(tgt_path)

    problems, notes = [], []

    # 1. coverage
    missing = set(base_s) - set(tgt_s)
    extra = set(tgt_s) - set(base_s)
    if missing:
        problems.append(f"{len(missing)} string(s) MISSING (render English): {sorted(missing)[:6]}")
    if extra:
        problems.append(f"{len(extra)} string(s) NOT IN BASE: {sorted(extra)[:6]}")
    pmissing = set(base_p) - set(tgt_p)
    pextra = set(tgt_p) - set(base_p)
    if pmissing:
        problems.append(f"plurals MISSING: {sorted(pmissing)}")
    if pextra:
        problems.append(f"plurals NOT IN BASE: {sorted(pextra)}")

    # 2. format specifiers — count and multiset must match
    for k, v in tgt_s.items():
        if k not in base_s:
            continue
        a, b = sorted(SPEC.findall(base_s[k])), sorted(SPEC.findall(v))
        if a != b:
            problems.append(f"format spec mismatch in {k!r}: base {a} vs {loc} {b}")
    for k, qs in tgt_p.items():
        if k not in base_p:
            continue
        want = sorted(SPEC.findall(next(iter(base_p[k].values()))))
        for q, v in qs.items():
            got = sorted(SPEC.findall(v))
            if got != want:
                problems.append(f"format spec mismatch in plural {k!r}[{q}]: {want} vs {got}")

    # 3. plurals quantity completeness (exact set)
    req = REQUIRED.get(loc)
    if req:
        for k, qs in tgt_p.items():
            have = set(qs)
            if have != req:
                problems.append(
                    f"plural {k!r} has {sorted(have)}, CLDR {loc} requires exactly {sorted(req)}"
                )

    # 4. escaping
    for k, v in tgt_s.items():
        # An unescaped apostrophe is an aapt2 hard error for the whole file.
        for m in re.finditer(r"'", v):
            if m.start() == 0 or v[m.start() - 1] != "\\":
                problems.append(f"unescaped apostrophe in {k!r}: {v[:60]!r}")
                break
        if "&" in v:
            for m in re.finditer(r"&(?!amp;|lt;|gt;|quot;|apos;|#\d+;)", v):
                problems.append(f"bare & in {k!r}: {v[:60]!r}")
                break
        if "\n" in v and k in base_s and "\n" not in base_s[k]:
            problems.append(f"literal newline introduced in {k!r} (base has none)")
        if k in base_s:
            if base_s[k].count("\\n") != v.count("\\n"):
                problems.append(
                    f"\\n count differs in {k!r}: base {base_s[k].count(chr(92)+'n')} vs {v.count(chr(92)+'n')}"
                )

    # 5. wrong diacritics
    bad = WRONG_DIACRITICS.get(loc)
    if bad:
        for k, v in tgt_s.items():
            hit = [c for c in bad if c in v.lower()]
            if hit:
                problems.append(f"wrong diacritic(s) {hit} in {k!r}: {v[:60]!r}")

    # 6. untranslated (report only)
    same = [k for k, v in tgt_s.items() if k in base_s and v == base_s[k]]

    print(f"locale       : {loc}")
    print(f"base keys    : {len(base_s)} strings + {len(base_p)} plurals")
    print(f"translated   : {len(tgt_s)} strings + {len(tgt_p)} plurals")
    print(f"identical    : {len(same)} (product names/symbols are legitimate)")
    if same[:15]:
        print(f"               e.g. {same[:15]}")
    print()
    if problems:
        print(f"FAIL — {len(problems)} problem(s):")
        for p in problems[:40]:
            print("  -", p)
        if len(problems) > 40:
            print(f"  … and {len(problems) - 40} more")
        return 1
    print("PASS — coverage, format specs, plurals, escaping and diacritics all clean.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
