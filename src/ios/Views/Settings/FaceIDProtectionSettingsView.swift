//
//  FaceIDProtectionSettingsView.swift
//  MinisApp
//
//  Settings entry for per-session biometric protection. Surfaces the
//  master toggle, an idle-timeout picker, and (when relevant) a count of
//  currently-locked sessions with a "clear all" affordance.
//
//  Hidden in the parent settings list when the device lacks biometric
//  capability — see `BiometricAuth.isAvailable`.
//

import SwiftUI

struct FaceIDProtectionSettingsView: View {
    @ObservedObject private var store = SessionLockStore.shared
    @AppStorage(SessionLockDefaultsKey.enabled) private var enabled: Bool = false
    @AppStorage(SessionLockDefaultsKey.idleSeconds) private var idleSeconds: Int = 300
    @AppStorage(SessionLockDefaultsKey.appLockEnabled) private var appLockEnabled: Bool = false
    @AppStorage(SessionLockDefaultsKey.appLockIdleSeconds) private var appLockIdleSeconds: Int = -1

    var body: some View {
        Form {
            // MARK: - App Lock
            Section {
                Toggle(isOn: $appLockEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Lock App")
                        Text("Require \(BiometricAuth.biometryDisplayName) to open Minis.")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: appLockEnabled) { newValue in
                    guard newValue else {
                        store.appLockEnabled = false
                        return
                    }
                    Task { @MainActor in
                        let reason = AppLocalized("Enable \(BiometricAuth.biometryDisplayName) app lock")
                        let ok = await BiometricAuth.authenticate(reason: reason)
                        if ok {
                            store.appLockEnabled = true
                            store.noteAppUnlock()
                        } else {
                            appLockEnabled = false
                        }
                    }
                }
            } footer: {
                Text("When enabled, \(BiometricAuth.biometryDisplayName) (or device passcode) is required every time you open the app.")
            }

            if appLockEnabled {
                Section {
                    Picker(AppLocalized("Require unlock"), selection: $appLockIdleSeconds) {
                        ForEach(SessionLockIdleOption.allOptions) { opt in
                            Text(opt.labelKey).tag(opt.seconds)
                        }
                    }
                    .onChange(of: appLockIdleSeconds) { newValue in
                        store.appLockIdleSeconds = newValue
                    }
                } header: {
                    Text("App Lock Timeout")
                } footer: {
                    Text("How long after leaving the app before the lock re-engages.")
                }
            }

            // MARK: - Per-Session Lock
            Section {
                Toggle(isOn: $enabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Lock Sessions")
                        Text("Long-press a session in the list to lock it.")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: enabled) { newValue in
                    guard newValue else { return }
                    Task { @MainActor in
                        let reason = AppLocalized("Enable \(BiometricAuth.biometryDisplayName) protection for chat sessions")
                        let ok = await BiometricAuth.authenticate(reason: reason)
                        if !ok {
                            enabled = false
                        }
                    }
                }
            } footer: {
                Text("When enabled, locked sessions require \(BiometricAuth.biometryDisplayName) (or device passcode) before their contents are revealed.")
            }

            if enabled {
                Section {
                    Picker(AppLocalized("Re-lock after idle"), selection: $idleSeconds) {
                        ForEach(SessionLockIdleOption.allOptions) { opt in
                            Text(opt.labelKey).tag(opt.seconds)
                        }
                    }
                } header: {
                    // Session-only timeout (bound to `idleSeconds`). The app-level
                    // lock has its own separate "App Lock Timeout" above bound to
                    // `appLockIdleSeconds` — so this stays session-scoped.
                    Text("Session Lock Timeout")
                } footer: {
                    Text("After leaving an unlocked session, the lock re-engages once the idle window elapses.")
                }

                Section {
                    HStack {
                        Text("Locked sessions")
                        Spacer()
                        Text("\(store.lockedSessionIds.count)")
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    if !store.lockedSessionIds.isEmpty {
                        Button(role: .destructive) {
                            Task { @MainActor in
                                let reason = AppLocalized("Remove all session locks")
                                let ok = await BiometricAuth.authenticate(reason: reason)
                                if ok {
                                    for sid in store.lockedSessionIds {
                                        store.unlockPermanently(sid)
                                    }
                                }
                            }
                        } label: {
                            Label("Remove All Locks", systemImage: "lock.open")
                        }
                    }
                }
            }
        }
        .navigationTitle("\(BiometricAuth.biometryDisplayName) Protection")
        .navigationBarTitleDisplayMode(.inline)
    }
}
