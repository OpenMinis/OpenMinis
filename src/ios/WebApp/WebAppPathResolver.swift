import Foundation

private let resolverLogger = AppLogger(category: "WebAppResolver")

/// Translates a `(scope, scopeContext, relativeHtmlPath)` triple into the
/// host file URL of the HTML and a base directory the WebView should be
/// granted read-access to. The base is the directory we hand to
/// `WKWebView.loadFileURL(_:allowingReadAccessTo:)` so relative
/// `<script src="…">` / `<link href="…">` / `<img src="…">` references
/// inside the HTML can load.
///
/// All inputs are sandbox-relative so the row survives container UUID
/// rotation across reinstalls — the host base is recomputed at launch.
enum WebAppPathResolver {
    struct Resolved {
        let htmlURL: URL
        /// The directory passed to `loadFileURL(_:allowingReadAccessTo:)`.
        /// Always a parent of `htmlURL` (often the same dir). For mounts
        /// it's the mount root; for sessions it's the session root so
        /// nested asset folders work.
        let readAccessRoot: URL
    }

    enum ResolveError: Error, CustomStringConvertible {
        case missingContext
        case unknownMount
        case sourceMissing
        case escapesScope

        var description: String {
            switch self {
            case .missingContext: return "WebApp shortcut is missing scope context (sessionId or mountId)."
            case .unknownMount:   return "Mount no longer exists or hasn't been authorized in this run."
            case .sourceMissing:  return "Source HTML file is missing — the file may have been moved or deleted."
            case .escapesScope:   return "WebApp shortcut path escapes its scope and was rejected."
            }
        }
    }

    /// Resolve a shortcut to a host URL pair. Caller is responsible for
    /// presenting `ResolveError` to the user (e.g. an offer to remove the
    /// stale tile). Reads `MountedFoldersManager.shared` on `MainActor`
    /// when the scope is `.mount`, so call from main.
    @MainActor
    static func resolve(_ shortcut: WebAppShortcut) throws -> Resolved {
        let base: URL
        switch shortcut.pathScope {
        case .sessionAttachment:
            guard let sid = shortcut.scopeContext else { throw ResolveError.missingContext }
            base = AIChatViewModel.minisAttachmentsPersistentDir(for: sid)
        case .sessionWorkspace:
            guard let sid = shortcut.scopeContext else { throw ResolveError.missingContext }
            base = AIChatViewModel.minisWorkspacePersistentDir(for: sid)
        case .shared:
            base = AIChatViewModel.minisSharedPersistentDir
        case .mount:
            guard let raw = shortcut.scopeContext, let mountId = UUID(uuidString: raw) else {
                throw ResolveError.missingContext
            }
            guard let mountRoot = MountedFoldersManager.shared.resolvedURL(for: mountId) else {
                throw ResolveError.unknownMount
            }
            base = mountRoot
        }

        // Strip any leading slash so appendingPathComponent doesn't anchor
        // the stored path absolutely (which would defeat the scope).
        let trimmed = shortcut.htmlPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        // [T-ios-webapp-shared-blackscreen] Normalise the html URL and the
        // scope root THE SAME WAY, and do it with `resolvingSymlinksInPath()`
        // rather than `standardizedFileURL`.
        //
        // `standardizedFileURL` only collapses "." / ".." lexically; it leaves
        // symlinks in place. The App Group container backing `shared` (and
        // `mount`) is reached through symlinked path segments, so the old code
        // — which standardized `candidate` but passed `base` to
        // `readAccessRoot` completely un-normalised — handed WKWebView two
        // paths of different shape. WebKit resolves both to real paths when it
        // validates the sandbox grant, decided the file was not under the
        // granted root, and refused the load with
        //     "Ignoring request to load this main resource because it is
        //      outside the sandbox"
        // which the user sees as a black screen with only the floating menu.
        // `workspace`/`attachments` escaped this because their base
        // (Library/MinisChat/…) has no symlinked segment, so the two shapes
        // happened to agree.
        //
        // Resolving BEFORE the escape check below also closes a real hole:
        // a symlink planted inside the scope that points outside it used to
        // pass the lexical prefix test, because the old comparison never
        // looked through it.
        let candidate = base.appendingPathComponent(trimmed)
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let scopeRoot = base.standardizedFileURL.resolvingSymlinksInPath()

        // Reject paths that escape the scope (e.g. "../../other-session/..").
        // Both sides are now fully resolved, so the prefix comparison sees
        // through "..", "." and symlinks alike.
        if !candidate.path.hasPrefix(scopeRoot.path + "/") && candidate.path != scopeRoot.path {
            resolverLogger.warning("resolve: path escapes scope; candidate=\(candidate.path) scopeRoot=\(scopeRoot.path)")
            throw ResolveError.escapesScope
        }

        if !FileManager.default.fileExists(atPath: candidate.path) {
            throw ResolveError.sourceMissing
        }

        // For sessions, grant read access at the session root so the HTML
        // can pull from sibling folders (workspace/, attachments/, etc. —
        // common when the agent generates a multi-file site under the
        // session). For mounts we already have the mount root. For shared
        // the shared dir itself is the natural sandbox.
        // [T-ios-webapp-shared-blackscreen] Must be normalised EXACTLY like
        // `candidate` above — same operations, same order. Any asymmetry here
        // is what produced the black screen; `candidate` is already resolved,
        // so an unresolved root cannot be a prefix of it.
        let readAccessRoot: URL
        switch shortcut.pathScope {
        case .sessionAttachment, .sessionWorkspace:
            // <minisPersistentBase>/<sid>/   ← parent of attachments/ and workspace/
            readAccessRoot = base.deletingLastPathComponent()
                .standardizedFileURL
                .resolvingSymlinksInPath()
        case .shared, .mount:
            readAccessRoot = scopeRoot   // already standardized + symlink-resolved
        }

        // Cheap invariant: the grant must actually cover the file. If this
        // ever trips, WebKit would reject the load as "outside the sandbox"
        // and the user would get the black screen again — better to fail with
        // a named error than to render nothing.
        guard candidate.path == readAccessRoot.path
                || candidate.path.hasPrefix(readAccessRoot.path + "/") else {
            resolverLogger.warning("resolve: html not under readAccessRoot; html=\(candidate.path) root=\(readAccessRoot.path)")
            throw ResolveError.escapesScope
        }

        return Resolved(htmlURL: candidate, readAccessRoot: readAccessRoot)
    }
}
