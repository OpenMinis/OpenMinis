// Tests for [T-ios-listsessions-perf] Phase 5c items 1+2 — the message-list
// height cache is keyed by CONTENT and kept in a capacity-bounded LRU that
// survives session change.
//
// Before: `[ObjectIdentifier: CGFloat]` keyed on the identity of the
// NSAttributedString, cleared outright on session change and on font change.
// Every re-render of unchanged text — a streaming block finalizing, a re-parse
// after compaction, a session reload — minted a new attributed string object
// and therefore missed, so a page of long assistant blocks was re-measured from
// cold through the near-O(n^2) `sizeThatFits` path. That is the applySnapshot →
// measureAttributedStringHeight → NSLayoutManager _fillLayoutHole stack behind
// the four 0.42-0.73 s main-thread hangs in the CPU Profiler trace.
//
// The danger this file guards is the opposite one: a cache that returns a
// height for the WRONG content is worse than the hang (see the stale-height-gap
// family). So the key must separate anything that can change a measurement.
//
// Standalone (`swift HeightCacheKeyTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

#if canImport(CoreGraphics)
import CoreGraphics
#endif

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Under test (mirrors CollectionViewMessageListV3.swift)

struct HeightCacheKey: Hashable {
    let contentLength: Int
    let contentHash: Int
    let width: Int
    let fontSize: Int

    init(content: String, width: CGFloat, fontSize: CGFloat) {
        self.contentLength = content.count
        self.contentHash = content.hashValue
        // 0.01 pt precision, NOT Int truncation. A wrap decision can turn on
        // a fraction of a point (@3x widths are thirds; the font-scale steps
        // land on .49/.51), and two widths that merely round to the same
        // integer must MISS rather than share a height. A distinct key only
        // costs one re-measure; a shared key can seed a wrong height, which is
        // the stale-height-gap failure again.
        self.width = Int((width * 100).rounded())
        self.fontSize = Int((fontSize * 100).rounded())
    }
}

struct HeightLRU {
    private var storage: [HeightCacheKey: CGFloat] = [:]
    private var order: [HeightCacheKey] = []
    private let capacity: Int

    init(capacity: Int) {
        self.capacity = max(1, capacity)
        storage.reserveCapacity(self.capacity)
        order.reserveCapacity(self.capacity)
    }

    var count: Int { storage.count }

    subscript(key: HeightCacheKey) -> CGFloat? {
        get { storage[key] }
        set {
            guard let newValue else {
                if storage.removeValue(forKey: key) != nil {
                    order.removeAll { $0 == key }
                }
                return
            }
            if storage.updateValue(newValue, forKey: key) == nil {
                order.append(key)
                while order.count > capacity {
                    let oldest = order.removeFirst()
                    storage.removeValue(forKey: oldest)
                }
            }
        }
    }

    mutating func removeAll() {
        storage.removeAll(keepingCapacity: true)
        order.removeAll(keepingCapacity: true)
    }
}

// MARK: - 1. Key identity

print("\n▶️  same text + width + font → same key")
let body = "# Heading\n\nSome **bold** prose with a [link](https://x.com) and a list:\n\n- one\n- two"
checkEq("identical inputs produce an equal key",
        HeightCacheKey(content: body, width: 358, fontSize: 16),
        HeightCacheKey(content: body, width: 358, fontSize: 16))
checkEq("and an equal hash",
        HeightCacheKey(content: body, width: 358, fontSize: 16).hashValue,
        HeightCacheKey(content: body, width: 358, fontSize: 16).hashValue)

print("\n▶️  any single differing input → a different key")
let base = HeightCacheKey(content: body, width: 358, fontSize: 16)
check("different content", HeightCacheKey(content: body + "!", width: 358, fontSize: 16) != base)
check("different width (rotation)", HeightCacheKey(content: body, width: 744, fontSize: 16) != base)
check("different font size (Dynamic Type)", HeightCacheKey(content: body, width: 358, fontSize: 20) != base)

// The markdown-source-vs-rendered-text distinction. These render to the SAME
// plain text of the same length, but carry different attribute runs and so
// measure to different heights — keying on the rendered string would collide.
print("\n▶️  markdown source, not rendered text")
let markupPairs: [(String, String, String)] = [
    ("bold", "**answer**", "answer"),
    ("italic", "*answer*", "answer"),
    ("heading", "# answer", "answer"),
    ("code span", "`answer`", "answer"),
    ("list item", "- answer", "answer"),
    ("blockquote", "> answer", "answer"),
    ("link", "[answer](https://example.com)", "answer"),
]
for (name, markup, plain) in markupPairs {
    let a = HeightCacheKey(content: markup, width: 358, fontSize: 16)
    let b = HeightCacheKey(content: plain, width: 358, fontSize: 16)
    check("\(name): source differs from its rendered text → distinct keys", a != b)
}

print("\n▶️  width and font size are keyed at 0.01pt, never truncated to whole points")
checkEq("sub-0.01pt width jitter maps to one key",
        HeightCacheKey(content: body, width: 358.0, fontSize: 16),
        HeightCacheKey(content: body, width: 358.004, fontSize: 16))
check("a 0.4pt width difference is a DIFFERENT key (it can change a wrap)",
      HeightCacheKey(content: body, width: 358.0, fontSize: 16)
        != HeightCacheKey(content: body, width: 358.4, fontSize: 16))
check("font-scale steps that share an integer part are DIFFERENT keys",
      HeightCacheKey(content: body, width: 358, fontSize: 14.52)
        != HeightCacheKey(content: body, width: 358, fontSize: 14.85))
check("358 and 359 do not",
      HeightCacheKey(content: body, width: 358, fontSize: 16)
        != HeightCacheKey(content: body, width: 359, fontSize: 16))

print("\n▶️  length is carried alongside the hash")
// Two bodies of different length can never share a key even in the (absurdly
// unlikely) event their hashes collide — the same belt-and-braces the existing
// userBubbleHeightCache key uses.
let k1 = HeightCacheKey(content: "short", width: 358, fontSize: 16)
let k2 = HeightCacheKey(content: String(repeating: "x", count: 5000), width: 358, fontSize: 16)
check("different lengths → different keys", k1 != k2)
checkEq("length is recorded", k1.contentLength, 5)

// MARK: - 2. LRU behaviour

print("\n▶️  LRU: cap and eviction order")
var lru = HeightLRU(capacity: 3)
func key(_ n: Int) -> HeightCacheKey {
    HeightCacheKey(content: "block-\(n)", width: 358, fontSize: 16)
}
lru[key(1)] = 100
lru[key(2)] = 200
lru[key(3)] = 300
checkEq("holds three", lru.count, 3)
checkEq("reads back", lru[key(2)], 200)

lru[key(4)] = 400
checkEq("still capped at three", lru.count, 3)
checkEq("the OLDEST insert was evicted", lru[key(1)], nil)
checkEq("the rest survive (2)", lru[key(2)], 200)
checkEq("the rest survive (3)", lru[key(3)], 300)
checkEq("and the newcomer is there", lru[key(4)], 400)

print("\n▶️  LRU: overwriting an existing key does not grow it or re-order")
var lru2 = HeightLRU(capacity: 3)
lru2[key(1)] = 1
lru2[key(2)] = 2
lru2[key(3)] = 3
lru2[key(1)] = 111              // overwrite, not insert
checkEq("count unchanged by an overwrite", lru2.count, 3)
checkEq("value updated", lru2[key(1)], 111)
lru2[key(4)] = 4                // evicts the oldest INSERT, which is still 1
checkEq("overwrite did not refresh insertion order", lru2[key(1)], nil)
checkEq("key(2) survived", lru2[key(2)], 2)

print("\n▶️  LRU: removal and clear")
var lru3 = HeightLRU(capacity: 4)
for i in 1...4 { lru3[key(i)] = CGFloat(i) }
lru3[key(2)] = nil
checkEq("explicit removal drops it", lru3[key(2)], nil)
checkEq("count reflects it", lru3.count, 3)
// And the freed slot is genuinely reusable — the order array must have dropped
// the key too, or the next insert would evict the wrong entry.
lru3[key(5)] = 5
lru3[key(6)] = 6
checkEq("after removal + 2 inserts, still capped", lru3.count, 4)
checkEq("the oldest remaining (1) was the one evicted", lru3[key(1)], nil)
checkEq("3 survived", lru3[key(3)], 3)

lru3.removeAll()
checkEq("removeAll empties it", lru3.count, 0)
lru3[key(9)] = 9
checkEq("and it still works afterwards", lru3[key(9)], 9)

print("\n▶️  LRU: capacity floor")
var tiny = HeightLRU(capacity: 0)
tiny[key(1)] = 1
checkEq("capacity 0 is clamped to 1, not a crash or an unbounded cache", tiny.count, 1)

print("\n▶️  LRU: the 300-entry cap holds under churn")
var big = HeightLRU(capacity: 300)
for i in 0..<5000 {
    big[HeightCacheKey(content: "b\(i)", width: 358, fontSize: 16)] = CGFloat(i)
}
checkEq("5000 inserts, 300 retained", big.count, 300)
checkEq("the most recent is present",
        big[HeightCacheKey(content: "b4999", width: 358, fontSize: 16)], 4999)
checkEq("an early one is long gone",
        big[HeightCacheKey(content: "b0", width: 358, fontSize: 16)], nil)

// MARK: - 3. The reuse the LRU exists for

print("\n▶️  a session revisit hits instead of re-measuring")
var sim = HeightLRU(capacity: 300)
var measures = 0
func height(_ content: String, width: CGFloat, font: CGFloat, cache: inout HeightLRU) -> CGFloat {
    let k = HeightCacheKey(content: content, width: width, fontSize: font)
    if let hit = cache[k] { return hit }
    measures += 1
    return { let h = CGFloat(content.count); cache[k] = h; return h }()
}
let page = (0..<40).map { "assistant block \($0) " + String(repeating: "word ", count: 200) }
for b in page { _ = height(b, width: 358, font: 16, cache: &sim) }
checkEq("first visit measures the page", measures, 40)
// Switch away and back — the old code cleared here.
for b in page { _ = height(b, width: 358, font: 16, cache: &sim) }
checkEq("revisit measures nothing", measures, 40)
// Re-render with identical text (stream finalize / compaction re-parse): the
// identity-keyed cache missed every one of these.
for b in page { _ = height(b, width: 358, font: 16, cache: &sim) }
checkEq("re-render of unchanged text measures nothing", measures, 40)
print("  📊 identity-keyed: 120 measures across the same 3 passes | content-keyed: 40")

// Rotation and a font change must MISS, not serve a stale height.
for b in page { _ = height(b, width: 744, font: 16, cache: &sim) }
checkEq("rotation re-measures", measures, 80)
for b in page { _ = height(b, width: 744, font: 20, cache: &sim) }
checkEq("a Dynamic Type change re-measures", measures, 120)

// MARK: - 4. The untouched constants (spec item 4)

print("\n▶️  nothing that was ruled out has moved")
let listPath = "../../Agent/MessageList/CollectionViewMessageListV3.swift"
guard let src = try? String(contentsOfFile: listPath, encoding: .utf8) else {
    print("  ❌ could not read \(listPath)"); failures += 1; exit(1)
}

// Item 3 of the proposal was explicitly NOT approved.
check("the 8000-char watchdog is unchanged", src.contains("attrStr.length <= 8000"))
check("the sizeThatFits path is still there", src.contains("measureTextView.sizeThatFits"))
check("the bare-NSLayoutManager fallback is still there", src.contains("lm.ensureLayout(for: container)"))
// The precalc pass itself must still seed heights.
check("the precalc seed is still called", src.contains("layout.setPrecalcHeight(height + 4, at: i)"))

// The cache must be content-keyed now, and NOT identity-keyed.
check("the cache is the content-keyed LRU", src.contains("HeightLRU(capacity: 300)"))
check("no ObjectIdentifier key remains for it", !src.contains("ObjectIdentifier(attrStr)"))
// Font size must be in the key, or a Dynamic Type change would collide now
// that the cache survives reconfigureAllCells.
check("the key carries the renderer's 16.5 base size",
      src.contains("fontSize: FontSettings.shared.scaledMessage(16.5)"))
// And it must be keyed on the markdown source.
check("the key is built from block.content", src.contains("content: block.content"))

// Memory-pressure release, since it now outlives session change.
check("a memory warning drops it",
      src.contains("didReceiveMemoryWarningNotification"))

// The DEBUG counter the on-device pass will read.
check("the HeightCache logger exists", src.contains("AppLogger(category: \"HeightCache\")"))
check("it reports a hit rate", src.contains("hitRate="))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All height-cache key + LRU tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
