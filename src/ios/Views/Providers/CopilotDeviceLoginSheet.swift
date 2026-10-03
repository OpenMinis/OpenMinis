import SwiftUI
import SafariServices

/// [T-copilot-safari-cancels-poll] Sheet-side logging, so the lifecycle event
/// that used to silently kill the device flow leaves a trace next to the
/// manager's own `[Copilot]` lines.
private let copilotOAuthLog = AppLogger(category: "CopilotOAuth")

/// [T-copilot-provider] Device-code login for GitHub Copilot (RFC 8628).
///
/// Structurally a sibling of `KimiDeviceLoginSheet`, with one deliberate
/// addition: a RISK GATE the user must pass before the flow starts.
///
/// This integration is unofficial. GitHub's terms list proxying Copilot usage as
/// grounds for restricting an account, and the consequence lands on the user's
/// account, not ours. Showing that only in small print under a button the user
/// has already pressed would be too late — so the first thing this sheet does is
/// state it plainly and require an explicit "I understand" before a single
/// request goes out.
@available(iOS 16.0, *)
struct CopilotDeviceLoginSheet: View {
    let instanceId: String
    var onFinish: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    private enum Phase: Equatable {
        /// The risk notice. Nothing has been sent yet.
        case consent
        case starting
        case awaitingUser(userCode: String, url: String)
        case success
        case failed(String)
    }

    /// [T-copilot-consent-remembered] Start on the notice only if it has not
    /// been accepted before; otherwise go straight to requesting a device code.
    /// The `.task` below performs that jump — the initial value stays `.consent`
    /// so that a first run renders the notice before any network call, with no
    /// frame in which `.starting` is briefly shown to someone who has not agreed.
    @State private var phase: Phase = .consent
    @State private var copied = false
    @State private var loginTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                switch phase {
                case .consent:
                    consentBody

                case .starting:
                    ProgressView()
                    Text(AppLocalized("Contacting GitHub…"))
                        .foregroundStyle(.secondary)

                case let .awaitingUser(userCode, url):
                    awaitingBody(userCode: userCode, url: url)

                case .success:
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.green)
                    Text(AppLocalized("Signed in"))
                        .font(.headline)

                case let .failed(message):
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(.orange)
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button(AppLocalized("Try Again")) { start() }
                        .buttonStyle(.borderedProminent)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding()
            .navigationTitle(AppLocalized("GitHub Copilot Login"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { finish(false) }
                }
            }
        }
        .interactiveDismissDisabled(phase == .starting)
        // [T-copilot-consent-remembered] Skip the notice for a user who has
        // already accepted it. Placed in `.task` rather than in the `@State`
        // initialiser so the decision is read when the sheet actually appears.
        .task {
            if CopilotConstants.hasAcceptedSignInConsent, phase == .consent {
                start()
            }
        }
        // [T-copilot-safari-cancels-poll] Do NOT cancel the poll here.
        //
        // This was `.onDisappear { loginTask?.cancel() }`, and it killed the
        // device flow at the exact moment the user went to authorize it.
        // `presentSafari` presents SFSafariViewController on the TOP view
        // controller — which is this sheet — so the sheet is covered, SwiftUI
        // sends `onDisappear`, and the polling task was cancelled before it had
        // issued a single request. Device log, 2026-09-12 22:54:20: the
        // "polling START" line appears and then not one "poll status=" line in
        // the following 46s, where a 5s interval owes ~9.
        //
        // The task is still cancelled on every real exit — `finish()` (Cancel
        // button, success, the parent's completion) and `start()` before a
        // retry — so this is not a leak: what is removed is only the
        // *incidental* cancel from being visually covered. The Task also holds
        // no strong reference to the view, and the flow has its own deadline
        // (`expiresIn`), so a sheet that somehow goes away without `finish()`
        // ends on its own within 15 minutes.
        .onDisappear {
            copilotOAuthLog.info("[Copilot] login sheet disappeared — poll continues (Safari may be covering it)")
        }
    }

    /// The gate. Deliberately not dismissible-by-default into the flow: the
    /// user has to press the button for anything to happen.
    ///
    /// [T-copilot-disclaimer] The notice is scrollable and spells out each
    /// distinct risk as its own line rather than one dense paragraph — the
    /// points are different in kind (unofficial, may break, account risk, not
    /// for work accounts) and a reader skimming a block of small grey text
    /// takes none of them in. Cancel is the only other exit, and it sends
    /// nothing.
    @ViewBuilder
    private var consentBody: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(.orange)
                Text(AppLocalized("Notice regarding unofficial access"))
                    .font(.headline)

                VStack(alignment: .leading, spacing: 12) {
                    disclaimerPoint(AppLocalized(
                        "This sign-in method is not officially authorized. Minis is not affiliated with, endorsed by, or supported by GitHub, and this integration has not been reviewed or approved by GitHub.",
                        comment: "Copilot disclaimer: unofficial status"))
                    disclaimerPoint(AppLocalized(
                        "Authorization is performed using a client identity issued to a third-party developer tool rather than to Minis, and it relies on service interfaces that are not published for this purpose.",
                        comment: "Copilot disclaimer: borrowed client identity and unpublished interfaces"))
                    disclaimerPoint(AppLocalized(
                        "Access may be withdrawn without notice. The provider may change or discontinue these interfaces at any time, which can cause requests to fail or this feature to stop working entirely.",
                        comment: "Copilot disclaimer: requests may fail or stop working"))
                    disclaimerPoint(AppLocalized(
                        "Use may violate the provider's terms of service and may result in rate limiting, suspension, or permanent termination of your Copilot entitlement or your GitHub account. Any such consequence applies to your account and cannot be reversed by Minis.",
                        comment: "Copilot disclaimer: account suspension or termination risk"))
                    disclaimerPoint(AppLocalized(
                        "Use of an organization, enterprise, or production account is strongly discouraged. Please sign in with a personal account whose loss you are prepared to accept.",
                        comment: "Copilot disclaimer: not for org/enterprise/production accounts"))
                    disclaimerPoint(AppLocalized(
                        "Your access token is held in the iOS Keychain on this device and is transmitted only to the provider. Minis does not collect it and does not route it through any server operated by us. A backup you export yourself may contain it.",
                        comment: "Copilot disclaimer: where the token goes"))
                    disclaimerPoint(AppLocalized(
                        "This feature is provided on an “as is” basis, without warranty of any kind. By continuing you confirm that you have read this notice and accept these risks entirely at your own discretion.",
                        comment: "Copilot disclaimer: as-is, user accepts the risk"))
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.bottom, 8)
        }

        Button {
            start()
        } label: {
            Text(AppLocalized("I have read and accept these terms",
                              comment: "Copilot disclaimer: explicit consent button"))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(.orange)
    }

    /// One bullet of the notice.
    @ViewBuilder
    private func disclaimerPoint(_ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(verbatim: "•")
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private func awaitingBody(userCode: String, url: String) -> some View {
        // [T-copilot-copy-on-open] The wording no longer tells the user to tap
        // the code: opening the page copies it, so "paste" is the accurate
        // instruction. Tapping the code still works for anyone who wants it.
        Text(AppLocalized("1. Open the verification page — this copies the code\n2. Paste it to authorize Minis"))
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

        // Tap to copy — the code is long enough that retyping it in a browser
        // is a real annoyance.
        Button {
            UIPasteboard.general.string = userCode
            copied = true
        } label: {
            HStack(spacing: 8) {
                Text(userCode)
                    .font(.system(.title, design: .monospaced).weight(.semibold))
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 20)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)

        Button {
            // [T-copilot-copy-on-open] Copy the code as part of opening the
            // page, not as a separate tap the user has to know to make first.
            // GitHub's page asks for the code immediately, and by then the
            // browser is covering the only place it was shown — so a user who
            // did not tap the code first has to dismiss Safari to read it. The
            // pasteboard is the one place the code is still reachable from
            // inside the browser.
            UIPasteboard.general.string = userCode
            copied = true
            if let u = URL(string: url) { presentSafari(u) }
        } label: {
            Label(AppLocalized("Open verification page"), systemImage: "safari")
        }
        .buttonStyle(.borderedProminent)

        HStack(spacing: 6) {
            ProgressView().controlSize(.small)
            Text(AppLocalized("Waiting for authorization…"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 4)
    }

    private func start() {
        // [T-copilot-consent-remembered] Reaching here means the notice was
        // either accepted just now or accepted previously; either way the
        // standing decision is recorded, so the next sign-in goes straight to
        // the device code.
        CopilotConstants.recordSignInConsent()
        loginTask?.cancel()
        copied = false
        phase = .starting
        loginTask = Task { @MainActor in
            do {
                let auth = try await CopilotOAuthManager.shared.requestDeviceAuthorization()
                if Task.isCancelled { return }
                phase = .awaitingUser(userCode: auth.userCode, url: auth.openURL)
                _ = try await CopilotOAuthManager.shared.pollForAccessToken(
                    auth: auth, instanceId: instanceId)
                if Task.isCancelled { return }
                phase = .success
                try? await Task.sleep(nanoseconds: 900_000_000)
                finish(true)
            } catch is CancellationError {
                // User cancelled — nothing to report.
            } catch {
                if Task.isCancelled { return }
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private func finish(_ success: Bool) {
        loginTask?.cancel()
        // [T-copilot-safari-cancels-poll] Close the in-app Safari we presented,
        // if it is still up. Now that the poll survives being covered,
        // authorization typically completes WHILE Safari is on screen — and
        // dismissing only the sheet underneath would leave the user staring at
        // the GitHub page with no sign anything happened.
        dismissPresentedSafari()
        onFinish(success)
        dismiss()
    }

    /// Dismiss the `SFSafariViewController` this sheet presented, if present.
    /// Checked by type so an unrelated presentation is never torn down.
    private func dismissPresentedSafari() {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
        else { return }
        var topVC = root
        while let presented = topVC.presentedViewController { topVC = presented }
        if topVC is SFSafariViewController {
            topVC.dismiss(animated: true)
        }
    }

    /// In-app Safari, matching the other OAuth providers rather than jumping
    /// out to system Safari.
    private func presentSafari(_ url: URL) {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var topVC = root
        while let presented = topVC.presentedViewController { topVC = presented }
        topVC.present(SFSafariViewController(url: url), animated: true)
    }
}
