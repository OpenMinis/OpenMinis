//
//  ImagePreview.swift
//  MinisApp
//
//  In-bubble zoomable image + fullscreen image preview with pinch/zoom/
//  drag/save/share. Extracted from AIChatView.swift.
//

import Photos
import SwiftUI
import UIKit

// MARK: - Zoomable Image View (inline bubble)

/// Displays an image fitted to the container width with pinch-to-zoom and drag support.
struct ZoomableImageView: View {
    let image: UIImage
    var cornerRadius: CGFloat = 10

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            let aspect = image.size.height / max(image.size.width, 1)
            let fittedHeight = geo.size.width * aspect

            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: geo.size.width)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
                .contextMenu {
                    Button { UIPasteboard.general.image = image } label: {
                        Label("Copy Image", systemImage: "doc.on.doc")
                    }
                }
                .scaleEffect(scale)
                .offset(offset)
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in
                            scale = max(1.0, lastScale * value)
                        }
                        .onEnded { value in
                            lastScale = scale
                            if scale < 1.0 {
                                withAnimation(.easeOut(duration: 0.2)) {
                                    scale = 1.0
                                    lastScale = 1.0
                                    offset = .zero
                                    lastOffset = .zero
                                }
                            }
                        }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
                            guard scale > 1.0 else { return }
                            offset = CGSize(
                                width: lastOffset.width + value.translation.width,
                                height: lastOffset.height + value.translation.height
                            )
                        }
                        .onEnded { _ in
                            lastOffset = offset
                        }
                )
                .simultaneousGesture(
                    TapGesture(count: 2)
                        .onEnded {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                if scale > 1.0 {
                                    scale = 1.0
                                    lastScale = 1.0
                                    offset = .zero
                                    lastOffset = .zero
                                } else {
                                    scale = 3.0
                                    lastScale = 3.0
                                }
                            }
                        }
                )
                .frame(width: geo.size.width, height: max(fittedHeight * scale, geo.size.height))
                .clipped()
        }
    }
}


// MARK: - Fullscreen Image Preview Content (no chrome)

/// Zoomable / pannable / pull-to-dismiss image surface. Composable into
/// both the single-image `ImagePreviewView` and the paged
/// `MessageImageGallery`. Backed by a UIKit `UIScrollView` so gesture
/// arbitration (1-finger pan vs. 2-finger pinch vs. tap vs. double-tap
/// vs. parent UIPageViewController page-swipe) is handled by UIKit's
/// recognizer system. SwiftUI's composite gestures, when hosted inside
/// TabView's UIPageViewController bridge, were deferring all state
/// updates until the finger lifted and treating 2-finger touches as
/// drags.
///
/// Behavior:
///   · 1 finger drag at scale 1  → pull-to-dismiss (follows finger 1:1).
///     Release past `dismissThreshold` fires `onDismiss`. Release short
///     of threshold springs back.
///   · 1 finger drag at scale > 1 → pan inside zoomed image, clamped
///     to edges. Not a dismiss.
///   · 2 finger pinch            → zoom around the pinch midpoint
///     (native UIScrollView behavior).
///   · double tap                → toggle between 1x and max zoom,
///     anchored at the tap location.
///   · single tap                → host callback (`onSingleTap`) for
///     chrome toggle.
///
/// `horizontalDragLocked` (true inside a gallery) only engages the
/// dismiss drag when initial motion is vertical-dominant at scale 1,
/// so TabView still gets horizontal page swipes. At scale > 1, the
/// scrollView's own pan recognizer takes over and TabView page swipes
/// are naturally blocked until the user pans back to an edge.
struct ImagePreviewContent: UIViewRepresentable {
    let image: UIImage
    let onDismiss: () -> Void
    var horizontalDragLocked: Bool = false
    var onSingleTap: (() -> Void)? = nil
    /// Downward-pull distance (in pt) past which release triggers dismiss.
    var dismissThreshold: CGFloat = 80
    /// Maximum zoom scale for pinch / double-tap.
    var maximumScale: CGFloat = 3.0

    func makeUIView(context: Context) -> ImagePreviewContentView {
        let view = ImagePreviewContentView(image: image)
        view.onDismiss = onDismiss
        view.onSingleTap = onSingleTap
        view.horizontalDragLocked = horizontalDragLocked
        view.dismissThreshold = dismissThreshold
        view.maximumScale = maximumScale
        return view
    }

    func updateUIView(_ view: ImagePreviewContentView, context: Context) {
        view.onDismiss = onDismiss
        view.onSingleTap = onSingleTap
        view.horizontalDragLocked = horizontalDragLocked
        view.dismissThreshold = dismissThreshold
        view.maximumScale = maximumScale
    }
}

/// UIKit implementation backing `ImagePreviewContent`.
final class ImagePreviewContentView: UIView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    var onDismiss: (() -> Void)?
    var onSingleTap: (() -> Void)?
    var horizontalDragLocked: Bool = false
    var dismissThreshold: CGFloat = 80
    var maximumScale: CGFloat = 3.0 {
        didSet { scrollView.maximumZoomScale = maximumScale }
    }

    private let scrollView = UIScrollView()
    private let imageView: UIImageView
    private let backdrop = UIView()

    /// Translation applied to the scrollView during an in-progress
    /// dismiss drag. Lives on `scrollView.transform` (not
    /// contentOffset) so the scrollView's own clamping doesn't fight
    /// us.
    private var dismissTranslation: CGPoint = .zero
    /// Lateral drift factor so horizontal motion during dismiss still
    /// produces sideways movement (matches Photos.app feel).
    private let lateralDriftFactor: CGFloat = 0.5

    private var singleFingerDismissPan: UIPanGestureRecognizer!
    private var doubleTap: UITapGestureRecognizer!
    private var singleTap: UITapGestureRecognizer!

    init(image: UIImage) {
        self.imageView = UIImageView(image: image)
        super.init(frame: .zero)

        backgroundColor = .clear
        backdrop.backgroundColor = .black
        backdrop.translatesAutoresizingMaskIntoConstraints = false
        addSubview(backdrop)

        scrollView.delegate = self
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = maximumScale
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.alwaysBounceHorizontal = false
        scrollView.alwaysBounceVertical = false
        scrollView.bouncesZoom = true
        scrollView.decelerationRate = .fast
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scrollView)

        // NOTE: imageView uses manual frame layout (not autolayout) so
        // we can size it to the aspect-fitted rect within the scroll
        // view's bounds every time layoutSubviews runs. Pinning it to
        // the contentLayoutGuide instead would leave a tall image
        // running off-screen and appear to the user as "already zoomed".
        imageView.contentMode = .scaleToFill  // we compute the exact aspect-fit frame ourselves
        imageView.isUserInteractionEnabled = false
        scrollView.addSubview(imageView)

        NSLayoutConstraint.activate([
            backdrop.topAnchor.constraint(equalTo: topAnchor),
            backdrop.bottomAnchor.constraint(equalTo: bottomAnchor),
            backdrop.leadingAnchor.constraint(equalTo: leadingAnchor),
            backdrop.trailingAnchor.constraint(equalTo: trailingAnchor),

            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])

        // Single-finger dismiss pan. `maximumNumberOfTouches = 1` so
        // two-finger touches bypass this recognizer and reach the
        // scrollView's pinch recognizer instead.
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handleDismissPan(_:)))
        pan.minimumNumberOfTouches = 1
        pan.maximumNumberOfTouches = 1
        pan.delegate = self
        singleFingerDismissPan = pan
        addGestureRecognizer(pan)

        let double = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        double.numberOfTapsRequired = 2
        double.numberOfTouchesRequired = 1
        addGestureRecognizer(double)
        doubleTap = double

        let single = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
        single.numberOfTapsRequired = 1
        single.numberOfTouchesRequired = 1
        single.require(toFail: double)
        addGestureRecognizer(single)
        singleTap = single
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        sizeAndCenterImageView()
    }

    /// Compute the aspect-fitted rect for the image within the
    /// scrollView's bounds and set the imageView's frame to it at
    /// zoom 1.  Also drives `contentSize` so the scrollView knows
    /// the true natural page size. When zoomed in, the imageView's
    /// frame is grown by `zoomScale` via `scrollView.zoom(...)`'s
    /// internal transform — we only re-center on layout changes.
    private func sizeAndCenterImageView() {
        let boundsSize = scrollView.bounds.size
        guard boundsSize.width > 0, boundsSize.height > 0 else { return }
        let imgSize = imageView.image?.size ?? .zero
        guard imgSize.width > 0, imgSize.height > 0 else { return }

        // Only recompute the base (zoomScale == 1) frame when the
        // scroll view is at its resting zoom. When zoomed in, the
        // content size and imageView frame are already managed by the
        // scroll view itself, and we just re-center via insets.
        if abs(scrollView.zoomScale - 1.0) < 0.01 {
            let ratio = min(boundsSize.width / imgSize.width,
                            boundsSize.height / imgSize.height)
            let fitted = CGSize(width: imgSize.width * ratio,
                                height: imgSize.height * ratio)
            imageView.frame = CGRect(origin: .zero, size: fitted)
            scrollView.contentSize = fitted
        }

        // Re-center content so it sits in the middle of the viewport
        // when the content is smaller than the viewport (both at
        // zoom 1 and while zooming out).
        let xInset = max((boundsSize.width - scrollView.contentSize.width) / 2, 0)
        let yInset = max((boundsSize.height - scrollView.contentSize.height) / 2, 0)
        scrollView.contentInset = UIEdgeInsets(top: yInset, left: xInset, bottom: yInset, right: xInset)
    }

    // MARK: UIScrollViewDelegate

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        // Re-center via contentInset as the content grows/shrinks
        // relative to the viewport.
        let boundsSize = scrollView.bounds.size
        let xInset = max((boundsSize.width - scrollView.contentSize.width) / 2, 0)
        let yInset = max((boundsSize.height - scrollView.contentSize.height) / 2, 0)
        scrollView.contentInset = UIEdgeInsets(top: yInset, left: xInset, bottom: yInset, right: xInset)
    }

    // MARK: Dismiss pan

    @objc private func handleDismissPan(_ gr: UIPanGestureRecognizer) {
        guard scrollView.zoomScale <= 1.01 else { return }

        switch gr.state {
        case .began, .changed:
            let t = gr.translation(in: self)
            dismissTranslation = CGPoint(x: t.x * lateralDriftFactor, y: t.y)
            applyDismissTransform()
        case .ended, .cancelled, .failed:
            let pulled = dismissTranslation.y
            if pulled >= dismissThreshold {
                onDismiss?()
                return
            }
            UIView.animate(
                withDuration: 0.3,
                delay: 0,
                usingSpringWithDamping: 0.8,
                initialSpringVelocity: 0,
                options: [.curveEaseOut, .allowUserInteraction],
                animations: {
                    self.dismissTranslation = .zero
                    self.applyDismissTransform()
                }
            )
        default:
            break
        }
    }

    private func applyDismissTransform() {
        scrollView.transform = CGAffineTransform(translationX: dismissTranslation.x,
                                                  y: dismissTranslation.y)
        let progress = min(max(dismissTranslation.y, 0), dismissThreshold) / dismissThreshold
        backdrop.alpha = 1.0 - progress * 0.6
    }

    // MARK: Taps

    @objc private func handleDoubleTap(_ gr: UITapGestureRecognizer) {
        if scrollView.zoomScale > 1.01 {
            scrollView.setZoomScale(1.0, animated: true)
        } else {
            let location = gr.location(in: imageView)
            let targetScale = maximumScale
            let size = scrollView.bounds.size
            let w = size.width / targetScale
            let h = size.height / targetScale
            let rect = CGRect(x: location.x - w / 2,
                              y: location.y - h / 2,
                              width: w,
                              height: h)
            scrollView.zoom(to: rect, animated: true)
        }
    }

    @objc private func handleSingleTap(_ gr: UITapGestureRecognizer) {
        onSingleTap?()
    }

    // MARK: UIGestureRecognizerDelegate

    // `UIView` has its own `gestureRecognizerShouldBegin(_:)` so this
    // needs `override` even though the signature originates on the
    // `UIGestureRecognizerDelegate` protocol.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === singleFingerDismissPan else { return true }
        guard scrollView.zoomScale <= 1.01 else { return false }
        if horizontalDragLocked {
            let v = singleFingerDismissPan.velocity(in: self)
            if abs(v.x) > abs(v.y) { return false }
        }
        return true
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        return false
    }
}

// MARK: - Fullscreen Image Preview (single image + chrome)

struct ImagePreviewView: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var saveStatus: SaveStatus = .idle
    @State private var copyDone = false
    @State private var showShareSheet = false

    private enum SaveStatus {
        case idle, saving, saved, failed
    }

    var body: some View {
        ZStack {
            // [T-image-preview-lightmode-bands] Full-bleed black backdrop.
            // ImagePreviewContent draws its own black backdrop, but as a
            // UIViewRepresentable it is laid out INSIDE the safe area — in
            // light mode the fullScreenCover's white background showed through
            // the top/bottom safe-area bands (invisible in dark mode, which is
            // why it went unnoticed). Paint the whole cover black and let the
            // zoom canvas extend edge-to-edge; the chrome VStack below stays
            // safe-area-bound so buttons don't slide under the notch.
            Color.black.ignoresSafeArea()
            ImagePreviewContent(
                image: image,
                onDismiss: { dismiss() }
            )
            .ignoresSafeArea()

            // Top bar overlay — inside ZStack to match MinisVideoFullscreenPlayer
            // zIndex ensures buttons sit above gesture layers on iPad
            VStack {
                HStack {
                    // Close button (left)
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(ChatColors.primaryText)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                            .background(.ultraThinMaterial, in: Circle())
                    }

                    Spacer()

                    // Copy button
                    Button {
                        UIPasteboard.general.image = image
                        copyDone = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copyDone = false }
                    } label: {
                        Image(systemName: copyDone ? "checkmark" : "doc.on.doc")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(copyDone ? .green : ChatColors.primaryText)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .disabled(copyDone)

                    // Save button
                    Button {
                        saveImageToPhotos()
                    } label: {
                        Group {
                            switch saveStatus {
                            case .idle:
                                Image(systemName: "square.and.arrow.down")
                                    .offset(y: -1)
                            case .saving:
                                ProgressView()
                                    .tint(ChatColors.primaryText)
                            case .saved:
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.green)
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
                    .disabled(saveStatus == .saving || saveStatus == .saved)

                    // Print button
                    Button {
                        PrintHelper.printImage(image, jobName: AppLocalized("Image"))
                    } label: {
                        Image(systemName: "printer")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(ChatColors.primaryText)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                            .background(.ultraThinMaterial, in: Circle())
                    }

                    // Share button
                    Button {
                        showShareSheet = true
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                            .offset(y: -1)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(ChatColors.primaryText)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                            .background(.ultraThinMaterial, in: Circle())
                    }
                }
                .padding(.horizontal)
                .padding(.top, 7)
                Spacer()
            }
            .zIndex(10)
        }
        .sheet(isPresented: $showShareSheet) {
            if let data = image.pngData(), let tmpURL = Self.writeTempImageFile(data: data) {
                MinisShareSheet(url: tmpURL)
            }
        }
        .statusBar(hidden: true)
    }

    private func saveImageToPhotos() {
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

    fileprivate static func writeTempImageFile(data: Data) -> URL? {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("share_image_\(UUID().uuidString).png")
        try? data.write(to: tmp)
        return tmp
    }
}

// MARK: - Async Image Preview (loads from URL)

struct AsyncImagePreviewView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var loadedImage: UIImage?

    var body: some View {
        if let image = loadedImage {
            ImagePreviewView(image: image)
        } else {
            ZStack {
                Color.black.ignoresSafeArea()
                ProgressView()
                    .tint(.white)
            }
            .overlay(alignment: .topLeading) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(ChatColors.primaryText)
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                        .background(.ultraThinMaterial, in: Circle())
                }
                .padding(.horizontal)
                .padding(.top, 2)
            }
            .task {
                if let (data, _) = try? await URLSession.shared.data(from: url),
                   let img = UIImage(data: data) {
                    loadedImage = img
                }
            }
        }
    }
}

