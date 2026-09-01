package com.openminis.app.backup.remote

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * User-configured rclone remotes (SMB / WebDAV / S3 / …) usable as backup
 * destinations. Mirrors `src/ios/Agent/Backup/Remote/RcloneRemoteStore.swift`.
 *
 * ## Where the credentials live
 *
 * rclone's own config file stores passwords "obscured", which is reversible by
 * design (`rclone reveal` undoes it) — so it is NOT protection. Writing a
 * user's NAS password into a file in the app's data dir would be the same
 * mistake §5.4 calls out for backup packages.
 *
 * So the split matches iOS exactly:
 *   - **EncryptedSharedPreferences** (Keystore-backed) holds the secrets —
 *     Android's equivalent of the iOS Keychain.
 *   - **Plain SharedPreferences** holds everything non-secret: name, backend
 *     type, host, share, user, path.
 *   - rclone's in-memory config is populated per launch via `config/create`,
 *     with its config file pointed at a throwaway path so rclone itself
 *     persists nothing.
 *
 * That keeps exactly one copy of each secret, in the place the platform
 * provides for it, and means deleting a remote actually removes the credential.
 */
class RcloneRemoteStore(private val context: Context) {

    /** A configured remote, minus its secret. */
    @Serializable
    data class Remote(
        /** rclone remote name — also the secret-store key. */
        val name: String,
        /** rclone backend type: "smb", "webdav", "s3", "sftp", … */
        val backend: String,
        /** Non-secret backend parameters (host, url, user, share, region…). */
        val params: Map<String, String> = emptyMap(),
        /** Directory inside the remote that backups are written to. */
        val path: String = "",
        val createdAt: Long = 0,
        /**
         * Whether new backups are delivered here.
         *
         * Disabling is NOT deleting: the server stays configured, with its
         * credential, so a user who wants to skip one destination for a while
         * doesn't have to re-enter an address and password to bring it back.
         * Defaults true so remotes stored before this field existed keep working.
         */
        val enabled: Boolean = true,
        /**
         * Accept a self-signed / untrusted TLS certificate for this server.
         *
         * Off by default: turning it on means an attacker who can intercept the
         * connection could impersonate the server, and a backup carries
         * everything the user has. It exists because a home NAS serving HTTPS
         * with its own certificate is completely ordinary, and without this the
         * only alternatives are "cannot use the app" or "downgrade to plain
         * HTTP", the second of which is strictly worse.
         *
         * The default also makes the decode tolerant: remotes stored before
         * this field existed have no key in their JSON and read back as false,
         * so adding it cannot make an existing destination vanish.
         */
        val allowInsecureTLS: Boolean = false,
    ) {
        /** `remote:` as rclone expects it. */
        val fsSpec: String get() = "$name:"

        /**
         * Join [child] under this remote's backup directory. Mirrors
         * `Remote.join` on iOS.
         *
         * Naive `"$path/$child"` breaks when the user picked the SERVER ROOT as
         * the destination: [path] is "" and the join yields "/child" — a
         * LEADING-SLASH path that rclone's WebDAV backend resolves against the
         * server root, ESCAPING the folder baked into the fs URL. Trimming
         * slashes on both sides keeps every produced path fs-relative.
         *
         * SFTP keeps its leading slash: there, `/srv/backup` and `srv/backup`
         * are DIFFERENT locations (absolute vs relative to the login user's
         * home), so trimming it rewrites the destination the user chose.
         */
        fun join(child: String): String {
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                // `/` is the filesystem ROOT, not an empty path: dropping its
                // trailing slash would leave "" and silently retarget the
                // package at the login user's home.
                if (path == "/") return "/$child"
                val base = path.removeSuffix("/")
                return if (base.isEmpty()) child else "$base/$child"
            }
            val base = path.trim('/')
            return if (base.isEmpty()) child else "$base/$child"
        }

        /** This remote's backup directory as an fs-relative listing target. */
        val listRoot: String
            get() = if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                if (path == "/") "/" else path.removeSuffix("/")
            } else {
                path.trim('/')
            }
    }

    class StoreException(message: String) : Exception(message)

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val secrets by lazy { EncryptedPrefsFactory.safeCreate(context, SECRETS_PREFS_NAME) }

    var remotes: List<Remote>
        get() = runCatching {
            prefs.getString(KEY_REMOTES, null)?.let { JSON.decodeFromString(REMOTE_LIST, it) }
        }.getOrNull() ?: emptyList()
        private set(value) {
            prefs.edit().putString(KEY_REMOTES, JSON.encodeToString(REMOTE_LIST, value)).apply()
        }

    fun remote(name: String): Remote? = remotes.firstOrNull { it.name == name }

    /** Remotes that new backups should actually be delivered to. */
    val enabledRemotes: List<Remote> get() = remotes.filter { it.enabled }

    // MARK: - Registration

    /** Add a remote. [secret] goes to the encrypted store; everything else to prefs. */
    fun add(
        name: String,
        backend: String,
        params: Map<String, String>,
        secret: String?,
        path: String,
        allowInsecureTLS: Boolean = false,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (!secret.isNullOrEmpty()) storeSecret(trimmed, secret)
        remotes = remotes + Remote(
            name = trimmed, backend = backend, params = params,
            path = path, createdAt = System.currentTimeMillis(),
            allowInsecureTLS = allowInsecureTLS,
        )
        AppLogger.info(TAG, "[Rclone] remote added: $trimmed ($backend)")
    }

    /** Remove a remote and its credential. */
    fun remove(name: String) {
        remotes = remotes.filterNot { it.name == name }
        secrets.edit().remove(name).apply()
        AppLogger.info(TAG, "[Rclone] remote removed: $name")
    }

    /** Flip a remote on or off without touching its config or credential. */
    fun setEnabled(name: String, on: Boolean) {
        val all = remotes
        if (all.none { it.name == name }) return
        remotes = all.map { if (it.name == name) it.copy(enabled = on) else it }
        AppLogger.info(TAG, "[Rclone] remote '$name' ${if (on) "enabled" else "disabled"}")
    }

    // MARK: - Secrets

    private fun storeSecret(name: String, secret: String) {
        secrets.edit().putString(name, secret).apply()
    }

    private fun loadSecret(name: String): String? = secrets.getString(name, null)

    /**
     * Which parameter carries the secret, per backend. rclone names these
     * differently and there is no generic "password" key, so the mapping is
     * explicit rather than guessed.
     */
    private fun secretKey(backend: String): String = secretKeyFor(backend)

    // MARK: - Handing config to rclone

    /**
     * Push every configured remote into rclone's in-memory config.
     *
     * Runs per launch, off the main thread. rclone is told to use a config path
     * under cacheDir so it never writes credentials to a file we would then
     * have to protect — the encrypted store stays the only copy.
     */
    fun syncToRclone() {
        val configPath = File(context.cacheDir, "rclone-ephemeral.conf").absolutePath
        runCatching { RcloneBridge.rpc("config/setpath", mapOf("path" to configPath)) }

        // Global TLS relaxation is all-or-nothing (WebDAV and S3 expose no
        // per-remote option), so it goes on only when a configured server
        // actually asks for it — one NAS with a self-signed certificate must
        // not quietly lower the bar for every other destination the user has.
        RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })

        for (r in remotes) {
            val params = buildConfigParams(
                backend = r.backend,
                params = r.params,
                secret = loadSecret(r.name),
                allowInsecureTLS = r.allowInsecureTLS,
                obscure = ::obscureViaRclone,
            ).toMutableMap<String, Any?>()
            runCatching {
                RcloneBridge.rpc(
                    "config/create",
                    mapOf(
                        "name" to r.name,
                        "type" to r.backend,
                        "parameters" to params,
                        // Don't let rclone try to run an interactive OAuth flow.
                        "opt" to mapOf("nonInteractive" to true),
                    ),
                )
            }.onFailure {
                AppLogger.error(TAG, "[Rclone] config/create failed for '${r.name}': ${it.message}")
            }
        }
        AppLogger.info(TAG, "[Rclone] synced ${remotes.size} remote(s) into rclone config")
    }

    /** `core/obscure`, or null when the RPC is unavailable/fails. */
    private fun obscureViaRclone(clear: String): String? = runCatching {
        RcloneBridge.rpc("core/obscure", mapOf("clear" to clear))
            .optString("obscured").takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        private const val TAG = "Rclone"
        private const val PREFS_NAME = "backup_rclone_remotes"
        private const val SECRETS_PREFS_NAME = "backup_rclone_secrets"
        private const val KEY_REMOTES = "remotes"

        /**
         * Which parameter carries the secret, per backend. rclone names these
         * differently and there is no generic "password" key, so the mapping
         * is explicit rather than guessed.
         */
        fun secretKeyFor(backend: String): String = when (backend) {
            "s3" -> "secret_access_key"
            else -> "pass"
        }

        /**
         * Whether this backend's secret is a PASSWORD rclone will de-obscure,
         * as opposed to a key it uses literally.
         *
         * Obscuring is rclone's expected on-the-wire form for password fields
         * and it reveals them again on use — but `secret_access_key` is
         * consumed verbatim. Obscuring it makes the client sign every S3
         * request with the obscured blob, and all of them fail with
         * SignatureDoesNotMatch (iOS 21f08d85f, found against a real MinIO).
         */
        fun secretNeedsObscuring(backend: String): Boolean = backend != "s3"

        /**
         * Build the `config/create` parameters for one remote.
         *
         * Pure so the rules below can be tested without an rclone bridge:
         * [obscure] is the `core/obscure` call, returning null when it fails.
         *
         * This is the ONLY place that decides obscure-vs-verbatim. The
         * add-time connection test builds its config from the plaintext the
         * user just typed, so when the two disagree an S3 destination tests
         * fine at add time and fails on every later list/upload — exactly the
         * bug iOS hit. Both callers go through here.
         */
        fun buildConfigParams(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            obscure: (String) -> String?,
            allowInsecureTLS: Boolean = false,
        ): Map<String, Any?> {
            val out = params.toMutableMap<String, Any?>()
            out["type"] = backend

            // FTP is the one backend here with a real per-remote switch, so use
            // it rather than relying only on the global InsecureSkipVerify —
            // this keeps FTP correct even when the global flag is off because
            // no other destination asked for it.
            if (backend == "ftp" && allowInsecureTLS) out["no_check_certificate"] = "true"

            // SMB is configured WITHOUT a share, so the share becomes the
            // first level of the folder browser — what Finder and Windows
            // Explorer do when they enumerate shares after connecting. A
            // `share` stored by an older build is DROPPED rather than
            // migrated: a share-less fs reaches every share the account can
            // see, so the stored path still resolves through the browser,
            // while keeping the key would pin that remote to the one share it
            // was created with.
            if (backend == "smb") out.remove("share")

            // Blank credentials mean GUEST, and that has to be said
            // explicitly: rclone's smb `user` option defaults to the OS login
            // name when the key is absent, so an omitted username would
            // silently try to authenticate as some unrelated account instead
            // of connecting as a guest.
            if (backend == "smb" && (params["user"] ?: "").isBlank()) out["user"] = ""

            val hasSecret = !secret.isNullOrEmpty()

            // Anonymous access needs an OBSCURED EMPTY password, not a missing
            // one. rclone marks `pass` as IsPassword and de-obscures whatever
            // it finds — including the option's own "" default — so a bare ""
            // or an absent key fails with "decrypt password: input too short"
            // before any connection is attempted.
            //
            // Measured against rclone v1.73.3, this bites per backend: FTP
            // fails outright on a bare "" (and reaches the server's own
            // "Login incorrect" once obscured), while WebDAV tolerates it and
            // gets as far as a 401. `obscure("")` is the single form that
            // works for BOTH, so it is applied uniformly rather than
            // per-backend — one rule, no backend-specific exceptions to keep
            // in sync as rclone's option defaults change.
            //
            // S3 is excluded: its secret is consumed verbatim, so an anonymous
            // bucket wants NO key rather than an obscured blank.
            if (backend != "s3" && !hasSecret && (out["pass"] as? String).isNullOrEmpty()) {
                obscure("")?.let { out["pass"] = it }
                return out
            }

            // An empty stored secret is never written as a parameter.
            if (!hasSecret) {
                out.remove(secretKeyFor(backend))
                return out
            }

            out[secretKeyFor(backend)] =
                if (secretNeedsObscuring(backend)) obscure(secret!!) ?: secret else secret
            return out
        }
        /**
         * Whether a failed connection looks like a TLS trust problem.
         *
         * Used to decide whether to OFFER the self-signed opt-in, so it errs
         * toward not offering: matching too eagerly would put a "don't verify
         * certificates" control in front of users whose real problem is a wrong
         * password. The strings are what Go's crypto/tls and x509 actually
         * surface through rclone — rclone passes the underlying error text
         * through rather than normalising it, so there is no error code to key
         * off instead.
         *
         * Pure, so the matching can be tested without a server.
         */
        fun looksLikeCertificateFailure(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return m.contains("x509") ||
                m.contains("certificate") ||
                m.contains("tls: failed to verify") ||
                m.contains("unknown authority") ||
                m.contains("self-signed") ||
                m.contains("self signed")
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val REMOTE_LIST = ListSerializer(Remote.serializer())
    }
}
