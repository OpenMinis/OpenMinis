import Foundation

private let logger = AppLogger(category: "Backup")

/// The credential section of a backup package (docs/backup-restore-design.md §5.4).
///
/// **Stage 3a scope: the values are base64-encoded, NOT encrypted.** §5's
/// AES-256-GCM/Argon2id layer is a later step, so a package produced now
/// contains credentials that anyone can decode with a base64 tool. The manifest
/// reports `encrypted: false` and `includesCredentials: true` so this is stated
/// rather than implied — see `BackupExporter.exportProviders`.
///
/// Field names and encoding are NOT invented here. They match the existing
/// cross-platform provider-share format on both ends:
///   - iOS `ProviderConfigStore.exportInstanceJSON` (ProviderConfigStore.swift:1037)
///   - Android `ProviderRepository.exportInstanceJSON`
///     (data/repository/ProviderRepository.kt:2122, credentials at :2204-2242)
/// Both write `apiKey` / `manualOAuthToken` / `oauthToken` / `oauthEmail` /
/// `oauthGcpProject` as base64 of the UTF-8 bytes (Android uses
/// `Base64.NO_WRAP`, which is the same standard alphabet + padding that
/// `Data.base64EncodedString()` produces, just without line breaks — and
/// `base64EncodedString()` emits no line breaks by default, so the two are
/// byte-identical). Deviating here would silently break interop, which §5.4
/// explicitly warns against ("不再发明第二套").
struct BackupSecrets: Codable {
    var v: Int = 1
    var providers: [ProviderSecret] = []
    var envVars: [EnvVarSecret] = []
    var mcpOAuth: [MCPOAuthSecret] = []

    /// One provider instance's credentials. Every value is base64; nil means
    /// the credential simply isn't present for that instance.
    struct ProviderSecret: Codable {
        let instanceId: String
        /// Carried for human/debug legibility and to help a cross-platform
        /// importer match instances; not a secret.
        let label: String?
        let providerType: String?
        var apiKey: String?
        var manualOAuthToken: String?
        /// The structured OAuth login blob (access + refresh + expiry),
        /// JSON-encoded then base64'd — matching both platforms' `oauthToken`.
        var oauthToken: String?
        var oauthEmail: String?
        var oauthGcpProject: String?

        var isEmpty: Bool {
            apiKey == nil && manualOAuthToken == nil && oauthToken == nil
                && oauthEmail == nil && oauthGcpProject == nil
        }
    }

    struct EnvVarSecret: Codable {
        /// The variable name, e.g. `GITHUB_TOKEN`. Plaintext: it's metadata,
        /// and §2's `env_vars.json` already carries it unencoded.
        let name: String
        /// base64 of the value's UTF-8 bytes.
        let value: String
    }

    struct MCPOAuthSecret: Codable {
        let serverId: String
        /// base64 of the JSON token blob.
        let token: String
        /// base64 of the client secret, when one was issued.
        var clientSecret: String?
    }
}

/// Reads credentials out of the Keychain and assembles the §5.4 structure.
///
/// §5.4 is explicit that the ONLY viable path is "decrypt in-process, re-encode,
/// re-protect with the backup's own key" — a Keychain item cannot be exported as
/// a ciphertext container, and Android's EncryptedSharedPreferences XML is
/// device-bound garbage off-device. So this deliberately reads plaintext into
/// memory; the protection is supposed to come from §5's encryption layer.
enum BackupSecretsCollector {

    /// Assemble the credential section for the given provider instances.
    ///
    /// - Parameter instances: the instances being exported, so the secrets
    ///   section never carries keys for a provider the package doesn't include.
    @MainActor
    static func collect(instances: [ProviderInstance]) -> BackupSecrets {
        var out = BackupSecrets()

        for instance in instances {
            var secret = BackupSecrets.ProviderSecret(
                instanceId: instance.id,
                label: instance.label,
                providerType: instance.providerType.rawValue)

            if let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) {
                secret.apiKey = b64(key)
            }
            if let manual = ProviderKeychainHelper.loadOAuthString(
                instanceId: instance.id, account: "manual-oauth-token") {
                secret.manualOAuthToken = b64(manual)
            }
            // The structured OAuth blob is stored under a per-provider-type
            // Codable, so the read has to switch on the type exactly as
            // exportInstanceJSON does.
            if let blob = oauthTokenBlob(for: instance) {
                secret.oauthToken = blob.base64EncodedString()
            }
            if instance.providerType == .gemini {
                if let email = ProviderKeychainHelper.loadOAuthString(
                    instanceId: instance.id, account: "oauth-email") {
                    secret.oauthEmail = b64(email)
                }
                if let project = ProviderKeychainHelper.loadOAuthString(
                    instanceId: instance.id, account: "oauth-gcp-project") {
                    secret.oauthGcpProject = b64(project)
                }
            }

            // An instance with no credential contributes nothing — writing an
            // all-nil row would just inflate the section and imply a secret
            // exists where none does.
            if !secret.isEmpty { out.providers.append(secret) }
        }

        for entry in EnvVarStore.shared.entries {
            guard let value = EnvVarStore.loadValueSync(forKey: entry.key), !value.isEmpty
            else { continue }
            out.envVars.append(.init(name: entry.key, value: b64(value)))
        }

        // MCPServerConfig.id IS the servers.json key / server name — the same
        // string MCPOAuthController keys its Keychain accounts by.
        for server in MCPStore.shared.servers {
            guard let tokens = MCPOAuthController.tokens(server: server.id),
                  let data = try? JSONEncoder().encode(tokens) else { continue }
            var item = BackupSecrets.MCPOAuthSecret(
                serverId: server.id, token: data.base64EncodedString())
            if let cs = MCPOAuthController.clientSecret(server: server.id), !cs.isEmpty {
                item.clientSecret = b64(cs)
            }
            out.mcpOAuth.append(item)
        }

        logger.info("[Backup] secrets collected providers=\(out.providers.count) envVars=\(out.envVars.count) mcpOAuth=\(out.mcpOAuth.count)")
        return out
    }

    @MainActor
    private static func oauthTokenBlob(for instance: ProviderInstance) -> Data? {
        let id = instance.id
        switch instance.providerType {
        case .anthropic:
            return ProviderKeychainHelper.loadOAuthToken(instanceId: id, as: ClaudeTokenStorage.self)
                .flatMap { try? JSONEncoder().encode($0) }
        case .openAI:
            return ProviderKeychainHelper.loadOAuthToken(instanceId: id, as: CodexTokenStorage.self)
                .flatMap { try? JSONEncoder().encode($0) }
        case .gemini:
            return ProviderKeychainHelper.loadOAuthToken(instanceId: id, as: GeminiTokenStorage.self)
                .flatMap { try? JSONEncoder().encode($0) }
        case .xAI:
            return ProviderKeychainHelper.loadOAuthToken(instanceId: id, as: XAITokenStorage.self)
                .flatMap { try? JSONEncoder().encode($0) }
        case .kimiCode:
            return ProviderKeychainHelper.loadOAuthToken(instanceId: id, as: KimiTokenStorage.self)
                .flatMap { try? JSONEncoder().encode($0) }
        default:
            return nil
        }
    }

    private static func b64(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
    }
}
