import Foundation

// [T-child-delete-storage] Pure helpers behind "delete a session" and
// "how much does a session weigh": walking the parent/child tree of agent
// sessions, measuring and removing a session's on-disk directory, and
// rolling child sizes up onto their root parent. Kept free of ChatStore /
// UI so they can be unit-tested against a temp directory and a dictionary.

enum SessionTree {
    /// `roots` plus every descendant, any depth, cycle-safe. `children`
    /// returns the direct children of an id.
    static func withDescendants(_ roots: some Sequence<String>,
                                children: (String) -> [String]) -> [String] {
        var seen = Set<String>()
        var order: [String] = []
        var queue = Array(roots)
        while !queue.isEmpty {
            let id = queue.removeFirst()
            guard !id.isEmpty, seen.insert(id).inserted else { continue }
            order.append(id)
            queue.append(contentsOf: children(id))
        }
        return order
    }

    /// Walk `parentOf` (child → parent) up to the top-level session.
    /// A dangling or cyclic parent chain resolves to the last id reached.
    static func rootId(of id: String, parentOf: [String: String]) -> String {
        var cur = id
        var seen: Set<String> = [id]
        while let p = parentOf[cur], !p.isEmpty, seen.insert(p).inserted {
            cur = p
        }
        return cur
    }

    /// Per-session byte sizes folded onto their root parent. Children are
    /// not listed separately in the result.
    static func aggregateOntoRoots(_ sizes: [String: Int64], parentOf: [String: String]) -> [String: Int64] {
        var out: [String: Int64] = [:]
        for (id, size) in sizes {
            out[rootId(of: id, parentOf: parentOf), default: 0] += size
        }
        return out
    }
}

enum SessionStorageCleanup {
    /// Only real session ids (UUID strings) name a per-session directory
    /// under `MinisChat/minis`; `shared`, `skills`, `memory` … are global
    /// buckets and must never be removed by a session delete.
    static func isSessionDirectoryName(_ name: String) -> Bool {
        UUID(uuidString: name) != nil
    }

    static func directory(for sessionId: String, minisBase: URL) -> URL {
        minisBase.appendingPathComponent(sessionId, isDirectory: true)
    }

    /// Remove `<minisBase>/<sessionId>` in full (workspace, offloads,
    /// browser, attachments, …). Idempotent; refuses non-session names.
    /// Returns true when something was removed.
    @discardableResult
    static func removeSessionDirectory(_ sessionId: String, minisBase: URL,
                                       fm: FileManager = .default) -> Bool {
        guard isSessionDirectoryName(sessionId) else { return false }
        let dir = directory(for: sessionId, minisBase: minisBase)
        guard fm.fileExists(atPath: dir.path) else { return false }
        do {
            try fm.removeItem(at: dir)
            return true
        } catch {
            return false
        }
    }

    struct Measurement: Equatable {
        var fileCount = 0
        var bytes: Int64 = 0
        var sampleNames: [String] = []
    }

    /// Regular files under each session directory: count, bytes, and the
    /// first few names (for the confirmation sheet).
    static func measure(_ sessionIds: some Sequence<String>, minisBase: URL,
                        sampleLimit: Int = 3, fm: FileManager = .default) -> Measurement {
        var m = Measurement()
        for id in sessionIds {
            let dir = directory(for: id, minisBase: minisBase)
            guard let e = fm.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) else { continue }
            for case let url as URL in e {
                let v = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard v?.isRegularFile == true else { continue }
                m.fileCount += 1
                m.bytes += Int64(v?.fileSize ?? 0)
                if m.sampleNames.count < sampleLimit { m.sampleNames.append(url.lastPathComponent) }
            }
        }
        return m
    }
}
