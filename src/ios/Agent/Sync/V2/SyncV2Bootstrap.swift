import Foundation

private let logger = AppLogger(category: "SyncCore")

/// Top-level entry point for the v2 sync stack. Called once at app launch
/// from MinisApp.swift. Wires Syncable types into the registry, registers
/// ChatStore-backed hydrators, hooks ICloudSharedZoneTransport into
/// SyncCore, and (optionally) runs the v1→v2 migration engine.
///
/// Gated by `UserDefaults.cloudSync.v2.enabled` (default false). When
/// disabled, this function is a no-op and v1 CloudSyncEngine continues
/// to drive sync. Once the flag flips on, v1 is paused and v2 takes over
/// (at app start; an in-place hot-swap mid-session is not supported).
enum SyncV2Bootstrap {

    /// [T-ios-icloud-ckrecordid-nilname-crash] Per-step guard for the
    /// deferred startup chain. Catches Swift errors thrown from `body` and
    /// logs them under a step label so one failing step doesn't take the
    /// rest of startup down with it. ObjC NSException raised inside an
    /// awaited Apple-framework call cannot be caught here (the await
    /// boundary discards the sync stack frame holding the @try); for that
    /// path we validate inputs at the call site (see
    /// `ICloudSharedZoneTransport.isValidCKRecordName`).
    @MainActor
    private static func runGuarded(_ step: String, _ body: @MainActor () async throws -> Void) async {
        do {
            try await body()
        } catch {
            logger.error("[SyncCore] v2 startup step=\(step) FAILED with Swift error: \(error) — continuing")
        }
    }


    /// User-facing toggle. On a first read with no v2 key set we default
    /// to OFF (clean install starts opted-out so the user gets to make
    /// an informed decision instead of background iCloud traffic on
    /// first launch). For users upgrading from a build that only had
    /// the v1 engine we inherit their v1 toggle (`cloudSync.enabled`)
    /// so a user who had iCloud Sync ON in v1 keeps it on after the
    /// upgrade, and one who had it OFF keeps it off. The resolved
    /// value is persisted under the v2 key.
    /// Override via the `cloudSync.v2.enabled` UserDefaults key directly
    /// during development.
    static var isEnabled: Bool {
        let key = "cloudSync.v2.enabled"
        if UserDefaults.standard.object(forKey: key) == nil {
            // Inherit from v1 if the user ever interacted with that
            // toggle (object(forKey:) returns non-nil once any write
            // occurred — including the v1 engine's own write-back at
            // init). If neither key exists this is a clean install:
            // default OFF.
            let v1Key = "cloudSync.enabled"
            let inherited: Bool
            if let v1 = UserDefaults.standard.object(forKey: v1Key) as? Bool {
                inherited = v1
                logger.info("[SyncCore] v2 isEnabled bootstrap — inheriting v1 cloudSync.enabled=\(v1)")
            } else {
                inherited = false
                logger.info("[SyncCore] v2 isEnabled bootstrap — fresh install, defaulting OFF")
            }
            UserDefaults.standard.set(inherited, forKey: key)
            return inherited
        }
        return UserDefaults.standard.bool(forKey: key)
    }

    static func setEnabled(_ enabled: Bool) {
        let prev = UserDefaults.standard.object(forKey: "cloudSync.v2.enabled") as? Bool ?? true
        UserDefaults.standard.set(enabled, forKey: "cloudSync.v2.enabled")
        logger.info("[SyncCore] v2 enabled flag set to \(enabled) (was \(prev))")
        // When the user disables iCloud Sync, stop the active SyncCore +
        // transports immediately so dirty queue drains pause and no more
        // fetch/push fires until they opt back in. Without this, the
        // engine keeps running with stale credentials for the rest of
        // the launch — the indicator hides via @AppStorage observation,
        // but background CKQuery traffic continues. Re-enabling still
        // requires a relaunch because boot wiring is non-idempotent.
        if prev == true && enabled == false {
            if #available(iOS 17.0, *) {
                Task { await SyncCore.shared.stop() }
            }
        }
        // T-v2-hot-enable: user flipped the toggle ON after launch. Older
        // builds required a relaunch because boot only ran from MinisApp
        // launch path — leaving the engine in 'Starting…' with
        // transports=0, isRunning=false, and sendNow warnings forever.
        // Bootstrap is now idempotent (SyncCore.register dedups by name,
        // type/hydrator registries overwrite-as-self, SyncCore.start
        // guards on isRunning), so it is safe to invoke from here.
        if prev == false && enabled == true {
            if #available(iOS 17.0, *) {
                Task { @MainActor in
                    await SyncV2Bootstrap.startIfEnabled()
                }
            }
        }
    }

    /// Has the user explicitly requested migration of their pre-v2 history?
    /// Default OFF: only newly-created sessions/messages sync to v2 by
    /// default. The user must tap "Request Migration" in the Sync sheet
    /// to backfill historical data — and can also tap "Force Delete V1
    /// Zone" to discard the legacy copy entirely. Earlier builds defaulted
    /// to ON which kicked off a multi-hour throttled push on every
    /// upgrading device, often without the user's knowledge.
    private static let migrationRequestedKey = "cloudSync.v2.migrationRequested"
    static var isMigrationRequested: Bool {
        UserDefaults.standard.bool(forKey: migrationRequestedKey)
    }
    private static var provisionalRetryTask: Task<Void, Never>?
    static func setMigrationRequested(_ requested: Bool) {
        UserDefaults.standard.set(requested, forKey: migrationRequestedKey)
        logger.info("[SyncCore] migration requested=\(requested)")
    }

    /// Should v1 CloudSyncEngine be allowed to run? It is paused as soon
    /// as v2 is enabled AND migration is in progress / completed. While
    /// migration is `inProgress`, v2 fetches v1 data via a minimal shim
    /// — full v1 engine MUST stay quiet to avoid double-sends.
    @MainActor
    static func shouldPauseV1() -> Bool {
        guard isEnabled else { return false }
        if #available(iOS 17.0, *) {
            switch MigrationEngine.shared.currentStatus() {
            case .pending:
                return true   // about to start migration; v1 must already be quiet
            case .inProgress, .completed:
                return true
            case .failed:
                return false  // migration broke; let v1 keep running so user isn't stranded
            }
        }
        return false
    }

    /// Called once from MinisApp launch (or on flag flip in debug builds).
    /// Idempotent — running twice is harmless.
    @MainActor
    static func startIfEnabled() async {
        guard isEnabled else {
            logger.debug("[SyncCore] v2 disabled (flag off) — staying on v1")
            return
        }
        guard #available(iOS 17.0, *) else {
            logger.warning("[SyncCore] v2 requires iOS 17+ — disabling")
            return
        }

        // [T-ios-reboot-keychain-identity-rotation] Same guard as
        // CloudSyncEngine.start, for the same reason: this runs from
        // scenePhase→.active, which iOS can deliver on a background relaunch
        // BEFORE first unlock after a reboot. The Keychain is unreadable then, so
        // `DeviceIdentity.deviceId` returns a throwaway id — and both
        // `setSyncZoneName` below and the `registerDevice(recordId:)` call further
        // down would bake it in, tagging every dirty row for a zone that is not
        // this device's. Bail and let the post-unlock activation start v2 for real.
        guard !DeviceIdentity.isProvisional else {
            // [T-icloud-device-provisional-retry] On iOS a later scenePhase →
            // .active retries this. On an iPad app running on macOS Apple does
            // not document which scene-phase transitions fire on window close
            // vs quit, so the retry is not guaranteed — and a device that never
            // registers is invisible to peers AND blind to them. Arm a timed
            // retry as well; idempotent (one pending at a time).
            logger.warning("[SyncCore] v2 startup deferred — device identity is provisional (keychain locked after reboot); retrying in 30s and on next activation")
            if provisionalRetryTask == nil {
                provisionalRetryTask = Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
                    provisionalRetryTask = nil
                    if !DeviceIdentity.isProvisional {
                        logger.info("[SyncCore] device identity now available — starting v2 (timed retry)")
                        await startIfEnabled()
                    } else {
                        logger.warning("[SyncCore] device identity still provisional after 30s; will retry again")
                        // re-enter to re-arm
                        await startIfEnabled()
                    }
                }
            }
            return
        }

        logger.info("[SyncCore] v2 startup STEP=begin")

        // 1. Register all Syncable types.
        SyncedTypesBootstrap.registerAll()
        logger.info("[SyncCore] v2 startup STEP=typesRegistered count=\(SyncableTypeRegistry.shared.count)")

        // 1b. Set ChatStore.syncZoneName so markDirty actually writes
        // rows. v1's CloudSyncEngine.start does this; v2 paused v1 so
        // we must do it ourselves. The zone-name string is shared
        // across engines (it tags dirty rows; the v2 transport ignores
        // it on send and re-derives from the registry).
        await ChatStore.shared.setSyncZoneName(DeviceIdentity.zoneName)
        logger.info("[SyncCore] v2 startup STEP=zoneNameSet")

        // 2. Register hydrators (builders + mergers + deletion appliers)
        //    for the types whose stores are owned by ChatStore. Only
        //    Session and Message are real first-class merge paths in
        //    this initial wiring; the rest get builder-only hooks so
        //    pushes work but inbound is logged + skipped (to be filled
        //    in subsequent patches).
        await ChatStoreSyncHydrators.registerAll()
        logger.info("[SyncCore] v2 startup STEP=hydratorsRegistered")

        // 3. Wire transports (cheap — no network yet).
        let transport = ICloudSharedZoneTransport()
        SyncCore.shared.register(transport)
        // LAN skeleton stays unregistered (LANTransport.isImplemented = false).
        logger.info("[SyncCore] v2 startup STEP=transportRegistered")

        // 4. Defer the network-touching part of startup so the app's
        //    cold-launch (UI hydration, ChatStore SQLite warmup, web
        //    view boots) doesn't compete with CloudKit fetchChanges /
        //    initial sendChanges for CPU + main thread time. We've
        //    observed 30%+ CPU spikes during the first minute when v2
        //    boots in parallel with the chat list rendering.
        let bootDelay: TimeInterval = 15
        let bootDeadline = Date().addingTimeInterval(bootDelay)
        SyncCore.shared.bootDelayUntil = bootDeadline
        logger.info("[SyncCore] v2 startup STEP=deferring network boot by \(Int(bootDelay))s")
        Task { @MainActor in
            // [T-ios-icloud-ckrecordid-nilname-crash] Outer guards around the
            // deferred startup so a single bad CloudKit interaction (e.g. a
            // dirty record with an empty/invalid recordName that iOS 26.6
            // Beta rejects via NSException, dirty migration row, unexpected
            // CKError) cannot take launch down with it.
            //
            // Two complementary layers:
            //   1. **Input validation at the call site** is the only
            //      effective ObjC-NSException guard. Inside async code on
            //      Swift concurrency, an NSException raised by an awaited
            //      Apple-framework call escalates straight to
            //      `objc_terminate → SIGABRT` because the await boundary
            //      drops the @try frame. The dedicated fix is the
            //      `isValidCKRecordName` precheck in
            //      `ICloudSharedZoneTransport.send` (the actual crash
            //      site at line 657 in build 49).
            //   2. **Per-step Swift do/catch** via `runGuarded` so any
            //      throwing path (a future CloudKit step that surfaces a
            //      `throws` instead of NSException) doesn't abort the
            //      remaining startup steps. The outer do/catch catches
            //      anything `runGuarded` re-throws (currently nothing).
            do {
                try? await Task.sleep(nanoseconds: UInt64(bootDelay * 1_000_000_000))
                logger.info("[SyncCore] v2 startup STEP=coreStart begin (post-delay)")
                await runGuarded("coreStart") {
                    await SyncCore.shared.start()
                }
                // Keep the indicator on the wakeup timer until start() actually
                // returns isRunning=true, otherwise the few seconds spent in
                // ensureZonesExist / initialSendChanges show as 'Stopped'.
                SyncCore.shared.bootDelayUntil = nil
                logger.info("[SyncCore] v2 startup STEP=coreStart done")
                // Self-register this device into minis-devices zone so peers
                // can list us (and we'll fetch their records back). Re-runs
                // on every launch — markDirty is idempotent (INSERT OR
                // REPLACE in sync_dirty_records); the record's lastSeen
                // field updates each time so peers see freshness.
                await runGuarded("deviceRegister") {
                    await ChatStore.shared.markDirty(
                        recordType: "SyncDeviceV2",
                        recordId: DeviceIdentity.deviceId
                    )
                    // [T-icloud-device-retire-old-id] If this install just
                    // re-minted its id, retire the previous one: tombstone it
                    // locally (so a stale echo cannot resurrect it) and queue
                    // op=delete so peers drop the ghost row. Older peers ignore
                    // a delete for a type they never delete — harmless.
                    if let old = DeviceIdentity.takeRetiredDeviceId() {
                        await ChatStore.shared.recordDeletedRecordTombstone(type: "SyncDeviceV2", id: old)
                        await ChatStore.shared.markDirty(recordType: "SyncDeviceV2", recordId: old, operation: "delete")
                        logger.info("[SyncCore] retiring previous device id \(old.prefix(8)) (op=delete queued)")
                    }
                }
                logger.info("[SyncCore] v2 startup STEP=device registered (deviceId=\(DeviceIdentity.deviceId.prefix(8)))")
                logger.info("[SyncCore] v2 startup STEP=migration begin")
                await runGuarded("migration") {
                    await MigrationEngine.shared.runIfNeeded()
                }
                logger.info("[SyncCore] v2 startup STEP=migration done")
                // Start the periodic dirty scanner. Under v1 this was kicked
                // off by CloudSyncEngine.start(); but v2 paused v1, so the
                // scanner was never running — and any record type that
                // relies on the scanner (skills mtime, config files, and now
                // memory files) silently stopped detecting on-disk edits.
                // Scanner is idempotent and safe to start regardless of v1
                // state.
                SyncDirtyScanner.shared.start()
                logger.info("[SyncCore] v2 startup STEP=dirtyScanner started")
                // Flush whatever ended up in the dirty queue during launch /
                // migration / scanner first-tick. scheduleSend's debounce-
                // cancel chain can keep getting reset for minutes when many
                // markDirty calls happen in close succession (observed on
                // pix: forceFullSync staged 24k rows and the queue never
                // drained because every markDirty cancelled the in-flight
                // 3s sleep). sendNow runs immediately and guards against
                // re-entry via isSending, so it's safe to call here even
                // if a scheduleSend timer fires concurrently.
                await runGuarded("initialFlush") {
                    await SyncCore.shared.sendNow(trigger: .manual)
                }
                logger.info("[SyncCore] v2 startup STEP=initialFlush done")
                logger.info("[SyncCore] v2 startup complete")
            } catch {
                logger.error("[SyncCore] v2 startup aborted with Swift error: \(error)")
            }
        }
    }
}
