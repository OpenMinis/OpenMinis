import SwiftUI
import UIKit

// MARK: - Inline voice input (replaces the text field in the input bar)
//
// Tapping the mic switches the composer's text area into this view: a compact
// waveform + live transcript + controls. VAD segments are transcribed by the
// configured ASR provider and accumulated into `transcript`, which is mirrored
// into the composer's input text (so Send works unchanged). Double-tapping the
// transcript pauses VAD and turns it into an editable field for precise fixes.

struct InlineVoiceInputView: View {
    @ObservedObject var viewModel: VoiceInputViewModel
    /// Mirrors the recognized text into the composer's input binding.
    @Binding var inputText: String
    var onPasteImage: ((UIImage) -> Void)?
    var onPasteFile: ((URL) -> Void)?
    /// [voice-correction §6] Supplies the current conversation's recent turns as the
    /// third correction signal.
    ///
    /// This is NOT a nicety: on a fresh install both learned tables are empty, so without
    /// conversation context the prompt carries zero evidence and the model — correctly —
    /// declines to change anything. Verified on device: with an empty DB and no context,
    /// "张山说食堂今天关门" comes back unchanged; with context, "石塘"→"食堂" is caught
    /// immediately. Context is what makes the feature work on day one.
    var conversationContext: (() -> ConversationContext)?
    /// Tear down voice mode (X button / done).

    @FocusState private var editFocused: Bool
    /// [T-voice-bg-fg-gap] Last global frame this panel reported — read by the
    /// keyboard/lifecycle log lines so position is visible at event edges.
    @State private var lastPanelFrame: CGRect = .zero
    /// Resolved currently-active engine + its group, refreshed when the user
    /// switches via the selector.
    @State private var activeModelLabel: String = ""
    @State private var activeGroupName: String? = nil
    @State private var showModelSelector = false

    // [voice-correction] Manual AI correction. True while a user-requested correction is
    // running — drives the button's spinner and blocks re-taps.
    @State private var isCorrecting = false
    /// One-time opt-in prompt for correction data collection, shown on the FIRST
    /// tap of the AI-correction button (T-voice-correction-collection-consent).
    @State private var showCollectionConsentPrompt = false
    @ObservedObject private var voiceSelection = VoiceSelectionStore.shared
    /// Measured intrinsic height of the transcript text, so the panel can grow
    /// with content and then scroll once it hits the 60%-screen cap.
    @State private var transcriptContentHeight: CGFloat = 0
    /// [T-voice-scroll-gesture-priority] Live scroll-boundary state of the
    /// transcript ScrollView (either mode). The panel's expand/collapse drag and
    /// the exit-edit drag both consult this so content-scroll always wins until
    /// the boundary is reached. @StateObject: owned by this view, and it must
    /// survive the editing↔non-editing branch swap (the probe re-attaches to the
    /// new ScrollView, but the state object itself stays put).
    @StateObject private var transcriptScroll = TranscriptScrollState()
    /// [T-voice-edit-keyboard-dismiss] True between a real panel-drag's onChanged
    /// and its onEnded. Distinguishes a genuine finger drag from the synthetic
    /// terminal onEnded SwiftUI emits when the drag modifier's priority branch
    /// swaps mid-touch (the scroll-boundary flip), so a boundary flip can't emit a
    /// phantom collapse/expand. Only consulted when NOT editing — in edit mode the
    /// panel installs no drag at all.
    @State private var panelDragInFlight = false

    /// Two display modes:
    ///  • compact — only the mic button + active-ASR chip, no hints (~92pt).
    ///  • expand  — full status + controls + reserved transcript space (~200pt).
    /// Backed by the shared in-memory preference so the expand/compact choice is
    /// REMEMBERED across sessions. Reset to expand only by: (1) switching from text
    /// to voice, (2) sending a message (→ compact). A toggle/drag flips it.
    @ObservedObject private var voiceMode = VoiceModePreference.shared
    private var expanded: Bool {
        get { voiceMode.expanded }
        nonmutating set { voiceMode.expanded = newValue }
    }


    /// Compact height — only ~20pt taller than the single-line text composer.
    private static let compactHeight: CGFloat = 92
    /// Expanded height WITHOUT any transcript (status + mic + chip + spacing + padding).
    /// Tuned to leave minimal blank space below the model chip when no transcript
    /// is present. The Spacer between status and controls absorbs any remaining gap.
    ///
    /// [T-voice-status-mic-gap] Measured content in the empty-transcript state is
    /// ~198pt (vpad 16 + band floor 28 + spacing 32 + status 20 + mic 72 + chip 24
    /// + controls top pad 6), against a 212pt panel — so ~14pt fell to the Spacer
    /// and the gap under the status line read as slack precisely when there was
    /// least to show. Trimmed by 12pt here; `expandedStatusSlack` caps what the
    /// Spacer may keep, and `expandedStackSpacing` tightens the rows, so the
    /// reclaimed space leaves the panel instead of moving elsewhere in it.
    private static let expandedBaseHeight: CGFloat = 148
    /// Compact mic diameter (the expanded layout uses a larger focal mic).
    private static let compactMic: CGFloat = 60
    private static let expandedMic: CGFloat = 72

    /// [T-voice-status-mic-gap] Row spacing of the expanded VStack. Named because
    /// `expandedChromeOverhead` has to reserve exactly one of these gaps — the two
    /// were previously both the literal 8 and could drift apart silently.
    private static let expandedStackSpacing: CGFloat = 6

    /// [T-voice-status-mic-gap] Ceiling on the status→controls Spacer.
    ///
    /// That Spacer is deliberately the first thing to compress as the transcript
    /// grows (see `expandedBody`), which is right — but with an empty transcript
    /// nothing was competing for the room, so it kept every spare point and the
    /// panel looked emptiest exactly when it held the least. Capping it means the
    /// surplus is given back to `effectiveHeight` (the panel gets shorter) rather
    /// than parked under the status line. Long transcripts are unaffected: there
    /// the Spacer is already at 0, far below this cap.
    private static let expandedStatusSlack: CGFloat = 4

    private var micDiameter: CGFloat { expanded ? Self.expandedMic : Self.compactMic }

    /// [T-voice-panel-gap-after-edit] Force the current first responder down.
    ///
    /// Clearing `@FocusState` is not sufficient when the focused TextField is
    /// being removed from the view tree in the same layout pass: SwiftUI may
    /// never deliver the resign to UIKit, leaving a phantom keyboard inset
    /// reserved on the window (the bottom gap). Dispatched to the next runloop
    /// turn so the focus clear commits first — sending the action synchronously
    /// from inside the state-change handler can land BEFORE SwiftUI has updated
    /// the responder and be a no-op. Idempotent: a no-op when nothing is
    /// focused.
    private static func releaseKeyboardResponder(reason: String) {
        DispatchQueue.main.async {
            let window = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
            let hadResponder = window?.endEditing(true) ?? false
            VoiceLog.log("[voice-edit] releaseKeyboardResponder(\(reason)) endEditing=\(hadResponder)")
        }
    }

    /// Hard ceiling: the whole panel may not exceed 60% of the screen height.
    private var maxPanelHeight: CGFloat { UIScreen.main.bounds.height * 0.6 }

    /// Tallest the transcript band may grow before it starts scrolling.
    /// [T-ios-voice-transcript-blank-band] Subtract the in-frame chrome too, so
    /// a saturated band still fits inside the 60% panel cap instead of being
    /// clipped by it (the cap is applied by `min(maxPanelHeight, …)` in
    /// `effectiveHeight`, which would otherwise silently shave the band).
    private var transcriptMaxHeight: CGFloat {
        max(40, maxPanelHeight - Self.expandedBaseHeight - Self.expandedChromeOverhead)
    }

    /// [T-ios-voice-transcript-blank-band] Chrome that lives INSIDE the panel's
    /// `.frame(height: effectiveHeight)` but is not part of `expandedBaseHeight`:
    /// the body's own `.padding(.vertical, 8)` (applied before the frame, so it
    /// eats into it) plus the `VStack(spacing: 8)` gap between the transcript
    /// band and the status line. `effectiveHeight` has to reserve these on top
    /// of the band, or the band is handed less room than it asked for.
    private static let expandedChromeOverhead: CGFloat = 8 * 2 + expandedStackSpacing   // vpad + spacing

    /// Minimum band height. One line of `.body` is ~20-22pt, so the old 28pt
    /// floor was already tight; it is kept as the floor for the band ITSELF, but
    /// `effectiveHeight` now budgets the chrome separately so the floor is
    /// actually delivered instead of being eaten by padding.
    private static let transcriptMinHeight: CGFloat = 28

    /// Visible transcript band height = content height, clamped to the cap.
    private var transcriptBandHeight: CGFloat {
        guard viewModel.isEditingTranscript || !viewModel.transcript.isEmpty else { return 0 }
        return min(max(transcriptContentHeight, Self.transcriptMinHeight), transcriptMaxHeight)
    }

    /// [T-voice-scroll-gesture-priority] Should the panel's drag gesture step
    /// aside and let the transcript ScrollView have the touch?
    ///
    /// This is evaluated at gesture-ATTACH time (per body pass), when the drag
    /// direction is not yet known — so it cannot consult the direction-specific
    /// `shouldAbsorbDrag(dy:)`. It yields whenever the content is scrollable and
    /// NOT sitting on the boundary this panel would hand off from.
    ///
    /// Why the boundary must be checked here and not merely in `onEnded`: a
    /// SwiftUI ScrollView BOUNCES, so it keeps the touch even when pinned at its
    /// end. A parent gesture left at default priority would therefore never fire
    /// and collapse/exit-edit would become unreachable on any scrollable
    /// transcript. The panel has to reclaim HIGH priority at the boundary.
    ///
    /// The hand-off boundary differs per mode, and each mode only ever hands off
    /// in ONE direction, which is what makes a direction-agnostic test correct
    /// here:
    ///   • editing → exit-edit is a DOWNWARD drag, handed off at the TOP.
    ///   • expanded → collapse is a DOWNWARD drag, handed off at the TOP.
    ///   • compact → expand is an UPWARD drag; the compact panel shows no
    ///     transcript band at all, so there is no scroll to arbitrate with.
    /// So in every case where a transcript is on screen, the panel only needs the
    /// touch at the TOP boundary. Away from the top we yield, and the ScrollView
    /// scrolls — including a drag DOWN from mid-content back toward the top.
    ///
    /// When the transcript is too short to scroll, this is false and the panel
    /// behaves exactly as it did before this change.
    private var panelDragAbsorbedByScroll: Bool {
        transcriptScroll.isScrollable && !transcriptScroll.atTop
    }

    /// Effective panel height per mode. Expand grows with the transcript up to the
    /// 60%-screen cap, after which the transcript scrolls internally.
    private var effectiveHeight: CGFloat {
        guard expanded else { return Self.compactHeight }
        let band = transcriptBandHeight
        // [T-ios-voice-transcript-blank-band] Reserve the in-frame chrome, not
        // just `band + 8`.
        //
        // The old arithmetic added a bare 8pt for the VStack spacing and ignored
        // the body's `.padding(.vertical, 8)` — which is applied BEFORE
        // `.frame(height: effectiveHeight)` and therefore consumes 16pt from
        // INSIDE that height. The band then asked for more room than the layout
        // could give it and was squeezed: with a short/not-yet-measured
        // transcript (the 28pt floor) the panel reserved 196pt but left only
        // 12pt for the band — less than one line of body text — so the panel
        // rendered as a tall block with a blank transcript area even though the
        // text was there. That is the reported "面板展开但看不到文字".
        return min(maxPanelHeight, Self.expandedBaseHeight + (band > 0 ? band + Self.expandedChromeOverhead : 0))
    }

    var body: some View {
        Group {
            if expanded {
                expandedBody
                    .transition(.identity)
            } else {
                compactBody
                    .transition(.identity)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .frame(height: effectiveHeight)
        .background(
            // Long-press a blank area → Paste. Placed on a background
            // layer so it doesn't compete with interactive child views
            // (VoiceDeleteButton's UILongPressGestureRecognizer, mic
            // button, etc.) for gesture priority.
            Color.clear
                .contentShape(Rectangle())
                .contextMenu {
                    if UIPasteboard.general.hasImages, onPasteImage != nil {
                        Button {
                            guard let images = UIPasteboard.general.images else { return }
                            for image in images { onPasteImage?(image) }
                            VoiceLog.log("voice panel paste: \(images.count) image(s)")
                        } label: {
                            Label("Paste Image", systemImage: "photo.on.rectangle")
                        }
                    }
                    if UIPasteboard.general.hasStrings {
                        Button {
                            guard let text = UIPasteboard.general.string, !text.isEmpty else { return }
                            let asciiLetters = text.unicodeScalars.filter { ($0.value >= 0x41 && $0.value <= 0x5A) || ($0.value >= 0x61 && $0.value <= 0x7A) }.count
                            let isEnglishDominant = asciiLetters > text.count / 2
                            let isLong: Bool
                            if isEnglishDominant {
                                let wordCount = text.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.count
                                isLong = wordCount > 1000
                            } else {
                                isLong = text.count > 1200
                            }
                            if isLong, let onPasteFile {
                                let tmp = FileManager.default.temporaryDirectory
                                    .appendingPathComponent("pasted_\(UUID().uuidString.prefix(8)).txt")
                                if let data = text.data(using: .utf8) {
                                    try? data.write(to: tmp)
                                    onPasteFile(tmp)
                                    VoiceLog.log("voice panel paste: long text → file (\(text.count) chars)")
                                    return
                                }
                            }
                            let base = viewModel.transcript
                            let joined = base.isEmpty ? text : (base + text)
                            viewModel.setTranscript(joined)
                            if !expanded {
                                VoiceLog.log("paste → expand")
                                withAnimation(.easeInOut(duration: 0.28)) { expanded = true }
                            }
                            VoiceLog.log("voice panel paste: +\(text.count) chars")
                        } label: {
                            Label("Paste", systemImage: "doc.on.clipboard")
                        }
                    }
                }
        )
        // Compact↔expand toggle (top-left) + globe (top-right). Pinned to the top
        // corners in BOTH modes. Leading/trailing insets = 12 to line up with the
        // bottom toolbar's `+` and send buttons (inputBottomRow padding 12).
        .overlay(alignment: .topLeading) {
            expandCollapseButton
                .padding(.top, 10)
                .padding(.leading, 12)
        }
        .overlay(alignment: .topTrailing) {
            // [voice-correction, experimental] In DEBUG, this corner doubles as the
            // correction-suggestion affordance (design §15.3.2): once the transcript has
            // landed, switching the RECOGNITION language is meaningless (the audio is
            // already transcribed), so the slot is free — and reusing it costs zero new
            // screen space and no layout reflow, since the sparkles button has exactly the
            // globe's 30×30 pill geometry.
            //
            voiceCorrectionOrLanguageMenu
                .padding(.top, 10)
                .padding(.trailing, 12)
        }
        // Vertical drag controls the panel:
        //  • while editing → drag down dismisses the keyboard / exits editing;
        //  • otherwise → drag down collapses (expand→compact), drag up expands.
        // Ranked BELOW the delete control (a UIKit recognizer, which sidesteps
        // SwiftUI priority entirely) and ABOVE the outer swipe-to-send (a
        // .simultaneousGesture), so a collapse/expand swipe never doubles as a send.
        //
        // [T-voice-scroll-gesture-priority] Conflicts I + H + C.
        //
        // The priority is now CONDITIONAL on the transcript's scroll boundary.
        // Previously this was an unconditional `.highPriorityGesture`, which
        // outranks any inner ScrollView's pan — so every vertical drag past 18pt
        // anywhere in the panel was hijacked into collapse/expand (or exit-edit),
        // and a long transcript could never actually be scrolled in EITHER mode.
        //
        // Gating only the ACTION (deciding inside onEnded) would not have fixed
        // it: highPriorityGesture steals the touch from the ScrollView the moment
        // it engages, so the content would still refuse to scroll no matter what
        // we did at the end. The arbitration has to change WHICH gesture receives
        // the touch. Hence:
        //
        //   • transcript can still scroll the way the finger is moving
        //       → attach at DEFAULT priority, which loses to the inner
        //         ScrollView's pan: the content scrolls, the panel stays put.
        //   • at the boundary (or not scrollable at all)
        //       → attach at HIGH priority exactly as before: the panel claims the
        //         drag and collapses / expands / exits editing.
        //
        // This is the native "content scrolls, then hands off to the container at
        // the boundary" behaviour (UIScrollView inside a pan-to-dismiss sheet).
        // `panelDragAbsorbedByScroll` is recomputed per body pass from the live
        // boundary state, so the hand-off flips the instant the content bottoms out.
        //
        // Conflict C: this is now the ONLY exit-edit drag. The competing
        // minimumDistance-60 recognizer that used to sit on the edit-mode
        // ScrollView is gone (see transcriptArea) — two recognizers racing to call
        // endEditing(resume:) at different thresholds is the likeliest source of
        // the isEditingTranscript flapping in earlier device logs.
        .modifier(PanelDragGestureModifier(
            // [T-voice-edit-gesture-exclusive] In edit mode the modifier installs
            // NO gesture, so this whole handler only ever runs when NOT editing.
            // The exit-edit branch and the synthetic-onEnded (`panelDragInFlight`)
            // defenses are gone: there is no panel gesture to synthesize a
            // callback or race the editor while editing.
            isEditing: viewModel.isEditingTranscript,
            absorbedByScroll: panelDragAbsorbedByScroll,
            onDragChanged: {
                // A real finger drag always fires onChanged before onEnded; a
                // synthetic terminal onEnded (from the .gesture↔.highPriorityGesture
                // identity swap when the scroll boundary flips mid-touch) has none.
                // Requiring this before acting keeps a boundary flip from emitting a
                // phantom collapse/expand.
                panelDragInFlight = true
            },
            onDragEnded: { value in
                let began = panelDragInFlight
                panelDragInFlight = false

                let dy = value.translation.height
                guard abs(dy) > 18, abs(value.translation.width) < 110 else { return }
                // Only a real drag (had a paired onChanged) that started at the
                // scroll boundary may move the panel.
                guard began else { return }
                if transcriptScroll.shouldAbsorbDrag(dy: dy) {
                    VoiceLog.log("[voice-gesture] panel drag absorbed by transcript scroll (dy=\(Int(dy)))")
                    return
                }
                if dy > 0 {
                    if expanded {
                        VoiceLog.log("drag down → collapse")
                        withAnimation(.easeInOut(duration: 0.28)) { expanded = false }
                    }
                } else {
                    if !expanded {
                        VoiceLog.log("drag up → expand")
                        withAnimation(.easeInOut(duration: 0.28)) { expanded = true }
                    }
                }
            }
        ))
        .animation(.easeInOut(duration: 0.28), value: expanded)
        .background(
            GeometryReader { geo in
                Color.clear
                    .onAppear {
                        let f = geo.frame(in: .global)
                        lastPanelFrame = f
                        VoiceLog.log("[panel-layout] frame y=\(Int(f.minY))…\(Int(f.maxY)) x=\(Int(f.minX))…\(Int(f.maxX)) w=\(Int(f.width)) h=\(Int(f.height)) expanded=\(expanded)")
                    }
                    .onChange(of: geo.frame(in: .global)) { f in
                        lastPanelFrame = f
                        VoiceLog.log("[panel-layout] frame y=\(Int(f.minY))…\(Int(f.maxY)) x=\(Int(f.minX))…\(Int(f.maxX)) w=\(Int(f.width)) h=\(Int(f.height)) expanded=\(expanded)")
                    }
            }
        )
        // [T-voice-bg-fg-gap] (b) Keyboard notifications relative to this panel —
        // frame + who holds focus. The 48pt background shift could only be
        // attributed by seeing whether UIKit broadcast a keyboard frame during
        // the bg/fg window.
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            let f = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect) ?? .zero
            VoiceLog.log("[voice-kbd] willShow end=\(Int(f.minY))h\(Int(f.height)) editing=\(viewModel.isEditingTranscript) focused=\(editFocused) panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY))")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { note in
            let f = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect) ?? .zero
            VoiceLog.log("[voice-kbd] didShow end=\(Int(f.minY))h\(Int(f.height)) editing=\(viewModel.isEditingTranscript) focused=\(editFocused) panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY))")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            VoiceLog.log("[voice-kbd] willHide editing=\(viewModel.isEditingTranscript) focused=\(editFocused) panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY))")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidHideNotification)) { _ in
            VoiceLog.log("[voice-kbd] didHide editing=\(viewModel.isEditingTranscript) focused=\(editFocused) panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY))")
        }
        // [T-voice-bg-fg-gap] (c) Panel position at every app-lifecycle edge —
        // pins WHEN the shift happens (send→bg, bg pass, fg return).
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
            VoiceLog.log("[voice-lifecycle] willResignActive panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY)) h=\(Int(lastPanelFrame.height)) editing=\(viewModel.isEditingTranscript) focused=\(editFocused)")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            VoiceLog.log("[voice-lifecycle] didEnterBackground panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY)) h=\(Int(lastPanelFrame.height)) editing=\(viewModel.isEditingTranscript) focused=\(editFocused)")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            VoiceLog.log("[voice-lifecycle] willEnterForeground panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY)) h=\(Int(lastPanelFrame.height)) editing=\(viewModel.isEditingTranscript) focused=\(editFocused)")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            VoiceLog.log("[voice-lifecycle] didBecomeActive panelY=\(Int(lastPanelFrame.minY))…\(Int(lastPanelFrame.maxY)) h=\(Int(lastPanelFrame.height)) editing=\(viewModel.isEditingTranscript) focused=\(editFocused)")
        }
        #if DEBUG
        // [debug-rpc voice bridge] Synthetic debug.tap can't reach SwiftUI
        // buttons, so on-device e2e voice tests (play audio at the phone, then
        // inspect debug.voiceInputs) drive the mic via this notification from
        // the debug server's `debug.voice.panel {action:"mic"}` instead.
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("MinisDebugVoiceMicTap"))) { _ in
            VoiceLog.log("[debug-bridge] mic tap via debug.voice.panel")
            if showCancelIcon {
                viewModel.cancelTranscription()
            } else {
                viewModel.handleMainButtonTap()
            }
        }
        #endif
        .onAppear {
            VoiceLog.log("[panel-lifecycle] onAppear expanded=\(expanded) transcript=\(viewModel.transcript.count)ch state=\(viewModel.state) effectiveH=\(Int(effectiveHeight))")
            viewModel.accumulate = true
            refreshActiveLabels()
            // Pay the jieba dictionary load (~1.9s on an iPhone 11, first call only) now,
            // while the user is still speaking — not inside the correction that follows.
            VoiceCorrectionEngine.warmUp()
            // Seamless text → voice: carry whatever was typed into the transcript
            // so dictation appends to it (and it shows in the panel). Voice → text
            // already carries because inputText mirrors the transcript live.
            // [T-ios-voice-keyboard-text-carry] Seed UNCONDITIONALLY (even when
            // empty): reset(clearTranscript: false) keeps the transcript across a
            // voice→keyboard switch, so if the user then clears/sends the input
            // and re-enters voice mode, a stale transcript would otherwise
            // resurrect the old text here.
            viewModel.setTranscript(inputText)
            // Open expanded ONLY when the user just switched text→voice; otherwise
            // resume the remembered expand/compact state (cross-session memory).
            if voiceMode.enteredFromText {
                voiceMode.enteredFromText = false
                VoiceLog.log("enteredFromText → expand")
                expanded = true
            }
            // Don't auto-start: show "Tap to speak" and let the user tap the mic.
            viewModel.prepare()
        }
        // [voice-correction] No auto-correction .onChange here by design — correction is
        // manual, driven solely by the top-right AI-correction button (runManualCorrection).
        .onDisappear {
            VoiceLog.log("[panel-lifecycle] onDisappear expanded=\(expanded) transcript=\(viewModel.transcript.count)ch state=\(viewModel.state)")
            // [T-voice-panel-gap-after-edit] The panel can be torn down (voice→text
            // toggle, session switch) while the transcript editor still holds the
            // keyboard. Releasing focus here alone is not enough — the view is
            // already leaving — so force the responder down as well.
            if editFocused {
                editFocused = false
                Self.releaseKeyboardResponder(reason: "panel-disappear")
            }
            viewModel.stopListening()
        }
        // [T-voice-panel-gap-after-edit] SINGLE authoritative teardown for the
        // transcript editor's keyboard.
        //
        // Root cause of the "composer floats ~48pt above the bottom with a black
        // gap under it, persists until the next keyboard cycle": when
        // `isEditingTranscript` flips false, the `if` branch in `transcriptArea`
        // — which OWNS the focused TextField — is removed from the view tree in
        // the SAME layout pass. If the TextField is still first responder at that
        // moment, UIKit never gets a clean resign, and SwiftUI's automatic
        // keyboard avoidance keeps a stranded bottom inset reserved on the window
        // (observed: composer bottom stuck at y=792 instead of the correct y=840
        // on an 874pt screen — an 82pt phantom keyboard inset). The in-view
        // dismiss paths (Done button, swipe-down) DO set `editFocused = false`,
        // but they set it in the same tick the branch disappears, so the resign
        // races the teardown; and the view-model-driven paths
        // (`clearAndRearm()` on send, any external `endEditing()`) never clear
        // `editFocused` at all — the view owns that @FocusState, the model can't
        // reach it. Same failure class as the offscreen-WebView phantom keyboard
        // (8232308a): a responder that goes away without resigning.
        //
        // Observing the model's flag covers EVERY path (button, gesture, send,
        // external) in one place, and the explicit responder release guarantees
        // the keyboard is actually gone even if the TextField is already unmounted.
        .onChange(of: viewModel.isEditingTranscript) { isEditing in
            let wasFocused = editFocused
            VoiceLog.log("[voice-edit] isEditingTranscript→\(isEditing) editFocused=\(wasFocused)")
            guard !isEditing else { return }
            editFocused = false
            // Only force the responder down when OUR editor actually held it.
            // `editFocused` is true only for this panel's transcript TextField, so
            // this can never steal the keyboard from the text composer — which
            // matters on the voice→text mic toggle, where `reset()` clears
            // `isEditingTranscript` and the PastableTextView mounts and autofocuses
            // in the same beat. When the editor wasn't focused there is no stranded
            // responder to release, so the clear alone is sufficient.
            guard wasFocused else { return }
            Self.releaseKeyboardResponder(reason: "end-editing")
        }
        // Reverse mirror: when the composer's inputText is changed EXTERNALLY
        // (e.g. "add selected reply to input", share/paste injection), pull it
        // back into the voice transcript so the panel shows it. Guarded against the
        // transcript→inputText loop above (which leaves them already equal).
        .onChange(of: inputText) { newValue in
            if viewModel.transcript != newValue {
                viewModel.setTranscript(newValue)
                VoiceLog.log("external input → transcript sync: \"\(newValue.prefix(40))\"")
                if !expanded, !newValue.isEmpty {
                    VoiceLog.log("external input → expand (text=\(newValue.count) chars)")
                    withAnimation(.easeInOut(duration: 0.28)) { expanded = true }
                }
            }
        }
        .onChange(of: viewModel.transcript) { newValue in
            // Mirror the recognized text into the composer field ONLY. Never
            // auto-send — the user reviews then taps Send (or the swipe/return).
            if inputText != newValue {
                inputText = newValue
                VoiceLog.log("filled input field (no auto-send): \"\(newValue)\"")
            }
            // Recognized text while compact → expand immediately so the user sees
            // the transcript. (A send clears the transcript to "" first, so that
            // path won't re-expand.)
            if !expanded, !newValue.isEmpty {
                VoiceLog.log("transcript change → expand (text=\(newValue.count) chars)")
                withAnimation(.easeInOut(duration: 0.28)) { expanded = true }
            }
        }
        .onChange(of: viewModel.collapseAfterSendToken) { _ in
            // After a recognized utterance is sent, collapse to compact mode.
            // Deferred to the next runloop so a concurrent swipe-to-send drag
            // (which can also flip `expanded` via the in-panel drag gesture in the
            // SAME gesture sequence) can't win the race and leave it expanded.
            VoiceLog.log("collapseAfterSendToken → collapse to compact")
            DispatchQueue.main.async {
                withAnimation(.easeInOut(duration: 0.28)) { expanded = false }
            }
        }
        .onChange(of: voiceSelection.inputEntryId) { _ in
            // User switched the engine — re-resolve and relabel.
            refreshActiveLabels()
            viewModel.refreshInputProvider()
        }
        .sheet(isPresented: $showModelSelector) {
            NavigationStack {
                UnifiedModelPicker(config: .voiceInput())
            }
        }
    }

    private func refreshActiveLabels() {
        // The chip shows "Group · Model" — the group name is rendered separately,
        // so the model label omits the instance suffix to stay short.
        activeModelLabel = VoiceProviderResolver.inputModelLabel(includeInstance: false)
        activeGroupName = VoiceProviderResolver.inputGroupName()
    }

    // MARK: - Compact mode (just the centered mic, no hints / no chip)

    private var compactBody: some View {
        // Hint (when there's buffered text) sits ABOVE the mic; the whole stack is
        // vertically centered, so the mic shifts down just enough to make room for
        // the hint instead of overlapping it. No hint → mic is dead-centered.
        VStack(spacing: 6) {
            if !viewModel.transcript.isEmpty {
                Text("… \(viewModel.transcript.count) chars …", comment: "Compact voice buffered char count")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            micButton
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Expanded mode (full status + controls + transcript space)

    private var expandedBody: some View {
        VStack(spacing: Self.expandedStackSpacing) {
            // ── Transcript — wraps, grows with content, then scrolls once the
            // panel hits the 60%-screen cap. Double-tap to correct by keyboard. ──
            if viewModel.isEditingTranscript || !viewModel.transcript.isEmpty {
                transcriptArea
                    .frame(height: transcriptBandHeight)
            }

            // ── Status line (with Exit chip while editing). ──
            HStack(spacing: 8) {
                Text(stateLabel)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if viewModel.isEditingTranscript {
                    Button {
                        viewModel.endEditing(resume: false)
                    } label: {
                        Text("Exit", comment: "Exit transcript editing")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Color.secondary.opacity(0.15)))
                    }
                    .buttonStyle(.plain)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: viewModel.state)
            .animation(.easeInOut(duration: 0.2), value: viewModel.isEditingTranscript)

            // Absorbs spare vertical space so the controls below are pushed to the
            // bottom of the panel. When transcript text grows, this spacer shrinks
            // first — controls stay pinned — before the panel expands upward.
            //
            // [T-voice-status-mic-gap] Capped: uncapped it also absorbed the slack
            // of the EMPTY state, leaving a ~14pt void under the status line with
            // nothing above it to justify the space. `effectiveHeight` was trimmed
            // by the same budget, so the panel closes up instead of the gap merely
            // relocating.
            Spacer(minLength: 0)
                .frame(maxHeight: Self.expandedStatusSlack)

            // ── Transcription error tip (with retry countdown / manual retry) ──
            if viewModel.transcribeError != nil || viewModel.retryCountdown != nil {
                HStack(spacing: 5) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(.orange)
                    if let countdown = viewModel.retryCountdown {
                        Text("Network error, retrying in \(countdown)s…")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    } else if viewModel.canManualRetry {
                        Text(viewModel.transcribeError ?? "")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        Button {
                            viewModel.manualRetry()
                        } label: {
                            Text("Retry", comment: "Manual retry transcription")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.orange)
                        }
                        .buttonStyle(.plain)
                    } else {
                        Text(viewModel.transcribeError ?? "")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(Capsule().fill(Color.orange.opacity(0.12)))
                .transition(.opacity)
            }
            // [voice-correction] Non-error one-off status ("No corrections
            // needed" / "Correction failed…") now shows as a window-level
            // MinisToast instead of an inline pill here — the pill inserted a
            // row into this VStack and shifted the mic button every time it
            // appeared (user feedback: don't consume panel space).

            // ── Central focal mic / waveform button, with a delete control to
            // its right (only when there's text). ──
            ZStack {
                micButton
                if !viewModel.transcript.isEmpty {
                    HStack {
                        Spacer()
                        VoiceDeleteButton(
                            onDeleteOne: { viewModel.deleteLastCharacter() },
                            onClearAll: { viewModel.clearTranscript() },
                            isEmpty: { viewModel.transcript.isEmpty }
                        )
                        .padding(.trailing, max(0, (micDiameter / 2) - 6))
                    }
                    .frame(maxWidth: .infinity)
                    .transition(.opacity)
                }
            }

            // ── Active ASR model chip. ──
            modelChip
        }
        .padding(.top, 6)
    }

    /// Compact↔expand toggle. Mirrors the globe's pill styling.
    private var expandCollapseButton: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.28)) { expanded.toggle() }
        } label: {
            Image(systemName: expanded ? "chevron.down" : "chevron.up")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(ChatColors.secondaryText)
                .frame(width: 30, height: 30)
                .background(Circle().fill(ChatColors.inputIconBg))
                .overlay(Circle().strokeBorder(ChatColors.inputIconBorder, lineWidth: 0.5))
        }
        .accessibilityLabel(expanded
            ? Text("Collapse voice panel", comment: "Voice panel collapse")
            : Text("Expand voice panel", comment: "Voice panel expand"))
    }

    // MARK: - Transcript area (display ↔ editable)

    @ViewBuilder
    private var transcriptArea: some View {
        // Wrapped in a Group so the extra horizontal inset below applies to
        // both branches uniformly (if/else is a statement, not chainable).
        Group {
        if viewModel.isEditingTranscript {
            // [T-voice-edit-nested-scroll] NO outer ScrollView here.
            // `TextField(axis: .vertical)` is backed by a UITextView, which IS a
            // UIScrollView. Wrapping it in a SwiftUI ScrollView nested two
            // scrollers: `.fixedSize(vertical:)` asked SwiftUI for the field's
            // full intrinsic height, but the UITextView still clipped itself to
            // the band height and kept its own scrolling, while the outer
            // ScrollView measured content that "fit" and refused to scroll. With
            // a transcript taller than the band, the text above the caret became
            // unreachable by EITHER scroller — the reported "can't drag the top
            // half back into view" freeze. The text view scrolls itself here,
            // which also restores native caret-tracking that the wrapped form
            // lost.
            TextField("", text: Binding(
                get: { viewModel.transcript },
                set: { viewModel.setTranscript($0); inputText = $0 }
            ), axis: .vertical)
                .focused($editFocused)
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(ChatColors.primaryText)
                .onAppear { editFocused = true }
                .toolbar {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button {
                            // [T-voice-panel-gap-after-edit] Focus teardown is
                            // owned by the isEditingTranscript observer (which
                            // also force-resigns the responder). Clearing
                            // `editFocused` here as well would make the observer
                            // see focus already false and skip that release.
                            viewModel.endEditing(resume: false)
                        } label: {
                            Text("Done", comment: "Finish transcript editing")
                                .font(.body.weight(.semibold))
                        }
                    }
                }
                // Fill the band and let the text view scroll inside it. Without
                // an explicit height the field would size to its content and
                // overflow the fixed-height band instead of scrolling.
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                // [T-voice-scroll-gesture-priority] Boundary probe (zero-size,
                // non-interactive) — reports the text view's scroll position to
                // the panel gesture. In this branch the enclosing UIScrollView
                // IS the UITextField's text view, which is exactly the scroller
                // the panel drag must yield to while editing.
                .background(TranscriptScrollProbe(state: transcriptScroll).frame(width: 0, height: 0))
            // [T-voice-edit-gesture-exclusive] NO drag recognizer on the editor.
            // While editing, the panel installs no gesture (PanelDragGestureModifier
            // isEditing:true) and this branch adds none either — so the ONLY things
            // recognizing touches here are the TextField's own native gestures
            // (caret, selection, internal scroll). Exiting edit is an explicit
            // action, not a drag: the keyboard toolbar's "Done" button above
            // (and the top-right control / send). This is the "edit mode is
            // mutually exclusive with panel gestures" behaviour the user asked for,
            // replacing the earlier drag-to-exit that kept colliding with scroll.
        } else {
            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: true) {
                    Text(viewModel.transcript)
                        .font(.body)
                        .foregroundStyle(ChatColors.primaryText)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity)
                        .background(GeometryReader { geo in
                            Color.clear.preference(key: TranscriptContentHeightKey.self,
                                                   value: geo.size.height)
                        })
                        .contextMenu {
                            Button(role: .destructive) {
                                viewModel.clearTranscript()
                                VoiceLog.log("cleared transcript via long-press")
                            } label: {
                                Label(AppLocalized("Clear", comment: "Clear transcript context menu"), systemImage: "trash")
                            }
                        }
                        // [T-voice-scroll-gesture-priority] Same boundary probe as
                        // the editing branch — the read-only transcript (used while
                        // dictating / reviewing) must be scrollable too. Overlay
                        // rather than background: the background slot here already
                        // carries the content-height GeometryReader.
                        .overlay(TranscriptScrollProbe(state: transcriptScroll).frame(width: 0, height: 0))
                        .id("voiceTranscriptTail")
                }
                .onPreferenceChange(TranscriptContentHeightKey.self) { h in
                    if abs(h - transcriptContentHeight) > 1 {
                        VoiceLog.log("[panel-lifecycle] transcriptContentHeight \(Int(transcriptContentHeight))→\(Int(h)) effectiveH=\(Int(effectiveHeight))")
                        // [T-voice-inputbar-collapse-selfheal] Mark the LARGE
                        // single-frame collapse specifically. The 2026-08-18
                        // 01:29 blank-composer failure began with 262→42 (a
                        // short transcript replacing a tall dictation band) in
                        // one frame, immediately followed by an intermediate
                        // panel frame past the window bottom and then permanent
                        // silence from the composer's geometry reporting.
                        // Grepping [panel-collapse] gives the next investigation
                        // the trigger instant directly instead of having to
                        // reconstruct it from the height stream.
                        if transcriptContentHeight - h > 120 {
                            VoiceLog.log("[panel-collapse] LARGE transcript shrink \(Int(transcriptContentHeight))→\(Int(h)) (Δ\(Int(transcriptContentHeight - h))) effectiveH=\(Int(effectiveHeight)) — watch the next [InputBarHealth] line for a stalled composer")
                        }
                    }
                    transcriptContentHeight = h
                }
                // Auto-scroll to the newest text as it streams in.
                .onChange(of: viewModel.transcript) { _ in
                    withAnimation(.linear(duration: 0.12)) {
                        proxy.scrollTo("voiceTranscriptTail", anchor: .bottom)
                    }
                }
            }
            // Double-tap to correct by keyboard (pauses VAD).
            .onTapGesture(count: 2) { viewModel.beginEditing() }
        }
        }
        // Extra horizontal inset so wrapped text never reaches under the
        // top-left/top-right circular overlay buttons (expand toggle + language
        // menu, 30pt each at x=[12,42]). The panel has 16pt outer horizontal
        // padding; 32pt more → text starts at 48pt, clearing the 42pt button
        // edge by 6pt on each side.
        .padding(.horizontal, 32)
    }

    // MARK: - Controls

    /// Active ASR engine + its group, tappable to open the model selector.
    private var modelChip: some View {
        Button {
            showModelSelector = true
        } label: {
            HStack(spacing: 5) {
                if let g = activeGroupName {
                    Text(g)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.secondary)
                    Text("·").font(.caption2).foregroundStyle(.quaternary)
                }
                Text(activeModelLabel)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(Color.secondary.opacity(0.1)))
        }
    }

    /// Recognition-language switcher (options from the system's preferred locales).
    private var languageMenu: some View {
        Menu {
            ForEach(VoiceLanguages.options) { opt in
                Button {
                    viewModel.language = opt.code
                } label: {
                    if viewModel.language == opt.code {
                        Label(opt.label, systemImage: "checkmark")
                    } else {
                        Text(opt.label)
                    }
                }
            }
        } label: {
            // Globe-only, like the keyboard's language switch key. Matches the
            // other controls: black fill, white icon, subtle border.
            Image(systemName: "globe")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(ChatColors.secondaryText)
                .frame(width: 30, height: 30)
                .background(Circle().fill(ChatColors.inputIconBg))
                .overlay(Circle().strokeBorder(ChatColors.inputIconBorder, lineWidth: 0.5))
        }
        .accessibilityLabel(Text("Recognition language: \(VoiceLanguages.option(for: viewModel.language).label)", comment: "Inline voice language switch"))
    }

    // MARK: - Voice correction — design §15.3

    /// Top-right slot: globe (switch recognition language) ⇄ manual AI-correction button.
    ///
    /// [voice-correction] MANUAL by design. Correction is user-initiated: the button is a
    /// standing affordance whenever a transcript exists, and nothing runs until the user
    /// taps it. There is NO automatic background pass and NO auto glyph swap — the earlier
    /// auto-on-transcript flow was removed because a correction should happen when the user
    /// asks for it, not on its own.
    @ViewBuilder
    private var voiceCorrectionOrLanguageMenu: some View {
        if viewModel.isEditingTranscript {
            // Editing: the user's attention is on the keyboard. Show nothing here rather
            // than a button competing for it.
            Color.clear.frame(width: 30, height: 30)
        } else if !viewModel.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // A transcript exists → offer manual AI correction. Switching the RECOGNITION
            // language is meaningless once the audio is already transcribed, so this slot is
            // free to host the correction button.
            correctionButton
                .transition(.scale.combined(with: .opacity))
        } else {
            languageMenu
                .transition(.scale.combined(with: .opacity))
        }
    }

    /// Standing "AI correction" button. Tap → run correction on the current transcript;
    /// apply the result in place if the model changed anything, otherwise tell the user
    /// there was nothing to fix.
    private var correctionButton: some View {
        Button {
            // One-time collection-consent prompt on the very first tap
            // (T-voice-correction-collection-consent). After the user answers
            // once — either way — it never shows again; the correction itself
            // runs regardless of the choice.
            if !VoiceCorrectionCollectionConsent.shared.hasPrompted {
                showCollectionConsentPrompt = true
            } else {
                runManualCorrection()
            }
        } label: {
            // Same 30×30 pill as the globe so swapping the glyph never reflows the layout.
            ZStack {
                if isCorrecting {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "sparkles")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(ChatColors.secondaryText)
                }
            }
            .frame(width: 30, height: 30)
            .background(Circle().fill(ChatColors.inputIconBg))
            .overlay(Circle().strokeBorder(ChatColors.inputIconBorder, lineWidth: 0.5))
        }
        .disabled(isCorrecting)
        .accessibilityLabel(Text("Correct transcript with AI", comment: "Voice manual-correction button"))
        .alert(AppLocalized("Improve voice corrections?",
                      comment: "One-time prompt: enable correction data collection"),
               isPresented: $showCollectionConsentPrompt) {
            Button(AppLocalized("Enable", comment: "Enable correction data collection")) {
                VoiceCorrectionCollectionConsent.shared.isEnabled = true
                VoiceCorrectionCollectionConsent.shared.hasPrompted = true
                runManualCorrection()
            }
            Button(AppLocalized("Not Now", comment: "Decline correction data collection"),
                   role: .cancel) {
                VoiceCorrectionCollectionConsent.shared.hasPrompted = true
                runManualCorrection()
            }
        } message: {
            Text("Minis can store your transcript fixes (original → corrected pairs) and accepted AI corrections in a local on-device database to make future voice corrections smarter. Nothing is uploaded. You can change this or clear the data anytime in Settings → Permissions.",
                 comment: "One-time prompt body: correction data collection")
        }
    }

    /// User tapped the AI-correction button. Run the engine on the current transcript and
    /// apply the result in place. Manual, one-shot: no debounce, no auto pass.
    ///
    /// Applying a real change is ALSO the "accepted" signal for learning — the user asked
    /// for the correction and took it — so it records the acceptance the same way the old
    /// apply-suggestion button did. That is what lets the confusion dictionary grow (the
    /// prior auto-flow left acceptance at zero because it required a second explicit tap on
    /// a menu the user rarely opened).
    private func runManualCorrection() {
        let text = viewModel.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !viewModel.isEditingTranscript, !isCorrecting else { return }
        isCorrecting = true
        VoiceLog.log("[VoiceCorrection] manual correction requested transcript=\"\(text.prefix(40))\" len=\(text.count)")

        let context = conversationContext?() ?? .empty
        Task {
            let suggestion = await VoiceCorrectionEngine.shared.correct(
                transcript: text,
                locale: PhoneticNormalizerRegistry.normalizedLocaleKey(viewModel.language),
                context: context,
                trigger: "manual")
            await MainActor.run {
                isCorrecting = false
                // Bail if the transcript changed under us (re-recorded / edited / sent while
                // the model was running) — applying to text no longer on screen is wrong.
                guard viewModel.transcript.trimmingCharacters(in: .whitespacesAndNewlines) == text else {
                    VoiceLog.log("[VoiceCorrection] manual result discarded — transcript changed during run")
                    return
                }
                if suggestion.hasChange {
                    VoiceLog.log("[VoiceCorrection] manual applied: \(suggestion.diffSummary)")
                    withAnimation(.easeInOut(duration: 0.2)) {
                        viewModel.setTranscript(suggestion.corrected)
                    }
                    inputText = suggestion.corrected
                    // Taking a requested correction is the acceptance signal (learning).
                    Task.detached(priority: .utility) {
                        await VoiceCorrectionRecorder.shared.recordSuggestionAccepted(
                            original: suggestion.original,
                            corrected: suggestion.corrected,
                            summary: suggestion.diffSummary)
                    }
                } else {
                    let reason = suggestion.rejectedReason ?? "none"
                    VoiceLog.log("[VoiceCorrection] manual: no change (reason=\(reason))")
                    if reason == "llm_timeout" || reason.hasPrefix("llm_error") {
                        // Infrastructure failure (timeout / empty stream / API
                        // error) — the model never actually judged "no fix
                        // needed". Saying "No corrections needed" here disguises
                        // an outage as a semantic verdict (exactly how the
                        // adaptive-thinking token-burn bug stayed hidden).
                        MinisToast.show(AppLocalized("Correction failed, original kept",
                                               comment: "Voice: AI correction call failed (timeout/error); transcript left unchanged"),
                                        systemImage: "exclamationmark.triangle.fill")
                    } else {
                        // Model genuinely found nothing to fix — tell the user so
                        // the tap doesn't read as "nothing happened".
                        MinisToast.show(AppLocalized("No corrections needed",
                                               comment: "Voice: AI found nothing to fix"),
                                        systemImage: "sparkles")
                    }
                }
            }
        }
    }

    /// True when the spinner is showing but mic is idle — tapping should cancel.
    private var showCancelIcon: Bool {
        viewModel.isTranscribing && viewModel.state != .recording
    }

    private var micButton: some View {
        Button {
            if showCancelIcon {
                viewModel.cancelTranscription()
            } else {
                viewModel.handleMainButtonTap()
            }
        } label: {
            ZStack {
                Circle()
                    .fill(Color.white)
                    .frame(width: micDiameter, height: micDiameter)
                    .shadow(color: .black.opacity(0.18), radius: expanded ? 7 : 5, y: expanded ? 3 : 2)

                if viewModel.isTranscribing {
                    TimelineView(.animation) { context in
                        let t = context.date.timeIntervalSinceReferenceDate
                        let angle = Angle(degrees: (t / 0.9).truncatingRemainder(dividingBy: 1) * 360)
                        Circle()
                            .trim(from: 0, to: 75.0 / 360.0)
                            .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .frame(width: micDiameter, height: micDiameter)
                            .rotationEffect(angle)
                    }
                }

                switch viewModel.state {
                case .recording:
                    InlineMiniWaveform(levels: viewModel.waveformLevels)
                        .frame(width: expanded ? 38 : 26, height: expanded ? 24 : 18)
                default:
                    if showCancelIcon {
                        Image(systemName: "xmark")
                            .font(.system(size: expanded ? 22 : 16, weight: .semibold))
                            .foregroundStyle(.black)
                    } else {
                        Image(systemName: "mic.fill")
                            .font(.system(size: expanded ? 24 : 18, weight: .medium))
                            .foregroundStyle(.black)
                    }
                }
            }
            .frame(width: micDiameter, height: micDiameter)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(viewModel.permissionDenied)
        .accessibilityLabel(showCancelIcon
            ? Text("Cancel transcription", comment: "Voice cancel transcription")
            : Text("Toggle recording", comment: "Inline voice mic"))
    }

    private var stateLabel: String {
        if viewModel.permissionDenied {
            return AppLocalized("Microphone access denied — enable it in Settings", comment: "Inline voice permission denied")
        }
        if let err = viewModel.startError {
            return AppLocalized("Couldn't start microphone: \(err)", comment: "Inline voice start error")
        }
        if viewModel.isEditingTranscript {
            return AppLocalized("Editing — tap mic to resume", comment: "Inline voice editing state")
        }
        switch viewModel.state {
        case .waiting:
            if !viewModel.transcript.isEmpty {
                let tips = [
                    AppLocalized("Double-tap text to edit", comment: "Voice tip: edit transcript"),
                    AppLocalized("Tap mic to continue", comment: "Voice tip: resume dictation"),
                    AppLocalized("Hold ⌫ to clear all", comment: "Voice tip: long press delete to clear"),
                ]
                return tips[viewModel.resultTipIndex % tips.count]
            }
            return AppLocalized("Tap to speak", comment: "Inline voice waiting")
        case .recording:
            let tips = [
                AppLocalized("Tap to transcribe", comment: "Voice tip: tap mic to stop and transcribe"),
                AppLocalized("Listening…", comment: "Voice tip: steady recording"),
                AppLocalized("Long press to paste", comment: "Voice tip: paste from clipboard"),
                AppLocalized("Listening…", comment: "Voice tip: steady recording"),
            ]
            return tips[viewModel.recordingTipIndex % tips.count]
        case .processing: return AppLocalized("Recognizing…", comment: "Inline voice processing")
        case .result:
            let tips = [
                AppLocalized("Double-tap text to edit", comment: "Voice tip: edit transcript"),
                AppLocalized("Tap mic to continue", comment: "Voice tip: resume dictation"),
                AppLocalized("Hold ⌫ to clear all", comment: "Voice tip: long press delete to clear"),
            ]
            return tips[viewModel.resultTipIndex % tips.count]
        }
    }
}

// MARK: - Delete control (tap = one char, long-press = repeat, swipe-up = clear all)

/// Holds the per-char repeat + 2s hold timers OUTSIDE the SwiftUI struct so they
/// survive body re-renders. A `@State var Timer?` lives in SwiftUI's backing
/// store and the gesture closures capture a stale `self` copy, so the timers
/// never fire reliably (long-press delete + Clear-all both silently break). A
/// reference-typed `@StateObject` is stable across re-renders, so the timers
/// stay live on the RunLoop.
private final class DeleteButtonState: ObservableObject {
    /// Becomes true after a 2s hold — gates the "Clear all" affordance. Drives
    /// UI, so it's @Published.
    @Published var canClearAll = false

    private var repeatTimer: Timer?
    private var holdTimer: Timer?

    func startRepeat(action: @escaping () -> Void) {
        stopRepeat()
        // .common run-loop mode: while a DragGesture is active the main run loop is
        // in .tracking mode, where a default-mode Timer never fires — that's why the
        // long-press repeat-delete silently broke. Adding it in .common makes it
        // fire during the press.
        let t = Timer(timeInterval: 0.12, repeats: true) { _ in
            VoiceLog.log("[delete] repeat tick")
            action()
        }
        RunLoop.main.add(t, forMode: .common)
        repeatTimer = t
    }

    func stopRepeat() {
        repeatTimer?.invalidate()
        repeatTimer = nil
    }

    func startHoldTimer(after seconds: TimeInterval) {
        stopHoldTimer()
        let t = Timer(timeInterval: seconds, repeats: false) { [weak self] _ in
            withAnimation(.easeInOut(duration: 0.2)) { self?.canClearAll = true }
        }
        RunLoop.main.add(t, forMode: .common)
        holdTimer = t
    }

    func stopHoldTimer() {
        holdTimer?.invalidate()
        holdTimer = nil
    }

    /// Reset everything when the press ends.
    func reset() {
        stopRepeat()
        stopHoldTimer()
        canClearAll = false
    }
}

private struct VoiceDeleteButton: View {
    let onDeleteOne: () -> Void
    let onClearAll: () -> Void
    /// Called each repeat tick; returns true when the transcript is empty so the
    /// Timer auto-stops (prevents lingering deletes eating newly-arrived text).
    var isEmpty: () -> Bool = { false }

    @StateObject private var state = DeleteButtonState()
    @State private var pressing = false
    /// Diameter of the delete button.
    private static let diameter: CGFloat = 47

    var body: some View {
        Image(systemName: "delete.left.fill")
            .font(.system(size: 21, weight: .medium))
            .foregroundStyle(ChatColors.secondaryText)
            .frame(width: Self.diameter, height: Self.diameter)
            .background(Circle().fill(ChatColors.inputIconBg))
            .overlay(Circle().strokeBorder(ChatColors.inputIconBorder, lineWidth: 0.5))
            .scaleEffect(pressing ? 0.9 : 1.0)
            .overlay(
                DeletePressOverlay(
                    onBegan: { [state] in
                        pressing = true
                        onDeleteOne()
                        state.startRepeat {
                            if isEmpty() { state.reset(); return }
                            onDeleteOne()
                        }
                    },
                    onEnded: { [state] in
                        state.reset()
                        withAnimation(.easeInOut(duration: 0.15)) { pressing = false }
                    }
                )
            )
            .onDisappear { state.reset() }
            .accessibilityLabel(Text("Delete dictated text", comment: "Voice delete button"))
    }
}

/// UIKit-based press recognizer that fires immediately on touch-down and keeps
/// firing repeat-delete via the `DeleteButtonState` Timer until touch-up. Using
/// UIKit's `UILongPressGestureRecognizer(minimumPressDuration: 0)` completely
/// sidesteps SwiftUI's gesture priority — the parent's `highPriorityGesture`
/// (panel collapse drag) can no longer steal the delete button's press.
private struct DeletePressOverlay: UIViewRepresentable {
    let onBegan: () -> Void
    let onEnded: () -> Void

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .clear
        let gr = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pressed(_:)))
        gr.minimumPressDuration = 0
        gr.cancelsTouchesInView = true
        v.addGestureRecognizer(gr)
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onBegan = onBegan
        context.coordinator.onEnded = onEnded
    }

    func makeCoordinator() -> Coordinator { Coordinator(onBegan: onBegan, onEnded: onEnded) }

    final class Coordinator: NSObject {
        var onBegan: () -> Void
        var onEnded: () -> Void
        init(onBegan: @escaping () -> Void, onEnded: @escaping () -> Void) {
            self.onBegan = onBegan; self.onEnded = onEnded
        }
        @objc func pressed(_ gr: UILongPressGestureRecognizer) {
            switch gr.state {
            case .began:  onBegan()
            case .ended, .cancelled, .failed: onEnded()
            default: break
            }
        }
    }
}

// MARK: - Mini waveform (dark bars, sits inside the white mic button)

// Measures the transcript's intrinsic content height so the panel can grow
// with content and then cap-and-scroll.
private struct TranscriptContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct InlineMiniWaveform: View {
    let levels: [Float]

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(levels.suffix(7).enumerated()), id: \.offset) { _, level in
                Capsule()
                    .fill(Color.black.opacity(0.85))
                    .frame(width: 3, height: max(5, CGFloat(level) * 26))
                    .animation(.spring(response: 0.18, dampingFraction: 0.6), value: level)
            }
        }
    }
}

// MARK: - Transcript scroll-boundary tracking
//
// [T-voice-scroll-gesture-priority] Shared mechanism for Conflicts I + H.
//
// The panel's outer `.highPriorityGesture(DragGesture(minimumDistance: 18))` sits
// on the OUTERMOST container, so it outranks any inner ScrollView's pan and used
// to hijack every vertical drag > 18pt — the user could never actually scroll a
// long transcript in EITHER mode: dragging to read simply collapsed the panel
// (or exited editing).
//
// The fix needs one reliable signal — "can this transcript still scroll in the
// direction the finger is moving?" — consulted by the panel gesture before it
// acts. SwiftUI's ScrollView exposes no offset on our deployment target (the app
// ships iOS 16.0, so iOS 17/18 ScrollPosition APIs are out), so we read it from
// UIKit: a zero-size probe planted inside the ScrollView walks up to the real
// UIScrollView backing it and observes contentOffset/contentSize/bounds.
//
// This deliberately does NOT rewrite the transcript editor as a
// UIViewRepresentable (the spec floated that): the editing branch is a
// `TextField(axis: .vertical)` whose focus/keyboard lifecycle we have just
// stabilised across several fixes (T-voice-panel-gap-after-edit). Swapping it for
// a custom UITextView would put all of that at risk for no added signal — the
// probe below yields the same contentOffset/contentSize facts without touching
// the editor.

/// Live scroll-boundary state for the transcript ScrollView, shared by the
/// panel's expand/collapse drag and the exit-edit drag.
@MainActor
final class TranscriptScrollState: ObservableObject {
    /// True when the content is taller than the viewport — i.e. there is anything
    /// to scroll at all. When false the panel gesture behaves exactly as it did
    /// before this change (immediate threshold, nothing to absorb).
    @Published var isScrollable = false
    /// True when the content is scrolled to (or past, via rubber-band) the top.
    @Published var atTop = true
    /// True when the content is scrolled to (or past) the bottom.
    @Published var atBottom = true

    /// Should the panel-level drag yield to the transcript's own scrolling?
    ///
    /// `dy > 0` is a downward finger drag, which scrolls content toward its TOP;
    /// it must be absorbed by the ScrollView while there is still room above.
    /// `dy < 0` drags up, scrolling toward the BOTTOM; absorbed while room below.
    /// A non-scrollable transcript never absorbs anything.
    func shouldAbsorbDrag(dy: CGFloat) -> Bool {
        guard isScrollable else { return false }
        return dy > 0 ? !atTop : !atBottom
    }

    func update(isScrollable: Bool, atTop: Bool, atBottom: Bool) {
        if self.isScrollable != isScrollable { self.isScrollable = isScrollable }
        if self.atTop != atTop { self.atTop = atTop }
        if self.atBottom != atBottom { self.atBottom = atBottom }
    }
}

/// Zero-size probe placed INSIDE a SwiftUI ScrollView. Walks up the UIKit view
/// tree to the UIScrollView that backs it and keeps `state` in sync with its
/// scroll position. Purely an observer — it installs no gesture recognizer and
/// never alters hit-testing, so it cannot disturb the scroll it is measuring.
private struct TranscriptScrollProbe: UIViewRepresentable {
    @ObservedObject var state: TranscriptScrollState

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .clear
        v.isUserInteractionEnabled = false
        context.coordinator.attach(to: v)
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.state = state
        // Re-resolve on every update: the transcript grows/shrinks as text
        // arrives, and the editing/non-editing branch swap rebuilds the tree.
        context.coordinator.attach(to: uiView)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    func makeCoordinator() -> Coordinator { Coordinator(state: state) }

    final class Coordinator: NSObject {
        var state: TranscriptScrollState
        private weak var scrollView: UIScrollView?
        private var observations: [NSKeyValueObservation] = []

        init(state: TranscriptScrollState) {
            self.state = state
        }

        func attach(to view: UIView) {
            // Defer one runloop turn: at makeUIView time the probe is not yet in
            // the window, so the UIScrollView ancestor does not exist to find.
            DispatchQueue.main.async { [weak self, weak view] in
                guard let self, let view else { return }
                guard let sv = Self.enclosingScrollView(of: view) else { return }
                guard sv !== self.scrollView else {
                    self.publish(from: sv)   // same scroll view, refresh metrics
                    return
                }
                self.detach()
                self.scrollView = sv
                // Observe the three properties that define the boundary state.
                // KVO (not a delegate) so we never take over UIScrollView's
                // delegate, which SwiftUI itself owns.
                let keyPaths: [KeyPath<UIScrollView, CGPoint>] = [\.contentOffset]
                for kp in keyPaths {
                    self.observations.append(sv.observe(kp, options: [.new, .initial]) { [weak self] sv, _ in
                        MainActor.assumeIsolated { self?.publish(from: sv) }
                    })
                }
                self.observations.append(sv.observe(\.contentSize, options: [.new, .initial]) { [weak self] sv, _ in
                    MainActor.assumeIsolated { self?.publish(from: sv) }
                })
                self.observations.append(sv.observe(\.bounds, options: [.new, .initial]) { [weak self] sv, _ in
                    MainActor.assumeIsolated { self?.publish(from: sv) }
                })
                self.publish(from: sv)
            }
        }

        func detach() {
            observations.forEach { $0.invalidate() }
            observations.removeAll()
            scrollView = nil
        }

        @MainActor
        private func publish(from sv: UIScrollView) {
            // Viewport height net of insets is the true visible extent.
            let viewport = sv.bounds.height - sv.adjustedContentInset.top - sv.adjustedContentInset.bottom
            let content = sv.contentSize.height
            // 1pt of slack absorbs sub-point layout rounding, which would
            // otherwise leave a fully-scrolled view reporting "not at bottom"
            // and permanently swallow the hand-off drag.
            let scrollable = content > viewport + 1
            let minY = -sv.adjustedContentInset.top
            let maxY = max(minY, content - viewport + minY)
            let y = sv.contentOffset.y
            state.update(
                isScrollable: scrollable,
                // `<=` / `>=` so a rubber-band overscroll still counts as "at the
                // boundary" — otherwise a bounce would re-swallow the hand-off.
                atTop: !scrollable || y <= minY + 1,
                atBottom: !scrollable || y >= maxY - 1
            )
        }

        private static func enclosingScrollView(of view: UIView) -> UIScrollView? {
            var v: UIView? = view.superview
            while let cur = v {
                if let sv = cur as? UIScrollView { return sv }
                v = cur.superview
            }
            return nil
        }
    }
}

/// [T-voice-scroll-gesture-priority] Attaches the panel's collapse/expand/
/// exit-edit drag at a priority that depends on the transcript's scroll state.
///
/// SwiftUI cannot change a gesture's priority in place, so the two cases are
/// distinct modifiers applied by a branch:
///   • `absorbedByScroll` — the transcript still has scrolling to do in this
///     region, so attach with `.gesture` (DEFAULT priority). The inner
///     ScrollView's pan outranks it and the content scrolls normally; the panel
///     stays put.
///   • otherwise — attach with `.highPriorityGesture`, the original behaviour, so
///     the panel reliably claims the drag (necessary because a bouncing
///     ScrollView holds onto the touch even at its boundary, and a default-
///     priority parent would never fire).
///
/// The branch changes the gesture's identity, so SwiftUI tears the old recognizer
/// down and installs the new one.
///
/// [T-voice-edit-gesture-exclusive] EDIT MODE INSTALLS NO PANEL GESTURE AT ALL.
/// When `isEditing` is true this modifier attaches nothing, so the transcript
/// TextField's own native gestures (caret placement, selection, internal scroll)
/// are the ONLY thing recognizing touches — there is no panel drag to arbitrate
/// with, race, or suppress. This replaces the earlier approach of keeping the
/// drag installed and rejecting its synthetic/mid-touch callbacks
/// (`panelDragInFlight`): the user asked for true mutual exclusion in edit mode,
/// not a per-trigger patch. Removing the recognizer is exclusion at the source —
/// the edit-entry double-tap can no longer flip the gesture's identity mid-touch
/// (the recognizer isn't there), and no in-edit drag can reach the panel.
///
/// Exit-edit is NOT lost: it moves to the transcript editor's own swipe-down
/// (see the editing branch in transcriptArea), which is a gesture ON the text
/// area, consistent with "only the text box's gestures respond while editing."
private struct PanelDragGestureModifier: ViewModifier {
    let isEditing: Bool
    let absorbedByScroll: Bool
    let onDragChanged: () -> Void
    let onDragEnded: (DragGesture.Value) -> Void

    private var drag: some Gesture {
        DragGesture(minimumDistance: 18)
            .onChanged { _ in onDragChanged() }
            .onEnded(onDragEnded)
    }

    @ViewBuilder
    func body(content: Content) -> some View {
        if isEditing {
            // Mutual exclusion: no panel-level gesture competes with the editor.
            content
        } else if absorbedByScroll {
            content.gesture(drag)
        } else {
            content.highPriorityGesture(drag)
        }
    }
}
