import SwiftUI
import SafariServices

/// Minimal device-code login sheet for Kimi Code OAuth (RFC 8628). Presents the
/// user code + verification URL while `KimiOAuthManager` polls the token
/// endpoint in the background. Self-contained so the provider screens only need
/// to present it — mirrors the state/error surface of the other OAuth flows.
///
/// Usage (once ProviderType.kimiCode is wired):
///   .sheet(isPresented: $showKimiLogin) {
///       KimiDeviceLoginSheet(instanceId: instance.id) { success in ... }
///   }
@available(iOS 16.0, *)
struct KimiDeviceLoginSheet: View {
    let instanceId: String
    /// Called on dismiss with whether authentication succeeded.
    var onFinish: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    private enum Phase: Equatable {
        case starting
        case awaitingUser(userCode: String, url: String)
        case success
        case failed(String)
    }

    @State private var phase: Phase = .starting
    @State private var copied = false
    @State private var loginTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                switch phase {
                case .starting:
                    ProgressView()
                    Text(AppLocalized("Contacting Kimi…"))
                        .foregroundStyle(.secondary)

                case let .awaitingUser(userCode, url):
                    awaitingBody(userCode: userCode, url: url)

                case .success:
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.green)
                    Text(AppLocalized("Signed in to Kimi Code"))
                        .font(.headline)

                case let .failed(message):
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.orange)
                    Text(message)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                    Button(AppLocalized("Try Again")) { start() }
                        .buttonStyle(.borderedProminent)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding()
            .navigationTitle(AppLocalized("Kimi Code Login"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { finish(false) }
                }
            }
        }
        .interactiveDismissDisabled(phase == .starting)
        .onAppear { start() }
        // [T-copilot-safari-cancels-poll] Same defect as the Copilot sheet, and
        // the same fix. `presentSafari` presents on the TOP view controller,
        // i.e. over this sheet, so opening the verification page covered the
        // sheet, SwiftUI sent `onDisappear`, and the poll was cancelled at the
        // precise moment the user left to authorize. Diagnosed on Copilot from a
        // device log (a "polling START" line with no poll following it); this
        // file has the identical structure, so it is corrected together rather
        // than left as a known-broken twin.
        //
        // Real exits still cancel: `finish()` and `start()`'s retry.
        .onDisappear { }
    }

    @ViewBuilder
    private func awaitingBody(userCode: String, url: String) -> some View {
        Text(AppLocalized("1. Open the verification page\n2. Enter this code to authorize Minis"))
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

        // The user code — tap to copy.
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
        loginTask?.cancel()
        copied = false
        phase = .starting
        loginTask = Task { @MainActor in
            do {
                try await KimiOAuthManager.shared.login(instanceId: instanceId) { presentation in
                    // Presentation callback fires once the device code is issued.
                    Task { @MainActor in
                        phase = .awaitingUser(userCode: presentation.userCode,
                                              url: presentation.verificationURL)
                    }
                }
                if Task.isCancelled { return }
                phase = .success
                // Brief success flash, then dismiss.
                try? await Task.sleep(nanoseconds: 900_000_000)
                finish(true)
            } catch is CancellationError {
                // user cancelled — no error surface
            } catch {
                if Task.isCancelled { return }
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private func finish(_ success: Bool) {
        loginTask?.cancel()
        // [T-copilot-safari-cancels-poll] See the Copilot sheet: now that the
        // poll survives Safari covering this sheet, authorization completes with
        // Safari still on screen, so it has to be dismissed too.
        dismissPresentedSafari()
        onFinish(success)
        dismiss()
    }

    /// Dismiss the `SFSafariViewController` this sheet presented, if present.
    /// Type-checked so an unrelated presentation is never torn down.
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

    /// Open the verification page in an in-app SFSafariViewController — matching
    /// how the other OAuth providers (Claude / Codex / Gemini) present their auth
    /// pages, rather than jumping out to system Safari. Presented from the
    /// top-most VC (which is this sheet) so it stacks over the login sheet.
    private func presentSafari(_ url: URL) {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var topVC = root
        while let presented = topVC.presentedViewController { topVC = presented }
        topVC.present(SFSafariViewController(url: url), animated: true)
    }
}
