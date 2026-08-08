import SwiftUI
import UIKit
import WebKit

/// UIViewRepresentable wrapper that displays a `BrowserUseManager`'s render
/// surface — the WKWebView for the `.webkit` engine, the Blink render view for
/// `.blink`, or the SSR placeholder for `.ssr`.
struct BrowserWebView: UIViewRepresentable {
    let manager: BrowserUseManager

    func makeUIView(context: Context) -> UIView {
        manager.renderView
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        // The manager owns the render view — nothing to update here.
    }
}
