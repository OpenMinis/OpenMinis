import Foundation

/// The remote types offered in "Add Server", and the fields each one needs.
///
/// rclone accepts dozens of options per backend; asking for all of them would
/// make this unusable. Each entry lists only what is actually required to
/// connect, with everything else left at rclone's defaults — a user adding a
/// NAS should answer four questions, not forty.
///
/// Adding a type here does NOT add the code for it: the backend must also be
/// imported in deps/rclone-mobile/backends/backends.go, or rclone will reject
/// the config at runtime with "didn't find backend called …".
enum RcloneBackendCatalog {

    struct Field: Identifiable {
        var id: String { key }
        /// rclone parameter name.
        let key: String
        let label: String
        let placeholder: String
        /// One line saying what this field IS, for terms a user can't be
        /// expected to know. "Share: backups" told someone nothing about
        /// whether that meant a folder, a server name, or an example — the
        /// placeholder alone reads as a value, not as an explanation.
        var hint: String = ""
        /// Routed to the Keychain instead of UserDefaults.
        var isSecret = false
        var isOptional = false
        var keyboard: KeyboardKind = .default

        enum KeyboardKind { case `default`, url, numeric, email }
    }

    struct Backend: Identifiable {
        var id: String { type }
        /// rclone backend type — must match a directory under backend/.
        let type: String
        let title: String
        let subtitle: String
        let icon: String
        let fields: [Field]
    }

    /// [T-sftp-absolute-path] Whether a leading `/` in this backend's path is a
    /// FILESYSTEM-ABSOLUTE path that must be preserved.
    ///
    /// Only SFTP. For sftp, `remote:` resolves to the login user's home and
    /// `remote:/etc` is a genuinely different, valid location — verified
    /// against a non-chrooted server, where `remote:/` lists the real
    /// filesystem root. Stripping the slash silently rewrites such a path into
    /// a home-relative one.
    ///
    /// Every other backend here is URL/prefix-based, where a leading `/`
    /// resolves against the SERVER root and escapes the folder baked into the
    /// remote's fs spec. That is the WebDAV escape fixed in 1dec9e650 (a
    /// package written to `http://host/backup-….minisbak` instead of
    /// `…/backups/…`), so their stripping must stay exactly as it is.
    static func usesAbsolutePaths(_ backendType: String) -> Bool {
        backendType == "sftp"
    }

    /// Ordered by how likely someone backing up a phone is to want it.
    static let all: [Backend] = [
        Backend(
            type: "smb",
            title: "SMB / Windows Share",
            subtitle: "NAS, Windows shared folder, Samba",
            icon: "externaldrive.connected.to.line.below",
            fields: [
                .init(key: "host", label: "Server", placeholder: "192.168.1.10",
                      hint: AppLocalized("The NAS or PC's address on your network.")),
                // No "Share" field on purpose. rclone takes the share as a
                // config option, and mirroring that put a flat network name
                // ("backups") in front of a user whose NAS displays a disk path
                // ("/volume1/backups") — they type the path, and SMB reports a
                // missing share as a LOGON failure, so the app blamed their
                // credentials. Connecting share-less instead makes the share
                // the browser's first level, which is what every desktop SMB
                // client does. See the `share` strip in RcloneRemoteStore.
                // Optional: guest shares are a NAS default (Synology/QNAP ship
                // one), and requiring credentials left Connect permanently
                // greyed out with nothing explaining why — the same trap
                // anonymous WebDAV hit. Blank user/pass means guest access,
                // which is what rclone does when the keys are absent.
                .init(key: "user", label: "Username", placeholder: "admin", isOptional: true),
                .init(key: "pass", label: "Password", placeholder: "",
                      isSecret: true, isOptional: true),
                .init(key: "domain", label: "Domain", placeholder: "WORKGROUP",
                      hint: AppLocalized("Only needed on a corporate network that uses one."),
                      isOptional: true),
            ]),
        Backend(
            type: "webdav",
            title: "WebDAV",
            subtitle: "Nextcloud, ownCloud, Synology, alist",
            icon: "network",
            fields: [
                .init(key: "url", label: "URL", placeholder: "https://example.com/dav",
                      hint: AppLocalized("The full WebDAV address, including https:// and any path."),
                      keyboard: .url),
                // Optional on purpose: anonymous WebDAV shares are ordinary
                // (a guest NAS folder, alist public dir, a LAN test server).
                // Requiring credentials left Connect permanently greyed out
                // with no way to proceed and nothing explaining why.
                .init(key: "user", label: "Username", placeholder: "", isOptional: true),
                .init(key: "pass", label: "Password", placeholder: "",
                      isSecret: true, isOptional: true),
            ]),
        Backend(
            type: "sftp",
            title: "SFTP",
            subtitle: "A Linux server or NAS you access over SSH",
            icon: "terminal",
            fields: [
                .init(key: "host", label: "Server", placeholder: "example.com",
                      hint: AppLocalized("Address of the machine you connect to over SSH.")),
                .init(key: "user", label: "Username", placeholder: ""),
                .init(key: "pass", label: "Password", placeholder: "", isSecret: true),
                .init(key: "port", label: "Port", placeholder: "22",
                      hint: AppLocalized("Leave blank unless SSH runs on a non-standard port."),
                      isOptional: true,
                      keyboard: .numeric),
            ]),
        Backend(
            type: "s3",
            title: "S3-Compatible Storage",
            subtitle: "MinIO, Cloudflare R2, Wasabi, Alibaba OSS, Tencent COS…",
            icon: "cylinder.split.1x2",
            fields: [
                .init(key: "endpoint", label: "Endpoint", placeholder: "s3.example.com",
                      hint: AppLocalized("Your provider's S3 address — not the bucket name."),
                      keyboard: .url),
                .init(key: "access_key_id", label: "Access Key ID", placeholder: "",
                      hint: AppLocalized("Issued by your storage provider, together with the secret key.")),
                .init(key: "secret_access_key", label: "Secret Access Key",
                      placeholder: "", isSecret: true),
                .init(key: "region", label: "Region", placeholder: "us-east-1",
                      hint: AppLocalized("Leave blank if your provider doesn't use regions."),
                      isOptional: true),
            ]),
        Backend(
            type: "ftp",
            title: "FTP",
            subtitle: "Older file servers and routers",
            icon: "arrow.up.arrow.down.circle",
            fields: [
                .init(key: "host", label: "Server", placeholder: "ftp.example.com",
                      hint: AppLocalized("Address of the FTP server.")),
                // Anonymous FTP is the protocol's own convention; leaving these
                // blank means exactly that.
                .init(key: "user", label: "Username", placeholder: "", isOptional: true),
                .init(key: "pass", label: "Password", placeholder: "",
                      isSecret: true, isOptional: true),
                .init(key: "port", label: "Port", placeholder: "21",
                      hint: AppLocalized("Leave blank for the standard port."),
                      isOptional: true,
                      keyboard: .numeric),
            ]),
    ]

    static func backend(for type: String) -> Backend? {
        all.first { $0.type == type }
    }

    /// The field whose value belongs in the Keychain, if any.
    static func secretField(for type: String) -> String? {
        backend(for: type)?.fields.first(where: \.isSecret)?.key
    }
}
