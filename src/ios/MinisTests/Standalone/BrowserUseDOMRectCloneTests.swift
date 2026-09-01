// Regression test for [T-ios-browseruse-domrect-clone] — a value RETURNED by an
// `execute_js` script must survive WebKit's structured clone across the
// WebContent→UI IPC boundary.
//
// Six crash reports segfault in `WebCore::CloneDeserializer::readDOMRect` while
// decoding such a return value. On a current (fixed) WebKit the same value does
// not crash but deserializes to nil — so the bug is observable here as silent
// DATA LOSS, which is what these tests assert against. That makes the test
// meaningful on any machine, instead of only on the WebKit versions that crash.
//
// Standalone (`swift BrowserUseDOMRectCloneTests.swift`) because the MinisTests
// target has a pre-existing compile break — same rationale and directory as
// RestoreSymlinkContainmentTests / BackupZipContainmentTests.
//
// The wrapper is EXTRACTED FROM THE SHIPPING SOURCE at runtime rather than
// copied here, so this test cannot silently drift from the code it covers.

import WebKit
import Foundation

let sourcePath: String = {
    // …/src/ios/MinisTests/Standalone/thisfile.swift → …/src/ios/Agent/BrowserUse/…
    let here = URL(fileURLWithPath: #filePath)
    return here.deletingLastPathComponent()      // Standalone
        .deletingLastPathComponent()             // MinisTests
        .deletingLastPathComponent()             // ios
        .appendingPathComponent("Agent/BrowserUse/BrowserUseManager.swift").path
}()

/// Pulls the `"""…"""` literal out of `wrapExecuteJSReturn` in the real source.
let wrapperTemplate: String = {
    guard let src = try? String(contentsOfFile: sourcePath, encoding: .utf8),
          let fnRange = src.range(of: "static func wrapExecuteJSReturn"),
          let endRange = src.range(of: "static func stringifyJSReturn") else {
        print("❌ could not locate wrapExecuteJSReturn in \(sourcePath)")
        exit(1)
    }
    let body = String(src[fnRange.lowerBound..<endRange.lowerBound])
    let parts = body.components(separatedBy: "\"\"\"")
    guard parts.count >= 3 else {
        print("❌ could not extract the wrapper's multiline literal"); exit(1)
    }
    return parts[1]
}()

func wrap(_ script: String) -> String {
    wrapperTemplate.replacingOccurrences(of: "\\(script)", with: script)
}

@MainActor
final class Probe: NSObject {
    let wv = WKWebView(frame: .init(x: 0, y: 0, width: 400, height: 400))
    var fails = 0

    func check(_ name: String, _ script: String, _ expect: String) async {
        do {
            let v = try await wv.callAsyncJavaScript(wrap(script), arguments: [:], contentWorld: .page)
            let got: String
            if let v = v as? [String: Any] ?? (v as? [Any]).map({ ["_": $0] }) {
                let d = try! JSONSerialization.data(withJSONObject: v, options: [.sortedKeys])
                got = String(data: d, encoding: .utf8)!
            } else {
                got = String(describing: v ?? "nil")
            }
            let ok = got.contains(expect)
            print(ok ? "✅ \(name): \(got.prefix(150))" : "❌ \(name)\n     expected to contain: \(expect)\n     got: \(got.prefix(300))")
            if !ok { fails += 1 }
        } catch {
            print("❌ \(name): threw \(error)"); fails += 1
        }
    }

    func run() async {
        wv.loadHTMLString("<html><body><div id=d style='width:100px;height:50px'>hello</div></body></html>",
                          baseURL: URL(string: "https://example.invalid"))
        try? await Task.sleep(nanoseconds: 2_500_000_000)

        print("— THE BUG: DOM types that previously vanished or crashed —")
        await check("DOMRect direct", "return document.getElementById('d').getBoundingClientRect();", "\"width\":100")
        await check("DOMRect ctor", "return new DOMRect(1,2,3,4);", "\"width\":3")
        await check("array of DOMRect", "return [new DOMRect(1,2,3,4)];", "\"width\":3")
        await check("nested DOMRect", "return {r: new DOMRect(1,2,3,4)};", "\"width\":3")
        await check("DOMRectReadOnly", "return new DOMRectReadOnly(5,6,7,8);", "\"width\":7")

        print("\n— No regression: ordinary returns must be untouched —")
        await check("plain object", "return {a:1,b:'x'};", "{\"a\":1,\"b\":\"x\"}")
        await check("array", "return [1,2,3];", "[1,2,3]")
        await check("nested plain", "return {o:{p:[1,{q:2}]}};", "\"q\":2")
        await check("string passthrough", "return 'plain string';", "plain string")
        await check("number", "return 42;", "42")
        await check("bool", "return true;", "1")
        await check("null", "return null;", "<null>")
        await check("no return at all", "var x = 1;", "<null>")
        await check("JSON.stringify (our own scripts)", "return JSON.stringify({count:3});", "{\"count\":3}")

        print("\n— await / top-level return must still work —")
        await check("await preserved", "const v = await Promise.resolve({ok:1}); return v;", "\"ok\":1")
        await check("early return", "if (true) { return {early:1}; } return {late:1};", "\"early\":1")

        print("\n— Robustness —")
        await check("circular", "var a={}; a.self=a; a.n=1; return a;", "circular")
        await check("Error object", "return new Error('boom');", "boom")
        await check("Date", "return new Date(0);", "1970")
        await check("NaN/Infinity", "return {n: NaN, i: Infinity};", "Infinity")
        await check("DOM node", "return document.getElementById('d');", "hello")
        await check("NodeList", "return document.querySelectorAll('div');", "hello")
        await check("deep nesting capped", "var o={},c=o; for(var i=0;i<50;i++){c.n={};c=c.n;} return o;", "[max depth]")

        print("\n— Thrown errors must still surface as tool errors —")
        do {
            _ = try await wv.callAsyncJavaScript(wrap("throw new Error('user threw');"),
                                                 arguments: [:], contentWorld: .page)
            print("❌ throw: did not propagate"); fails += 1
        } catch {
            let msg = (error as NSError).userInfo["WKJavaScriptExceptionMessage"] as? String ?? "\(error)"
            print(msg.contains("user threw") ? "✅ throw propagates: \(msg)" : "❌ throw wrong msg: \(msg)")
            if !msg.contains("user threw") { fails += 1 }
        }

        print(fails == 0 ? "\n✅ all checks passed" : "\n❌ \(fails) check(s) failed")
        exit(fails == 0 ? 0 : 1)
    }
}
Task { @MainActor in await Probe().run() }
RunLoop.main.run()
