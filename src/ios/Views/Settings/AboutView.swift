import SwiftUI
import UIKit

struct AboutView: View {
    private let appVersion: String = {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }()

    /// When this binary was built, from the executable's modification date.
    ///
    /// Exists because the version string alone cannot answer "is the fix I
    /// just shipped actually running on that device?" — CFBundleVersion is
    /// static across rebuilds, so an OTA install that silently didn't replace
    /// the app looks identical to one that did. That ambiguity has cost real
    /// debugging time on the test devices. Same source as the debug server's
    /// `buildDate`, but visible in Release too, where no debug server exists.
    /// [T-env-diagnostic-line] One line naming the RUNTIME environment, for
    /// bug reports.
    ///
    /// A macOS 27 rendering report cost a full round trip because nothing in
    /// the app said which environment it was: "Designed for iPad" (an
    /// unmodified iOS binary on Apple Silicon) and Mac Catalyst (UIKit bridged
    /// onto AppKit) look identical in a screenshot but share almost no
    /// rendering path, and the SDK release notes that explain a symptom apply
    /// to one and not the other. Guessing wrong sends the whole diagnosis down
    /// the wrong track.
    ///
    /// `isiOSAppOnMac` is the authoritative discriminator and is what
    /// distinguishes the two; `targetEnvironment(macCatalyst)` is resolved at
    /// COMPILE time, so it reports how this binary was built rather than where
    /// it is running — both are printed because the pair is what identifies
    /// the mode unambiguously.
    private let environmentLine: String = {
        let osv = ProcessInfo.processInfo.operatingSystemVersion
        let os = "\(osv.majorVersion).\(osv.minorVersion).\(osv.patchVersion)"
        let idiom: String = switch UIDevice.current.userInterfaceIdiom {
        case .phone: "phone"
        case .pad: "pad"
        case .mac: "mac"
        case .tv: "tv"
        case .carPlay: "carPlay"
        case .vision: "vision"
        default: "unspecified"
        }
        #if targetEnvironment(macCatalyst)
        let built = "catalyst"
        #else
        let built = "ios"
        #endif
        let onMac = ProcessInfo.processInfo.isiOSAppOnMac
        // "iOSAppOnMac" is Designed for iPad; "catalyst" built + not
        // isiOSAppOnMac is a true Catalyst run; otherwise a real device.
        let mode = onMac ? "iOSAppOnMac" : (built == "catalyst" ? "catalyst" : "device")
        return "\(mode) · \(idiom) · OS \(os) · built:\(built)"
    }()

    private let buildDate: String = {
        guard let url = Bundle.main.executableURL,
              let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let mod = attrs[.modificationDate] as? Date
        else { return "unknown" }
        let fmt = DateFormatter()
        // Local time, seconds included: this gets compared by eye against the
        // timestamp of a build that just finished.
        fmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return fmt.string(from: mod)
    }()

    var body: some View {
        List {
            // MARK: - Project Info
            Section {
                VStack(spacing: 8) {
                    if let icon = UIImage(named: "AppIcon60x60") ?? UIImage(named: "AppIcon") {
                        Image(uiImage: icon)
                            .resizable()
                            .frame(width: 80, height: 80)
                            .clipShape(RoundedRectangle(cornerRadius: 18))
                            .overlay(
                                RoundedRectangle(cornerRadius: 18)
                                    .stroke(Color(UIColor.separator), lineWidth: 0.5)
                            )
                    }
                    Text("Minis")
                        .font(.title2.bold())
                    Text("Version \(appVersion)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("Built \(buildDate)")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        // Selectable so it can be copied into a bug report.
                        .textSelection(.enabled)
                    // [T-env-diagnostic-line] Sits with the build date because
                    // it answers the same question — "what exactly is running?"
                    // — and gets copied into the same bug reports.
                    Text(environmentLine)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .textSelection(.enabled)
                    Text("Minis is Your Fully Local, Fully Private On-Device Agent.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .listRowBackground(Color.clear)
            }

            // MARK: - Links
            Section("Links") {
                Link(destination: URL(string: "https://github.com/OpenMinis")!) {
                    Label {
                        HStack {
                            Text("GitHub Repository")
                                .foregroundStyle(Color(UIColor.label))
                            Spacer()
                            Image(systemName: "arrow.up.right.square")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "link.circle.fill")
                    }
                }
                Link(destination: URL(string: "https://github.com/OpenMinis/OpenMinis/issues")!) {
                    Label {
                        HStack {
                            Text("Report an Issue")
                                .foregroundStyle(Color(UIColor.label))
                            Spacer()
                            Image(systemName: "arrow.up.right.square")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "exclamationmark.circle.fill")
                    }
                }
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}
