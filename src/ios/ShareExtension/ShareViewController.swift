import UIKit

/// The Share Extension's principal view controller.
class ShareViewController: UIViewController {

    /// Set to true once async processing + redirect is done, so viewDidAppear can dismiss.
    private var processingDone = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        NSLog("[ShareExt] viewDidLoad — starting processing")

        guard SharedContainerStore.isAppGroupAvailable else {
            NSLog("[ShareExt] App Group unavailable; cancelling share without reporting delivery")
            extensionContext?.cancelRequest(withError: NSError(
                domain: NSCocoaErrorDomain,
                code: NSFileWriteNoPermissionError,
                userInfo: [NSLocalizedDescriptionKey: "Sharing is unavailable because this signing configuration cannot access the shared container."]
            ))
            return
        }

        Task { @MainActor in
            let vm = ShareViewModel()
            let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
            NSLog("[ShareExt] inputItems count: %d", items.count)
            await vm.processExtensionItems(items)
            let saved = vm.save()
            NSLog("[ShareExt] save() returned: %@", saved ? "true" : "false")

            // Verify what was saved
            if let pending = SharedContainerStore.loadPendingShare() {
                NSLog("[ShareExt] Verified pending share: %d items", pending.items.count)
                for (i, item) in pending.items.enumerated() {
                    NSLog("[ShareExt]   item[%d] kind=%@ value=%@", i, item.kind.rawValue, String(item.value.prefix(100)))
                }
            } else {
                NSLog("[ShareExt] WARNING: loadPendingShare returned nil after save!")
            }

            // Verify shared file directory
            if let dir = SharedContainerStore.sharedFileDirectory {
                let files = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
                NSLog("[ShareExt] Shared file dir: %@ — files: %@", dir.path, files.description)
            } else {
                NSLog("[ShareExt] WARNING: sharedFileDirectory is nil!")
            }

            // Verify sharedDefaults accessible
            if let defaults = SharedContainerStore.sharedDefaults {
                let hasKey = defaults.data(forKey: "pendingShare") != nil
                NSLog("[ShareExt] sharedDefaults accessible, pendingShare key present: %@", hasKey ? "YES" : "NO")
            } else {
                NSLog("[ShareExt] WARNING: sharedDefaults is nil — App Group not configured?")
            }

            redirectToHostApp()

            // Mark done and dismiss — if viewDidAppear already ran, dismiss now
            processingDone = true
            NSLog("[ShareExt] Processing done, completing request")
            extensionContext?.completeRequest(returningItems: [])
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Only dismiss if async processing is already done.
        // If not done yet, the Task completion above will dismiss.
        if processingDone {
            NSLog("[ShareExt] viewDidAppear — processingDone, completing request")
            extensionContext?.completeRequest(returningItems: [])
        } else {
            NSLog("[ShareExt] viewDidAppear — processing still in progress, deferring dismiss")
        }
    }

    // MARK: - Redirect to main app

    private func redirectToHostApp() {
        guard let url = URL(string: "minis://share") else {
            NSLog("[ShareExt] ERROR: Failed to create minis://share URL")
            return
        }

        let selectorOpenURL = sel_registerName("openURL:")
        var responder: UIResponder? = self
        var found = false
        while responder != nil {
            if let application = responder as? UIApplication {
                NSLog("[ShareExt] Found UIApplication in responder chain, opening URL")
                if #available(iOS 18.0, *) {
                    application.open(url, options: [:], completionHandler: nil)
                } else {
                    application.perform(selectorOpenURL, with: url)
                }
                found = true
                break
            }
            responder = responder?.next
        }
        if !found {
            NSLog("[ShareExt] WARNING: UIApplication not found in responder chain!")
        }
    }
}
