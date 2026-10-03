import SwiftUI

/// [T-swipe-edit-white-screen] Programmatic push whose destination CANNOT
/// collapse to a blank screen.
///
/// Background: swiping a provider / model-group row and tapping the pencil
/// pushed a dead-white screen the user could not get out of. The old form was
/// a hidden `NavigationLink(isActive:)` whose destination re-read the mutable
/// selection `@State` *inside* the closure:
///
/// ```swift
/// NavigationLink(isActive: Binding(get: { editingId != nil }, ...)) {
///     if let id = editingId, store.contains(id) { DetailView(id) }   // else: nothing
/// } label: { EmptyView() }
/// ```
///
/// Root cause: `isActive` and the destination's content are two SEPARATE reads
/// of the same state, and SwiftUI does not guarantee they observe the same
/// value. The destination closure is re-evaluated for the pushed screen on
/// every subsequent body pass, so it is not enough for `editingId` to be
/// non-nil at the instant of the push — it has to STAY non-nil, and the row
/// has to stay in the store, for as long as the screen is on the stack. A
/// swipe-action button is the worst case for that: tapping it runs the action
/// while UIKit is simultaneously retracting the swipe and asking SwiftUI to
/// re-evaluate the row, so the selection can be read back as nil during the
/// push animation. When the `if let` fails the closure yields NO content, and
/// SwiftUI pushes a real, empty, opaque screen — a white dead end, still on
/// the stack, with the back chevron often not yet drawn.
///
/// Fix: make the destination a pure function of a value captured ONCE, at push
/// time. `item` is the single source of truth for both "is something pushed"
/// and "what is pushed", so the two can no longer disagree, and `content`
/// receives the payload directly instead of re-reading state that may have
/// moved on. There is no `if` in the destination, so there is no branch that
/// can produce an empty screen.
///
/// Deployment target is iOS 16, so this wraps the deprecated
/// `NavigationLink(isActive:)` rather than `navigationDestination(item:)`
/// (17+). The hazard fixed here is the *collapsing destination*, which is
/// orthogonal to which push API is used — `navigationDestination(item:)` would
/// be a tidier spelling of the same value-driven contract, not a different fix.
extension View {
    /// Pushes `content(value)` whenever `item` is non-nil, binding the pushed
    /// screen to the value captured at push time.
    ///
    /// - Parameters:
    ///   - item: Drives the push. Set it to present, and it is cleared when the
    ///     user pops back. Must be `Equatable` so SwiftUI can tell an actual
    ///     change from a redundant write during the transition.
    ///   - content: Builds the destination from the captured value. It is never
    ///     asked to handle "no value", which is what makes the blank push
    ///     impossible.
    func swipeEditDestination<Item: Equatable, Destination: View>(
        item: Binding<Item?>,
        @ViewBuilder content: @escaping (Item) -> Destination
    ) -> some View {
        background {
            SwipeEditLink(item: item, content: content)
        }
    }
}

private struct SwipeEditLink<Item: Equatable, Destination: View>: View {
    @Binding var item: Item?
    @ViewBuilder var content: (Item) -> Destination

    /// The value the push is bound to, captured when the push begins and held
    /// until the screen is actually off the stack.
    ///
    /// This is the crux of the fix. `item` is owned by the list and can be
    /// cleared by anything that happens while the detail screen is open — the
    /// swipe retracting, the row being deleted, a sync merge rewriting the
    /// collection. `pushed` deliberately does NOT follow it back to nil: the
    /// screen that is already on the stack keeps rendering the value it was
    /// pushed with, so a vanished row leaves a stale (readable, dismissable)
    /// detail screen rather than a blank one.
    @State private var pushed: Item?

    var body: some View {
        NavigationLink(
            isActive: Binding(
                get: { pushed != nil },
                // Only the pop direction is handled here: when SwiftUI
                // deactivates the link, clear BOTH so the list's selection does
                // not keep a screen "open" that is no longer on the stack.
                set: { active in
                    guard !active else { return }
                    pushed = nil
                    if item != nil { item = nil }
                }
            ),
            destination: {
                // No `if let`: the destination exists only while `pushed` does,
                // and `Group` keeps the builder total. If `pushed` were somehow
                // nil here the link would already be inactive, so this branch
                // is unreachable rather than a blank-screen fallback.
                if let value = pushed {
                    content(value)
                }
            },
            label: { EmptyView() }
        )
        .opacity(0)
        // Hidden plumbing: never a stop on the VoiceOver / keyboard path, and
        // never a tappable target overlapping the list behind it.
        .accessibilityHidden(true)
        .allowsHitTesting(false)
        .onAppear { pushed = item }
        .onChange(of: item) { new in
            // Capture on the rising edge only. A nil arriving while the screen
            // is open is ignored on purpose (see `pushed` above) — the pop path
            // is driven by the link's own `isActive` setter.
            guard let new else { return }
            pushed = new
        }
    }
}
