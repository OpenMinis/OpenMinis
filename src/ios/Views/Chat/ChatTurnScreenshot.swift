import SwiftUI
import UIKit

// MARK: - ChatScreenshotPreview

struct ChatScreenshotPreview: Identifiable {
    let id = UUID()
    let image: UIImage
}

// MARK: - ChatScreenshotPreviewSheet

struct ChatScreenshotPreviewSheet: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss

    @State private var toast: String?

    private let logger = AppLogger(category: "ScreenshotPreview")

    var body: some View {
        NavigationStack {
            ScrollView(.vertical, showsIndicators: true) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: Color(UIColor { $0.userInterfaceStyle == .dark ? .clear : UIColor(white: 0, alpha: 0.35) }), radius: 12, y: 4)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(UIColor.systemBackground).ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    HStack(spacing: 16) {
                        Button {
                            copyImage(image)
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 14))
                        }
                        Button {
                            shareImage(image)
                        } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                        Button {
                            saveImageToPhotos(image)
                        } label: {
                            Image(systemName: "square.and.arrow.down")
                        }
                    }
                }
            }
            .toolbarBackground(.visible, for: .navigationBar)
            .overlay(alignment: .bottom) {
                if let toast {
                    Text(toast)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color(UIColor.systemBackground))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color(UIColor.label).opacity(0.75))
                        .clipShape(Capsule())
                        .padding(.bottom, 32)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
        }
    }

    // MARK: - Diagnostics

    /// [T-sharesheet-main-hang] Diagnostics used to run inline on the main
    /// thread on every Copy/Share/Save tap, encoding the turn image to BOTH
    /// PNG and JPEG synchronously — for a tall chat-turn screenshot that alone
    /// blocked the main thread ~1s+ before the action even started (the
    /// [HangDetector] MAIN HANG with ShareSheet frames). Now DEBUG-only and
    /// dispatched off the main thread so it never stalls the UI.
    private func logImageDiagnostics(_ action: String) {
        #if DEBUG
        DispatchQueue.global(qos: .utility).async {
            let cg = image.cgImage
            let pngSize = image.pngData()?.count ?? -1
            let jpegSize = image.jpegData(compressionQuality: 0.9)?.count ?? -1
            logger.info("[\(action)] UIImage size=\(Int(image.size.width))x\(Int(image.size.height)) scale=\(Int(image.scale)) orientation=\(image.imageOrientation.rawValue)")
            if let cg {
                logger.info("[\(action)] CGImage \(cg.width)x\(cg.height) bpc=\(cg.bitsPerComponent) bpp=\(cg.bitsPerPixel) bpr=\(cg.bytesPerRow) alphaInfo=\(cg.alphaInfo.rawValue) colorSpace=\(cg.colorSpace?.name ?? "nil" as CFString)")
            } else {
                logger.info("[\(action)] CGImage is nil!")
            }
            logger.info("[\(action)] pngData=\(pngSize) bytes, jpegData=\(jpegSize) bytes")
        }
        #endif
    }

    // MARK: - Save & Share

    /// [T-sharesheet-main-hang] Copy the turn image to the pasteboard. The
    /// bare `UIImage` assignment is cheap, but the explicit `public.png`
    /// re-encode is not — for a large turn it blocked the main thread. Encode
    /// off-main and set the PNG data back on the main thread. `logImageDiagnostics`
    /// is now off-main/DEBUG-only too, so nothing here stalls the tap.
    private func copyImage(_ image: UIImage) {
        logImageDiagnostics("Copy")
        // The bare UIImage assignment already puts the image on the pasteboard
        // cheaply, so confirm success immediately. The explicit public.png
        // re-encode (expensive for a tall turn) then runs off-main and augments
        // the pasteboard entry when ready.
        UIPasteboard.general.image = image
        flashToast(AppLocalized("Copied"))
        DispatchQueue.global(qos: .userInitiated).async {
            guard let pngData = image.pngData() else { return }
            DispatchQueue.main.async {
                UIPasteboard.general.setData(pngData, forPasteboardType: "public.png")
                logger.info("[Copy] set PNG data \(pngData.count) bytes")
            }
        }
    }

    private func saveImageToPhotos(_ image: UIImage) {
        ImageSaver.shared.save(image) { error in
            if let error {
                flashToast(AppLocalized("Save failed: \(error.localizedDescription)"))
            } else {
                flashToast(AppLocalized("Saved to Photos"))
            }
        }
    }

    private func shareImage(_ image: UIImage) {
        logImageDiagnostics("Share")
        // [T-sharesheet-main-hang] PNG-encoding a tall chat-turn image and
        // writing it to disk used to run inline on the main thread right before
        // presenting the share sheet — the dominant contributor to the
        // [HangDetector] MAIN HANG (>1s, growing to 1.5s+) with ShareSheet
        // frames on the stack when the user shared a large streamed reply. Do
        // the sweep + encode + write on a background queue, then hop back to
        // main only to present the sheet. `flashToast` gives immediate feedback
        // so the tap doesn't feel dead while encoding runs.
        flashToast(AppLocalized("Preparing…"))
        DispatchQueue.global(qos: .userInitiated).async {
            // [T-mac-share-save-race] Sweep old share screenshots instead of
            // deleting in completionWithItemsHandler. On Mac (Designed for iPad)
            // the completion fires when the share popover closes — BEFORE the
            // out-of-process "Save" folder panel / "Copy" file-promise consumer
            // reads the file — so deleting there raced the consumer and the
            // "saved" file never appeared. Filenames are UUID-unique; anything
            // older than 24h is safe to reap here.
            let fm = FileManager.default
            if let entries = try? fm.contentsOfDirectory(
                at: fm.temporaryDirectory,
                includingPropertiesForKeys: [.contentModificationDateKey]
            ) {
                let cutoff = Date().addingTimeInterval(-24 * 3600)
                for old in entries where old.lastPathComponent.hasPrefix("screenshot_") {
                    let mod = (try? old.resourceValues(forKeys: [.contentModificationDateKey]))?
                        .contentModificationDate ?? .distantPast
                    if mod < cutoff { try? fm.removeItem(at: old) }
                }
            }

            let url = fm.temporaryDirectory
                .appendingPathComponent("screenshot_\(UUID().uuidString).png")
            guard let data = image.pngData() else { return }
            do { try data.write(to: url) } catch { return }

            // [T-share-sheet-uti] Route through sanitizedShareURL for parity
            // with the other share entry points. `.png` is safe and the
            // helper will return nil here, leaving the original URL intact.
            let safeURL = MinisShareSheet.sanitizedShareURL(url) ?? url
            DispatchQueue.main.async {
                let avc = UIActivityViewController(activityItems: [safeURL], applicationActivities: nil)
                guard let scene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first(where: { $0.activationState == .foregroundActive }),
                      let root = scene.windows.first?.rootViewController else { return }
                var presenter = root
                while let p = presenter.presentedViewController { presenter = p }
                avc.popoverPresentationController?.sourceView = presenter.view
                avc.popoverPresentationController?.sourceRect = CGRect(x: presenter.view.bounds.midX, y: 60, width: 0, height: 0)
                presenter.present(avc, animated: true)
            }
        }
    }

    private func flashToast(_ message: String) {
        withAnimation(.easeOut(duration: 0.2)) { toast = message }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
            withAnimation(.easeIn(duration: 0.25)) { toast = nil }
        }
    }
}

// MARK: - ImageSaver

final class ImageSaver: NSObject {
    static let shared = ImageSaver()
    private var queue: [(UIImage, (Error?) -> Void)] = []
    private var inFlight = false

    func save(_ image: UIImage, completion: @escaping (Error?) -> Void) {
        DispatchQueue.main.async {
            self.queue.append((image, completion))
            self.pumpIfNeeded()
        }
    }

    private func pumpIfNeeded() {
        guard !inFlight, !queue.isEmpty else { return }
        inFlight = true
        let (image, _) = queue[0]
        UIImageWriteToSavedPhotosAlbum(image, self, #selector(didFinishSaving(_:error:contextInfo:)), nil)
    }

    @objc private func didFinishSaving(_ image: UIImage, error: Error?, contextInfo: UnsafeRawPointer) {
        DispatchQueue.main.async {
            if !self.queue.isEmpty {
                let (_, cb) = self.queue.removeFirst()
                cb(error)
            }
            self.inFlight = false
            self.pumpIfNeeded()
        }
    }
}
