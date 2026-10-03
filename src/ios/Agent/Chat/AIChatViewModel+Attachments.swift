import Foundation
import UIKit
import UniformTypeIdentifiers

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Pasted-text placeholders [T-paste-placeholder]

/// One long pasted text stashed for the current session's draft.
/// `charCount`/`preview` are derived at init for the chip row — the buffer
/// itself only ever needs `id` and `text`.
struct PastedText: Identifiable {
    let id: Int
    let text: String
    var charCount: Int { text.count }
    var preview: String {
        String(text.prefix(40)).replacingOccurrences(of: "\n", with: " ")
    }
}

enum PastePlaceholder {
    /// The literal the composer inserts and send() expands. Exactly one
    /// spelling — `[Pasted#N]`, capital P, no space —
    /// and the regex accepts only that: this is protocol text,
    /// not localizable UI copy, and a single strict form keeps hand-typed
    /// lookalikes falling through verbatim as designed.
    static let regex = try! NSRegularExpression(pattern: #"\[Pasted#(\d+)\]"#)

    static func literal(for id: Int) -> String { "[Pasted#\(id)]" }

    /// [T-paste-huge-to-file] Above this many characters a paste stops being a
    /// placeholder and becomes a .txt document attachment instead. Rationale:
    /// a placeholder still has to be rendered somewhere and expanded into the
    /// prompt, whereas a file attachment already has a bounded chip, an
    /// on-demand preview, and an established upload path.
    static let fileAttachmentThreshold = 15_000

    /// [T-paste-mediaref] A pasted-text MediaRef is identified by a
    /// `Pasted#<id>` filename prefix on an ordinary `text/plain` ref — NOT by a
    /// custom mimeType.
    ///
    /// Matches Android (bbf392ce0 / PastedMedia.isPastedRef) so both platforms
    /// read the same DB rows identically; these rows sync between devices, so a
    /// divergent convention here would make one platform misread the other's
    /// history. Reusing `text/plain` also keeps every existing consumer (icon
    /// mapper, preview router, share sheet) working unchanged, since they all
    /// treat `originalFileName` as an opaque label — teaching them a new
    /// mimeType subtype would be a much larger surface.
    static let pastedMimeType = "text/plain"
    static let mediaRefFileNamePrefix = "Pasted#"

    /// Session-media subdirectory pasted text is stored under. Separate from
    /// `attachments/` so a pasted ref is identifiable by path and never appears
    /// among the user's real uploads. Must be listed in
    /// ChatStore.deleteSessionMedia or these files would outlive their session.
    static let mediaSubdir = "pasted"

    /// True when this MediaRef holds pasted conversation text (not a user file).
    static func isPastedTextRef(_ ref: MediaRef) -> Bool {
        ref.mimeType == pastedMimeType
            && (ref.originalFileName?.hasPrefix(mediaRefFileNamePrefix) ?? false)
    }

    /// Filename given to a pasted-text MediaRef — carries the placeholder id so
    /// a stored message can be traced back to its `[Pasted#N]` origin.
    static func mediaRefFileName(for id: Int) -> String { "\(mediaRefFileNamePrefix)\(id).txt" }

    /// [T-paste-mediaref] Substituted when a pasted ref's file cannot be read.
    /// Explicit degradation beats a silent empty string: the model is told the
    /// context is missing instead of answering as if nothing was ever pasted.
    static let unavailableMarker =
        "[pasted content unavailable — the stored text file is missing]"

    /// Recover the placeholder id from such a filename (`Pasted#3.txt` → 3).
    static func idFromMediaRefFileName(_ name: String?) -> Int? {
        guard let name, name.hasPrefix(mediaRefFileNamePrefix) else { return nil }
        let rest = name.dropFirst(mediaRefFileNamePrefix.count)
        let digits = rest.prefix { $0.isNumber }
        return digits.isEmpty ? nil : Int(digits)
    }

    /// Replace every `[Pasted#N]` in `text` using `lookup`. Returns `text`
    /// unchanged when nothing resolves.
    ///
    /// Single pass over the ORIGINAL string: spans are collected first, then
    /// stitched, so replacement output is never rescanned — pasted content that
    /// itself contains something shaped like `[Pasted#2]` cannot be expanded a
    /// second time (no injection through paste content). A `nil` from `lookup`
    /// leaves that literal verbatim, matching the long-standing "unknown ids
    /// pass through" contract.
    static func expand(_ text: String, lookup: (Int) -> String?) -> String {
        guard text.contains("[Pasted#") else { return text }
        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return text }

        var result = ""
        var lastEnd = 0
        for m in matches {
            result += ns.substring(with: NSRange(location: lastEnd, length: m.range.location - lastEnd))
            let idStr = ns.substring(with: m.range(at: 1))
            if let id = Int(idStr), let replacement = lookup(id) {
                result += replacement
            } else {
                result += ns.substring(with: m.range)   // unknown id: keep literal
            }
            lastEnd = m.range.location + m.range.length
        }
        result += ns.substring(from: lastEnd)
        return result
    }

    /// The long-paste threshold, shared by every paste surface (composer
    /// Priority 5 + the voice panel's paste menu — previously two inline
    /// copies). English-dominant text (>50% ASCII letters) is measured in
    /// words, CJK/mixed in characters.
    static func isLong(_ text: String) -> Bool {
        let asciiLetters = text.unicodeScalars.filter {
            ($0.value >= 0x41 && $0.value <= 0x5A) || ($0.value >= 0x61 && $0.value <= 0x7A)
        }.count
        let isEnglishDominant = asciiLetters > text.count / 2
        if isEnglishDominant {
            let wordCount = text.components(separatedBy: .whitespacesAndNewlines)
                .filter { !$0.isEmpty }.count
            return wordCount > 1000
        }
        return text.count > 1200
    }
}

extension AIChatViewModel {

    /// Stash a long pasted text and return the placeholder literal to insert
    /// at the caret.
    ///
    /// [T-paste-huge-to-file] Above `PastePlaceholder.fileAttachmentThreshold`
    /// the paste does NOT become a placeholder at all: it is written to a .txt
    /// and added as an ordinary document attachment, so it travels the same
    /// send/preview/delete/upload path as a user-picked file. Returns nil in
    /// that case — the caller must NOT insert a literal, because the content is
    /// already represented by the attachment chip.
    func stashPastedText(_ text: String) -> String? {
        if text.count > PastePlaceholder.fileAttachmentThreshold {
            let ok = addPastedTextAsFileAttachment(text)
            logger.info("[PastePlaceholder] huge paste (\(text.count) chars) → .txt attachment ok=\(ok)")
            // If the file could not be written, fall through to the placeholder
            // path rather than dropping the user's paste on the floor.
            if ok { return nil }
        }
        let entry = PastedText(id: nextPasteId, text: text)
        nextPasteId += 1
        pastedTexts.append(entry)
        logger.info("[PastePlaceholder] stashed #\(entry.id) (\(text.count) chars), buffer=\(self.pastedTexts.count)")
        return PastePlaceholder.literal(for: entry.id)
    }

    /// [T-paste-huge-to-file] Write `text` to a temp .txt and attach it as a
    /// normal document. Returns false if the write failed.
    private func addPastedTextAsFileAttachment(_ text: String) -> Bool {
        let fm = FileManager.default
        let stamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let name = "Pasted_\(stamp).txt"
        let url = fm.temporaryDirectory.appendingPathComponent(name)
        do {
            try Data(text.utf8).write(to: url, options: .atomic)
        } catch {
            logger.error("[PastePlaceholder] failed to write \(name): \(error.localizedDescription)")
            return false
        }
        // Same entry point a Files-app pick uses, so classification, the chip,
        // upload and deletion all behave identically.
        addFileAttachment(from: url)
        // addFileAttachment copies into the attachment cache; the temp original
        // is no longer needed.
        try? fm.removeItem(at: url)
        return true
    }

    /// [T-paste-single-split] Consume a draft's `[Pasted#N]` literals in ONE
    /// place, producing everything every downstream consumer needs:
    ///
    ///   * `modelText`   — literals replaced by their full pasted bodies. This
    ///     is what goes into `agentHistory`, which therefore NEVER contains a
    ///     resolvable literal. Every provider request, retry, fallback,
    ///     compaction summary and title generation reads history as-is and is
    ///     automatically correct — there is no request-time expansion step left
    ///     to forget to call.
    ///   * `storedParts` — alternating `.text` / `.mediaRef` for `parts_json`,
    ///     each paste written to its own `text/plain` file (Android
    ///     PastedMedia.isPastedRef convention), so the DB never carries the
    ///     blob and the bubble never typesets it.
    ///
    /// Returns nil when nothing in `draft` resolves against the buffer — the
    /// caller keeps its plain `.text(draft)` path (unknown / hand-typed ids
    /// pass through verbatim, the long-standing contract).
    ///
    /// Consumed entries are removed from `pastedTexts` here and nowhere else:
    /// this function is the single point where a stash leaves the buffer.
    ///
    /// History: this replaces `resolveHistoryForOutbound`, which expanded
    /// literals at request-build time and had to be manually wrapped around
    /// every provider call site. Three separate bugs came from that shape —
    /// each time a call site existed (or appeared) that the wrap missed, the
    /// model silently received `[Pasted#N]` instead of the content. Expanding
    /// once at the draft→AgentMessage boundary removes the entire class.
    /// `liveCardMetas` mirrors what `toChatMessage` emits for a pasted ref on
    /// session reload, so the just-sent bubble shows the SAME card the reload
    /// path renders — before this, the live message carried no attachment for
    /// the paste at all and the card only appeared after re-entering the
    /// session ([T-paste-live-bubble-card]). Callers attach these to the
    /// ChatMessage ONLY — never to the metas that feed the
    /// `<user-attached-files>` XML: a paste is conversation content, not a
    /// user file the model should discover via file tools.
    /// `plannedFileIds` (paste id → storage uuid) comes from
    /// `planPastedCards`: when present, the media file is written under the
    /// PRE-PLANNED uuid so the card the bubble was born with points at the
    /// real file. Ids without a plan fall back to a fresh uuid.
    func consumePastedDraft(_ draft: String, sessionId: String,
                            plannedFileIds: [Int: String] = [:]) async
        -> (modelText: String, storedParts: [ContentPart], liveCardMetas: [AttachmentMeta])? {
        guard !pastedTexts.isEmpty, draft.contains("[Pasted#") else { return nil }
        let ns = draft as NSString
        let matches = PastePlaceholder.regex.matches(
            in: draft, range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return nil }

        var modelText = ""
        var storedParts: [ContentPart] = []
        var liveCardMetas: [AttachmentMeta] = []
        var pending = ""            // text accumulated since the last emitted stored part
        var lastEnd = 0
        var consumedIds: Set<Int> = []
        for m in matches {
            let before = ns.substring(with: NSRange(location: lastEnd, length: m.range.location - lastEnd))
            lastEnd = m.range.location + m.range.length
            let idStr = ns.substring(with: m.range(at: 1))
            guard let id = Int(idStr),
                  let entry = pastedTexts.first(where: { $0.id == id }) else {
                let literal = ns.substring(with: m.range)   // unknown id: keep literal
                modelText += before + literal
                pending += before + literal
                continue
            }
            modelText += before + entry.text
            pending += before
            if !pending.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                storedParts.append(.text(pending))
            }
            pending = ""
            // [T-paste-mediaref] Own subdirectory, deliberately NOT the shared
            // `attachments/` dir: identifiable by path alone (edit-guard +
            // renderer rely on it), never surfaces among real uploads, and
            // deleteSessionMedia already covers it. `linuxPath` stays nil so
            // the model's shell tools can't read it.
            let ref = await ChatStore.shared.saveMedia(
                data: Data(entry.text.utf8),
                mimeType: PastePlaceholder.pastedMimeType,
                sessionId: sessionId,
                originalFileName: PastePlaceholder.mediaRefFileName(for: id),
                subdir: PastePlaceholder.mediaSubdir,
                fileId: plannedFileIds[id]
            )
            storedParts.append(.mediaRef(ref))
            // Card meta matching the birth-time plan (same path when planned).
            // Callers use these only to reconcile bubbles that were NOT born
            // with a plan (queued prompts enqueued by older flows); paths
            // already present on the ChatMessage are skipped.
            let rel = ref.relativePath as NSString
            liveCardMetas.append(AttachmentMeta(
                path: "/var/minis/\((rel.deletingLastPathComponent as NSString).lastPathComponent)/\(rel.lastPathComponent)",
                size: Data(entry.text.utf8).count,
                modified: Date(),
                displayName: ref.originalFileName,
                pastedId: id,
                pastedCharCount: entry.text.count
            ))
            consumedIds.insert(id)
            logger.info("[PastePlaceholder] consumed #\(id) (\(entry.text.count) chars) as mediaRef \(ref.id)")
        }
        guard !consumedIds.isEmpty else { return nil }
        let tail = ns.substring(from: lastEnd)
        modelText += tail
        pending += tail
        if !pending.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            storedParts.append(.text(pending))
        }
        pastedTexts.removeAll { consumedIds.contains($0.id) }
        logger.info("[PastePlaceholder] draft consumed ids=\(consumedIds.sorted()), buffer=\(self.pastedTexts.count)")
        return (modelText, storedParts, liveCardMetas)
    }

    /// [T-paste-live-bubble-card] Plan the paste cards for a draft at BUBBLE
    /// BIRTH time (synchronous, main actor): for every literal that resolves
    /// against the buffer, pre-generate the storage uuid and build the final
    /// AttachmentMeta. The bubble is born with these cards — per the
    /// row-birth doctrine, post-insert attachment mutations are a re-layout
    /// race, and on device the tile row never appeared at all when attached
    /// after the first render. `consumePastedDraft` later writes each file
    /// to its planned id, so the card paths are valid from the start.
    ///
    /// The bubble TEXT keeps the `[Pasted#N]` literal verbatim (user
    /// requirement: the marker shows where the pasted content sits); the
    /// card carries the content reference in composer-chip style.
    func planPastedCards(for draft: String) -> (plan: [Int: String], metas: [AttachmentMeta]) {
        guard !pastedTexts.isEmpty, draft.contains("[Pasted#") else { return ([:], []) }
        let ns = draft as NSString
        let matches = PastePlaceholder.regex.matches(
            in: draft, range: NSRange(location: 0, length: ns.length))
        var plan: [Int: String] = [:]
        var metas: [AttachmentMeta] = []
        for m in matches {
            guard let id = Int(ns.substring(with: m.range(at: 1))),
                  plan[id] == nil,
                  let entry = pastedTexts.first(where: { $0.id == id }) else { continue }
            let fileId = UUID().uuidString
            plan[id] = fileId
            metas.append(AttachmentMeta(
                path: "/var/minis/\(PastePlaceholder.mediaSubdir)/\(fileId).txt",
                size: Data(entry.text.utf8).count,
                modified: Date(),
                displayName: PastePlaceholder.mediaRefFileName(for: id),
                pastedId: id,
                pastedCharCount: entry.text.count
            ))
        }
        return (plan, metas)
    }

    /// [T-paste-mediaref] True when the displayed message at `idx` carries
    /// pasted content stored as a mediaRef.
    ///
    /// Detected via the AttachmentMeta emitted for such a ref by
    /// `toChatMessage` — that card is the one artefact of the paste that IS
    /// reachable from a `ChatMessage`. A draft-stage message (buffer still
    /// populated, literal still in the text) is deliberately NOT matched: that
    /// one edits fine.
    func messageHasPastedRef(_ idx: Int) -> Bool {
        guard idx >= 0, idx < messages.count else { return false }
        // Exact: only pasted refs live under the dedicated subdir, so this
        // cannot mistake a user-uploaded .txt for pasted content (which would
        // wrongly block editing a perfectly ordinary message).
        return messages[idx].attachments.contains {
            $0.path.contains("/\(PastePlaceholder.mediaSubdir)/")
        }
    }

    /// Chip-row removal: drop the buffer entry AND strip its exact literal
    /// from the draft so no orphan `[pasted#N]` noise is left behind. Uses
    /// plain string replacement of the full literal — `#1` cannot bite into
    /// `#12` because the trailing `]` is part of the needle.
    func removePastedText(id: Int) {
        pastedTexts.removeAll { $0.id == id }
        let literal = PastePlaceholder.literal(for: id)
        if inputText.contains(literal) {
            inputText = inputText.replacingOccurrences(of: literal, with: "")
        }
        logger.info("[PastePlaceholder] removed #\(id), buffer=\(self.pastedTexts.count)")
    }

    private var attachmentCacheDir: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return caches.appendingPathComponent("InputAttachments")
    }

    /// Save an image (from camera, drag-drop, or paste) to Caches and add as attachment.
    /// Encodes losslessly as PNG to preserve transparency and pixel fidelity for downstream
    /// tasks. Falls back to JPEG only if PNG encoding fails (extremely rare).
    /// When you have the original file bytes (e.g. from PhotosPicker `loadTransferable`),
    /// prefer ``addImageAttachment(data:fileExtension:originalDate:)`` so the original
    /// encoding is preserved verbatim.
    func addImageAttachment(_ image: UIImage, originalDate: Date? = nil) {
        let fm = FileManager.default
        let dir = attachmentCacheDir
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        // [T-ios-camera-capture-image-too-large] Built-in camera hands us a
        // full-resolution UIImage (~12MP). Saving it as lossless PNG /
        // jpegData(1.0) produced 15–25 MB attachments (GH#33 / #565), while
        // photo-library / screenshot images — which arrive as already-compressed
        // bytes — weigh only a few hundred KB to a few MB. Downsample + JPEG-
        // encode the capture so a single photo lands around 1–3 MB, matching the
        // library path. Fall back to a plain JPEG encode only if downsampling
        // can't produce data.
        guard let data = Self.downsampledCameraJPEG(image)
                ?? image.jpegData(compressionQuality: 0.82) else {
            return
        }
        let ext = "jpg"
        let fileName = "photo_\(UUID().uuidString.prefix(8)).\(ext)"
        let url = dir.appendingPathComponent(fileName)
        do {
            try data.write(to: url)
            if let date = originalDate {
                try? fm.setAttributes([.creationDate: date, .modificationDate: date], ofItemAtPath: url.path)
            }
            attachments.append(InputAttachment(fileName: fileName, cacheURL: url, kind: .image))
        } catch {
            logger.error("Failed to cache image attachment: \(error.localizedDescription)")
        }
    }

    /// Save an image attachment from raw file bytes (preferred when the source
    /// provides the original encoded data, e.g. PhotosPicker `loadTransferable(type: Data.self)`).
    /// The bytes are written verbatim — no decode/re-encode — so PNG transparency,
    /// HEIC, animated GIFs, and exact pixel data are preserved for downstream tasks.
    /// `fileExtension` should be the lowercase extension (e.g. "png", "heic", "jpg");
    /// if missing/unrecognised, magic bytes are sniffed.
    func addImageAttachment(data: Data, fileExtension: String?, originalDate: Date? = nil) {
        let fm = FileManager.default
        let dir = attachmentCacheDir
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let knownImageExts = ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff"]
        let normalizedExt: String = {
            if let e = fileExtension?.lowercased(), knownImageExts.contains(e) { return e }
            return Self.detectImageType(from: data) ?? "bin"
        }()
        let fileName = "photo_\(UUID().uuidString.prefix(8)).\(normalizedExt)"
        let url = dir.appendingPathComponent(fileName)
        do {
            try data.write(to: url)
            if let date = originalDate {
                try? fm.setAttributes([.creationDate: date, .modificationDate: date], ofItemAtPath: url.path)
            }
            attachments.append(InputAttachment(fileName: fileName, cacheURL: url, kind: .image))
        } catch {
            logger.error("Failed to cache image attachment data: \(error.localizedDescription)")
        }
    }

    // MARK: - Photo-picker placeholder + concurrent-load support

    /// Insert N `.loading` placeholder chips immediately (one per picked photo /
    /// video) so the user sees their selection the instant the picker dismisses,
    /// before any bytes load. Returns the placeholder IDs in order so the caller
    /// can resolve each one as its concurrent load finishes.
    func addLoadingPlaceholders(kinds: [InputAttachment.Kind]) -> [UUID] {
        let placeholders = kinds.map { InputAttachment.loadingPlaceholder(id: UUID(), kind: $0) }
        attachments.append(contentsOf: placeholders)
        return placeholders.map(\.id)
    }

    /// Resolve a `.loading` image placeholder with freshly loaded bytes: write
    /// the file and flip the chip to `.ready` in place (no reordering, so photos
    /// keep their picked order). If the placeholder was already removed by the
    /// user, the bytes are simply dropped.
    func finalizeImagePlaceholder(id: UUID, data: Data, fileExtension: String?, originalDate: Date? = nil) {
        guard let idx = attachments.firstIndex(where: { $0.id == id }) else { return }
        let fm = FileManager.default
        let dir = attachmentCacheDir
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let knownImageExts = ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff"]
        let normalizedExt: String = {
            if let e = fileExtension?.lowercased(), knownImageExts.contains(e) { return e }
            return Self.detectImageType(from: data) ?? "bin"
        }()
        let fileName = "photo_\(UUID().uuidString.prefix(8)).\(normalizedExt)"
        let url = dir.appendingPathComponent(fileName)
        do {
            try data.write(to: url)
            if let date = originalDate {
                try? fm.setAttributes([.creationDate: date, .modificationDate: date], ofItemAtPath: url.path)
            }
            // Re-find the index — the array may have shifted while we were on a
            // background hop — then mutate in place.
            guard let i = attachments.firstIndex(where: { $0.id == id }) else { return }
            attachments[i].fileName = fileName
            attachments[i].cacheURL = url
            attachments[i].kind = .image
            attachments[i].loadState = .ready
        } catch {
            logger.error("Failed to cache picked image: \(error.localizedDescription)")
            markPlaceholderFailed(id: id)
        }
    }

    /// Resolve a `.loading` video placeholder by copying the loaded file in and
    /// flipping to `.ready`. Mirrors `addFileAttachment` but targets an existing
    /// placeholder so the chip doesn't jump to the end.
    func finalizeVideoPlaceholder(id: UUID, from sourceURL: URL, originalDate: Date? = nil) {
        guard attachments.contains(where: { $0.id == id }) else { return }
        let fm = FileManager.default
        let dir = attachmentCacheDir
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let ext = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension
        let fileName = "video_\(UUID().uuidString.prefix(8)).\(ext)"
        let destURL = dir.appendingPathComponent(fileName)
        do {
            try? fm.removeItem(at: destURL)
            try fm.copyItem(at: sourceURL, to: destURL)
            if let date = originalDate {
                try? fm.setAttributes([.creationDate: date, .modificationDate: date], ofItemAtPath: destURL.path)
            }
            guard let i = attachments.firstIndex(where: { $0.id == id }) else { return }
            attachments[i].fileName = fileName
            attachments[i].cacheURL = destURL
            attachments[i].kind = .video
            attachments[i].loadState = .ready
        } catch {
            logger.error("Failed to cache picked video: \(error.localizedDescription)")
            markPlaceholderFailed(id: id)
        }
    }

    /// Flip a placeholder to `.failed` so its chip shows an error state the user
    /// can dismiss. Leaves successfully-loaded siblings untouched.
    func markPlaceholderFailed(id: UUID) {
        guard let idx = attachments.firstIndex(where: { $0.id == id }) else { return }
        attachments[idx].loadState = .failed
    }

    /// True while any attachment is still loading — used to gate the send button.
    var hasLoadingAttachments: Bool {
        attachments.contains { $0.loadState == .loading }
    }

    /// Save a file picked via document picker to Caches and add as attachment.
    func addFileAttachment(from sourceURL: URL, originalDate: Date? = nil) {
        let fm = FileManager.default
        let dir = attachmentCacheDir
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let fileName = sourceURL.lastPathComponent
        let destURL = dir.appendingPathComponent("\(UUID().uuidString.prefix(8))_\(fileName)")
        do {
            // sourceURL may be security-scoped
            let accessed = sourceURL.startAccessingSecurityScopedResource()
            defer { if accessed { sourceURL.stopAccessingSecurityScopedResource() } }
            try fm.copyItem(at: sourceURL, to: destURL)

            if let date = originalDate {
                try? fm.setAttributes([.creationDate: date, .modificationDate: date], ofItemAtPath: destURL.path)
            }

            // Classify the attachment. Files-app picks can surface images whose
            // UTType reports only public.data / whose name lacks a recognisable
            // extension (GH report: PNG from Files rendered the generic doc chip
            // while the same PNG from Photos previewed fine). Decide in order:
            //   1. the file's declared content type (resource values on the local
            //      copy — no security scope needed),
            //   2. the extension list,
            //   3. magic-byte sniff of the copied file's header (cheap 16-byte
            //      read — never loads the whole file).
            let kind: InputAttachment.Kind
            let ext = sourceURL.pathExtension.lowercased()
            let imageExts = ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff"]
            let videoExts = ["mp4", "mov", "m4v", "avi", "mkv"]
            let contentType = try? destURL.resourceValues(forKeys: [.contentTypeKey]).contentType
            if contentType?.conforms(to: .image) == true || imageExts.contains(ext) {
                kind = .image
            } else if contentType?.conforms(to: .movie) == true || videoExts.contains(ext) {
                kind = .video
            } else if Self.detectImageType(atFileURL: destURL) != nil {
                kind = .image
            } else {
                kind = .document
            }
            attachments.append(InputAttachment(fileName: fileName, cacheURL: destURL, kind: kind))
        } catch {
            logger.error("Failed to cache file attachment: \(error.localizedDescription)")
        }
    }

    /// Add an attachment from raw data (e.g. from Shortcuts IntentFile).
    /// Writes data to the attachment cache and appends to the attachments list.
    /// When the filename lacks a recognisable extension (common when receiving
    /// photos from Shortcuts variables), the data's magic bytes are inspected
    /// to determine the real file type and a correct extension is appended.
    func addDataAttachment(data: Data, fileName: String) {
        let fm = FileManager.default
        let dir = attachmentCacheDir
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        // Resolve kind and ensure the filename has a matching extension
        var resolvedName = fileName
        let ext = (fileName as NSString).pathExtension.lowercased()
        let kind: InputAttachment.Kind

        if ["jpg", "jpeg", "png", "gif", "webp", "heic"].contains(ext) {
            kind = .image
        } else if ["mp4", "mov", "m4v", "avi", "mkv"].contains(ext) {
            kind = .video
        } else if let detected = Self.detectImageType(from: data) {
            // No recognised extension — sniff magic bytes
            kind = .image
            if ext.isEmpty {
                resolvedName = "\(fileName).\(detected)"
            } else {
                // Has an unrecognised extension — replace it
                let stem = (fileName as NSString).deletingPathExtension
                resolvedName = "\(stem).\(detected)"
            }
        } else {
            kind = .document
        }

        let safeName = "\(UUID().uuidString.prefix(8))_\(resolvedName)"
        let url = dir.appendingPathComponent(safeName)
        do {
            try data.write(to: url)
            attachments.append(InputAttachment(fileName: resolvedName, cacheURL: url, kind: kind))
        } catch {
            logger.error("Failed to cache data attachment: \(error.localizedDescription)")
        }
    }

    /// Detect image format from data magic bytes. Returns a file extension string or nil.
    static func detectImageType(from data: Data) -> String? {
        guard data.count >= 4 else { return nil }
        let bytes = [UInt8](data.prefix(12))

        // JPEG: FF D8 FF
        if bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF { return "jpg" }
        // PNG: 89 50 4E 47
        if bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 { return "png" }
        // GIF: 47 49 46 38
        if bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38 { return "gif" }
        // WebP: RIFF....WEBP
        if data.count >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
            && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50 { return "webp" }
        // HEIC/HEIF: ....ftypheic or ....ftypmif1 etc.
        if data.count >= 12 && bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70 {
            let brand = String(bytes: Array(bytes[8..<12]), encoding: .ascii) ?? ""
            if brand.hasPrefix("heic") || brand.hasPrefix("heix") || brand.hasPrefix("mif1") { return "heic" }
        }
        // BMP: 42 4D ("BM")
        if bytes[0] == 0x42 && bytes[1] == 0x4D { return "bmp" }
        // TIFF: II*\0 (little-endian) or MM\0* (big-endian)
        if (bytes[0] == 0x49 && bytes[1] == 0x49 && bytes[2] == 0x2A && bytes[3] == 0x00)
            || (bytes[0] == 0x4D && bytes[1] == 0x4D && bytes[2] == 0x00 && bytes[3] == 0x2A) { return "tiff" }
        return nil
    }

    /// File-URL variant of ``detectImageType(from:)`` — reads only the first
    /// 16 bytes so classifying a multi-hundred-MB file never loads it into
    /// memory.
    static func detectImageType(atFileURL url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 16), head.count >= 4 else { return nil }
        return detectImageType(from: head)
    }

    func removeAttachment(_ attachment: InputAttachment) {
        try? FileManager.default.removeItem(at: attachment.cacheURL)
        attachments.removeAll { $0.id == attachment.id }
    }

    /// Move an attachment from one position to another (drag-to-reorder).
    func moveAttachment(fromID: UUID, toID: UUID) {
        guard fromID != toID,
              let fromIndex = attachments.firstIndex(where: { $0.id == fromID }),
              let toIndex = attachments.firstIndex(where: { $0.id == toID }) else { return }
        attachments.move(fromOffsets: IndexSet(integer: fromIndex),
                         toOffset: toIndex > fromIndex ? toIndex + 1 : toIndex)
    }

    /// Return a unique filename inside `dir` by appending `_1`, `_2`, … when a collision exists.
    static func uniqueFileName(for name: String, in dir: URL) -> String {
        let fm = FileManager.default
        if !fm.fileExists(atPath: dir.appendingPathComponent(name).path) { return name }
        let nsName = name as NSString
        let stem = nsName.deletingPathExtension
        let ext = nsName.pathExtension
        var counter = 1
        while true {
            let candidate = ext.isEmpty ? "\(stem)_\(counter)" : "\(stem)_\(counter).\(ext)"
            if !fm.fileExists(atPath: dir.appendingPathComponent(candidate).path) { return candidate }
            counter += 1
        }
    }

    /// Clean up attachment chips from the UI (call after send).
    /// File cleanup is deferred — the async send block handles deletion after reading data.
    func clearAttachments() {
        attachments.removeAll()
    }

    /// Delete cached attachment files from disk (call after data has been read in async send block).
    nonisolated static func cleanupAttachmentFiles(_ items: [InputAttachment]) {
        let fm = FileManager.default
        for a in items {
            try? fm.removeItem(at: a.cacheURL)
        }
    }

    /// Process a list of InputAttachments: copy files to uploadsDir, build AttachmentMeta list,
    /// and build AgentContentPart list (image data parts + XML metadata block).
    /// Does NOT clean up the source cache files — caller is responsible.
    func processAttachments(
        _ attachments: [InputAttachment],
        uploadsDir: URL,
        nowStr: String
    ) -> (parts: [AgentContentPart], metas: [AttachmentMeta]) {
        let fm = FileManager.default
        var parts: [AgentContentPart] = []
        var metas: [AttachmentMeta] = []

        // The user's latest attachments always take priority: inline up to
        // `kImageContextKeepCount` of them regardless of how many images are
        // already in `agentHistory`. Older history images get evicted by the
        // next `trimOldImagesFromHistory` pass before the API call. Without
        // this, a long agent loop that already filled the image quota would
        // silently placeholder fresh user uploads — and the model, seeing
        // only `[image omitted ...]`, falls back to OCR or refuses to answer.
        let inlineBudget = Self.kImageContextKeepCount
        var inlinedImages = 0

        for (i, attachment) in attachments.enumerated() {
            let fileExists = fm.fileExists(atPath: attachment.cacheURL.path)
            logger.info("📎[QUEUE-DRAIN] attachment[\(i)] kind=\(String(describing: attachment.kind)) file=\(attachment.fileName) cacheExists=\(fileExists)")

            guard fileExists else {
                logger.error("📎[QUEUE-DRAIN]   FAILED — source missing at \(attachment.cacheURL.path)")
                continue
            }

            // [T-ios-attachment-oom-bg-kill] Stream the file to the uploads dir
            // and read its size from filesystem attributes — do NOT load it into
            // memory. Mirrors the SEND-ASYNC path fix: a large non-image
            // attachment (e.g. a 368 MB sysdiagnose) here would spike the app
            // footprint by its full size and let iOS jetsam-SIGKILL the process
            // on backgrounding. This QUEUE-DRAIN path runs for messages sent
            // WHILE the agent is busy (queued prompts), so it must be fixed too.
            let attrs = try? fm.attributesOfItem(atPath: attachment.cacheURL.path)
            // Preserve original file date if available (e.g. PHAsset creation date)
            let fileDate: Date = (attrs?[.modificationDate] as? Date) ?? Date()
            let fileSize = (attrs?[.size] as? Int) ?? 0

            let safeName = Self.uniqueFileName(for: attachment.fileName, in: uploadsDir)
            let destURL = uploadsDir.appendingPathComponent(safeName)
            do {
                try fm.copyItem(at: attachment.cacheURL, to: destURL)
            } catch {
                logger.error("📎[QUEUE-DRAIN]   FAILED to copy \(attachment.cacheURL.lastPathComponent) → \(destURL.path): \(error.localizedDescription)")
                continue
            }
            try? fm.setAttributes([.creationDate: fileDate, .modificationDate: fileDate], ofItemAtPath: destURL.path)
            let linuxPath = "/var/minis/attachments/uploads/\(safeName)"
            let meta = AttachmentMeta(path: linuxPath, size: fileSize, modified: fileDate)
            metas.append(meta)
            logger.info("📎[QUEUE-DRAIN]   saved \(safeName): \(fileSize) bytes → \(linuxPath)")

            // [T-ios-attachment-oom-bg-kill] Only IMAGES need bytes in memory
            // (resize/inline). Non-image attachments are already persisted above.
            guard attachment.kind == .image else { continue }
            guard let data = try? Data(contentsOf: attachment.cacheURL) else {
                logger.error("📎[QUEUE-DRAIN]   image load failed (kept on disk) \(attachment.cacheURL.path)")
                continue
            }

            if attachment.kind == .image {
                if inlinedImages < inlineBudget {
                    let resized = Self.resizedImageData(data, maxLongEdge: 2000) ?? data
                    let ext = attachment.cacheURL.pathExtension.lowercased()
                    let mime: String
                    switch ext {
                    case "png": mime = resized.count == data.count ? "image/png" : "image/jpeg"
                    case "gif": mime = "image/gif"
                    case "webp": mime = "image/webp"
                    default: mime = "image/jpeg"
                    }
                    parts.append(.text("[attached image: \(linuxPath)]"))
                    parts.append(.imageData(data: resized, mimeType: mime, linuxPath: linuxPath))
                    inlinedImages += 1
                    logger.info("📎[QUEUE-DRAIN]   image \(inlinedImages)/\(inlineBudget) inlined for inference: \(resized.count) bytes, mime=\(mime)")
                } else {
                    let placeholder = Self.imagePlaceholderText(data: data, originalPath: linuxPath, snapshotPath: nil)
                    parts.append(.text(placeholder))
                    logger.info("📎[QUEUE-DRAIN]   image not inlined (budget \(inlineBudget) exhausted), placeholder added for \(linuxPath)")
                }
            }
        }

        // Build <user-attached-files> XML block
        if !metas.isEmpty {
            var xml = "<user-attached-files>\n"
            for meta in metas {
                xml += "  <file path=\"\(meta.path)\" url=\"\(meta.minisURL)\" size=\"\(meta.size)\" modified=\"\(nowStr)\" />\n"
            }
            xml += "</user-attached-files>"
            parts.append(.text(xml))
        }

        // When some images were omitted, add a tip so the model knows to batch-read
        let totalImageAttachments = attachments.filter { $0.kind == .image }.count
        if totalImageAttachments > inlinedImages {
            let omitted = totalImageAttachments - inlinedImages
            parts.append(.text(
                "<system-reminder>Only \(inlinedImages) of \(totalImageAttachments) images are inlined above."
                + " The remaining \(omitted) are saved to disk — use read_image to view them."
                + " To stay within the context image limit (\(Self.kImageContextKeepCount)),"
                + " process images in batches: read a batch, analyze, then summarize your findings"
                + " before reading the next batch.</system-reminder>"
            ))
        }

        return (parts, metas)
    }
}
