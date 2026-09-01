//
//  MessageImageGallery.swift
//  MinisApp
//
//  Paged fullscreen image gallery shared by both preview paths:
//    1. User-message attachments (via \.openImageGallery environment action)
//    2. Assistant-message markdown-embedded images (via notification router)
//
//  Each `GalleryItem` is loaded lazily — only the current page (and its
//  neighbors, once TabView builds them) decode an image. Heavy pages off the
//  current slot stay as placeholders until navigated to.
//

import Photos
import SwiftUI
import UIKit

// MARK: - GalleryItem

/// A single image in a multi-image preview session. Images are loaded lazily
/// via the `load` closure so large sets don't decode all bitmaps up front.
struct GalleryItem: Identifiable, Equatable {
    let id: String              // unique per session — typically the source URL string
    let title: String           // human-readable caption (usually file name)
    let load: () async -> UIImage?

    static func == (lhs: GalleryItem, rhs: GalleryItem) -> Bool {
        lhs.id == rhs.id
    }
}

/// Presentation payload for a gallery session. Wrap `.fullScreenCover(item:)`
/// around this on the chat root view.
struct GalleryPresentation: Identifiable {
    let id = UUID()
    let items: [GalleryItem]
    let startIndex: Int
}

// MARK: - MessageImageGallery

struct MessageImageGallery: View {
    let items: [GalleryItem]
    let startIndex: Int
    @Environment(\.dismiss) private var dismiss
    @State private var currentIndex: Int
    @State private var loaded: [String: UIImage] = [:]
    @State private var saveStatus: SaveStatus = .idle
    @State private var copyDone = false
    @State private var showShareSheet = false
    /// Chrome (top action buttons + bottom caption) visibility toggled by
    /// single-tapping the image area, matching the system Photos app.
    @State private var chromeVisible: Bool = true

    private enum SaveStatus { case idle, saving, saved, failed }

    init(items: [GalleryItem], startIndex: Int) {
        self.items = items
        self.startIndex = startIndex
        // Clamp in case the caller passes a stale/out-of-range index.
        let clamped = max(0, min(startIndex, max(items.count - 1, 0)))
        _currentIndex = State(initialValue: clamped)
    }

    private var currentItem: GalleryItem? {
        guard items.indices.contains(currentIndex) else { return nil }
        return items[currentIndex]
    }

    private var currentImage: UIImage? {
        guard let item = currentItem else { return nil }
        return loaded[item.id]
    }

    var body: some View {
        ZStack {
            // No backdrop here — `ImagePreviewContent` renders its own
            // inside each page so it can fade in lock-step with the
            // pull-to-dismiss offset without bouncing a @Binding back
            // up through TabView and resetting the drag.
            Color.black.ignoresSafeArea()

            TabView(selection: $currentIndex) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    GalleryPage(
                        item: item,
                        image: loaded[item.id],
                        onDismiss: { dismiss() },
                        onSingleTap: { toggleChrome() }
                    )
                    .tag(index)
                    .task(id: item.id) {
                        await loadIfNeeded(item: item)
                    }
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()

            // Top chrome — action buttons only (close, copy, save, share).
            VStack {
                chromeTopBar
                Spacer()
            }
            .opacity(chromeVisible ? 1 : 0)
            .animation(.easeInOut(duration: 0.2), value: chromeVisible)
            .zIndex(10)

            // Bottom caption — file title, centered horizontally.
            VStack {
                Spacer()
                if let item = currentItem {
                    Text(item.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(ChatColors.primaryText)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 24)
                        .allowsHitTesting(false)
                }
            }
            .opacity(chromeVisible ? 1 : 0)
            .animation(.easeInOut(duration: 0.2), value: chromeVisible)
            .zIndex(10)
        }
        .onChange(of: currentIndex) { _ in
            // Reset per-image chrome state when swiping between pages so the
            // new image starts in an untouched state.
            saveStatus = .idle
            copyDone = false
        }
        .sheet(isPresented: $showShareSheet) {
            if let img = currentImage,
               let data = img.pngData(),
               let tmpURL = Self.writeTempImageFile(data: data) {
                MinisShareSheet(url: tmpURL)
            }
        }
        .statusBar(hidden: true)
    }

    private func toggleChrome() {
        chromeVisible.toggle()
    }

    @ViewBuilder
    private var chromeTopBar: some View {
        HStack(alignment: .center) {
            Button { dismiss() } label: {
                chromeIcon("xmark")
            }

            Spacer()

            Button {
                guard let img = currentImage else { return }
                UIPasteboard.general.image = img
                copyDone = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copyDone = false }
            } label: {
                chromeIcon(copyDone ? "checkmark" : "doc.on.doc",
                           tint: copyDone ? .green : nil)
            }
            .disabled(copyDone || currentImage == nil)

            Button {
                saveCurrentToPhotos()
            } label: {
                Group {
                    switch saveStatus {
                    case .idle:
                        Image(systemName: "square.and.arrow.down").offset(y: -1)
                    case .saving:
                        ProgressView().tint(ChatColors.primaryText)
                    case .saved:
                        Image(systemName: "checkmark").foregroundStyle(.green)
                    case .failed:
                        Image(systemName: "exclamationmark.triangle")
                    }
                }
                .font(.body.weight(.semibold))
                .foregroundStyle(ChatColors.primaryText)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .background(.ultraThinMaterial, in: Circle())
            }
            .disabled(saveStatus != .idle || currentImage == nil)

            Button {
                guard let img = currentImage else { return }
                PrintHelper.printImage(img, jobName: AppLocalized("Image"))
            } label: {
                chromeIcon("printer")
            }
            .disabled(currentImage == nil)

            Button {
                showShareSheet = true
            } label: {
                chromeIcon("square.and.arrow.up")
            }
            .disabled(currentImage == nil)
        }
        .padding(.horizontal)
        .padding(.top, 7)
    }

    @ViewBuilder
    private func chromeIcon(_ name: String, tint: Color? = nil) -> some View {
        Image(systemName: name)
            .font(.body.weight(.semibold))
            .foregroundStyle(tint ?? ChatColors.primaryText)
            .frame(width: 44, height: 44)
            .contentShape(Circle())
            .background(.ultraThinMaterial, in: Circle())
    }

    private func loadIfNeeded(item: GalleryItem) async {
        if loaded[item.id] != nil { return }
        if let img = await item.load() {
            await MainActor.run { loaded[item.id] = img }
        }
    }

    private func saveCurrentToPhotos() {
        guard let image = currentImage else { return }
        saveStatus = .saving
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { saveStatus = .failed }
                return
            }
            PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            } completionHandler: { success, _ in
                DispatchQueue.main.async {
                    saveStatus = success ? .saved : .failed
                    if success {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            saveStatus = .idle
                        }
                    }
                }
            }
        }
    }

    private static func writeTempImageFile(data: Data) -> URL? {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("share_gallery_\(UUID().uuidString).png")
        try? data.write(to: tmp)
        return tmp
    }
}

// MARK: - GalleryPage

/// A single page in the gallery — shows the zoomable content when the image is
/// loaded, otherwise a progress spinner. Pass-through horizontal drag is enabled
/// so TabView retains full control of paging gestures when the image is at
/// its default scale.
private struct GalleryPage: View {
    let item: GalleryItem
    let image: UIImage?
    let onDismiss: () -> Void
    let onSingleTap: () -> Void

    var body: some View {
        ZStack {
            Color.clear
            if let image {
                ImagePreviewContent(
                    image: image,
                    onDismiss: onDismiss,
                    horizontalDragLocked: true,
                    onSingleTap: onSingleTap
                )
            } else {
                ProgressView()
                    .tint(.white)
            }
        }
    }
}

// MARK: - Environment action

/// Environment action for requesting a paged gallery preview, used by
/// user-message attachment tiles. The chat root view installs a handler that
/// presents `MessageImageGallery` in a full-screen cover.
struct OpenImageGalleryAction {
    let perform: (GalleryPresentation) -> Void

    func callAsFunction(_ presentation: GalleryPresentation) {
        perform(presentation)
    }
}

// MARK: - Markdown inline image tap routing

/// Bridge used by UIKit-level tap handlers inside `SelectableMarkdownView` to
/// request a gallery preview of all markdown-embedded images in a given
/// assistant message. SwiftUI views observe `NotificationCenter` for these
/// posts and resolve the message → gallery items list on their side.
///
/// Payload keys:
///   - `messageId: UUID` — the assistant message that owns the tapped image
///   - `sourceURL: String` — the minis:// (or http) URL inside the markdown
///     `![](...)` that was tapped; used to pick the starting page
enum MarkdownImageTapRouter {
    static let tappedNotification = Notification.Name("MarkdownImageTappedNotification")
    static let messageIdKey = "messageId"
    static let sourceURLKey = "sourceURL"

    static func postTap(messageId: UUID, sourceURL: String) {
        NotificationCenter.default.post(
            name: tappedNotification,
            object: nil,
            userInfo: [messageIdKey: messageId, sourceURLKey: sourceURL]
        )
    }
}

private struct OpenImageGalleryKey: EnvironmentKey {
    static let defaultValue: OpenImageGalleryAction = OpenImageGalleryAction { _ in }
}

extension EnvironmentValues {
    var openImageGallery: OpenImageGalleryAction {
        get { self[OpenImageGalleryKey.self] }
        set { self[OpenImageGalleryKey.self] = newValue }
    }
}
