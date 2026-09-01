import Foundation

private let logger = AppLogger(category: "Backup")

/// Writes a package's credential section back into the Keychain
/// (docs/backup-restore-design.md §5.4).
///
/// [T-ios-backup-credential-restore] Before this existed, `secrets.json` was
/// collected, base64'd, encrypted, decrypted on restore — and then **discarded**,
/// because nothing read it. The restore UI meanwhile said providers "may need
/// to be re-authenticated if their tokens have expired", when in truth they
/// always did. That is precisely the "provider list intact, every request 401"
/// half-restored state design §3.3 merged Providers+Credentials to prevent, and
/// it made device migration — the feature's primary purpose — silently fail.
///
/// Merge semantics match every other category (§8.2): a credential already
/// present locally is NOT overwritten. Restoring an old backup must not clobber
/// a key the user has since rotated; the local value is the newer one by
/// definition, since the backup is a snapshot of the past.
enum BackupSecretsImporter {

    struct Result {
        var providersRestored = 0
        var providersSkippedExisting = 0
        var envVarsRestored = 0
        var envVarsSkippedExisting = 0
        var mcpOAuthRestored = 0
        var mcpOAuthSkippedExisting = 0

        var total: Int { providersRestored + envVarsRestored + mcpOAuthRestored }
    }

    /// Read `secrets.json` from an unpacked package root and apply it.
    ///
    /// Returns nil when the package carries no credential section at all —
    /// which is the normal case for a "share copy" export (§3.3).
    @MainActor
    static func restore(from root: URL) -> Result? {
        let url = root.appendingPathComponent("secrets.json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        guard let secrets = try? JSONDecoder().decode(BackupSecrets.self, from: data) else {
            logger.error("[Restore] secrets.json present but unreadable — credentials NOT restored")
            return nil
        }

        var result = Result()

        for p in secrets.providers {
            var wrote = false
            // Each field is independent: an instance may carry an apiKey, an
            // OAuth blob, or both, and a missing one must not block the others.
            if let b64 = p.apiKey, let value = decode(b64) {
                if ProviderKeychainHelper.loadAPIKey(instanceId: p.instanceId) == nil {
                    ProviderKeychainHelper.saveAPIKey(value, instanceId: p.instanceId)
                    wrote = true
                } else {
                    result.providersSkippedExisting += 1
                }
            }
            if let b64 = p.manualOAuthToken, let value = decode(b64),
               ProviderKeychainHelper.loadOAuthString(
                   instanceId: p.instanceId, account: "manual-oauth-token") == nil {
                ProviderKeychainHelper.saveOAuthString(
                    value, instanceId: p.instanceId, account: "manual-oauth-token")
                wrote = true
            }
            if let b64 = p.oauthEmail, let value = decode(b64),
               ProviderKeychainHelper.loadOAuthString(
                   instanceId: p.instanceId, account: "oauth-email") == nil {
                ProviderKeychainHelper.saveOAuthString(
                    value, instanceId: p.instanceId, account: "oauth-email")
                wrote = true
            }
            if let b64 = p.oauthGcpProject, let value = decode(b64),
               ProviderKeychainHelper.loadOAuthString(
                   instanceId: p.instanceId, account: "oauth-gcp-project") == nil {
                ProviderKeychainHelper.saveOAuthString(
                    value, instanceId: p.instanceId, account: "oauth-gcp-project")
                wrote = true
            }
            // The structured OAuth blob is stored under a per-provider-type
            // Codable. It is written back as raw JSON under the same Keychain
            // account, so the importer doesn't have to switch on every provider
            // type (and stays correct when a new one is added).
            if let b64 = p.oauthToken, let raw = Data(base64Encoded: b64) {
                if ProviderKeychainHelper.loadRawOAuthToken(instanceId: p.instanceId) == nil {
                    ProviderKeychainHelper.saveRawOAuthToken(raw, instanceId: p.instanceId)
                    wrote = true
                }
            }
            if wrote { result.providersRestored += 1 }
        }

        for e in secrets.envVars {
            guard let value = decode(e.value) else { continue }
            if EnvVarStore.loadValueSync(forKey: e.name) == nil {
                EnvVarStore.saveValueSync(value, forKey: e.name)
                result.envVarsRestored += 1
            } else {
                result.envVarsSkippedExisting += 1
            }
        }

        for m in secrets.mcpOAuth {
            if MCPOAuthController.tokens(server: m.serverId) == nil,
               let raw = Data(base64Encoded: m.token),
               let tokens = try? JSONDecoder().decode(
                   MCPOAuthController.StoredTokens.self, from: raw) {
                MCPOAuthController.restoreTokens(tokens, server: m.serverId)
                result.mcpOAuthRestored += 1
            } else {
                result.mcpOAuthSkippedExisting += 1
            }
            if let b64 = m.clientSecret, let secret = decode(b64),
               MCPOAuthController.clientSecret(server: m.serverId) == nil {
                MCPOAuthController.setClientSecret(secret, server: m.serverId)
            }
        }

        logger.info("[Restore] credentials: providers=\(result.providersRestored) (kept \(result.providersSkippedExisting)) envVars=\(result.envVarsRestored) mcpOAuth=\(result.mcpOAuthRestored)")
        return result
    }

    private static func decode(_ b64: String) -> String? {
        guard let data = Data(base64Encoded: b64) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
