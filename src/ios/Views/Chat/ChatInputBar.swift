import AVKit
import Combine
import ImageIO
import PhotosUI
import QuickLook
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private let pasteLog = AppLogger(category: "PastableTV")
/// [T-ios-context-usage-hint] Diagnostic trail for the usage line: every
/// hand-off point logs, so a missing line can be placed on a timeline.
private let hintLog = AppLogger(category: "ContextUsageHint")

private let minisLogger = AppLogger(category: "MinisURL")
struct SwipeToSendHint: View {
    let progress: CGFloat
    let armFraction: CGFloat
    let location: CGPoint
    /// When true, the capsule advertises "Release to queue" instead of
    /// "Release to send" — used while the agent is mid-stream and the
    /// gesture will be routed through `performEnqueue()`.
    var isEnqueue: Bool = false

    var body: some View {
        if progress > 0 {
            // Capsule full opacity at `armFraction` (default 0.8). Linear
            // from `armFraction - 0.4` → `armFraction`.
            let capsuleStart = max(0, armFraction - 0.4)
            let capsuleSpan = max(0.0001, armFraction - capsuleStart)
            let capsuleAlpha = max(0, min(1, (progress - capsuleStart) / capsuleSpan))
            // `ChatColors.sendButton == UIColor.label` (auto-inverts:
            // black in light, white in dark). For this floating hint we
            // need a clearly-contrasting *glyph on top of the circle* and
            // *text on top of the capsule* — both must use the inverse
            // (`UIColor.systemBackground`), otherwise dark mode renders
            // white-on-white and the indicator vanishes (bug 2026-05-18).
            let chipBg = ChatColors.sendButton
            let chipFg = Color(UIColor.systemBackground)
            HStack(spacing: 8) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 34))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(chipFg, chipBg)
                    .shadow(color: Color.black.opacity(0.18), radius: 6, y: 2)
                Text(isEnqueue
                     ? AppLocalized("Release to queue")
                     : AppLocalized("Release to send"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(chipFg)
                    .padding(.horizontal, 14)
                    .frame(height: 34)
                    .background(Capsule().fill(chipBg))
                    .shadow(color: Color.black.opacity(0.15), radius: 5, y: 2)
                    .opacity(Double(capsuleAlpha))
            }
            .opacity(Double(min(1, progress * 1.4)))
            .scaleEffect(0.9 + 0.18 * progress, anchor: .leading)
            // Anchor the HStack's top-leading corner so the arrow's *center*
            // lands at (finger.x, finger.y - 60). Arrow glyph ~34pt, half
            // = 17pt. ~60pt above the fingertip ≈ one thumb-tip away.
            .offset(x: location.x - 17, y: location.y - 60 - 17)
            .allowsHitTesting(false)
            .animation(.interactiveSpring(response: 0.18,
                                           dampingFraction: 0.85),
                       value: progress >= armFraction)
        }
    }
}

// MARK: - Flow Layout

/// A custom Layout that arranges subviews in a wrapping horizontal flow.
private struct FlowLayout: Layout {
    var hSpacing: CGFloat = 8
    var vSpacing: CGFloat = 8
    var alignment: HorizontalAlignment = .leading

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = arrange(proposal: proposal, subviews: subviews)
        // Use the full proposed width so the layout fills its container.
        return CGSize(width: proposal.width ?? result.size.width, height: result.size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(proposal: proposal, subviews: subviews)
        let containerWidth = bounds.width
        for (index, position) in result.positions.enumerated() {
            let xOffset: CGFloat
            if alignment == .trailing {
                let rowWidth = result.rowWidths[result.rowIndices[index]]
                xOffset = containerWidth - rowWidth + position.x
            } else {
                xOffset = position.x
            }
            subviews[index].place(
                at: CGPoint(x: bounds.minX + xOffset, y: bounds.minY + position.y),
                proposal: ProposedViewSize(result.sizes[index])
            )
        }
    }

    private struct ArrangeResult {
        var positions: [CGPoint]
        var sizes: [CGSize]
        var size: CGSize
        var rowWidths: [CGFloat]
        var rowIndices: [Int]
    }

    private func arrange(proposal: ProposedViewSize, subviews: Subviews) -> ArrangeResult {
        let maxWidth = proposal.width ?? .infinity
        var positions: [CGPoint] = []
        var sizes: [CGSize] = []
        var rowIndices: [Int] = []
        var rowWidths: [CGFloat] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0
        var currentRowStart = 0
        var currentRow = 0

        for (i, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                // Finish current row
                rowWidths.append(x - hSpacing)
                currentRow += 1
                x = 0
                y += rowHeight + vSpacing
                rowHeight = 0
                currentRowStart = i
            }
            positions.append(CGPoint(x: x, y: y))
            sizes.append(size)
            rowIndices.append(currentRow)
            rowHeight = max(rowHeight, size.height)
            x += size.width + hSpacing
            totalWidth = max(totalWidth, x - hSpacing)
        }
        // Last row
        if !subviews.isEmpty {
            rowWidths.append(x - hSpacing)
        }

        return ArrangeResult(
            positions: positions,
            sizes: sizes,
            size: CGSize(width: totalWidth, height: y + rowHeight),
            rowWidths: rowWidths,
            rowIndices: rowIndices
        )
    }
}

// MARK: - Input Attachment Grid

struct AttachmentGridHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

struct TranscriptHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

struct InputAttachmentGridView: View {
    let attachments: [InputAttachment]
    var loadingVideoCount: Int = 0
    let onRemove: (InputAttachment) -> Void
    var onMove: ((_ fromID: UUID, _ toID: UUID) -> Void)?

    @State private var draggingID: UUID?

    var body: some View {
        FlowLayout(hSpacing: 8, vSpacing: 8) {
            ForEach(attachments) { attachment in
                AttachmentChip(attachment: attachment) {
                    onRemove(attachment)
                }
                .opacity(draggingID == attachment.id ? 0.4 : 1)
                .onDrag {
                    draggingID = attachment.id
                    return NSItemProvider(object: attachment.id.uuidString as NSString)
                }
                .onDrop(of: [.text], delegate: AttachmentDropDelegate(
                    targetID: attachment.id,
                    draggingID: $draggingID,
                    onMove: onMove
                ))
            }
            ForEach(0..<loadingVideoCount, id: \.self) { _ in
                VideoLoadingChip()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)  // room for × button overhang (offset y: -4)
    }
}

// MARK: - Attachment Drop Delegate

private struct AttachmentDropDelegate: DropDelegate {
    let targetID: UUID
    @Binding var draggingID: UUID?
    var onMove: ((_ fromID: UUID, _ toID: UUID) -> Void)?

    func performDrop(info: DropInfo) -> Bool {
        draggingID = nil
        return true
    }

    func dropEntered(info: DropInfo) {
        guard let fromID = draggingID, fromID != targetID else { return }
        onMove?(fromID, targetID)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }
}

// MARK: - Video Loading Placeholder

private struct VideoLoadingChip: View {
    var body: some View {
        VStack(spacing: 4) {
            ProgressView()
                .controlSize(.small)

            Text("Loading…")
                .font(.caption2)
                .foregroundStyle(ChatColors.secondaryText)
        }
        .frame(width: 64, height: 64)
        .background(ChatColors.secondaryBg)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 0.5))
    }
}

// MARK: - Attachment Chip

private struct AttachmentChip: View {
    let attachment: InputAttachment
    let onRemove: () -> Void
    @State private var showPreview = false
    @State private var cachedImage: UIImage? = nil

    var body: some View {
        Group {
            switch attachment.loadState {
            case .loading:
                loadingChip
            case .failed:
                failedChip
            case .ready:
                readyChip
            }
        }
    }

    /// A normal, loaded attachment (image / video thumbnail or file chip).
    @ViewBuilder
    private var readyChip: some View {
        Group {
            if attachment.kind == .image {
                thumbnailChip(image: cachedImage)
            } else if attachment.kind == .video {
                thumbnailChip(image: cachedImage, isVideo: true)
            } else {
                fileChip
            }
        }
        .onAppear { loadThumbnailIfNeeded() }
        .onTapGesture { showPreview = true }
        .sheet(isPresented: $showPreview) {
            NavigationStack {
                AttachmentPreviewView(url: attachment.cacheURL)
                    .navigationTitle(attachment.fileName)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") { showPreview = false }
                        }
                    }
            }
        }
    }

    /// [T-ios-photo-pick-placeholder] Loading placeholder shown while the picked
    /// photo's bytes load concurrently. No remove button — it resolves shortly.
    private var loadingChip: some View {
        ProgressView()
            .controlSize(.small)
            .frame(width: 64, height: 64)
            .background(ChatColors.secondaryBg)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 0.5))
    }

    /// [T-ios-photo-pick-placeholder] Error chip for a photo that failed to load.
    /// Tappable × removes just this one; successfully-loaded siblings are kept.
    private var failedChip: some View {
        ZStack(alignment: .topTrailing) {
            VStack(spacing: 4) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.orange)
                Text("Failed")
                    .font(.caption2)
                    .foregroundStyle(ChatColors.secondaryText)
            }
            .frame(width: 64, height: 64)
            .background(ChatColors.secondaryBg)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.orange.opacity(0.5), lineWidth: 0.5))

            Button {
                onRemove()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.5), radius: 2)
            }
            .offset(x: 4, y: -4)
        }
        .fixedSize()
    }

    /// Thumbnail chip for images and videos.
    @ViewBuilder
    private func thumbnailChip(image: UIImage?, isVideo: Bool = false) -> some View {
        if let uiImage = image {
            ZStack(alignment: .topTrailing) {
                ZStack(alignment: .bottomLeading) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 64, height: 64)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 0.5))
                        .shadow(color: .black.opacity(0.08), radius: 2, x: 0, y: 1)

                    if isVideo {
                        Image(systemName: "play.fill")
                            .font(.system(size: 10))
                            .foregroundStyle(.white)
                            .padding(4)
                            .background(.black.opacity(0.5))
                            .clipShape(Circle())
                            .padding(4)
                    }
                }

                Button {
                    onRemove()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.5), radius: 2)
                }
                .offset(x: 4, y: -4)
            }
            .fixedSize()
        } else {
            fileChip
        }
    }

    private static func loadImage(from url: URL) -> UIImage? {
        downsampleImage(fileURL: url, maxPixelSize: 256)
    }

    /// URL-based downsample: lets ImageIO stream the source from disk, so
    /// thumbnailing a very large image file (Files app allows 100MB+ picks)
    /// never loads the full bytes into memory the way `Data(contentsOf:)` +
    /// the data-based `downsampleImage` would.
    private static func downsampleImage(fileURL: URL, maxPixelSize: CGFloat) -> UIImage? {
        let sourceOptions: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, sourceOptions as CFDictionary) else {
            return nil
        }
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
            // Fallback for formats ImageIO can't thumbnail directly.
            return UIImage(contentsOfFile: fileURL.path)
        }
        return UIImage(cgImage: cgImage)
    }

    private static func generateVideoThumbnail(from url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 256, height: 256)
        if let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) {
            return UIImage(cgImage: cgImage)
        }
        return nil
    }

    private func loadThumbnailIfNeeded() {
        let key = attachment.cacheURL.path
        if let cached = MinisMediaCache.shared.thumbnail(for: key) {
            cachedImage = cached
            return
        }
        Task.detached(priority: .userInitiated) {
            let image: UIImage?
            if attachment.kind == .video {
                image = Self.generateVideoThumbnail(from: attachment.cacheURL)
            } else {
                image = Self.downsampleImage(fileURL: attachment.cacheURL, maxPixelSize: 256)
            }
            if let image {
                MinisMediaCache.shared.setThumbnail(image, for: key)
                await MainActor.run { cachedImage = image }
            }
        }
    }

    /// Non-image attachment: icon on top, filename on bottom, same size as image thumbnails.
    private var fileChip: some View {
        ZStack(alignment: .topTrailing) {
            VStack(spacing: 2) {
                Image(systemName: fileIconName)
                    .font(.system(size: 20))
                    .foregroundStyle(ChatColors.secondaryText)
                    .frame(maxHeight: .infinity, alignment: .bottom)

                Text(attachment.fileName)
                    .font(.system(size: 9))
                    .foregroundStyle(ChatColors.primaryText)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .truncationMode(.middle)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
            .frame(width: 64, height: 64)
            .background(ChatColors.secondaryBg)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 0.5))

            Button {
                onRemove()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.4), radius: 2)
            }
            .offset(x: 4, y: -4)
        }
        .fixedSize()
    }

    private var fileIconName: String {
        let ext = attachment.cacheURL.pathExtension.lowercased()
        if ["pdf", "doc", "docx", "pages"].contains(ext) { return "doc.richtext" }
        if ["txt", "log", "csv"].contains(ext) { return "doc.text" }
        if ["md", "markdown"].contains(ext) { return "doc.text" }
        if ["mp4", "mov", "avi", "mkv"].contains(ext) { return "film" }
        if ["mp3", "wav", "m4a", "aac"].contains(ext) { return "waveform" }
        if ["zip", "tar", "gz", "7z"].contains(ext) { return "doc.zipper" }
        return "doc.fill"
    }
}

// MARK: - Pasted-text chips [T-paste-placeholder]

/// One row per stashed long paste: "📋 #N · chars · preview", with a remove
/// button that also strips the `[Pasted#N]` literal from the draft (the vm's
/// removePastedText does both). Tapping a chip opens a READ-ONLY full-text
/// preview — the stash is immutable by design (editing it would silently
/// change what an already-typed placeholder expands to), so the sheet is a
/// selectable Text, never an editor.
struct PastedTextChipRow: View {
    let pastedTexts: [PastedText]
    let onRemove: (Int) -> Void

    /// The entry whose full text is being previewed (sheet presentation).
    @State private var previewEntry: PastedText?

    var body: some View {
        // [T-paste-chip-square] Same 64x64 card as AttachmentChip.fileChip, in
        // the same horizontal scroller as the attachment grid, so a paste and a
        // file attachment read as the same kind of object in the composer. The
        // old full-width capsule made a paste look like a stray line of text.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(pastedTexts) { entry in
                    ZStack(alignment: .topTrailing) {
                        VStack(spacing: 2) {
                            Image(systemName: "doc.text")
                                .font(.system(size: 20))
                                .foregroundStyle(ChatColors.secondaryText)
                                .frame(maxHeight: .infinity, alignment: .bottom)

                            Text("#\(entry.id)")
                                .font(.system(size: 10, weight: .semibold).monospacedDigit())
                                .foregroundStyle(ChatColors.primaryText)
                            Text(String(format: AppLocalized("%d chars"), entry.charCount))
                                .font(.system(size: 8))
                                .foregroundStyle(ChatColors.secondaryText)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                                .frame(maxHeight: .infinity, alignment: .top)
                        }
                        .padding(.horizontal, 4)
                        .padding(.vertical, 6)
                        .frame(width: 64, height: 64)
                        .background(ChatColors.secondaryBg)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 0.5))
                        .contentShape(RoundedRectangle(cornerRadius: 8))
                        // The ✕ Button keeps priority for its own hit area; the
                        // rest of the card opens the read-only preview.
                        .onTapGesture { previewEntry = entry }

                        Button {
                            onRemove(entry.id)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 16))
                                .foregroundStyle(.white)
                                .shadow(color: .black.opacity(0.4), radius: 2)
                        }
                        .buttonStyle(.plain)
                        .offset(x: 4, y: -4)
                    }
                    .fixedSize()
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            // Room for the ✕ that overhangs the top-right corner.
            .padding(.trailing, 4)
        }
        .sheet(item: $previewEntry) { entry in
            NavigationStack {
                ScrollView {
                    // Read-only by construction: selectable text (copyable),
                    // deliberately NOT a TextEditor.
                    //
                    // [T-ios-paste-preview-watchdog] Rendered in chunks rather
                    // than as one `Text(entry.text)`. A ScrollView does not
                    // virtualize, so a single Text forced the main thread to
                    // lay out the ENTIRE paste through CoreText in one pass,
                    // and that cost is super-linear: 80K chars already measured
                    // 9.4s, past the 5s watchdog, while a real 1.07M-char paste
                    // never finished. That was the FRONTBOARD SIGKILL users hit
                    // when tapping a "Pasted #N" chip.
                    LazyLongTextView(text: entry.text)
                        .padding()
                }
                .navigationTitle("Pasted#\(entry.id) · " + String(format: AppLocalized("%d chars"), entry.charCount))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(AppLocalized("Done")) { previewEntry = nil }
                    }
                }
            }
        }
    }
}

// MARK: - Attachment Preview (QuickLook)

private struct AttachmentPreviewView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

// MARK: - Video File Transferable (for PhotosPicker video export)

struct VideoFileTransferable: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { video in
            SentTransferredFile(video.url)
        } importing: { received in
            // Copy to a temp location so the file outlives the picker callback
            let tmp = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString.prefix(8) + "_" + received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: tmp)
            return Self(url: tmp)
        }
    }
}

// MARK: - Camera Picker (UIImagePickerController wrapper)

struct CameraPicker: UIViewControllerRepresentable {
    let onCapture: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let cameraAvailable = UIImagePickerController.isSourceTypeAvailable(.camera)
        let authStatus = AVCaptureDevice.authorizationStatus(for: .video)
        minisLogger.info("[QuickAction] CameraPicker.makeUIViewController cameraAvailable=\(cameraAvailable) authStatus=\(authStatus.rawValue)")
        let picker = UIImagePickerController()
        if cameraAvailable {
            picker.sourceType = .camera
        } else {
            minisLogger.warning("[QuickAction] CameraPicker — .camera source unavailable, falling back to .photoLibrary")
            picker.sourceType = .photoLibrary
        }
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                parent.onCapture(image)
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Code Block Copy Button

private struct CodeBlockCopyButton: View {
    let content: String
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = content
            copied = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                copied = false
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(copied ? .green : .white.opacity(0.5))
                .animation(.easeInOut(duration: 0.2), value: copied)
        }
        .buttonStyle(.plain)
    }
}

/// [T-ios-user-attach-estimate-mismatch] Single source of truth for the user
/// bubble's attachment-tile geometry.
///
/// These used to live only inside `UserAttachmentList` while
/// `measureUserBubbleHeight` / `estimateItemHeight` carried their own
/// hard-coded `70`, so the height estimate for every image-attachment bubble
/// was ~6pt per row too tall and the cell visibly shrank when the real measure
/// landed. Both sides now read the same numbers; changing a tile's size here
/// updates the estimator by construction.
enum UserAttachmentTileMetrics {
    /// Rendered tile edge length (square).
    static let tile: CGFloat = 64
    /// FlowLayout hSpacing / vSpacing between tiles.
    static let gap: CGFloat = 6
    /// Spacing between the tile block and the text bubble in `userRow`'s VStack.
    static let vStackSpacing: CGFloat = 6

    /// How many tiles fit per row for a given collection-view width.
    /// Mirrors FlowLayout packing: N tiles need N*tile + (N-1)*gap, i.e. the
    /// LAST tile carries no trailing gap — which is why this is
    /// `(avail + gap) / (tile + gap)` and not `avail / (tile + gap)`.
    static func tilesPerRow(availableWidth: CGFloat) -> Int {
        max(1, Int((availableWidth + gap) / (tile + gap)))
    }

    /// Total height of the tile block (excluding the text bubble), including
    /// the VStack spacing that separates it from the bubble.
    static func blockHeight(count: Int, availableWidth: CGFloat) -> CGFloat {
        guard count > 0 else { return 0 }
        let perRow = tilesPerRow(availableWidth: availableWidth)
        let rows = ceil(CGFloat(count) / CGFloat(perRow))
        return rows * tile + (rows - 1) * gap + vStackSpacing
    }
}

struct UserAttachmentList: View {
    let attachments: [AttachmentMeta]
    @Environment(\.openURL) private var openURL
    @Environment(\.openImageGallery) private var openImageGallery

    private let tileSize: CGFloat = UserAttachmentTileMetrics.tile

    /// All image attachments in this message, in original order. Used to
    /// populate the paged gallery when the user taps any single image tile.
    private var imageAttachments: [AttachmentMeta] {
        attachments.filter { $0.isImage }
    }

    var body: some View {
        FlowLayout(hSpacing: UserAttachmentTileMetrics.gap, vSpacing: UserAttachmentTileMetrics.gap, alignment: .trailing) {
            ForEach(attachments) { meta in
                if meta.isImage {
                    AsyncImageTile(meta: meta, tileSize: tileSize) {
                        openGallery(startingAt: meta)
                    }
                } else if meta.isVideo {
                    AsyncVideoTile(meta: meta, tileSize: tileSize)
                } else if meta.pastedId != nil {
                    pastedTile(meta)
                } else {
                    fileTile(meta)
                }
            }
        }
    }

    /// Build a `[GalleryItem]` from this message's image attachments and
    /// present it with `tapped` as the starting page.
    private func openGallery(startingAt tapped: AttachmentMeta) {
        let all = imageAttachments
        guard let start = all.firstIndex(where: { $0.minisURL == tapped.minisURL }) else {
            return
        }
        let items: [GalleryItem] = all.map { meta in
            // Fingerprint-keyed id so if the underlying file is rewritten
            // between two opens of the gallery, TabView treats it as a
            // different page and doesn't reuse the cached state/bitmap.
            let fpKey = minisMediaCacheKey(for: meta.minisURL)
            return GalleryItem(
                id: fpKey,
                title: meta.fileName,
                load: {
                    // Route through the shared NSCache using the same
                    // fingerprint key so repeated paging doesn't re-decode
                    // an unchanged file.
                    let cacheKey = "gallery:\(fpKey)"
                    if let cached = NativeMediaImageCache.shared.image(for: cacheKey) {
                        return cached
                    }
                    guard let url = URL(string: meta.minisURL),
                          let fileURL = resolveMinisFileURLCached(url: url),
                          let data = try? Data(contentsOf: fileURL),
                          let img = downsampleImageData(data, maxPixelSize: 2048)
                    else { return nil }
                    NativeMediaImageCache.shared.set(img, for: cacheKey)
                    return img
                }
            )
        }
        openImageGallery(GalleryPresentation(items: items, startIndex: start))
    }

    /// [T-paste-live-bubble-card] Pasted-text reference card: same visual
    /// language as the composer's PastedTextChipRow ("#N · n chars"), NOT the
    /// generic file tile — a paste is a content reference the user should be
    /// able to match to the `[Pasted#N]` marker in the bubble text, not a
    /// file named by its storage UUID. Tap opens the stored text preview.
    private func pastedTile(_ meta: AttachmentMeta) -> some View {
        Button {
            if let url = URL(string: meta.minisURL) { openURL(url) }
        } label: {
            VStack(spacing: 2) {
                Image(systemName: "doc.text")
                    .font(.system(size: 20))
                    .foregroundStyle(ChatColors.secondaryText)
                    .frame(maxHeight: .infinity, alignment: .bottom)

                Text("#\(meta.pastedId ?? 0)")
                    .font(.system(size: 10, weight: .semibold).monospacedDigit())
                    .foregroundStyle(ChatColors.primaryText)
                Text(String(format: AppLocalized("%d chars"), meta.pastedCharCount ?? meta.size))
                    .font(.system(size: 8))
                    .foregroundStyle(ChatColors.secondaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
            .frame(width: tileSize, height: tileSize)
            .background(ChatColors.secondaryBg)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 0.5))
        }
        .buttonStyle(.plain)
    }

    private func fileTile(_ meta: AttachmentMeta) -> some View {
        Button {
            if let url = URL(string: meta.minisURL) { openURL(url) }
        } label: {
            VStack(spacing: 2) {
                Image(systemName: fileIconName(for: meta.fileName))
                    .font(.system(size: 20))
                    .foregroundStyle(ChatColors.secondaryText)
                    .frame(maxHeight: .infinity, alignment: .bottom)

                Text(meta.fileName)
                    .font(.system(size: 9))
                    .foregroundStyle(ChatColors.primaryText)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .truncationMode(.middle)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
            .frame(width: tileSize, height: tileSize)
            .background(ChatColors.secondaryBg)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        // WebApp entry point — only for .html / .htm. Long-press → context
        // menu → "Add to Home Screen". The sheet resolves the minis://
        // URL to a host URL via resolveMinisFileURLCached and hands off
        // to WebAppAddToHomeSheet for classification, icon extraction, and
        // persistence.
        .modifier(WebAppAddToHomeMenuModifier(meta: meta))
    }

    private func fileIconName(for fileName: String) -> String {
        let ext = (fileName as NSString).pathExtension.lowercased()
        if ["pdf", "doc", "docx", "pages"].contains(ext) { return "doc.richtext" }
        if ["txt", "log", "csv"].contains(ext) { return "doc.text" }
        if ["md", "markdown"].contains(ext) { return "doc.text" }
        if ["mp4", "mov", "avi", "mkv"].contains(ext) { return "film" }
        if ["mp3", "wav", "m4a", "aac"].contains(ext) { return "waveform" }
        if ["zip", "tar", "gz", "7z"].contains(ext) { return "doc.zipper" }
        return "doc.fill"
    }
}

/// Adds the WebApp "Add to Home Screen" long-press menu to a chat-rendered
/// attachment file tile. Inert for non-HTML attachments. Resolves the
/// minis:// URL to a host URL on tap and presents `WebAppAddToHomeSheet`.
private struct WebAppAddToHomeMenuModifier: ViewModifier {
    let meta: AttachmentMeta
    @State private var showAddSheet = false
    @State private var resolvedHostURL: URL?

    private var isHTML: Bool {
        let ext = (meta.fileName as NSString).pathExtension.lowercased()
        return ext == "html" || ext == "htm"
    }

    func body(content: Content) -> some View {
        if isHTML {
            content
                .contextMenu {
                    Button {
                        guard let url = URL(string: meta.minisURL),
                              let host = resolveMinisFileURLCached(url: url) else {
                            return
                        }
                        resolvedHostURL = host
                        showAddSheet = true
                    } label: {
                        Label("Add to Home Screen", systemImage: "rectangle.stack.badge.plus")
                    }
                }
                .sheet(isPresented: $showAddSheet) {
                    if let host = resolvedHostURL {
                        WebAppAddToHomeSheet(htmlURL: host,
                                          sourceSessionId: AIChatViewModel.activeSessionId)
                    }
                }
        } else {
            content
        }
    }
}

/// Async image tile — loads and decodes off the main thread, caches via NativeMediaImageCache.
struct QueuedAttachmentPreview: View {
    let attachments: [InputAttachment]

    private let tileSize: CGFloat = UserAttachmentTileMetrics.tile

    var body: some View {
        FlowLayout(hSpacing: UserAttachmentTileMetrics.gap, vSpacing: UserAttachmentTileMetrics.gap, alignment: .trailing) {
            ForEach(attachments) { attachment in
                switch attachment.kind {
                case .image:
                    AsyncCacheURLImageTile(cacheURL: attachment.cacheURL, tileSize: tileSize)
                        .opacity(0.7)
                case .video:
                    placeholderTile(icon: "film", fileName: attachment.fileName)
                case .document:
                    placeholderTile(icon: "doc.fill", fileName: attachment.fileName)
                }
            }
        }
    }

    private func placeholderTile(icon: String, fileName: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(.secondary)
            Text(fileName)
                .font(.system(size: 8))
                .foregroundStyle(.tertiary)
                .lineLimit(1)
        }
        .frame(width: tileSize, height: tileSize)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color(.systemGray5)))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .opacity(0.7)
    }
}

/// Async image tile for local cache URLs (InputAttachment).
// MARK: - PastableTextView (paste image/file support)


class PastableUITextView: UITextView, UIDropInteractionDelegate {
    var onPasteImage: ((UIImage) -> Void)?
    var onPasteFile: ((URL) -> Void)?
    /// [T-paste-placeholder] Long pasted text → the vm stashes it and returns
    /// the `[pasted#N]` literal to insert. nil (not wired) falls through to a
    /// plain text paste — never to the removed file conversion.
    var onPasteLongText: ((String) -> String?)?
    var onCaretChange: ((Int) -> Void)?
    var onReturnKey: (() -> Void)?
    /// Returns true if the arrow key was consumed (slash menu active).
    var onArrowUp: (() -> Bool)?
    /// Returns true if the arrow key was consumed (slash menu active).
    var onArrowDown: (() -> Bool)?
    /// Returns true if Tab was consumed (slash menu autocomplete).
    var onTab: (() -> Bool)?
    /// [T-ipad-composer-resize] The composer's natural growth cap. Single
    /// source of truth so the SwiftUI side and the text view can't drift.
    static let defaultMaxHeight: CGFloat = 120
    /// The height at which typing stops growing the composer and starts
    /// scrolling inside it. Raised while the user drags the composer taller.
    var maxHeight: CGFloat = PastableUITextView.defaultMaxHeight

    /// [T-ipad-composer-resize] A FIXED height the user dragged the composer to.
    ///
    /// Distinct from `maxHeight`, which is only a cap: with a cap, short text
    /// still reports its own (short) intrinsic height, so the box would collapse
    /// back around one line and the drag would appear to snap back. When pinned,
    /// the intrinsic height IS this value regardless of content.
    var pinnedHeight: CGFloat?

    // [T-voice-text-switch-autofocus-race] Gate against UIKit promoting this
    // freshly-mounted text view to first responder unsolicited. When the user
    // switches voice→text, the voice panel's transcript editor (or the raised
    // keyboard) resigns during teardown, and UIKit hands first-responder to the
    // next text input in the chain — this newly-created composer — WITHOUT any
    // user touch and WITHOUT our programmatic focus-sync asking for it. That
    // spontaneous focus raises the keyboard, floats the composer on a phantom
    // avoidance inset (bottom gap), and only heals ~1s later when the lagging
    // updateUIView focus-sync resigns it. Blocking the promotion at the source
    // is the fix: focus is allowed only when the user actually taps the view
    // (touchesBegan sets the flag before UIKit's own tap-to-focus calls
    // becomeFirstResponder) or when the SwiftUI focus-sync explicitly requests
    // it. An unsolicited chain hand-off satisfies neither and is refused, so
    // the keyboard never rises and no bottom gap ever appears.
    var allowsFirstResponder = false

    override var canBecomeFirstResponder: Bool {
        allowsFirstResponder && super.canBecomeFirstResponder
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        // A direct touch is an unambiguous focus intent — open the gate before
        // UIKit's tap-to-focus runs, then let super drive the tap handling.
        allowsFirstResponder = true
        super.touchesBegan(touches, with: event)
    }

    // [T-ios-composer-tap-focus-scroll-delay GH#143] Backstop for a tap whose
    // focus attempt was already refused before the gate opened.
    //
    // The hitTest override below should make that unreachable, but a refusal is
    // silent and the user's only recourse is to tap again — so if the touch ends
    // as a tap (no scrolling happened, we are still not first responder, and the
    // gate is now open), claim focus explicitly rather than leaving the tap to do
    // nothing. `isDragging`/`isDecelerating` distinguish a tap from a scroll
    // gesture, so this never steals focus from a flick that merely started on the
    // text.
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesEnded(touches, with: event)
        guard allowsFirstResponder, !isFirstResponder, !isDragging, !isDecelerating,
              window != nil, isEditable else { return }
        // Place the caret where the user actually tapped. Without this the
        // recovered focus would restore the previous selection, which for a long
        // pre-existing draft usually means the caret lands somewhere the user did
        // not point at — the issue explicitly expects "光标应出现在用户点击的位置".
        if let touch = touches.first, let pos = closestPosition(to: touch.location(in: self)) {
            selectedTextRange = textRange(from: pos, to: pos)
        }
        becomeFirstResponder()
    }

    // [T-ios-composer-tap-focus-scroll-delay GH#143] Open the gate at hit-test
    // time, not only in touchesBegan.
    //
    // UITextView IS a UIScrollView, and it flips `isScrollEnabled = true` as soon
    // as the text exceeds maxHeight — i.e. exactly the "lots of text" case users
    // reported. A scrollable UIScrollView has `delaysContentTouches = true` by
    // default, so it withholds `touchesBegan` from the content while its pan
    // recognizer decides whether the touch is a scroll. UIKit's own tap-to-focus
    // recognizer is not subject to that delay, so it can reach
    // `canBecomeFirstResponder` while `allowsFirstResponder` is STILL false —
    // the gate refuses focus, and because a refusal is silent the tap simply
    // does nothing. Short text never scrolls, so the delay never engages and the
    // bug never appears; that asymmetry is the reported "more text = more likely".
    //
    // It also explains the workarounds in the report: a long press outlives the
    // delay window (touchesBegan is eventually delivered, opening the gate), and
    // repeated tapping wins whenever one tap happens to be delivered undelayed.
    //
    // hitTest runs during delivery, before any gesture arbitration or delay, so
    // opening the gate here makes a tap on the composer always eligible for
    // focus. This does NOT reopen the hole the gate was built for
    // (T-voice-text-switch-autofocus-race): an unsolicited responder-chain
    // hand-off involves no touch, so it never hit-tests this view and the gate
    // stays shut.
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        if hit === self || (hit.map { $0.isDescendant(of: self) } ?? false) {
            allowsFirstResponder = true
        }
        return hit
    }

    private var dropInteractionInstalled = false
    private lazy var customDropInteraction = UIDropInteraction(delegate: self)

    /// Install a custom drop interaction that intercepts file/image drops
    /// before the default UITextView text-drop behavior converts them to text.
    func installDropInteraction() {
        guard !dropInteractionInstalled else { return }
        dropInteractionInstalled = true
        addInteraction(customDropInteraction)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // UIKit lazily adds the built-in text drop interaction after the view
        // is inserted into the window hierarchy. Remove it here so our custom
        // UIDropInteraction takes precedence.
        for interaction in interactions {
            if interaction is UIDropInteraction && interaction !== customDropInteraction {
                removeInteraction(interaction)
            }
        }
    }

    // MARK: - UIDropInteractionDelegate

    func dropInteraction(_ interaction: UIDropInteraction, canHandle session: any UIDropSession) -> Bool {
        true // Accept everything — we sort in performDrop
    }

    func dropInteraction(_ interaction: UIDropInteraction, sessionDidUpdate session: any UIDropSession) -> UIDropProposal {
        UIDropProposal(operation: .copy)
    }

    func dropInteraction(_ interaction: UIDropInteraction, performDrop session: any UIDropSession) {
        for item in session.items {
            let provider = item.itemProvider
            let types = provider.registeredTypeIdentifiers

            // Determine if this item represents a file (not just inline text).
            // A dragged .txt file will conform to both public.plain-text AND
            // public.file-url (or a concrete file UTI like public.text).
            // Inline text (e.g. selected text from Safari) only has public.plain-text / public.utf8-plain-text.
            let hasFileType = types.contains(where: { id in
                guard let ut = UTType(id) else { return false }
                return ut.conforms(to: .fileURL)
                    || ut.conforms(to: .image)
                    || ut.conforms(to: .movie)
                    || ut.conforms(to: .pdf)
                    || ut.conforms(to: .data) && !ut.conforms(to: .plainText)
            })
            // Also treat it as a file when the suggested name has a file extension
            let hasFileName = provider.suggestedName.map {
                !($0 as NSString).pathExtension.isEmpty
            } ?? false

            let isFile = hasFileType || hasFileName

            if isFile {
                // Load as file via the most specific content type to preserve
                // the original file (not a text conversion).
                let fileTypeID = types.first(where: { id in
                    guard let ut = UTType(id) else { return false }
                    return ut.conforms(to: .fileURL)
                }) ?? types.first(where: { id in
                    guard let ut = UTType(id) else { return false }
                    return ut.conforms(to: .image) || ut.conforms(to: .movie)
                        || ut.conforms(to: .pdf) || ut.conforms(to: .data)
                })

                if let fileTypeID {
                    if UTType(fileTypeID)?.conforms(to: .fileURL) == true {
                        // File URL — load the URL directly
                        provider.loadItem(forTypeIdentifier: fileTypeID) { [weak self] data, _ in
                            guard let data = data as? Data,
                                  let url = URL(dataRepresentation: data, relativeTo: nil) else { return }
                            DispatchQueue.main.async { self?.onPasteFile?(url) }
                        }
                    } else {
                        // Non-URL file content — load via file representation
                        provider.loadFileRepresentation(forTypeIdentifier: fileTypeID) { [weak self] url, _ in
                            guard let url else { return }
                            let tmp = FileManager.default.temporaryDirectory
                                .appendingPathComponent(UUID().uuidString.prefix(8) + "_" + url.lastPathComponent)
                            try? FileManager.default.copyItem(at: url, to: tmp)
                            DispatchQueue.main.async { self?.onPasteFile?(tmp) }
                        }
                    }
                    continue
                }
            }

            // Inline text (selected text dragged from another app) — insert normally
            if provider.canLoadObject(ofClass: String.self) {
                provider.loadObject(ofClass: String.self) { [weak self] object, _ in
                    guard let text = object as? String else { return }
                    DispatchQueue.main.async { self?.insertText(text) }
                }
                continue
            }
        }
    }

    // Return an empty, zero-height accessory view so iPadOS hides the
    // floating keyboard shortcut bar when a hardware keyboard is connected.
    private var _inputAccessoryView: UIView? = {
        let v = UIView(frame: .zero)
        v.translatesAutoresizingMaskIntoConstraints = true
        return v
    }()
    override var inputAccessoryView: UIView? {
        get { _inputAccessoryView }
        set { _inputAccessoryView = newValue }
    }

    override var keyCommands: [UIKeyCommand]? {
        // Return (no modifiers) → send message (hardware keyboard only)
        let send = UIKeyCommand(input: "\r", modifierFlags: [], action: #selector(handleReturnKey))
        // Shift+Return → insert newline
        let newline = UIKeyCommand(input: "\r", modifierFlags: .shift, action: #selector(handleShiftReturnKey))
        // Arrow keys for slash menu navigation
        let up = UIKeyCommand(input: UIKeyCommand.inputUpArrow, modifierFlags: [], action: #selector(handleArrowUp))
        up.wantsPriorityOverSystemBehavior = true
        let down = UIKeyCommand(input: UIKeyCommand.inputDownArrow, modifierFlags: [], action: #selector(handleArrowDown))
        down.wantsPriorityOverSystemBehavior = true
        // Tab for slash menu autocomplete
        let tab = UIKeyCommand(input: "\t", modifierFlags: [], action: #selector(handleTab))
        tab.wantsPriorityOverSystemBehavior = true
        return [send, newline, up, down, tab]
    }

    @objc private func handleReturnKey() {
        onReturnKey?()
    }

    @objc private func handleShiftReturnKey() {
        insertText("\n")
    }

    @objc private func handleArrowUp() {
        if onArrowUp?() != true {
            // Not consumed — fall through to default cursor movement
            let start = beginningOfDocument
            if let newPos = position(from: selectedTextRange?.start ?? start, in: .up, offset: 1) {
                selectedTextRange = textRange(from: newPos, to: newPos)
            }
        }
    }

    @objc private func handleArrowDown() {
        if onArrowDown?() != true {
            // Not consumed — fall through to default cursor movement
            let end = endOfDocument
            if let newPos = position(from: selectedTextRange?.end ?? end, in: .down, offset: 1) {
                selectedTextRange = textRange(from: newPos, to: newPos)
            }
        }
    }

    @objc private func handleTab() {
        if onTab?() != true {
            // Not consumed — insert tab character (default behavior)
            insertText("\t")
        }
    }

    override var intrinsicContentSize: CGSize {
        // Guard against zero-width layout pass (happens on iOS 16 during keyboard animation)
        // to prevent sizeThatFits returning an inflated height that causes the input bar to jump.
        guard bounds.width > 0 else {
            return CGSize(width: UIView.noIntrinsicMetric, height: font?.lineHeight ?? 20)
        }
        let size = sizeThatFits(CGSize(width: bounds.width, height: .greatestFiniteMagnitude))
        isScrollEnabled = size.height > maxHeight
        // [T-ipad-composer-resize] A dragged composer keeps its height even when
        // the text is short; otherwise the box would shrink back around one line.
        if let pinnedHeight {
            return CGSize(width: UIView.noIntrinsicMetric, height: pinnedHeight)
        }
        return CGSize(width: UIView.noIntrinsicMetric, height: min(size.height, maxHeight))
    }

    /// [T-ios-composer-paste-scroll-stale] Whether the CURRENT text overflows
    /// `maxHeight`, measured on demand instead of read back off
    /// `isScrollEnabled`.
    ///
    /// `isScrollEnabled` is only refreshed as a side effect of
    /// `intrinsicContentSize` / `sizeThatFits`, both of which UIKit runs on a
    /// LATER layout pass. When text arrives in one shot (paste, share-sheet,
    /// programmatic set) `updateUIView` assigns it and then reads
    /// `isScrollEnabled` in the same turn — still the pre-paste value. The
    /// incremental typing/ASR path never showed this because
    /// `textViewDidChange` drives the re-measure before the read.
    ///
    /// Callers that need the value NOW (to publish into SwiftUI state) must use
    /// this rather than the flag.
    var overflowsMaxHeight: Bool {
        let width = bounds.width > 0 ? bounds.width : textContainer.size.width
        guard width > 0 else { return false }
        let fit = sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return fit.height > maxHeight
    }

    /// [T-ios-composer-swipe-send-at-bottom] Whether the text is scrolled to
    /// (or past) the end of its content — the point where there is nothing left
    /// to reveal by dragging up, so a further upward drag can be handed to
    /// swipe-to-send instead of being swallowed by a scroll that cannot move.
    ///
    /// `bottomSlack` absorbs sub-pixel content-height rounding AND rubber-band
    /// overscroll: `contentOffset.y` exceeds `maxOffsetY` while the user is
    /// stretching past the end, which must still count as "at bottom" or the
    /// hand-off would flicker off exactly when the user pulls further.
    /// Non-scrollable text is trivially at bottom (there is no scrolling to
    /// finish first), which keeps the short-text case behaving as it always did.
    var isScrolledToBottom: Bool {
        guard isScrollEnabled else { return true }
        let maxOffsetY = contentSize.height - bounds.height
        // Content shorter than the viewport: nothing to scroll through.
        guard maxOffsetY > 0.5 else { return true }
        let bottomSlack: CGFloat = 2
        return contentOffset.y >= maxOffsetY - bottomSlack
    }

    /// [T-ios-composer-paste-truncation] Settle scrollability + content layout
    /// whenever the view's real geometry is known.
    ///
    /// The round-1 fix (0024cbd3) did this inside `updateUIView`, which only
    /// covers text arriving through the SwiftUI binding. An IN-APP paste never
    /// goes that way: `PastableUITextView.paste(_:)` falls through to
    /// `super.paste(_:)`, so UIKit mutates the text directly and the only
    /// follow-up is `textViewDidChange` → `invalidateIntrinsicContentSize()`,
    /// which merely SCHEDULES a re-measure. Nothing enabled scrolling or grew
    /// `contentSize`, so a long pasted reply rendered clipped with no way to
    /// reach the rest.
    ///
    /// Even in `updateUIView` the round-1 sequence was order-dependent: it ran
    /// `ensureLayout` while the view still had its OLD (short) frame, so
    /// `contentSize` was computed against the wrong viewport and stayed stale.
    ///
    /// `layoutSubviews` is the one place where the frame is authoritative, and
    /// UIKit calls it after every text mutation on every path — so doing the
    /// work here fixes all writers at once and is inherently order-safe.
    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0 else { return }
        let shouldScroll = overflowsMaxHeight
        if isScrollEnabled != shouldScroll {
            isScrollEnabled = shouldScroll
            // Toggling this changes how the view derives its own height; ask for
            // a fresh intrinsic measurement rather than waiting for the next
            // unrelated invalidation.
            invalidateIntrinsicContentSize()
        }
        // [T-ipad-composer-resize] Pin short text to the TOP of a taller frame.
        //
        // UITextView IS a UIScrollView, and when its content is shorter than its
        // bounds UIKit vertically CENTERS the text. That never showed before
        // this feature because the frame always hugged the content (fixedSize),
        // so the two heights matched. Once the user drags the composer to a
        // fixed height, a one-line message renders floating in the middle of a
        // tall empty box. Nudging contentOffset back to the top has no effect —
        // the centering is applied on every layout pass — so the fix is to
        // absorb the surplus as BOTTOM inset, which leaves the first line at the
        // top edge where the caret belongs.
        let surplus = bounds.height - ceil(layoutManager.usedRect(for: textContainer).height)
        let desiredBottomInset = shouldScroll ? 0 : max(0, surplus)
        if abs(textContainerInset.bottom - desiredBottomInset) > 0.5 {
            textContainerInset = UIEdgeInsets(
                top: 0, left: 0, bottom: desiredBottomInset, right: 0)
        }
        guard shouldScroll else { return }
        // Enabling scrolling does not itself recompute contentSize — the text
        // needs laying out against the CURRENT viewport. Without this the scroll
        // view reports contentSize == bounds and refuses to scroll at all.
        layoutManager.ensureLayout(for: textContainer)
        let used = layoutManager.usedRect(for: textContainer)
        let needed = ceil(used.height) + textContainerInset.top + textContainerInset.bottom
        if abs(contentSize.height - needed) > 0.5 {
            contentSize = CGSize(width: bounds.width, height: needed)
        }
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)) {
            let pb = UIPasteboard.general
            if pb.hasImages || pb.hasURLs || pb.hasStrings {
                return true
            }
            // Also allow paste when itemProviders contain files or images
            for provider in pb.itemProviders {
                if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
                    || provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    return true
                }
            }
        }
        // Only expose the "Insert Line Break" action while the user has
        // opted into Send-on-Return — otherwise the plain Return key already
        // inserts a newline and the menu item would be redundant.
        if action == #selector(insertLineBreakFromMenu(_:)) {
            return UserDefaults.standard.integer(forKey: "returnKeyBehavior") == 1
        }
        return super.canPerformAction(action, withSender: sender)
    }

    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        if UserDefaults.standard.integer(forKey: "returnKeyBehavior") == 1 {
            let insert = UICommand(
                title: AppLocalized("Insert Line Break"),
                image: UIImage(systemName: "return"),
                action: #selector(insertLineBreakFromMenu(_:))
            )
            let menu = UIMenu(title: "", options: .displayInline, children: [insert])
            builder.insertSibling(menu, afterMenu: .standardEdit)
        }
    }

    @objc func insertLineBreakFromMenu(_ sender: Any?) {
        // If the user has a selection, the standard insertText behavior
        // replaces it with the newline; if the caret is a zero-length range,
        // the newline is inserted at the caret. Both match what the Return
        // key would do in newline mode, which is the mental model we're
        // reproducing here.
        insertText("\n")
    }

    override func paste(_ sender: Any?) {
        let pb = UIPasteboard.general

        // Priority 1: File URLs (images, videos, documents, etc.)
        if pb.hasURLs, let urls = pb.urls {
            let fileURLs = urls.filter { $0.isFileURL }
            if !fileURLs.isEmpty {
                for url in fileURLs {
                    pasteLog.debug("[Paste] pasting file URL \(url.lastPathComponent)")
                    onPasteFile?(url)
                }
                return
            }
        }

        // Priority 2: Item providers with file representations (e.g. files copied from Files.app)
        // Must check before pb.hasImages — copied files often carry a thumbnail image
        // that would incorrectly match the image check.
        let fileProviders = pb.itemProviders.filter {
            $0.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
        }
        if !fileProviders.isEmpty {
            for provider in fileProviders {
                pasteLog.debug("[Paste] loading file from itemProvider")
                provider.loadFileRepresentation(forTypeIdentifier: UTType.fileURL.identifier) { [weak self] url, error in
                    guard let url else { return }
                    let tmp = FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString.prefix(8) + "_" + url.lastPathComponent)
                    try? FileManager.default.copyItem(at: url, to: tmp)
                    DispatchQueue.main.async { self?.onPasteFile?(tmp) }
                }
            }
            return
        }

        // Priority 3: Images (only after ruling out file URLs above)
        if pb.hasImages, let images = pb.images, !images.isEmpty {
            for image in images {
                pasteLog.debug("[Paste] pasting image \(image.size.width)x\(image.size.height)")
                onPasteImage?(image)
            }
            return
        }

        // Priority 4: Item providers with image-only representations
        let imageProviders = pb.itemProviders.filter {
            $0.hasItemConformingToTypeIdentifier(UTType.image.identifier)
                && !$0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)
        }
        if !imageProviders.isEmpty {
            for provider in imageProviders {
                pasteLog.debug("[Paste] loading image from itemProvider")
                provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
                    guard let image = object as? UIImage else { return }
                    DispatchQueue.main.async { self?.onPasteImage?(image) }
                }
            }
            return
        }

        // Priority 5: Long text → session paste buffer + `[pasted#N]` literal
        // at the caret. [T-paste-placeholder] Replaces the old "write a .txt
        // and attach it" behaviour: models treat file attachments through a
        // different (often worse) path than inline text, so the full text now
        // stays inline — stashed in the vm's buffer and expanded back into the
        // message by send(). Threshold lives in PastePlaceholder.isLong,
        // shared with the voice panel's paste menu.
        if let text = pb.string, PastePlaceholder.isLong(text), let handler = onPasteLongText {
            // [T-paste-huge-file-composer-residue] The handler ALWAYS consumes
            // a long text: it returns the `[Pasted#N]` literal to insert, or
            // nil when the text crossed the file-attachment threshold and
            // became a .txt card (stashPastedText's only other nil-free path
            // is the write-failure fallback, which returns a placeholder).
            // nil used to fall through to `super.paste(sender)`, dumping the
            // full raw text into the composer NEXT TO the file card — exactly
            // what routing to a file exists to prevent. Now nil inserts
            // nothing: the card carries the content, the composer stays as
            // the user left it.
            if let placeholder = handler(text) {
                pasteLog.debug("[Paste] long text (\(text.count) chars) → placeholder \(placeholder)")
                // insertText replaces the current selection / inserts at the
                // caret, exactly like typing — the normal editing pipeline
                // (delegate → binding) runs, no special settle needed for a
                // dozen-character literal.
                insertText(placeholder)
            } else {
                pasteLog.debug("[Paste] huge text (\(text.count) chars) → .txt attachment card, composer untouched")
            }
            return
        }

        // Priority 6: Normal text paste
        pasteLog.debug("[Paste] pasting text fallback")
        super.paste(sender)
    }
}

struct PastableTextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var isFocused: Bool
    @Binding var hasSelection: Bool
    @Binding var isScrollable: Bool
    /// [T-ios-composer-swipe-send-at-bottom] True when the composer's text is
    /// scrolled to the end (or is too short to scroll at all). Lets the
    /// swipe-to-send gesture engage only after the user has read to the bottom
    /// of a long value, instead of being disabled outright whenever the text
    /// happens to overflow.
    @Binding var isAtScrollBottom: Bool
    var placeholder: String
    /// [T-ios-composer-placeholder-rotation] Feature hints cycled while the
    /// composer is empty. Empty (the default) keeps the static placeholder,
    /// so the other `PastableTextView` call sites are unaffected.
    var placeholderRotation: [String] = []
    /// When true, the FIRST focus rotates as well. Set for sessions that
    /// already have messages, where the default placeholder has served its
    /// purpose and the hints are more useful than repeating it.
    var placeholderRotatesOnFirstFocus: Bool = false
    /// [T-ios-context-usage-hint] A request to show the context-usage line
    /// in the placeholder for a few seconds. The Coordinator acts on each
    /// `generation` once; nil (the default) leaves every other call site of
    /// this view untouched.
    var contextUsageHint: ContextUsageHint? = nil
    var onPasteImage: (UIImage) -> Void
    var onPasteFile: (URL) -> Void
    /// [T-paste-placeholder] See PastableUITextView.onPasteLongText.
    var onPasteLongText: ((String) -> String?)?
    var onReturnKey: (() -> Void)?
    var onArrowUp: (() -> Bool)?
    var onArrowDown: (() -> Bool)?
    var onTab: (() -> Bool)?
    /// Receives the caret location (UTF-16 offset) whenever the user moves
    /// the caret or edits the text. Used for `@`-mention detection.
    var onCaretChange: ((Int) -> Void)?
    /// [T-text-input-correction-source] Fires when the user replaces a
    /// SELECTED span of already-typed text with new text (before = the
    /// replaced substring, after = the replacement). The composer's
    /// analogue of a voice-transcript edit — fed to the same correction
    /// learner. Not fired for plain insertions (empty selection) or IME
    /// composition.
    var onSelectionReplace: ((_ before: String, _ after: String) -> Void)?
    /// Signal from the view model that the caret should programmatically move
    /// to this offset (used after inserting a mention). Nil = no pending move.
    var desiredCaret: Int?

    /// [T-ipad-composer-resize] Overrides `PastableUITextView.maxHeight` — the
    /// point at which typing stops growing the composer and starts scrolling
    /// inside it. nil keeps the built-in default (120pt).
    ///
    /// Load-bearing for the drag-to-resize feature: enlarging only the SwiftUI
    /// frame would leave the text view still capped at 120, so text would
    /// scroll internally while the extra space rendered as dead padding. The
    /// growth cap has to move with the frame.
    var maxHeightOverride: CGFloat?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> PastableUITextView {
        let tv = PastableUITextView()
        tv.delegate = context.coordinator
        tv.font = UIFont.systemFont(ofSize: FontSettings.shared.scaledChatInput(16.5))
        tv.backgroundColor = .clear
        if let maxHeightOverride { tv.maxHeight = maxHeightOverride }
        tv.isScrollEnabled = false
        tv.textContainerInset = .zero
        tv.textContainer.lineFragmentPadding = 0
        // [T-gh59-input-word-wrap] .byWordWrapping, NOT .byCharWrapping.
        // History: T-share-url-input-height (2026-05-23) set char wrapping
        // to make pasted long URLs (one unspaced token) produce multi-line
        // layout, on the claim that "Latin prose still breaks at spaces
        // when there's room". That claim was wrong — .byCharWrapping is
        // pure character-level breaking, and GH#59 (device screenshots
        // 2026-07-17: "and" split into "a|nd", "wrapping" into
        // "wra|pping") is its direct fallout on ordinary English text.
        // .byWordWrapping is the correct tool for BOTH cases: it breaks
        // at word boundaries for prose, and TextKit's emergency breaking
        // still splits a single token longer than the whole line width
        // (the URL case) at character level rather than overflowing —
        // verified on-device with a 60-char unspaced token. The paragraph
        // style must ride typingAttributes (textContainer.lineBreakMode
        // alone doesn't reach the typesetter), same plumbing as before.
        let urlBreakStyle = NSMutableParagraphStyle()
        urlBreakStyle.lineBreakMode = .byWordWrapping
        tv.textContainer.lineBreakMode = .byWordWrapping
        var typing = tv.typingAttributes
        typing[.paragraphStyle] = urlBreakStyle
        tv.typingAttributes = typing
        // Reflect the user's Return-key preference on the soft keyboard.
        tv.returnKeyType = UserDefaults.standard.integer(forKey: "returnKeyBehavior") == 1 ? .send : .default
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        tv.setContentHuggingPriority(.defaultHigh, for: .vertical)
        tv.onPasteImage = onPasteImage
        tv.onPasteFile = onPasteFile
        tv.onPasteLongText = onPasteLongText
        tv.onReturnKey = onReturnKey
        tv.onArrowUp = onArrowUp
        tv.onArrowDown = onArrowDown
        tv.onTab = onTab
        tv.onCaretChange = onCaretChange
        // Hide the input-assistant bar that iPadOS shows above a physical
        // keyboard — it leaves a visible grey strip at the bottom of the screen.
        tv.inputAssistantItem.leadingBarButtonGroups = []
        tv.inputAssistantItem.trailingBarButtonGroups = []
        pasteLog.debug("[makeUIView] created")

        // Install custom drop interaction to intercept file/image drops on iPad
        tv.installDropInteraction()

        // Placeholder overlay — same font and colour as the primary text, so
        // the hint reads as ghosted input rather than as chrome. The default
        // string ("Message %@ (@ to mention files)") is one parameterized
        // xcstrings entry so translators can adapt word-order per locale
        // (e.g. zh moves the verb and uses fullwidth brackets).
        //
        // [T-ios-composer-placeholder-wrap] It WRAPS. It used to be pinned by
        // leading+top only, with UILabel's default `numberOfLines = 1`: with no
        // trailing constraint the label laid out at its intrinsic width and ran
        // straight off the edge of the text view, so any hint longer than the
        // composer was clipped mid-sentence. That was survivable for the short
        // default, but the rotating feature hints
        // ([T-ios-composer-placeholder-rotation]) are full sentences — e.g.
        // "Long-press anywhere in a reply to select and copy it all" — and are
        // longer still in several locales, so most of them were unreadable.
        //
        // Fixing it needs BOTH halves: `numberOfLines = 0` alone still measures
        // against an unbounded width (one line, clipped), and a trailing
        // constraint alone still truncates at one line.
        let placeholderLabel = UILabel()
        placeholderLabel.text = placeholder
        placeholderLabel.font = UIFont.systemFont(ofSize: FontSettings.shared.scaledChatInput(16.5))
        placeholderLabel.textColor = .placeholderText
        placeholderLabel.tag = 999
        placeholderLabel.numberOfLines = 0
        placeholderLabel.lineBreakMode = .byWordWrapping
        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        tv.addSubview(placeholderLabel)
        // Width is bound to the text view's own width rather than to a trailing
        // anchor: `tv` is a UIScrollView, so its trailing anchor tracks the
        // (unbounded) CONTENT area, not the visible viewport — anchoring there
        // reintroduces the same runaway width this fixes. `widthAnchor` is the
        // frame width, which is what the user actually sees.
        //
        // The text itself is typeset with `textContainerInset = .zero` and
        // `lineFragmentPadding = 0` (set above), so leading/top at zero and a
        // full-width label line up exactly with where the first typed character
        // lands — no separate padding constant to keep in sync.
        NSLayoutConstraint.activate([
            placeholderLabel.leadingAnchor.constraint(equalTo: tv.leadingAnchor),
            placeholderLabel.topAnchor.constraint(equalTo: tv.topAnchor),
            placeholderLabel.widthAnchor.constraint(equalTo: tv.widthAnchor),
        ])
        placeholderLabel.isHidden = !text.isEmpty

        return tv
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView tv: PastableUITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? UIScreen.main.bounds.width
        // [T-share-url-input-height] UITextView.sizeThatFits returns the
        // height typeset against the CURRENT textContainer width, not the
        // requested one. When a large block of text lands via the shared-
        // sheet path (`vm.inputText += sharedURL`), SwiftUI re-runs
        // updateUIView with the new text but UITextView's typesetter is
        // still keyed to the previous (single-line) frame width, so
        // sizeThatFits reports the unwrapped one-line height and the
        // composer doesn't grow. Forcing the textContainer width to the
        // proposed value triggers an immediate re-layout against the real
        // wrap point, so the returned height matches what the user will
        // actually see once the cell renders.
        if abs(tv.textContainer.size.width - width) > 0.5 {
            tv.textContainer.size = CGSize(width: width, height: .greatestFiniteMagnitude)
            tv.layoutManager.ensureLayout(for: tv.textContainer)
        }
        let fitSize = tv.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let lineH = tv.font?.lineHeight ?? 20
        let effective = tv.text.isEmpty ? lineH : fitSize.height
        let maxH = tv.maxHeight
        tv.isScrollEnabled = fitSize.height > maxH
        // [T-ipad-composer-resize] When the user has dragged the composer to an
        // explicit height, that height IS the answer — report it verbatim.
        //
        // A UIViewRepresentable's `sizeThatFits` outranks an outer
        // `.frame(height:)`: SwiftUI asks the representable how big it wants to
        // be and lays it out at that size. So returning the CONTENT height here
        // (one line ≈ 20pt) pulled the composer back down mid-drag — the frame
        // said 400pt, this said 20pt, and this won. Returning the override makes
        // the two agree, which is what stops the snap-back.
        if let maxHeightOverride {
            return CGSize(width: width, height: maxHeightOverride)
        }
        return CGSize(width: width, height: min(effective, maxH))
    }

    func updateUIView(_ tv: PastableUITextView, context: Context) {
        // [T-ios-context-usage-hint] Hand the Coordinator the CURRENT view
        // value. It was only ever assigned in init, so every `parent.*` read
        // on the Coordinator — placeholder, rotation pool, contextUsageHint —
        // saw the values from creation time: the usage line never showed
        // (its request stayed nil forever) and a soul rename never reached
        // the placeholder. The @Binding writes kept working, which is why
        // the freeze went unnoticed. Standard UIViewRepresentable practice.
        context.coordinator.parent = self
        // [T-ipad-composer-resize] Track the growth cap as the user drags. The
        // invalidate is required: `maxHeight` feeds `intrinsicContentSize` and
        // `isScrollEnabled`, neither of which UIKit re-derives on its own, so
        // without this the composer would keep scrolling at the OLD cap inside
        // its newly enlarged frame.
        let resolvedMaxHeight = maxHeightOverride ?? PastableUITextView.defaultMaxHeight
        if abs(tv.maxHeight - resolvedMaxHeight) > 0.5 || tv.pinnedHeight != maxHeightOverride {
            tv.maxHeight = resolvedMaxHeight
            tv.pinnedHeight = maxHeightOverride
            tv.invalidateIntrinsicContentSize()
            // The bottom inset that top-aligns short text is derived from the
            // frame, so it must be recomputed for the new height.
            tv.setNeedsLayout()
        }
        if tv.text != text, tv.markedTextRange == nil || text.isEmpty {
            // [T-ios-composer-residual-text-33549] Clearing the composer
            // post-send is the race-prone path: an in-flight IME
            // composition or a deferred UIKit input callback can write
            // the prior text back into `parent.text` after we set
            // `tv.text = ""`. Three mitigations:
            //   1. Unmark any active IME composition so it can't fire a
            //      delayed completion handler with stale text.
            //   2. Reset `selectedRange` to (0, 0) so the caret-publish
            //      callback that follows doesn't carry the old offset.
            //   3. Arm `skipNextProgrammaticChange` so the
            //      `textViewDidChange` callback that UIKit schedules
            //      from `tv.text = text` doesn't push the (potentially
            //      stale) UITextView buffer back into the SwiftUI
            //      binding.
            let isClearing = text.isEmpty
            if tv.markedTextRange != nil {
                // Force-unmark so the IME composition doesn't write back
                // stale text. After unmarkText() the textView's `text`
                // property reflects the committed-but-cleared state.
                tv.unmarkText()
            }
            tv.text = text
            // [T-share-url-input-height] Plain `tv.text =` reset clears
            // attributes — the word-wrap paragraph style we stamped
            // onto typingAttributes only applies to text typed AFTER this
            // point, so a pasted/shared long URL ends up with the default
            // .byWordWrapping style and refuses to break in the middle of
            // the token. Re-apply the char-wrap paragraph style across
            // the whole storage so the typesetter actually wraps.
            if !text.isEmpty,
               let para = (tv.typingAttributes[.paragraphStyle] as? NSParagraphStyle) {
                let full = NSRange(location: 0, length: (tv.text as NSString).length)
                tv.textStorage.addAttribute(.paragraphStyle, value: para, range: full)
            }
            if isClearing {
                tv.selectedRange = NSRange(location: 0, length: 0)
                if let label = tv.viewWithTag(999) as? UILabel {
                    label.isHidden = false
                }
            }
            tv.invalidateIntrinsicContentSize()
            // [T-ios-composer-paste-scroll-stale] Settle scrollability NOW.
            // invalidateIntrinsicContentSize only SCHEDULES the re-measure that
            // would flip `isScrollEnabled`, so on this one-shot write path the
            // text view stays non-scrollable for the rest of the turn — long
            // pasted text renders clipped with no way to reach the remainder
            // until some later pass happens to re-measure. Assigning it here
            // makes the paste immediately scrollable, and matches what the
            // next intrinsicContentSize would compute anyway.
            let shouldScroll = tv.overflowsMaxHeight
            if tv.isScrollEnabled != shouldScroll {
                tv.isScrollEnabled = shouldScroll
            }
            if shouldScroll {
                // Enabling scrolling does NOT by itself recompute contentSize:
                // the text view needs a layout pass to pick up the new used
                // rect. Pasting a long block into a composer that had not yet
                // scrolled left contentSize pinned at the visible height (120),
                // so the content past the first ~6 lines was unreachable — the
                // "text is statically truncated, not just un-scrollable"
                // symptom. Force the typesetter + layout to catch up in this
                // same turn so the full range is scrollable immediately.
                tv.layoutManager.ensureLayout(for: tv.textContainer)
                tv.layoutIfNeeded()
            }
        }
        tv.onPasteImage = onPasteImage
        tv.onPasteFile = onPasteFile
        tv.onPasteLongText = onPasteLongText
        tv.onReturnKey = onReturnKey
        tv.onArrowUp = onArrowUp
        tv.onArrowDown = onArrowDown
        tv.onTab = onTab

        // Apply a pending caret move (e.g. after mention insert). Clamp to the
        // text length and only move when different to avoid fighting the user.
        if let want = desiredCaret {
            let length = (tv.text as NSString).length
            let clamped = max(0, min(want, length))
            if tv.selectedRange.location != clamped || tv.selectedRange.length != 0 {
                tv.selectedRange = NSRange(location: clamped, length: 0)
            }
        }

        // Keep font in sync with FontSettings
        let inputFont = UIFont.systemFont(ofSize: FontSettings.shared.scaledChatInput(16.5))
        if tv.font != inputFont {
            tv.font = inputFont
            tv.invalidateIntrinsicContentSize()
        }

        // Keep Return-key type in sync with the Appearance Settings toggle.
        // Refreshing requires reloading the keyboard input views when the
        // text view is first responder; otherwise iOS keeps the old key type
        // until focus cycles.
        let desiredReturnKey: UIReturnKeyType = UserDefaults.standard.integer(forKey: "returnKeyBehavior") == 1 ? .send : .default
        if tv.returnKeyType != desiredReturnKey {
            tv.returnKeyType = desiredReturnKey
            if tv.isFirstResponder { tv.reloadInputViews() }
        }

        // Sync placeholder
        if let label = tv.viewWithTag(999) as? UILabel {
            label.isHidden = !text.isEmpty
            if label.font != inputFont { label.font = inputFont }
            // [T-ios-composer-placeholder-rotation] Only the focus handler
            // picks a new entry. This runs on EVERY parent re-render (the
            // composer has no Equatable gate), so assigning `placeholder`
            // unconditionally would yank a rotated hint back to the default.
            //
            // Sync placeholder text — soulName changes (SOUL.md edit)
            // flow through as a new `placeholder` value from the parent,
            // and locale switches re-resolve String(localized:). Routing
            // through the coordinator re-resolves against the pool, so those
            // updates still land while a rotated hint is showing.
            // [T-ios-context-usage-hint] Hint requests first: a new generation
            // schedules the usage line; refresh then yields to it while it is
            // on screen.
            context.coordinator.syncContextUsageHint(in: tv)
            context.coordinator.consumeTransientHintIfNeeded(in: tv)
            context.coordinator.refreshPlaceholderText(in: tv)
        }

        // Sync focus from SwiftUI → UIKit (guard to prevent feedback loops)
        let wantsFocus = isFocused
        let hasFocus = tv.isFirstResponder
        if wantsFocus != hasFocus {
            pasteLog.debug("[updateUIView] focus sync: wantsFocus=\(wantsFocus) hasFocus=\(hasFocus)")
            let coordinator = context.coordinator
            coordinator.isSyncingFocus = true
            if wantsFocus {
                DispatchQueue.main.async {
                    // Only become first responder when no sheet /
                    // fullScreenCover is presented above this view.
                    // SwiftUI sheets share the same window on iOS, so
                    // isKeyWindow is always true — instead walk the VC
                    // hierarchy and check presentedViewController.
                    let blocked: Bool = {
                        guard let vc = tv.nearestViewController else { return true }
                        var walk: UIViewController? = vc
                        while let w = walk {
                            if w.presentedViewController != nil { return true }
                            walk = w.parent
                        }
                        return false
                    }()
                    guard !blocked else {
                        pasteLog.debug("[updateUIView] skipping becomeFirstResponder — a modal is presented")
                        coordinator.isSyncingFocus = false
                        return
                    }
                    pasteLog.debug("[updateUIView] calling becomeFirstResponder")
                    // [T-voice-text-switch-autofocus-race] SwiftUI is
                    // deliberately requesting focus — open the gate so
                    // canBecomeFirstResponder permits it (it defaults closed to
                    // block unsolicited chain hand-offs on mount).
                    tv.allowsFirstResponder = true
                    tv.becomeFirstResponder()
                    coordinator.isSyncingFocus = false
                }
            } else {
                DispatchQueue.main.async {
                    pasteLog.debug("[updateUIView] calling resignFirstResponder")
                    // [T-voice-text-switch-autofocus-race] Re-close the gate on
                    // resign so a later unsolicited chain hand-off (if UIKit
                    // reuses this instance) is refused again until the next real
                    // tap or focus-sync request.
                    tv.allowsFirstResponder = false
                    tv.resignFirstResponder()
                    // Re-measure after keyboard dismiss so the text view
                    // doesn't retain its keyboard-present height (iOS 16).
                    tv.invalidateIntrinsicContentSize()
                    coordinator.isSyncingFocus = false
                }
            }
        }

        // Sync scroll state back to SwiftUI.
        // [T-ios-composer-paste-scroll-stale] Measure it, don't read the flag.
        // `isScrollEnabled` is refreshed only as a side effect of
        // intrinsicContentSize/sizeThatFits, which UIKit runs on a LATER layout
        // pass — so on the one-shot paste/share/programmatic path this read
        // returned the PRE-paste value (false) and left `inputIsScrollable`
        // stuck false. AIChatView's swipe-to-send DragGesture gates on
        // `!inputIsScrollable`, so it kept claiming vertical drags and the user
        // could never scroll to the rest of the pasted text. The incremental
        // typing/ASR path hid the bug: textViewDidChange re-measures first.
        let scrollable = tv.overflowsMaxHeight
        if isScrollable != scrollable {
            let binding = _isScrollable
            DispatchQueue.main.async {
                binding.wrappedValue = scrollable
            }
        }
        // [T-ios-composer-swipe-send-at-bottom] Publish the at-bottom state on
        // the same pass. A text change re-lays-out the content, which moves
        // where "bottom" is: growing the text usually leaves the view no longer
        // at the end, and shrinking/clearing it makes the value trivially true
        // again. Without this the flag would only refresh on user scrolling and
        // could authorise a send right after a paste pushed new content in.
        let atBottom = tv.isScrolledToBottom
        if isAtScrollBottom != atBottom {
            let binding = _isAtScrollBottom
            DispatchQueue.main.async {
                binding.wrappedValue = atBottom
            }
        }
    }

    class Coordinator: NSObject, UITextViewDelegate {
        var parent: PastableTextView
        /// Guard flag to prevent focus feedback loop between UIKit delegate → SwiftUI → updateUIView
        var isSyncingFocus = false
        /// [T-ios-composer-residual-text-33549] When `updateUIView` clears

        init(_ parent: PastableTextView) {
            self.parent = parent
        }

        // MARK: - [T-ios-composer-placeholder-rotation] Placeholder hint cycle
        //
        // Rotation is driven by the FOCUS EDGE, not a timer: the placeholder
        // changes once each time the user focuses the empty composer. Nothing
        // runs while the view merely sits on screen, so there is no periodic
        // main-thread wakeup to pay for.
        //
        // State lives on the Coordinator, not in SwiftUI @State: the Coordinator
        // is reference-typed and survives the composer's re-renders, whereas
        // @State would be read back through a stale `self` copy — the failure
        // the voice panel's DeleteButtonState comment records.
        //
        // `textViewDidBeginEditing` is the ONLY place that picks a new entry.
        // `updateUIView` must re-resolve through `refreshPlaceholderText`, never
        // assign `parent.placeholder` directly, or a re-render would snap a
        // rotated hint back to the default.

        /// Index into `placeholderPool` of the text currently on screen. Starts
        /// at 0 — the default — because that is what `makeUIView` renders, and
        /// stays there until the first focus swaps it.
        private var placeholderPoolIndex: Int = 0
        /// False until focus has been gained once for this composer. The FIRST
        /// focus after entering a session leaves the text alone: the user just
        /// tapped the field they were already reading, and swapping the words
        /// out from under that tap reads as a glitch. Every later focus rotates.
        private var hasFocusedOnce = false

        /// The full rotation pool: the default placeholder is element 0 and is
        /// a rotation candidate like any other, so it can come back around.
        private var placeholderPool: [String] {
            [parent.placeholder] + parent.placeholderRotation
        }

        /// True while something other than the default is on screen, so
        /// `updateUIView` doesn't overwrite a rotated hint on the next
        /// re-render (the composer has no Equatable gate — this runs a lot).
        var isShowingRotatedHint: Bool { placeholderPoolIndex != 0 }

        // MARK: - [T-ios-context-usage-hint] Transient context-usage line
        //
        // After a turn finishes the parent hands down a `ContextUsageHint`;
        // the label shows "Context 62% used · 124k / 200k" and KEEPS showing it
        // until the user actually types — the first character entering the
        // composer is what consumes it. It used to expire on a 3.5 s Timer;
        // the user asked for it to stay so the figure can be read at leisure.
        // Everything about it lives here on the Coordinator for the same
        // reason the rotation state does: it is reference-typed and survives
        // re-renders, and a work item stored in SwiftUI @State would capture
        // a stale copy of the view struct (the objectdestroy UAF family).
        //
        // Ownership rule while the line is unconsumed
        // (`hasUnconsumedTransientHint`): `rotatePlaceholderOnFocus` skips its
        // swap — focusing without typing must not dismiss the line — and
        // `refreshPlaceholderText` re-asserts the line instead of the pool
        // entry. Consumption happens once, on the empty → non-empty edge of
        // the text; clearing the composer again afterwards brings back the
        // ordinary default/rotation placeholder, never the old figure. Only a
        // new generation (the next finished turn) shows a line again.

        /// Last `ContextUsageHint.generation` acted on, so a re-render that
        /// carries the same request is a no-op.
        private var contextHintGeneration = 0
        /// The 0.4 s debounce: a burst of quick turns shows one line, for the
        /// last of them. Cancelled and replaced by each newer generation.
        private var contextHintDebounce: DispatchWorkItem?
        /// Last `[sync]` diagnostic state, to log only on change.
        private var lastSyncLogState: String?
        private var transientHint: ContextUsageHint?
        /// True from the moment a usage line is REQUESTED (new generation seen)
        /// until the user starts typing. Both placeholder paths yield to the
        /// line while this holds — even during the debounce, before it is
        /// visible.
        private(set) var hasUnconsumedTransientHint = false
        /// [T-ios-context-usage-hint-first-focus-only] True once this line has
        /// spent its single "survive a focus" allowance. The line must ride
        /// out exactly ONE focus gain — the auto-focus after a reply, or the
        /// user's own tap right as it appears — but not a second one:
        /// focusing again without ever typing retires the line and restores
        /// the ordinary focus rotation. Without this, "keep until typing"
        /// silently disabled rotation for as long as the composer stayed
        /// empty (user report: after the auto-focus the tips never cycled).
        private(set) var hasProtectedFirstFocus = false
        /// True once the debounce fired and the label actually carries the
        /// line; gates `refreshPlaceholderText`'s re-assertion so a re-render
        /// inside the debounce window does not pre-empt the crossfade.
        private var transientHintApplied = false

        /// Called from `updateUIView` on every re-render; acts only when the
        /// parent's hint carries a generation not yet seen.
        func syncContextUsageHint(in textView: UITextView) {
            // Diagnostic: what this Coordinator SEES on each re-render. Logged
            // only when it changes, since updateUIView runs constantly.
            let seen = "parentHint=\(parent.contextUsageHint.map { "gen\($0.generation)" } ?? "nil") handled=gen\(contextHintGeneration) unconsumed=\(hasUnconsumedTransientHint)"
            if seen != lastSyncLogState {
                lastSyncLogState = seen
                hintLog.info("[sync] \(seen)")
            }
            guard let hint = parent.contextUsageHint,
                  hint.generation != contextHintGeneration else { return }
            contextHintGeneration = hint.generation
            hintLog.info("[sync] new gen\(hint.generation) → debounce 0.4s (textEmpty=\(textView.text.isEmpty))")
            // Claim the label NOW, before the debounce: from this instant a
            // focus gain must yield rather than rotate, or a hardware-keyboard
            // auto-focus landing inside the 0.4 s window would flash a random
            // hint first. The visual apply still waits for the debounce
            // (`transientHintApplied` gates re-assertion until then).
            transientHint = hint
            transientHintApplied = false
            hasUnconsumedTransientHint = true
            hasProtectedFirstFocus = false
            contextHintDebounce?.cancel()
            let work = DispatchWorkItem { [weak self, weak textView] in
                guard let self, let textView else { return }
                self.contextHintDebounce = nil
                self.showTransientHint(hint, in: textView)
            }
            contextHintDebounce = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
        }

        private func showTransientHint(_ hint: ContextUsageHint, in textView: UITextView) {
            // Re-checked at fire time: the user may have started typing during
            // the debounce, and the label is hidden behind text anyway.
            guard textView.text.isEmpty,
                  let label = textView.viewWithTag(999) as? UILabel else {
                hintLog.info("[show] gen\(hint.generation) SKIPPED textEmpty=\(textView.text.isEmpty) label=\(textView.viewWithTag(999) != nil)")
                return
            }
            hintLog.info("[show] gen\(hint.generation) applying '\(hint.text)' tier=\(hint.tier) focused=\(textView.isFirstResponder) reduceMotion=\(UIAccessibility.isReduceMotionEnabled)")
            // State was claimed in syncContextUsageHint; this is the visual.
            transientHintApplied = true
            let attributed = Self.attributedHint(hint, font: label.font)
            let apply = { label.attributedText = attributed }
            // Reduce Motion: swap instantly, same as the rotation.
            if UIAccessibility.isReduceMotionEnabled {
                apply()
            } else {
                UIView.transition(with: label, duration: 0.25,
                                  options: .transitionCrossDissolve, animations: apply)
            }
        }

        /// The user started typing: retire the usage line. Called on the
        /// empty → non-empty edge from `textViewDidChange` (keystrokes,
        /// paste, `insertText`) and from `updateUIView` (programmatic writes
        /// such as dictation appending to the binding), so every way text
        /// can enter the composer counts as "input began". Idempotent.
        ///
        /// No visual transition: the label is already hidden behind the text
        /// the moment this runs. The pool index is reset to the default so
        /// that, should the composer be cleared again, the ordinary
        /// placeholder returns — never the old figure.
        func consumeTransientHintIfNeeded(in textView: UITextView) {
            guard hasUnconsumedTransientHint, !textView.text.isEmpty else { return }
            hintLog.info("[consume] gen\(transientHint?.generation ?? -1) input began (\(textView.text.count) chars) → back to default")
            hasUnconsumedTransientHint = false
            transientHint = nil
            placeholderPoolIndex = 0
            transientHintApplied = false
            hasProtectedFirstFocus = false
            if let label = textView.viewWithTag(999) as? UILabel {
                label.text = placeholderPool[0]
            }
        }

        /// Grey line with the two number segments in the tier's colour and a
        /// medium weight. Segments are located by `range(of:)` on the exact
        /// strings the numbers were formatted as, so locale word order is
        /// irrelevant. Below the warning tier there is no highlight at all.
        ///
        /// Weight is `.medium`, not `.semibold`: on device the full-strength
        /// red at semibold jumped out of the grey placeholder line; a
        /// placeholder is meant to sit back, so the numbers get just enough
        /// weight to read as the part that matters.
        private static func attributedHint(_ hint: ContextUsageHint, font: UIFont) -> NSAttributedString {
            let attributed = NSMutableAttributedString(
                string: hint.text,
                attributes: [.font: font, .foregroundColor: UIColor.placeholderText])
            guard let tone = hint.tier.highlightColor else { return attributed }
            let emphasis = UIFont.systemFont(ofSize: font.pointSize, weight: .medium)
            let ns = hint.text as NSString
            for segment in hint.highlights {
                let range = ns.range(of: segment)
                guard range.location != NSNotFound else { continue }
                attributed.addAttributes([.foregroundColor: tone, .font: emphasis], range: range)
            }
            return attributed
        }

        deinit {
            contextHintDebounce?.cancel()
        }

        /// Rotate to a random pool entry on focus gain. Called from
        /// `textViewDidBeginEditing`, which is the only place that knows focus
        /// was actually acquired.
        func rotatePlaceholderOnFocus(_ textView: UITextView) {
            // Only meaningful while the placeholder is visible at all.
            guard textView.text.isEmpty else { return }
            // [T-ios-context-usage-hint] The usage line keeps the label until
            // the user types; merely focusing must not swap it away.
            hintLog.info("[rotate] focus gained unconsumed=\(hasUnconsumedTransientHint) protected=\(hasProtectedFirstFocus) voiceOver=\(UIAccessibility.isVoiceOverRunning) firstFocus=\(!hasFocusedOnce) idx=\(placeholderPoolIndex)")
            if hasUnconsumedTransientHint {
                // [T-ios-context-usage-hint-first-focus-only] The line survives
                // its first focus gain (auto-focus after the reply, or the
                // user's own tap) exactly once; the next focus without typing
                // retires it and the ordinary rotation below takes over.
                guard hasProtectedFirstFocus else {
                    hasProtectedFirstFocus = true
                    hintLog.info("[rotate] yield — usage line keeps the label (first focus protected)")
                    return
                }
                hintLog.info("[rotate] second focus — retiring usage line gen\(transientHint?.generation ?? -1)")
                contextHintDebounce?.cancel()
                contextHintDebounce = nil
                hasUnconsumedTransientHint = false
                transientHint = nil
                transientHintApplied = false
                hasProtectedFirstFocus = false
                // Put the pool entry back first, so every early return below
                // (VoiceOver, first-focus opt-out, single-entry pool) still
                // leaves the label without the stale figure.
                if let label = textView.viewWithTag(999) as? UILabel,
                   placeholderPoolIndex < placeholderPool.count {
                    label.text = placeholderPool[placeholderPoolIndex]
                }
            }
            // VoiceOver: swapping the label retriggers announcements and talks
            // over the user, so pin whatever is showing and never rotate.
            guard !UIAccessibility.isVoiceOverRunning else { return }
            // First focus normally shows the default untouched — the user just
            // tapped the field they were already reading. In a session that
            // already has messages the caller opts in to rotating immediately,
            // since the default has nothing left to teach there.
            if !hasFocusedOnce {
                hasFocusedOnce = true
                guard parent.placeholderRotatesOnFirstFocus else { return }
            }
            let pool = placeholderPool
            guard pool.count > 1 else { return }
            guard let label = textView.viewWithTag(999) as? UILabel else { return }

            // Random, but never the entry already on screen — a repeat reads as
            // "the rotation is broken" rather than as chance.
            var next = Int.random(in: 0..<pool.count)
            while next == placeholderPoolIndex { next = Int.random(in: 0..<pool.count) }
            placeholderPoolIndex = next
            let text = pool[next]
            hintLog.info("[rotate] swapped to pool[\(next)]")
            guard label.text != text else { return }

            // Reduce Motion: swap instantly rather than cross-dissolving.
            guard !UIAccessibility.isReduceMotionEnabled else {
                label.text = text
                return
            }
            // Cross-dissolve on the UIKit side. SwiftUI's .transition/.animation
            // do not cross the UIViewRepresentable boundary, so this is the only
            // place the fade can happen.
            UIView.transition(with: label,
                              duration: 0.25,
                              options: .transitionCrossDissolve,
                              animations: { label.text = text })
        }

        /// Re-resolve the on-screen text against the CURRENT pool, without
        /// animating and without picking a new entry. Used by `updateUIView`
        /// so a soul rename or locale switch still reaches a rotated hint.
        func refreshPlaceholderText(in textView: UITextView) {
            guard let label = textView.viewWithTag(999) as? UILabel else { return }
            // [T-ios-context-usage-hint] While the usage line is unconsumed a
            // parent re-render must not overwrite it with the pool entry;
            // re-assert it instead (a font-size change re-applies the label
            // font and would otherwise flatten the highlight).
            if hasUnconsumedTransientHint, let hint = transientHint {
                // Only once the debounce has applied it; before that the label
                // keeps whatever it showed, and the crossfade lands on time.
                if transientHintApplied, label.text != hint.text {
                    label.attributedText = Self.attributedHint(hint, font: label.font)
                }
                return
            }
            let pool = placeholderPool
            guard placeholderPoolIndex < pool.count else {
                placeholderPoolIndex = 0
                if label.text != pool[0] { label.text = pool[0] }
                return
            }
            let text = pool[placeholderPoolIndex]
            if label.text != text { label.text = text }
        }

        /// [T-ios-composer-swipe-send-at-bottom] Track the at-bottom state while
        /// the user scrolls the composer, so swipe-to-send becomes available the
        /// moment they reach the end of a long value.
        ///
        /// `UITextViewDelegate` refines `UIScrollViewDelegate`, so the text
        /// view's existing delegate already receives this — no separate delegate
        /// or KVO observer is needed.
        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard let tv = scrollView as? PastableUITextView else { return }
            publishAtBottom(tv)
        }

        /// Also refresh once deceleration/rubber-band settles. `scrollViewDidScroll`
        /// fires throughout the animation, but the final resting offset after an
        /// overscroll bounce-back arrives with these callbacks — without them a
        /// fling that bounces off the end could leave the flag reading `true`
        /// while the content has actually settled short of the bottom.
        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
            guard let tv = scrollView as? PastableUITextView else { return }
            publishAtBottom(tv)
        }

        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate: Bool) {
            guard !willDecelerate, let tv = scrollView as? PastableUITextView else { return }
            publishAtBottom(tv)
        }

        /// Push the measured at-bottom state into SwiftUI, skipping no-op writes.
        /// Called from the scroll callbacks, which fire many times per gesture —
        /// the equality check keeps that from spamming view updates.
        private func publishAtBottom(_ tv: PastableUITextView) {
            let atBottom = tv.isScrolledToBottom
            guard parent.isAtScrollBottom != atBottom else { return }
            parent.isAtScrollBottom = atBottom
        }

        func textViewDidChange(_ textView: UITextView) {
            // [T-ios-composer-input-frozen 2026-05-19] Earlier swallow-flag
            // mechanism (T-ios-composer-residual-text-33549) was removed —
            // it stayed `true` indefinitely on non-IME input paths and
            // silently dropped the user's NEXT real keystroke, freezing
            // the composer until the view was rebuilt. The residual-text
            // race it was guarding against is already neutralized at the
            // updateUIView write site: `unmarkText()` followed by
            // `tv.text = ""` leaves the UITextView with a known-empty
            // buffer, so any late IME callback that fires later just
            // writes "" back into the binding, not stale text.
            parent.text = textView.text
            // [T-ios-context-usage-hint] The first character typed retires the
            // usage line (see consumeTransientHintIfNeeded).
            consumeTransientHintIfNeeded(in: textView)
            textView.invalidateIntrinsicContentSize()
            // [T-ios-composer-paste-truncation] Republish the scroll-state
            // bindings here too. An in-app paste mutates the text through UIKit
            // (`super.paste`) and lands in this callback WITHOUT going through
            // `updateUIView`, so this is the only point where the SwiftUI side
            // learns that a long paste just made the composer scrollable. Left
            // stale, `inputIsScrollable` stayed false and the swipe-to-send
            // gesture kept claiming the vertical drags the user needed for
            // scrolling.
            if let tv = textView as? PastableUITextView {
                tv.layoutIfNeeded()   // settle via layoutSubviews before reading
                let scrollable = tv.overflowsMaxHeight
                if parent.isScrollable != scrollable { parent.isScrollable = scrollable }
                let atBottom = tv.isScrolledToBottom
                if parent.isAtScrollBottom != atBottom { parent.isAtScrollBottom = atBottom }
            }
            if let label = textView.viewWithTag(999) as? UILabel {
                label.isHidden = !textView.text.isEmpty
            }
            // Publish caret position for `@`-mention detection. The selectedRange
            // here is in UTF-16 units, which matches NSString offsets used by the
            // view model's mention detector.
            parent.onCaretChange?(textView.selectedRange.location)
        }

        /// Touch-keyboard Return interception. The hardware Return key is
        /// handled via UIKeyCommand in `PastableUITextView.keyCommands`; this
        /// path catches the on-screen keyboard where a newline otherwise
        /// inserts directly into the text view. When the user has opted into
        /// "Return → Send" in Appearance Settings, swallow the newline and
        /// fire `onReturnKey` instead. IME composition is left alone so
        /// Chinese/Japanese input confirmation via Return still works.
        func textView(_ textView: UITextView,
                      shouldChangeTextIn range: NSRange,
                      replacementText text: String) -> Bool {
            // [T-text-input-correction-source] Passive capture of
            // select-and-replace edits (the composer analogue of a voice
            // transcript fix). A non-empty replaced range with non-empty new
            // text, outside IME composition, is a "the user rewrote this span"
            // signal. Purely observational — never changes the edit; the
            // downstream recorder applies the same phonetic-admission + consent
            // gate as the voice path, so most rewrites are dropped and nothing
            // is stored without opt-in.
            if range.length > 0, !text.isEmpty, textView.markedTextRange == nil {
                let ns = textView.text as NSString
                if range.location + range.length <= ns.length {
                    let before = ns.substring(with: range)
                    parent.onSelectionReplace?(before, text)
                }
            }

            guard text == "\n" else { return true }
            // Don't hijack Return while an IME composition is active —
            // the Return key is confirming the composition, not submitting.
            if textView.markedTextRange != nil { return true }
            let behavior = UserDefaults.standard.integer(forKey: "returnKeyBehavior")
            guard behavior == 1 else { return true }
            parent.onReturnKey?()
            return false
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            pasteLog.debug("[Coordinator] textViewDidBeginEditing isSyncing=\(self.isSyncingFocus)")
            if !isSyncingFocus {
                parent.isFocused = true
            }
            // [T-ios-composer-placeholder-rotation] Focus gain is the sole
            // trigger. Fired here rather than off the `isFocused` binding
            // because this callback runs on the real UIKit edge — it cannot
            // double-fire from a re-render, and it covers programmatic focus
            // (`isSyncingFocus`) the same as a tap.
            rotatePlaceholderOnFocus(textView)
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            pasteLog.debug("[Coordinator] textViewDidEndEditing isSyncing=\(self.isSyncingFocus)")
            if !isSyncingFocus {
                parent.isFocused = false
            }
            // Force re-layout after keyboard dismiss — on iOS 16 the
            // UIViewRepresentable sizeThatFits may not be re-queried,
            // leaving the text view at its keyboard-present height.
            textView.invalidateIntrinsicContentSize()
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            parent.hasSelection = textView.selectedRange.length > 0
            parent.onCaretChange?(textView.selectedRange.location)
        }
    }
}


// MARK: - UIView helpers (local copy for cross-file access)

private extension UIView {
    var nearestViewController: UIViewController? {
        var responder: UIResponder? = self
        while let r = responder {
            if let vc = r as? UIViewController { return vc }
            responder = r.next
        }
        return nil
    }
}

// MARK: - [T-ios-context-usage-hint] Tier colours for the placeholder line

private extension ContextUsage.Tier {
    /// System dynamic colours so light and dark mode both get the intended
    /// contrast against `.placeholderText`; matches the orange the message
    /// footer's usage capsule already uses.
    ///
    /// Toned down with an alpha rather than a custom hex: the alpha is applied
    /// at draw time over the composer's own surface, so the colour tracks the
    /// light/dark appearance the way `.placeholderText` next to it does, and
    /// it stays a recognisable system red/orange — just no longer at full
    /// strength, which on device read as shouting inside a placeholder line.
    /// Red keeps a slightly lower alpha than orange because it is the more
    /// saturated hue; it still reads as the more urgent of the two.
    var highlightColor: UIColor? {
        switch self {
        case .normal: return nil
        // Orange is the lighter hue, so it needs the lower alpha to sit at the
        // same restraint as the red — on device 0.65 still read brighter than
        // the 0.60 red beside it.
        case .warning: return UIColor.systemOrange.withAlphaComponent(0.50)
        case .critical: return UIColor.systemRed.withAlphaComponent(0.60)
        }
    }
}
