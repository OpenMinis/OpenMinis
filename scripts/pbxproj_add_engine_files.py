#!/usr/bin/env python3
"""Add the BrowserUse/Engine/*.swift files to Minis.xcodeproj (app target)."""
import re
import sys

PBX = "src/ios/Minis.xcodeproj/project.pbxproj"

NEW_FILES = [
    "BrowserEngineKind.swift",
    "BlinkEngineBridge.swift",
    "BlinkTabSession.swift",
    "SSRManager.swift",
    "BrowserEngineCoordinator.swift",
    "OpenInSystemBrowser.swift",
]

BROWSER_USE_GROUP_ID = "E57000030"  # the BrowserUse PBXGroup
BROWSER_USE_MARKER = "E57000011 /* BrowserUseActions.swift */,"  # first child of that group


def make_id(i: int) -> str:
    return f"E5B1{1000 + i:020X}"


def main() -> int:
    with open(PBX, "r", encoding="utf-8") as f:
        text = f.read()

    ref_ids, build_ids = [], []
    for i, name in enumerate(NEW_FILES):
        ref_ids.append(make_id(i * 2))
        build_ids.append(make_id(i * 2 + 1))

    # 1. PBXBuildFile entries (insert after the section's Begin line)
    begin = "/* Begin PBXBuildFile section */"
    assert begin in text
    build_block = "\n".join(
        f"\t\t{b} /* {n} in Sources */ = {{isa = PBXBuildFile; fileRef = {r} /* {n} */; }};"
        for b, r, n in zip(build_ids, ref_ids, NEW_FILES)
    )
    text = text.replace(begin, begin + "\n" + build_block, 1)

    # 2. PBXFileReference entries
    begin = "/* Begin PBXFileReference section */"
    assert begin in text
    ref_block = "\n".join(
        f"\t\t{r} /* {n} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {n}; sourceTree = \"<group>\"; }};"
        for r, n in zip(ref_ids, NEW_FILES)
    )
    text = text.replace(begin, begin + "\n" + ref_block, 1)

    # 3. New "Engine" PBXGroup + child entries in BrowserUse group
    engine_group_id = "E5B10000000000000000000E"
    begin = "/* Begin PBXGroup section */"
    group_block = (
        f"\t\t{engine_group_id} /* Engine */ = {{\n"
        "\t\t\tisa = PBXGroup;\n"
        "\t\t\tchildren = (\n"
        + "\n".join(f"\t\t\t\t{r} /* {n} */," for r, n in zip(ref_ids, NEW_FILES))
        + "\n\t\t\t);\n"
        '\t\t\tpath = Engine;\n'
        '\t\t\tsourceTree = "<group>";\n'
        "\t\t};"
    )
    text = text.replace(begin, begin + "\n" + group_block, 1)

    # Add Engine group as a child of the BrowserUse group
    marker = BROWSER_USE_MARKER
    assert marker in text, "BrowserUse group marker not found"
    text = text.replace(marker, f"\t\t\t\t{engine_group_id} /* Engine */,\n{marker}", 1)

    # 4. Add build files to the app target's Sources phase (the phase that
    #    already compiles BrowserUseActions.swift)
    phase_marker = "\t\tE57000001 /* BrowserUseActions.swift in Sources */,"
    assert phase_marker in text, "app Sources phase marker not found"
    add = "\n".join(f"\t\t{b} /* {n} in Sources */," for b, n in zip(build_ids, NEW_FILES))
    text = text.replace(phase_marker, phase_marker + "\n" + add, 1)

    with open(PBX, "w", encoding="utf-8") as f:
        f.write(text)

    # Verify
    ok = all(
        f"{r} /* {n} */" in text and f"{b} /* {n} in Sources */" in text
        for r, b, n in zip(ref_ids, build_ids, NEW_FILES)
    )
    print("OK" if ok else "VERIFY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
