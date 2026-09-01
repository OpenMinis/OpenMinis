//
//  ConfigOffloadBridge.swift
//  MinisApp
//
//  Swift bridge for `minis-config`.
//  All registry / gate / audit logic lives here; the .m handler just
//  parses argv and routes to one of the @objc entry points below.
//

import Foundation

private let logger = AppLogger(category: "ConfigOffload")

@objc public class ConfigOffloadBridge: NSObject {

    // MARK: - Top-level enable check

    /// First-line check before doing anything else. Returns true when
    /// `permissions.minisConfig.enabled` is on (default). Off means
    /// every CLI call short-circuits with exit 126.
    @objc public static func isEnabled() -> Bool {
        let sem = DispatchSemaphore(value: 0)
        var enabled = true
        Task.detached { @MainActor in
            enabled = MinisConfigPermissionStore.shared.enabled
            sem.signal()
        }
        sem.wait()
        return enabled
    }

    /// JSON envelope for the "minis-config is disabled" error path.
    /// Includes the `user_message` so the agent relays guidance back
    /// to the user instead of silently failing or retrying.
    @objc public static func disabledErrorEnvelope() -> NSDictionary {
        return [
            "ok": false,
            "error": "permission_denied",
            "reason": "minis-config is disabled in Settings → Permissions.",
            "user_message": "I tried to change a setting but minis-config is currently disabled. You can enable it at [Settings → Permissions](minis://settings/permissions), then ask me again. Or change the setting yourself directly through the relevant Settings screen.",
        ]
    }

    /// JSON envelope for a field whose feature does not exist on this
    /// device / OS version. Distinct from permission_denied so the agent
    /// knows retrying / asking the user to enable something won't help.
    private static func unavailableErrorEnvelope(path: String, reason: String) -> NSDictionary {
        return [
            "ok": false,
            "error": "feature_unavailable",
            "reason": reason,
            "user_message": "The setting '\(path)' isn't available on this device: \(reason)",
        ]
    }

    // MARK: - Topic / list / help discovery

    /// All visible topics in the registry. Sorted alphabetical. The CLI
    /// prints these in `minis-config --help`.
    @objc public static func allTopics() -> [String] {
        let sem = DispatchSemaphore(value: 0)
        var topics: [String] = []
        Task.detached { @MainActor in
            ConfigRegistry.shared.registerBuiltinsIfNeeded()
            topics = ConfigRegistry.shared.topics()
            sem.signal()
        }
        sem.wait()
        return topics
    }

    /// All visible field metadata for a topic. Each dict carries:
    ///   path, displayName, description, schema, access, risk, revertable
    /// — plenty for the per-topic `--help` renderer.
    @objc public static func fieldsForTopic(_ topic: String) -> [NSDictionary] {
        let sem = DispatchSemaphore(value: 0)
        var out: [NSDictionary] = []
        Task.detached { @MainActor in
            ConfigRegistry.shared.registerBuiltinsIfNeeded()
            out = ConfigRegistry.shared.fields(forTopic: topic).map { f in
                [
                    "path": f.path,
                    "display_name": f.displayName,
                    "description": f.description,
                    "schema": f.valueSchema.helpDescription,
                    "access": String(describing: f.access),
                    "risk": String(describing: f.risk),
                    "revertable": f.revertable,
                ] as NSDictionary
            }
            sem.signal()
        }
        sem.wait()
        return out
    }

    // MARK: - Read

    /// Read a single field's current value. Returns
    ///   { ok: true, value: <ConfigValue JSON>, schema: ... }
    /// or { ok: false, error: ..., reason: ..., user_message: ... }.
    @objc public static func readField(path: String) -> NSDictionary {
        return readField(path: path, filter: nil, page: 0, pageSize: 0)
    }

    /// 2-arg compatibility shim — filter only, no pagination.
    @objc public static func readField(path: String,
                                       filter: String?) -> NSDictionary {
        return readField(path: path, filter: filter, page: 0, pageSize: 0)
    }

    /// Default page size used when the caller passes 0 (unset).
    private static let defaultPageSize: Int = 20
    /// Cap so a curious agent doesn't request the whole table in one
    /// page. Matches the documented `--page-size` ceiling.
    private static let maxPageSize: Int = 100

    /// Read with optional filter and pagination. Filter runs first;
    /// `pagination.total` reflects the post-filter count. Pagination
    /// only kicks in when the value is a JSON array — scalar fields
    /// pass through unchanged regardless of `page`/`pageSize`.
    ///
    /// Filter semantics: whitespace-split terms, case-insensitive AND
    /// against `candidate.jsonString().lowercased()`.
    /// Pagination: 1-based; out-of-range page returns an empty array
    /// with correct meta (no error). `pageSize <= 0` → default 20.
    /// `pageSize > 100` → clamped to 100.
    @objc public static func readField(path: String,
                                       filter: String?,
                                       page: Int,
                                       pageSize: Int) -> NSDictionary {
        let base = Self.readFieldRaw(path: path)
        guard (base["ok"] as? Bool) == true else { return base }

        // Apply filter (if any) — matches the prior --filter behavior.
        let filterText = (filter?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
        let terms: [String] = filterText.map { ft in
            ft.lowercased()
                .split(whereSeparator: { $0.isWhitespace })
                .map(String.init)
                .filter { !$0.isEmpty }
        } ?? []

        let valueJSON = (base["value"] as? String) ?? "null"
        guard let parsed = ConfigValue.decode(json: valueJSON) else {
            return base
        }

        func matches(_ candidate: ConfigValue) -> Bool {
            guard !terms.isEmpty else { return true }
            let hay = candidate.jsonString().lowercased()
            return terms.allSatisfy { hay.contains($0) }
        }

        var out: [String: Any] = base as? [String: Any] ?? [:]

        if case .array(let arr) = parsed {
            let totalRaw = arr.count
            let postFilter = terms.isEmpty ? arr : arr.filter(matches)
            let postFilterCount = postFilter.count

            // Filter telemetry (only when actually filtering).
            if !terms.isEmpty {
                out["filtered"] = true
                out["filter"] = filterText ?? ""
                out["total"] = totalRaw
                out["matched"] = postFilterCount
            }

            // Pagination: only attach meta when the caller asked for
            // it (page > 0 or pageSize > 0). Otherwise return the full
            // (possibly filtered) array unchanged for backward compat.
            let wantsPagination = page > 0 || pageSize > 0
            if wantsPagination {
                let size = min(max(pageSize > 0 ? pageSize : Self.defaultPageSize, 1), Self.maxPageSize)
                let totalPages = max(1, (postFilterCount + size - 1) / size)
                let requestedPage = page > 0 ? page : 1
                // Out-of-range pages return an empty slice with
                // correct meta — spec calls for no error.
                let pageSlice: [ConfigValue]
                if requestedPage < 1 || requestedPage > totalPages || postFilterCount == 0 {
                    pageSlice = []
                } else {
                    let start = (requestedPage - 1) * size
                    let end = min(start + size, postFilterCount)
                    pageSlice = Array(postFilter[start..<end])
                }
                out["value"] = ConfigValue.array(pageSlice).jsonString()
                let hasNext = requestedPage < totalPages && postFilterCount > 0
                let hasPrev = requestedPage > 1
                out["pagination"] = [
                    "page": requestedPage,
                    "page_size": size,
                    "total": postFilterCount,
                    "total_pages": totalPages,
                    "has_next": hasNext,
                    "has_prev": hasPrev,
                ] as [String: Any]
                out["agent_hint"] = Self.paginationHint(
                    path: path, filter: filterText,
                    page: requestedPage, pageSize: size,
                    totalPages: totalPages, total: postFilterCount,
                    pageCount: pageSlice.count
                )
            } else if !terms.isEmpty {
                // Filter only, no pagination — emit the filtered array as before.
                out["value"] = ConfigValue.array(postFilter).jsonString()
            }
        } else {
            // Scalar value — pagination ignored. Apply filter
            // pass-or-null (preserves the prior --filter contract).
            if !terms.isEmpty {
                let kept = matches(parsed) ? parsed : .null
                out["value"] = kept.jsonString()
                out["filtered"] = true
                out["filter"] = filterText ?? ""
                out["total"] = 1
                out["matched"] = (kept == .null) ? 0 : 1
            }
        }

        return out as NSDictionary
    }

    /// Build the natural-language `agent_hint` shown to the agent so
    /// it knows how to request the next page (or that there is no
    /// next page). Echoes the filter so the next-page command is
    /// self-contained.
    private static func paginationHint(path: String,
                                       filter: String?,
                                       page: Int,
                                       pageSize: Int,
                                       totalPages: Int,
                                       total: Int,
                                       pageCount: Int) -> String {
        let filterFragment: String = {
            guard let f = filter, !f.isEmpty else { return "" }
            return " --filter \"\(f)\""
        }()
        if total == 0 {
            if filter != nil && !(filter ?? "").isEmpty {
                return "No items match filter '\(filter ?? "")' under '\(path)'."
            }
            return "No items under '\(path)'."
        }
        if page > totalPages {
            return "Page \(page) is out of range (only \(totalPages) page\(totalPages == 1 ? "" : "s") available). Try: minis-config get \(path)\(filterFragment) --page \(totalPages)"
        }
        if totalPages <= 1 {
            return "Showing all \(total) item\(total == 1 ? "" : "s") (1 page)."
        }
        if page < totalPages {
            return "Showing page \(page) of \(totalPages) (\(pageCount) of \(total) items). To get more, use: minis-config get \(path)\(filterFragment) --page \(page + 1) --page-size \(pageSize)"
        }
        return "Showing page \(page) of \(totalPages) (\(pageCount) of \(total) items). This is the last page."
    }

    /// The unfiltered read implementation. Pulled out so the public
    /// `readField(path:)` and `readField(path:filter:)` entry points
    /// share the same semaphore-blocking + permission + access-check
    /// path without duplication.
    @MainActor
    private static func readFieldSync(path: String) -> NSDictionary {
        ConfigRegistry.shared.registerBuiltinsIfNeeded()
        guard MinisConfigPermissionStore.shared.enabled else {
            return Self.disabledErrorEnvelope()
        }
        guard let field = ConfigRegistry.shared.resolveField(path: path) else {
            return [
                "ok": false,
                "error": "unknown_path",
                "reason": "No registered field at '\(path)'.",
            ]
        }
        if field.access == .hidden {
            return [
                "ok": false,
                "error": "permission_denied",
                "reason": "'\(path)' is intentionally not exposed to minis-config.",
            ]
        }
        if let reason = field.unavailableReason {
            return Self.unavailableErrorEnvelope(path: path, reason: reason)
        }
        do {
            let v = try field.read()
            return [
                "ok": true,
                "value": v.jsonString(),
                "schema": field.valueSchema.helpDescription,
                "display_name": field.displayName,
            ]
        } catch {
            return [
                "ok": false,
                "error": "read_failed",
                "reason": String(describing: error),
            ]
        }
    }

    private static func readFieldRaw(path: String) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: NSDictionary = [:]
        Task.detached { @MainActor in
            result = Self.readFieldSync(path: path)
            sem.signal()
        }
        sem.wait()
        return result
    }

    // MARK: - Write (single or batch)

    /// Write one or more fields. Each item dict:
    ///   { "path": "appearance.theme", "value_json": "\"dark\"" }
    /// Returns the standard envelope including `applied[]`,
    /// `audit_ids[]`, `audit_url`, and `user_message`.
    ///
    /// Blocks the caller until the user resolves the confirmation
    /// sheet or the gate's 30 s timeout fires.
    @objc public static func writeFields(items: [NSDictionary],
                                         caption: String?,
                                         actorRaw: String,
                                         sessionId: String?) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: NSDictionary = [:]
        Task.detached { @MainActor in
            ConfigRegistry.shared.registerBuiltinsIfNeeded()
            guard MinisConfigPermissionStore.shared.enabled else {
                result = Self.disabledErrorEnvelope()
                sem.signal()
                return
            }
            result = await Self.performWriteBatch(items: items,
                                                  caption: caption,
                                                  actorRaw: actorRaw,
                                                  sessionId: sessionId)
            sem.signal()
        }
        sem.wait()
        return result
    }

    #if DEBUG
    /// [T-config-debug-rpc] Same write batch, with the confirmation gate optionally
    /// bypassed, for the `config.set` debug RPC.
    ///
    /// It is a SEPARATE, DEBUG-only overload rather than a defaulted parameter on
    /// `writeFields` so the shipping CLI signature is untouched and no release build can
    /// reach a bypass at all. `skip: false` (the RPC's default) goes through the real
    /// on-device sheet — that is the honest path, and the one worth testing; `true`
    /// exists only so an unattended run can still exercise apply/audit.
    @objc public static func writeFieldsForDebug(items: [NSDictionary],
                                                 caption: String?,
                                                 actorRaw: String,
                                                 sessionId: String?,
                                                 skipConfirmation: Bool) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: NSDictionary = [:]
        Task.detached { @MainActor in
            ConfigRegistry.shared.registerBuiltinsIfNeeded()
            guard MinisConfigPermissionStore.shared.enabled else {
                result = Self.disabledErrorEnvelope()
                sem.signal()
                return
            }
            result = await Self.performWriteBatch(items: items,
                                                  caption: caption,
                                                  actorRaw: actorRaw,
                                                  sessionId: sessionId,
                                                  skipConfirmation: skipConfirmation)
            sem.signal()
        }
        sem.wait()
        return result
    }
    #endif

    @MainActor
    private static func performWriteBatch(items: [NSDictionary],
                                          caption: String?,
                                          actorRaw: String,
                                          sessionId: String?,
                                          skipConfirmation: Bool = false) async -> NSDictionary {
        // 1. Resolve every path; reject the entire batch on any unknown
        //    or hidden path (fail fast before bothering the user).
        // Resolved enum is hoisted to class scope (search for `enum Resolved`)
        // so the audit/error helpers can pattern-match on it.
        var resolved: [Resolved] = []
        var resolvedItems: [PendingConfigChangeItem] = []
        for raw in items {
            let rawPath = (raw["path"] as? String) ?? ""
            let valueJSON = (raw["value_json"] as? String) ?? "null"

            // `.append` / `.remove` virtual writes:
            //   `<arrayPath>.append <element-json>` — append element.
            //   `<arrayPath>.remove <element-json>` — drop every
            //     occurrence of the matching element (by JSON
            //     equality).
            // Both resolve to the parent array field, read its current
            // value, compute the new array, and write the whole array.
            // The audit row is keyed off the underlying field path so
            // revert restores the pre-op list.
            let isAppend = rawPath.hasSuffix(".append")
            let isAdd = !isAppend && rawPath.hasSuffix(".add")
            let isRemove = !isAppend && !isAdd && rawPath.hasSuffix(".remove")
            let resolvePath: String
            if isAppend {
                resolvePath = String(rawPath.dropLast(".append".count))
            } else if isAdd {
                resolvePath = String(rawPath.dropLast(".add".count))
            } else if isRemove {
                resolvePath = String(rawPath.dropLast(".remove".count))
            } else {
                resolvePath = rawPath
            }

            // Collection-level add/remove short-circuit. When the
            // suffix is .add / .append / .remove AND the prefix is
            // exactly a registered collection's basePath (no further
            // dotted segments), dispatch to the ConfigCollection's
            // add(_:)/remove(id:) instead of trying to resolve a field.
            // This is how `minis-config set models.append <json>` and
            // `minis-config set models.remove <entry-id>` reach
            // ModelsCollection.add / .remove (T-minis-config-models-add).
            if (isAppend || isAdd || isRemove),
               let collection = ConfigRegistry.shared.collection(basePath: resolvePath) {
                guard let parsedValue = ConfigValue.decode(json: valueJSON) else {
                    return [
                        "ok": false, "error": "invalid_value",
                        "reason": "Value JSON is not parseable for '\(rawPath)'.",
                    ]
                }
                if isRemove {
                    guard collection.removable else {
                        return [
                            "ok": false, "error": "permission_denied",
                            "reason": "Collection '\(resolvePath)' does not support remove.",
                        ]
                    }
                    // For removes the payload is the child id (string).
                    guard case .string(let childId) = parsedValue, !childId.isEmpty else {
                        return [
                            "ok": false, "error": "invalid_value",
                            "reason": "'\(rawPath)' expects a JSON string child id (e.g. \"<uuid>\").",
                        ]
                    }
                    guard collection.childIds().contains(childId) else {
                        return [
                            "ok": false, "error": "not_found",
                            "reason": "No '\(resolvePath)' child with id '\(childId)'.",
                        ]
                    }
                    // Snapshot of the entry for audit (best-effort —
                    // a JSON map of all its fields' current values).
                    let snapshot = collection.fields(for: childId).reduce(into: [String: ConfigValue]()) { acc, f in
                        if let v = try? f.read() { acc[f.path] = v }
                    }
                    let snapshotJSON = ConfigValue.object(snapshot).jsonString()
                    resolved.append(.collectionRemove(collection: collection, childId: childId, snapshotJSON: snapshotJSON))
                    resolvedItems.append(PendingConfigChangeItem(
                        id: UUID().uuidString,
                        displayName: collection.displayName,
                        path: "\(resolvePath).\(childId)",
                        oldDisplay: childId,
                        newDisplay: "",
                        verb: "remove",
                        risk: collection.risk
                    ))
                } else {
                    guard collection.addable else {
                        return [
                            "ok": false, "error": "permission_denied",
                            "reason": "Collection '\(resolvePath)' does not support add.",
                        ]
                    }
                    // Validate payload shape against collection's schema.
                    do { try collection.addPayloadSchema.validate(parsedValue) } catch {
                        return [
                            "ok": false, "error": "validation_failed",
                            "reason": String(describing: error),
                        ]
                    }
                    // [T-minis-config-provider-add] Redact credential values
                    // (apiKey / oauthToken) before they reach the audit log or
                    // the confirmation sheet. The collection's add() still
                    // receives the un-redacted `parsedValue` so it can write the
                    // real secret to Keychain; only the persisted/displayed
                    // copies are masked. A `$$ENV` reference passes through.
                    let auditedPayload = parsedValue.redactingSecrets()
                    resolved.append(.collectionAdd(collection: collection, payload: parsedValue, payloadJSON: auditedPayload.jsonString()))
                    resolvedItems.append(PendingConfigChangeItem(
                        id: UUID().uuidString,
                        displayName: collection.displayName,
                        path: rawPath,
                        oldDisplay: "",
                        newDisplay: auditedPayload.displayString,
                        verb: "add",
                        risk: collection.risk
                    ))
                }
                continue
            }

            guard let field = ConfigRegistry.shared.resolveField(path: resolvePath) else {
                return [
                    "ok": false, "error": "unknown_path",
                    "reason": "No registered field at '\(rawPath)'.",
                ]
            }
            // Feature-unavailable gate BEFORE access/validation and before the
            // confirmation sheet — never ask the user to approve a write that
            // cannot apply on this device/OS (e.g. sync.* on iOS 16,
            // background.liveActivity on non-ActivityKit hardware).
            if let reason = field.unavailableReason {
                return Self.unavailableErrorEnvelope(path: rawPath, reason: reason)
            }
            if field.access != .readwrite {
                let reason = field.access == .hidden
                    ? "'\(rawPath)' is intentionally not exposed to minis-config."
                    : "'\(rawPath)' is read-only."
                return [
                    "ok": false, "error": "permission_denied", "reason": reason,
                ]
            }
            guard let parsedValue = ConfigValue.decode(json: valueJSON) else {
                return [
                    "ok": false, "error": "invalid_value",
                    "reason": "Value JSON is not parseable for '\(rawPath)'.",
                ]
            }

            // Read current value first — needed for both audit's old
            // value and to compute the post-append array.
            let oldValue: ConfigValue
            do {
                oldValue = try field.read()
            } catch {
                oldValue = .null
            }

            // `var` (not `let`): [T-soul-icon-config-images] rewrites this in
            // place when an image source resolves to a data URI.
            var newValue: ConfigValue
            let displayVerb: String
            let displayPath: String
            // `var`: [T-soul-icon-config-images] collapses a stored data URI
            // to "<image>" so the confirm sheet never shows base64.
            var displayOld: String
            var displayNew: String
            if isAppend {
                guard case .array(let elemSchema) = field.valueSchema else {
                    return [
                        "ok": false, "error": "invalid_value",
                        "reason": "'.append' is only valid on array-typed fields; '\(field.path)' is not an array.",
                    ]
                }
                do { try elemSchema.validate(parsedValue) } catch {
                    return [
                        "ok": false, "error": "validation_failed",
                        "reason": String(describing: error),
                    ]
                }
                let existing: [ConfigValue]
                if case .array(let arr) = oldValue { existing = arr } else { existing = [] }
                newValue = .array(existing + [parsedValue])
                displayVerb = "append"
                displayPath = rawPath
                displayOld = ""
                displayNew = parsedValue.displayString
            } else if isRemove {
                guard case .array = field.valueSchema else {
                    return [
                        "ok": false, "error": "invalid_value",
                        "reason": "'.remove' is only valid on array-typed fields; '\(field.path)' is not an array.",
                    ]
                }
                let existing: [ConfigValue]
                if case .array(let arr) = oldValue { existing = arr } else { existing = [] }
                let filtered = existing.filter { $0 != parsedValue }
                if filtered.count == existing.count {
                    return [
                        "ok": false, "error": "not_found",
                        "reason": "No occurrence of value \(parsedValue.displayString) in '\(field.path)'.",
                    ]
                }
                newValue = .array(filtered)
                displayVerb = "remove"
                displayPath = rawPath
                displayOld = parsedValue.displayString
                displayNew = ""
            } else {
                do {
                    try field.valueSchema.validate(parsedValue)
                } catch {
                    return [
                        "ok": false, "error": "validation_failed",
                        "reason": String(describing: error),
                    ]
                }
                newValue = parsedValue
                displayVerb = "set"
                displayPath = field.path
                displayOld = oldValue.displayString
                displayNew = newValue.displayString
            }

            // [T-soul-icon-config-images] Resolve an image source to its
            // stored form BEFORE the confirmation sheet.
            //
            // Three reasons this belongs here rather than in the writer:
            //   1. `ConfigField.write` is synchronous and @MainActor; an
            //      https source needs an await. This function is already async.
            //   2. The user should confirm a change that has already been
            //      fetched and validated — otherwise the sheet promises
            //      something the write may then fail to deliver.
            //   3. A failed fetch becomes a clean tool error instead of a
            //      half-applied batch.
            //
            // What lands in `newValue` is the finished data URI; what lands in
            // the sheet and the audit log is the literal "<image>", so neither
            // ever carries base64.
            if field.path == "soul.icon", case .string(let rawIcon) = newValue,
               SoulIconSource.looksLikeImageSource(rawIcon) {
                do {
                    let dataURI = try await SoulIconSource.resolveToDataURI(rawIcon)
                    newValue = .string(dataURI)
                    displayNew = "<image>"
                } catch {
                    let reason = (error as? LocalizedError)?.errorDescription
                        ?? error.localizedDescription
                    return [
                        "ok": false, "error": "validation_failed",
                        "reason": "Can't use that image: \(reason)",
                    ]
                }
            }

            // [T-minis-config-provider-add] If this field holds a credential
            // (path ends in a secret key, e.g. providers.<id>.apiKey), mask the
            // value persisted to the audit and shown in the confirm sheet. The
            // field's writer still receives the REAL `newValue` to store in
            // Keychain (auditNewValue is the masked copy used only for
            // audit/display). A `$$ENV` reference is a pointer, not a secret, so
            // it passes through unmasked.
            let fieldLeaf = field.path.split(separator: ".").last.map(String.init) ?? ""
            var auditNewValue = newValue
            if ConfigValue.secretObjectKeys.contains(fieldLeaf),
               case .object(let mo)? = Optional(ConfigValue.object([fieldLeaf: newValue]).redactingSecrets()),
               let mv = mo[fieldLeaf] {
                auditNewValue = mv
                displayNew = mv.displayString
            }

            // [T-soul-icon-config-images] Keep the encoded icon out of the
            // audit log and the confirm sheet. `displayString` truncates at 80
            // chars, which would still splash 75 characters of base64 across
            // both surfaces — and the audit column stores the value in full.
            if case .string(let sv) = auditNewValue, SoulIconImage.isDataURI(sv) {
                auditNewValue = .string("<image>")
                displayNew = "<image>"
            }
            if case .string(let ov) = oldValue, SoulIconImage.isDataURI(ov) {
                displayOld = "<image>"
            }

            resolved.append(.fieldWrite(field: field, oldValue: oldValue, newValue: newValue, auditNewValue: auditNewValue))
            resolvedItems.append(PendingConfigChangeItem(
                id: UUID().uuidString,
                displayName: field.displayName,
                path: displayPath,
                oldDisplay: displayOld,
                newDisplay: displayNew,
                verb: displayVerb,
                risk: field.risk
            ))
        }

        // 2. Enqueue confirmation. Suspends until user acts or timeout.
        // When `skipConfirmation` is true (user-initiated revert from the
        // Logs sheet), bypass the gate — the user already confirmed via
        // a local `.confirmationDialog` and stacking ConfigConfirmSheet
        // on top of the open Logs sheet would either fail (iOS doesn't
        // allow nested sheets) or queue silently behind it.
        let outcome: PendingConfigChange.Outcome
        if skipConfirmation {
            outcome = .approved(items: resolvedItems)
        } else {
            let pending = PendingConfigChange(items: resolvedItems, caption: caption)
            logger.info("confirmation requested id=\(pending.id) resolvedItems=\(resolvedItems.count) caption=\(caption ?? "")")
            outcome = await ConfigConfirmationGate.shared.requestConfirmation(pending)
        }
        logger.info("confirmation outcome=\(Self.describeOutcome(outcome)) resolved=\(resolved.count) caption=\(caption ?? "")")

        // 3. Map outcome → audit + user_message.
        let actor = ConfigAuditActor(rawValue: actorRaw) ?? .agent
        let audit = ConfigAuditLog.shared
        switch outcome {
        case .pending:
            // Should be unreachable (gate doesn't return .pending).
            return ["ok": false, "error": "internal_error"]

        case .timedOut:
            // Record one row per item with status=timeout. Helps the
            // user see what was attempted even if they missed the
            // sheet.
            let now = Date()
            for r in resolved {
                let a = auditFields(for: r)
                audit.append(ConfigAuditEntry(
                    id: UUID().uuidString, at: now, actor: actor,
                    sessionId: sessionId, scope: a.scope, key: a.key,
                    oldValueJSON: a.oldJSON, newValueJSON: a.newJSON,
                    confirmedAt: nil, status: .timeout, revertOf: nil,
                    caption: caption
                ))
            }
            return [
                "ok": false, "error": "timeout",
                "reason": "Confirmation timed out after \(Int(ConfigConfirmationGate.timeoutSeconds))s.",
                "user_message": "I waited but you didn't confirm the change. Tap me again if you want me to retry.",
            ]

        case .rejected:
            let now = Date()
            for r in resolved {
                let a = auditFields(for: r)
                audit.append(ConfigAuditEntry(
                    id: UUID().uuidString, at: now, actor: actor,
                    sessionId: sessionId, scope: a.scope, key: a.key,
                    oldValueJSON: a.oldJSON, newValueJSON: a.newJSON,
                    confirmedAt: now, status: .rejected, revertOf: nil,
                    caption: caption
                ))
            }
            return [
                "ok": false, "error": "user_rejected",
                "reason": "User cancelled the change.",
                "user_message": "Change cancelled.",
            ]

        case .approved(let approvedRows):
            // Match the per-row toggle state back to our resolved list.
            // Preserves order; rows with isApproved=false get audit-
            // logged as `rejected` (not silently dropped).
            let now = Date()
            var applied: [[String: String]] = []
            var auditIds: [String] = []
            var writeErrors: [[String: String]] = []
            if approvedRows.count != resolved.count {
                logger.error("confirmation row count mismatch resolved=\(resolved.count) approvedRows=\(approvedRows.count) caption=\(caption ?? "")")
            }
            for (idx, row) in approvedRows.enumerated() {
                guard idx < resolved.count else { continue }
                let r = resolved[idx]
                let a = auditFields(for: r)
                if !row.isApproved {
                    let auditId = UUID().uuidString
                    audit.append(ConfigAuditEntry(
                        id: auditId, at: now, actor: actor,
                        sessionId: sessionId, scope: a.scope, key: a.key,
                        oldValueJSON: a.oldJSON, newValueJSON: a.newJSON,
                        confirmedAt: now, status: .rejected, revertOf: nil,
                        caption: caption
                    ))
                    continue
                }
                do {
                    let appliedRow: [String: String]
                    let finalKey: String
                    switch r {
                    case .fieldWrite(let field, let oldValue, let newValue, let auditNewValue):
                        // Apply the REAL newValue; echo the masked auditNewValue
                        // back so a credential write never surfaces the secret in
                        // the response. [T-minis-config-provider-add]
                        try field.write(newValue)
                        finalKey = field.path
                        appliedRow = [
                            "path": field.path,
                            "display_name": field.displayName,
                            "old": oldValue.displayString,
                            "new": auditNewValue.displayString,
                        ]
                    case .collectionAdd(let collection, let payload, _):
                        let newId = try collection.add(payload)
                        finalKey = "\(collection.basePath).\(newId)"
                        appliedRow = [
                            "path": finalKey,
                            "display_name": collection.displayName,
                            "old": "",
                            "new": newId,
                        ]
                    case .collectionRemove(let collection, let childId, _):
                        try collection.remove(id: childId)
                        finalKey = "\(collection.basePath).\(childId)"
                        appliedRow = [
                            "path": finalKey,
                            "display_name": collection.displayName,
                            "old": childId,
                            "new": "",
                        ]
                    }
                    let auditId = UUID().uuidString
                    audit.append(ConfigAuditEntry(
                        id: auditId, at: now, actor: actor,
                        sessionId: sessionId, scope: a.scope, key: finalKey,
                        oldValueJSON: a.oldJSON, newValueJSON: a.newJSON,
                        confirmedAt: now, status: .applied, revertOf: nil,
                        caption: caption
                    ))
                    auditIds.append(auditId)
                    applied.append(appliedRow)
                } catch {
                    // Write/add/remove failed AFTER user confirmed —
                    // surface the underlying error verbatim. For
                    // ConfigError cases this carries the precise
                    // already_exists / permission_denied reason from
                    // the collection.
                    let message = errorMessage(error)
                    let errorCode = errorCodeKey(error)
                    logger.error("op failed for \(a.key): \(message)")
                    var entry: [String: String] = [
                        "path": a.key,
                        "display_name": a.displayName,
                        "reason": message,
                    ]
                    if let code = errorCode { entry["error"] = code }
                    writeErrors.append(entry)
                }
            }
            if applied.isEmpty {
                if !writeErrors.isEmpty {
                    logger.warning("write_failed resolved=\(resolved.count) approvedRows=\(approvedRows.count) approvedRowsOn=\(approvedRows.filter { $0.isApproved }.count) errors=\(writeErrors.count) caption=\(caption ?? "")")
                    return [
                        "ok": false,
                        "error": "write_failed",
                        "reason": writeErrors.map { "\($0["path"] ?? "?"): \($0["reason"] ?? "unknown error")" }.joined(separator: "; "),
                        "write_errors": writeErrors,
                        "user_message": "I confirmed the change, but applying it failed. No settings were updated.",
                    ]
                }
                if approvedRows.count != resolved.count {
                    return [
                        "ok": false,
                        "error": "internal_error",
                        "reason": "Confirmation row count mismatch: expected \(resolved.count), got \(approvedRows.count).",
                        "user_message": "I couldn't verify the confirmation result. No changes were applied. Please try again.",
                    ]
                }
                logger.warning("all_rejected resolved=\(resolved.count) approvedRows=\(approvedRows.count) approvedRowsOn=\(approvedRows.filter { $0.isApproved }.count) caption=\(caption ?? "")")
                return [
                    "ok": false, "error": "all_rejected",
                    "reason": "User rejected every row.",
                    "user_message": "No changes applied.",
                ]
            }
            if !writeErrors.isEmpty {
                return [
                    "ok": false,
                    "error": "partial_write_failed",
                    "applied": applied,
                    "audit_ids": auditIds,
                    "write_errors": writeErrors,
                    "reason": writeErrors.map { "\($0["path"] ?? "?"): \($0["reason"] ?? "unknown error")" }.joined(separator: "; "),
                    "user_message": "Some settings were updated, but at least one confirmed change failed to apply.",
                ]
            }
            return [
                "ok": true,
                "applied": applied,
                "audit_ids": auditIds,
                "audit_url": "minis://settings/logs?tab=config-audit",
                "user_message": "Settings updated. Review or revert at [Logs → Config Changes](minis://settings/logs?tab=config-audit).",
            ]
        }
    }

    // MARK: - Resolved write item (shared by performWriteBatch + helpers)

    /// Two flavours of write the batch pipeline handles:
    ///   .fieldWrite — set/append/remove against a registered field.
    ///   .collectionAdd / .collectionRemove — add/remove a whole child of
    ///     a ConfigCollection (e.g. `models.add`, `models.remove`). These
    ///     do not produce a revertable audit entry (ConfigCollection.swift
    ///     :16-17 — collection adds/removes can't be re-minted under the
    ///     original uuid, so revert would fail downstream lookups).
    private enum Resolved {
        // `newValue` is applied to the field's writer; `auditNewValue` is the
        // (possibly secret-masked) copy persisted to the audit and shown in the
        // confirm sheet. They differ only for credential fields.
        // [T-minis-config-provider-add]
        case fieldWrite(field: ConfigField, oldValue: ConfigValue, newValue: ConfigValue, auditNewValue: ConfigValue)
        case collectionAdd(collection: ConfigCollection, payload: ConfigValue, payloadJSON: String)
        case collectionRemove(collection: ConfigCollection, childId: String, snapshotJSON: String)
    }

    // MARK: - Resolved → audit/error helpers

    /// Audit-row fields derived from a Resolved item. Field writes carry
    /// the field's own scope/path/old/new JSON. Collection ops use the
    /// collection's basePath as the scope, leave the key blank (callers
    /// fill in the post-op `<basePath>.<id>` form), and serialize the
    /// add payload / remove snapshot into the appropriate value slot.
    @MainActor
    private static func auditFields(for r: Resolved) -> (scope: String, key: String, oldJSON: String, newJSON: String, displayName: String) {
        switch r {
        case .fieldWrite(let f, let old, _, let auditNew):
            // Persist the masked auditNewValue, never the raw secret.
            // [T-minis-config-provider-add]
            return (f.scope, f.path, old.jsonString(), auditNew.jsonString(), f.displayName)
        case .collectionAdd(let c, _, let json):
            return (c.basePath, "\(c.basePath).<new>", "", json, c.displayName)
        case .collectionRemove(let c, let cid, let snap):
            return (c.basePath, "\(c.basePath).\(cid)", snap, "", c.displayName)
        }
    }

    /// Best-effort human-readable error message. ConfigError already has
    /// CustomStringConvertible; other Swift errors fall back to default
    /// description.
    private static func errorMessage(_ err: Error) -> String {
        if let ce = err as? ConfigError { return String(describing: ce) }
        return String(describing: err)
    }

    /// Map specific failure modes to stable error codes the CLI/agent
    /// can branch on. Returns nil when no specialized code applies (the
    /// caller falls back to "write_failed").
    private static func errorCodeKey(_ err: Error) -> String? {
        if let ce = err as? ConfigError {
            switch ce {
            case .permissionDenied: return "permission_denied"
            case .unknownPath:      return "unknown_path"
            case .typeMismatch, .regexMismatch, .outOfRange:
                return "validation_failed"
            case .invalidValue(let msg):
                // ModelsCollection.add throws invalidValue("Entry already
                // exists for this model") when the (instance,modelId)
                // pair is a duplicate. Surface that as the stable
                // `already_exists` code so the agent can branch.
                if msg.lowercased().contains("already exists") {
                    return "already_exists"
                }
                return "invalid_value"
            case .io: return "io_error"
            }
        }
        return nil
    }

    private static func describeOutcome(_ outcome: PendingConfigChange.Outcome) -> String {
        switch outcome {
        case .pending:
            return "pending"
        case .rejected:
            return "rejected"
        case .timedOut:
            return "timedOut"
        case .approved(let rows):
            let approvedCount = rows.filter { $0.isApproved }.count
            return "approved rows=\(rows.count) approved=\(approvedCount)"
        }
    }

    // MARK: - Audit list / get / revert (read-only operations)

    @objc public static func auditList(limit: Int, scope: String?) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: NSDictionary = [:]
        Task.detached { @MainActor in
            guard MinisConfigPermissionStore.shared.enabled else {
                result = Self.disabledErrorEnvelope()
                sem.signal()
                return
            }
            let entries = ConfigAuditLog.shared.recent(limit: limit, scope: scope)
            let usage = ConfigAuditLog.shared.usage()
            result = [
                "ok": true,
                "count": entries.count,
                "capacity": usage.capacity,
                "total_used": usage.count,
                "entries": entries.map(Self.auditEntryDict),
            ]
            sem.signal()
        }
        sem.wait()
        return result
    }

    @objc public static func auditGet(id: String) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: NSDictionary = [:]
        Task.detached { @MainActor in
            guard MinisConfigPermissionStore.shared.enabled else {
                result = Self.disabledErrorEnvelope()
                sem.signal()
                return
            }
            guard let e = ConfigAuditLog.shared.get(id: id) else {
                result = ["ok": false, "error": "not_found",
                          "reason": "No audit entry with id '\(id)'."]
                sem.signal()
                return
            }
            result = ["ok": true, "entry": Self.auditEntryDict(e)]
            sem.signal()
        }
        sem.wait()
        return result
    }

    @MainActor
    private static func auditEntryDict(_ e: ConfigAuditEntry) -> NSDictionary {
        let iso = ISO8601DateFormatter()
        var d: [String: Any] = [
            "id": e.id,
            "at": iso.string(from: e.at),
            "actor": e.actor.rawValue,
            "scope": e.scope,
            "key": e.key,
            "old": e.oldValueJSON,
            "new": e.newValueJSON,
            "status": e.status.rawValue,
        ]
        if let s = e.sessionId { d["session_id"] = s }
        if let c = e.confirmedAt { d["confirmed_at"] = iso.string(from: c) }
        if let r = e.revertOf { d["revert_of"] = r }
        return d as NSDictionary
    }

    /// Revert a previous applied entry. Builds a synthetic write
    /// operation that flips old↔new and routes through the same
    /// confirmation path so the user sees what's about to roll back.
    ///
    /// `skipConfirmation` is for user-initiated reverts from the
    /// in-app Logs UI: the user already saw the row and tapped a
    /// `.confirmationDialog`-style speed bump before this call, so the
    /// gate's full ConfigConfirmSheet would be a redundant second
    /// confirmation. It also can't render correctly when Logs is
    /// itself presented as a sheet (iOS won't stack two sheets), so
    /// the gate's sheet would be queued behind the open Logs sheet.
    /// The CLI / agent paths always pass `false` here so the gate
    /// stays in place for agent-initiated writes.
    @objc public static func auditRevert(id: String,
                                         actorRaw: String,
                                         sessionId: String?,
                                         skipConfirmation: Bool = false) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: NSDictionary = [:]
        Task.detached { @MainActor in
            ConfigRegistry.shared.registerBuiltinsIfNeeded()
            guard MinisConfigPermissionStore.shared.enabled else {
                result = Self.disabledErrorEnvelope()
                sem.signal()
                return
            }
            guard let entry = ConfigAuditLog.shared.get(id: id) else {
                result = ["ok": false, "error": "not_found",
                          "reason": "No audit entry with id '\(id)'."]
                sem.signal()
                return
            }
            guard entry.status == .applied else {
                result = ["ok": false, "error": "not_revertable",
                          "reason": "Entry is \(entry.status.rawValue); only applied entries can be reverted."]
                sem.signal()
                return
            }
            guard let field = ConfigRegistry.shared.resolveField(path: entry.key),
                  field.revertable else {
                result = ["ok": false, "error": "not_revertable",
                          "reason": "Field '\(entry.key)' no longer registered, or doesn't support revert."]
                sem.signal()
                return
            }
            // Build a single-item batch flipping back to oldValue.
            let revertItem: NSDictionary = [
                "path": entry.key,
                "value_json": entry.oldValueJSON,
            ]
            let res = await Self.performWriteBatch(
                items: [revertItem],
                caption: "minis-config audit revert \(entry.id.prefix(8))",
                actorRaw: actorRaw,
                sessionId: sessionId,
                skipConfirmation: skipConfirmation
            )
            // Mark original entry as reverted on success.
            if (res["ok"] as? Bool) == true {
                ConfigAuditLog.shared.markReverted(id: entry.id)
            }
            result = res
            sem.signal()
        }
        sem.wait()
        return result
    }
}
