// Tests for [T-ios-skill-backup-fakefs] — files restored into a skill's
// directory by the backup importer (scripts, resources; anything but
// SKILL.md) must be registered in iSH's fakefs meta.db right away, not on
// the next cold-start mount walk. Also covers the sibling gap in
// SkillStore.writeSkillFile.
//
// Pure logic (the path mapping the restore uses to find the skill dirs it
// touched, and the linux-path derivation) is copied and exercised; the rest
// are source greps for the wiring, since meta.db needs the device rootfs.
//
// Standalone (`swift SkillBackupFakefsTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Copied logic

/// Skill ids touched by a set of backup index paths (importSkills).
func restoredSkillIds(_ paths: [String]) -> Set<String> {
    Set(paths.compactMap { path -> String? in
        let parts = path.split(separator: "/", maxSplits: 2).map(String.init)
        guard parts.count >= 3, parts[0] == "skills" else { return nil }
        return parts[1]
    })
}
/// Linux paths registerFakefsMetadataRecursively produces for a host tree.
func linuxEntries(hostRoot: String, relative: [(String, Bool)], prefix: String) -> [(String, Bool)] {
    [(prefix, true)] + relative.map { ("\(prefix)/\($0.0)", $0.1) }
}

print("▶️  restored skill ids from the file index")
checkEq("two skills, nested paths, SKILL.md excluded by shape",
        restoredSkillIds(["skills/a1/scripts/run.sh", "skills/a1/scripts/lib/x.py", "skills/b2/README.md", "chats/s1/out.zip", "skills/onlydir"]),
        Set(["a1", "b2"]))
checkEq("empty index → no ids", restoredSkillIds([]), Set<String>())

print("\n▶️  linux paths for a restored tree")
let entries = linuxEntries(hostRoot: "/host/skills/a1",
                           relative: [("scripts", true), ("scripts/run.sh", false)],
                           prefix: "/var/minis/skills/a1")
checkEq("dir itself first", entries[0].0, "/var/minis/skills/a1")
check("dir itself is a directory", entries[0].1)
checkEq("nested script path", entries[2].0, "/var/minis/skills/a1/scripts/run.sh")
check("script is a file", !entries[2].1)

// MARK: - Source invariants

print("\n▶️  source invariants")
let root = "../../"
func src(_ p: String) -> String { (try? String(contentsOfFile: root + p, encoding: .utf8)) ?? "" }
let coord = src("Agent/ISH/ISHExecutionCoordinator.swift")
let importer = src("Agent/Backup/BackupImporter+Categories.swift")
let store = src("Agent/Session/SkillStore.swift")
check("sources readable", !coord.isEmpty && !importer.isEmpty && !store.isEmpty)

check("coordinator exposes the recursive registrar (not private)",
      coord.contains("    func registerFakefsMetadataRecursively(for hostDir: URL, linuxPrefix: String) -> Int {"))
check("performMount now uses it (no duplicated walk)",
      coord.contains("let registered = registerFakefsMetadataRecursively(for: persistDir, linuxPrefix: linuxDir)")
        && coord.components(separatedBy: "fm.enumerator(at: persistDir").count == 1)
check("registrar batches through batchEnsureFakefsMetadata",
      coord.contains("        batchEnsureFakefsMetadata(metaEntries)\n        return metaEntries.count"))

check("importSkills registers after restoreFileTree, gated on files written",
      importer.contains("if files.written > 0 {\n            let restoredSkillIds"))
check("importSkills uses the skills linux prefix per skill id",
      importer.contains("linuxPrefix: \"\\(AIChatViewModel.minisSkillsLinuxDir)/\\(skillId)\")"))
check("importSkills awaits the coordinator actor",
      importer.contains("await ISHExecutionCoordinator.shared.registerFakefsMetadataRecursively("))
check("restoreFileTree itself is untouched (still the shared copier)",
      importer.contains("func restoreFileTree(root: URL,") && !importer.contains("batchEnsureFakefsMetadata"))
check("Shared Files keeps its no-meta.db policy",
      importer.contains("// Per §3.2 no meta.db write is needed"))

check("writeSkillFile registers the non-SKILL.md path",
      store.contains("let linuxPath = \"/var/minis/skills/\\(skillId)/\\(relativePath)\"\n            ensureParentDirsInMetaDB(for: linuxPath)\n            ensureFakefsMetadata(for: linuxPath, isDirectory: false)\n\n            // Update skill's updatedAt"))
check("writeSkillFile still routes SKILL.md through updateSkillContent",
      store.contains("if relativePath == \"SKILL.md\" {\n            try updateSkillContent(skillId, newContent: content)"))

print(failures == 0 ? "\n✅ All skill-backup fakefs tests passed" : "\n❌ \(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
