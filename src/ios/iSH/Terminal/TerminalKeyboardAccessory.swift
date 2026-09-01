//
//  TerminalKeyboardAccessory.swift
//  MinisApp
//
//  Quick command bar with terminal control buttons
//

import SwiftUI

/// Quick command bar displayed above the keyboard
struct TerminalKeyboardAccessory: View {
    /// Send raw bytes to the terminal
    var onInput: (Data) -> Void

    /// Sticky Ctrl modifier
    @Binding var ctrlActive: Bool

    /// Show file browser
    var onShowFileBrowser: () -> Void

    /// Show rootfs management
    var onShowRootfsManagement: () -> Void

    /// Whether the input view is first responder (controls keyboard input)
    @Binding var keyboardActive: Bool

    /// Whether the software keyboard is currently visible
    var softwareKeyboardVisible: Bool = false

    /// Paste the system pasteboard contents into the terminal.
    /// Provided by the host so the same rendering-safe path is reused
    /// (clears selection before feeding bytes to the emulator).
    var onPaste: () -> Void = {}

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                // Keyboard toggle — shows/hides the software keyboard.
                // The label reflects actual software keyboard visibility.
                // When keyboard is hidden but input view is first responder
                // (external keyboard scenario), tapping Show re-focuses to
                // bring up the software keyboard.
                QuickCommandButton(
                    label: softwareKeyboardVisible ? AppLocalized("Hide") : AppLocalized("Show"),
                    icon: softwareKeyboardVisible ? "keyboard.chevron.compact.down" : "keyboard"
                ) {
                    if softwareKeyboardVisible {
                        // Hide: resign first responder to dismiss keyboard
                        keyboardActive = false
                    } else if keyboardActive {
                        // Keyboard hidden but already first responder (e.g.
                        // external keyboard or user swiped keyboard away):
                        // toggle off then on to force re-becomeFirstResponder
                        keyboardActive = false
                        DispatchQueue.main.async { keyboardActive = true }
                    } else {
                        // Not active: become first responder to show keyboard
                        keyboardActive = true
                    }
                }

                // Paste — reuses the same path the long-press edit menu uses
                // (clears any lingering selection before feeding pasteboard
                // bytes into the emulator, so rendering is not gated).
                QuickCommandButton(label: AppLocalized("Paste"), icon: "doc.on.clipboard") {
                    onPaste()
                }

                // Escape
                QuickCommandButton(label: "Esc", icon: "escape") {
                    onInput(Data([0x1B]))
                }

                // Tab
                QuickCommandButton(label: "Tab", icon: "arrow.right.to.line") {
                    onInput(Data([0x09]))
                }

                // Enter — the iPad software keyboard's Return inserts a newline
                // (multi-line input) inside the terminal, so it can't send a
                // real carriage return to run a command line / trigger an
                // in-CLI prompt. This writes CR (0x0D) on the same raw-input
                // path as the other keys. [T-ios-shell-toolbar-enter-key]
                QuickCommandButton(label: "\u{23CE}", icon: "return") {
                    onInput(Data([0x0D]))
                }

                // Ctrl (sticky modifier)
                QuickCommandButton(label: "Ctrl", icon: "control", isActive: ctrlActive) {
                    ctrlActive.toggle()
                }

                // Arrow keys
                QuickCommandButton(label: "\u{2191}", icon: "chevron.up") {
                    sendArrow(.up)
                }
                QuickCommandButton(label: "\u{2193}", icon: "chevron.down") {
                    sendArrow(.down)
                }
                QuickCommandButton(label: "\u{2190}", icon: "chevron.left") {
                    sendArrow(.left)
                }
                QuickCommandButton(label: "\u{2192}", icon: "chevron.right") {
                    sendArrow(.right)
                }

                // Common control keys
                QuickCommandButton(label: "C-c", icon: "xmark.circle") {
                    onInput(Data([0x03])) // Ctrl+C
                }
                QuickCommandButton(label: "C-d", icon: "eject") {
                    onInput(Data([0x04])) // Ctrl+D
                }
                QuickCommandButton(label: "C-z", icon: "pause.circle") {
                    onInput(Data([0x1A])) // Ctrl+Z
                }

                // Files
                QuickCommandButton(label: "Files", icon: "folder") {
                    onShowFileBrowser()
                }

                // Rootfs
                QuickCommandButton(label: "Rootfs", icon: "gear") {
                    onShowRootfsManagement()
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
        }
        .background(Color(white: 0.12))
    }

    private func sendArrow(_ direction: ArrowDirection) {
        // Always send normal mode sequences from the accessory bar
        let code: UInt8
        switch direction {
        case .up:    code = 0x41
        case .down:  code = 0x42
        case .right: code = 0x43
        case .left:  code = 0x44
        }
        onInput(Data([0x1B, 0x5B, code]))
    }
}
