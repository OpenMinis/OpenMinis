// Package backends pins which rclone backends are linked into the app.
//
// This file IS the size trim. rclone registers each backend from its own
// init(), which only runs when the package is imported — so importing a
// subset instead of rclone's `backend/all` leaves the rest out of the binary
// entirely. Verified with nm: an excluded backend contributes 0 symbols.
//
// Measured on iOS arm64 (c-archive, -s -w), gzipped ≈ the IPA delta:
//
//	webdav only ......  18.1 MB raw /  6.0 MB gz
//	smb+webdav .......  20.0 MB raw /  6.6 MB gz
//	these 5 ..........  see build output (was 55.4 MB raw with the OAuth set)
//	the earlier 10 ...  55.4 MB raw / 15.7 MB gz
//	all 70 ...........  89.7 MB raw / 25.6 MB gz
//
// ~18 MB of that is the Go runtime and is paid regardless of backend count.
//
// Scope: exactly the backends the UI can reach. RcloneBackendCatalog offers
// SMB / WebDAV / SFTP / S3 / FTP and nothing else, so anything past that list
// was paying IPA size for a code path no user could ever select.
//
// Removed 2026-08-16: azureblob, box, drive, dropbox, onedrive. They had no
// catalog entry, no OAuth flow, and config/create is called with
// nonInteractive:true — which forbids the interactive authorisation those
// backends require. Dropbox / OneDrive / Google Drive remain usable as backup
// destinations through "Add Folder…": the user connects them in the Files app
// and iOS's FileProvider does the transfer, so the token stays with the
// system rather than in this app. Re-adding any of them means building that
// OAuth flow first, not just restoring an import line.
//
// Deliberately NOT imported: crypt / chunker / compress / cache. Those are
// rclone's own wrapper layers, and the app already has minisbak-enc/1
// encryption plus its own chunking — importing them would leave two
// mechanisms doing the same job.
//
// Note: `crypt` may still show up in config/providers at runtime — it arrives
// transitively via fs/operations. Harmless: nothing routes through it.
package backends

import (
	// `local` is NOT optional: every upload reads from the device filesystem,
	// and rclone addresses that through a backend like any other. Without it
	// operations/copyfile fails with `didn't find backend called "local"`.
	// Caught on device, not by reading the code.
	_ "github.com/rclone/rclone/backend/local"

	_ "github.com/rclone/rclone/backend/ftp"
	// s3 alone covers 53 providers (Cloudflare R2, MinIO, Synology,
	// TencentCOS, Wasabi, Alibaba OSS, Qiniu …) — see backend/s3/provider/.
	_ "github.com/rclone/rclone/backend/s3"
	_ "github.com/rclone/rclone/backend/sftp"
	_ "github.com/rclone/rclone/backend/smb"
	_ "github.com/rclone/rclone/backend/webdav"
)
