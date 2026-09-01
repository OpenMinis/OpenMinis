import Foundation
import os.log

private let logger = AppLogger(category: "ModelGroupRouter")

/// Resolves which ModelEntry to use from a ModelGroup based on the routing strategy.
@MainActor
enum ModelGroupRouter {

    /// Resolve the initial model entry for a session from a group.
    /// - Parameters:
    ///   - group: The model group to resolve from.
    ///   - sessionId: Used for deterministic load-balance hashing.
    ///   - store: The provider config store to look up entries.
    /// - Returns: The resolved ModelEntry id, or nil if no enabled member is available.
    static func resolve(group: ModelGroup, sessionId: String, store: ProviderConfigStore) -> String? {
        let available = availableEntryIds(group: group, store: store)
        logger.info("🔀ROUTE resolve group=\(group.name) strategy=\(group.strategy.rawValue) members=\(group.memberEntryIds) available=\(available)")
        guard !available.isEmpty else {
            logger.warning("🔀ROUTE resolve: no available entries")
            return nil
        }

        switch group.strategy {
        case .fallback:
            logger.info("🔀ROUTE resolve fallback → \(available.first!)")
            return available.first

        case .loadBalance:
            let index = abs(sessionId.hashValue) % available.count
            logger.info("🔀ROUTE resolve loadBalance index=\(index) → \(available[index])")
            return available[index]
        }
    }

    /// Get the next fallback entry after the current one failed.
    /// Cycles forward from the current entry, wrapping around to entries before it,
    /// so that one full round of all available entries is attempted per reasoning turn.
    /// Returns nil if no more fallbacks are available.
    static func nextFallback(
        group: ModelGroup,
        currentEntryId: String,
        store: ProviderConfigStore
    ) -> String? {
        let available = availableEntryIds(group: group, store: store)
        logger.info("🔀ROUTE nextFallback current=\(currentEntryId) available=\(available)")
        guard let currentIdx = available.firstIndex(of: currentEntryId) else {
            // Current entry not found in available list — try first available
            logger.warning("🔀ROUTE nextFallback: current entry not in available list, falling back to first=\(available.first ?? "nil")")
            return available.first
        }

        // Try remaining entries after current, then wrap around to entries before current.
        // This ensures each reasoning turn can cycle through all providers exactly once.
        let remaining = available[(currentIdx + 1)...]
        if let next = remaining.first {
            logger.info("🔀ROUTE nextFallback: currentIdx=\(currentIdx) → next=\(next)")
            return next
        }

        let before = available[..<currentIdx]
        if let wrapped = before.first {
            logger.info("🔀ROUTE nextFallback: wrap-around → \(wrapped)")
            return wrapped
        }

        logger.info("🔀ROUTE nextFallback: no more entries (single entry group)")
        return nil
    }

    /// Returns group members that were filtered out (unavailable) with reasons.
    static func unavailableMembers(
        group: ModelGroup, store: ProviderConfigStore
    ) -> [(model: String, instance: String, reason: String)] {
        var result: [(model: String, instance: String, reason: String)] = []
        for entryId in group.memberEntryIds {
            guard let entry = store.entry(for: entryId) else { continue }
            guard let inst = store.instance(for: entry.providerInstanceId) else { continue }
            let label = inst.label
            if entry.isHidden {
                result.append((model: entry.model.displayName, instance: label, reason: AppLocalized("Hidden")))
            } else if !inst.isEnabled {
                result.append((model: entry.model.displayName, instance: label, reason: AppLocalized("Disabled")))
            } else if !inst.hasAnyCredential {
                result.append((model: entry.model.displayName, instance: label, reason: AppLocalized("Not logged in")))
            }
        }
        return result
    }

    // MARK: - Private

    /// Filter group members to those that are enabled, whose provider instance
    /// is enabled, AND whose instance has a usable credential. The credential
    /// gate is what stops a no-token OAuth instance — common after iCloud
    /// sync brings the instance metadata across without bringing its
    /// per-device OAuth tokens — from winning the fallback race and producing
    /// a 403 `OAuth authentication is currently not allowed for this
    /// organization`. The factory would still build a provider for it, but
    /// the request would fail immediately and waste a fallback slot.
    private static func availableEntryIds(group: ModelGroup, store: ProviderConfigStore) -> [String] {
        group.memberEntryIds.filter { entryId in
            guard let entry = store.entry(for: entryId) else {
                logger.warning("🔀ROUTE filter: entry \(entryId) not found in store")
                return false
            }
            guard !entry.isHidden else {
                logger.info("🔀ROUTE filter: entry \(entryId) is hidden")
                return false
            }
            guard let instance = store.instance(for: entry.providerInstanceId) else {
                logger.warning("🔀ROUTE filter: instance \(entry.providerInstanceId) not found for entry \(entryId)")
                return false
            }
            guard instance.isEnabled else {
                logger.info("🔀ROUTE filter: instance \(instance.label) (\(instance.id)) disabled for entry \(entryId)")
                return false
            }
            guard instance.hasAnyCredential else {
                logger.info("🔀ROUTE filter: instance \(instance.label) (\(instance.id)) has no credential for entry \(entryId)")
                return false
            }
            return true
        }
    }
}
