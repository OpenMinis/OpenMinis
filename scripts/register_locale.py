#!/usr/bin/env python3
"""Register a new locale in the four project files that must move together.

Adding a locale fails SILENTLY if any of these is missed, and each failure looks
like a different bug:

  * knownRegions / PBXFileReference / InfoPlist PBXVariantGroup  -> the .lproj
    ships without its permission strings (this happened to `es`)
  * Info.plist CFBundleLocalizations -> the language is not offered in iOS
    Settings and system controls fall back to English (this happened to `es`)
  * the in-app picker -> Bundle.setLanguage silently falls back to the system
    language, so the entry looks like a no-op bug

The .lproj directory itself and its InfoPlist.strings must already exist -- those
are hand-written, since App Store review reads the permission prompts.

Usage: scripts/register_locale.py <locale> "<Native Name>" <flag-emoji>
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PBX = os.path.join(ROOT, "src/ios/Minis.xcodeproj/project.pbxproj")
PLIST = os.path.join(ROOT, "src/ios/Info.plist")
PICKER = os.path.join(ROOT, "src/ios/Views/ContentView.swift")


def edit(path, checks):
    """Apply (find, make_replacement) pairs; report what changed."""
    text = open(path, encoding="utf-8").read()
    orig = text
    for probe, fn in checks:
        if probe in text:
            continue
        text = fn(text)
    if text == orig:
        return False
    open(path, "w", encoding="utf-8").write(text)
    return True


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    loc, native, flag = sys.argv[1], sys.argv[2], sys.argv[3]

    lproj = os.path.join(ROOT, "src/ios", f"{loc}.lproj", "InfoPlist.strings")
    if not os.path.exists(lproj):
        print(f"ERROR: {lproj} missing -- write the permission strings first.")
        return 1

    # A locale id may need quoting in pbxproj (e.g. "pt-BR"), a bare token
    # otherwise. Xcode quotes anything with a hyphen.
    tok = f'"{loc}"' if not re.fullmatch(r"[A-Za-z0-9_]+", loc) else loc

    # ---- 1..3: project.pbxproj -------------------------------------------
    text = open(PBX, encoding="utf-8").read()
    if f"{loc}.lproj/InfoPlist.strings" in text:
        print(f"pbxproj: {loc} already registered")
    else:
        # Allocate an unused E5IP000NN id for the file reference.
        used = set(re.findall(r"E5IP000(\d\d)", text))
        nid = next(f"E5IP000{n:02d}" for n in range(20, 99) if f"{n:02d}" not in used)

        ref = (f'\t\t{nid} /* {loc} */ = {{isa = PBXFileReference; '
               f'lastKnownFileType = text.plist.strings; name = {tok}; '
               f'path = {loc}.lproj/InfoPlist.strings; sourceTree = "<group>"; }};')
        anchor = re.search(r"^\t\tE5IP000\d\d /\* \w[\w-]* \*/ = \{isa = PBXFileReference.*$",
                           text, re.M)
        last = None
        for m in re.finditer(r"^\t\tE5IP000\d\d /\* [^*]+ \*/ = \{isa = PBXFileReference.*$",
                             text, re.M):
            last = m
        text = text[:last.end()] + "\n" + ref + text[last.end():]

        # knownRegions
        text = re.sub(r"(knownRegions = \(\n(?:\t+[^\n]*\n)*?)(\t+\);)",
                      lambda m: m.group(1) + f"\t\t\t\t{tok},\n" + m.group(2),
                      text, count=1)

        # InfoPlist.strings variant group
        text = re.sub(r"(E5IP00010 /\* InfoPlist\.strings \*/ = \{\n\t+isa = PBXVariantGroup;\n"
                      r"\t+children = \(\n(?:\t+[^\n]*\n)*?)(\t+\);)",
                      lambda m: m.group(1) + f"\t\t\t\t{nid} /* {loc} */,\n" + m.group(2),
                      text, count=1)
        open(PBX, "w", encoding="utf-8").write(text)
        print(f"pbxproj: added {loc} as {nid}")

    # ---- 4: Info.plist CFBundleLocalizations ------------------------------
    text = open(PLIST, encoding="utf-8").read()
    block = re.search(r"(<key>CFBundleLocalizations</key>\s*<array>)(.*?)(</array>)",
                      text, re.S)
    if f"<string>{loc}</string>" in block.group(2):
        print(f"Info.plist: {loc} already listed")
    else:
        new = block.group(2).rstrip() + f"\n\t\t<string>{loc}</string>\n\t"
        text = text[:block.start(2)] + new + text[block.end(2):]
        open(PLIST, "w", encoding="utf-8").write(text)
        print(f"Info.plist: added {loc}")

    # ---- 5: in-app picker -------------------------------------------------
    text = open(PICKER, encoding="utf-8").read()
    if f'LanguageOption(id: "{loc}"' in text:
        print(f"picker: {loc} already present")
    else:
        m = re.search(r"(private let supportedLanguages: \[LanguageOption\] = \[\n"
                      r"(?:.*?\n)*?)(\]\n)", text)
        entry = f'    LanguageOption(id: "{loc}",{" " * max(1, 6 - len(loc))}'
        entry += f'name: "{native}", flag: "{flag}"),\n'
        text = text[:m.end(1)] + entry + text[m.end(1):]
        open(PICKER, "w", encoding="utf-8").write(text)
        print(f"picker: added {loc} ({native} {flag})")

    return 0


if __name__ == "__main__":
    sys.exit(main())
