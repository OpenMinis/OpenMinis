# Streaming package writer — design

**Status**: proposed, 2026-08-18
**Supersedes**: the packaging half of `backup-restore-design.md` §2 (staging →
`NSFileCoordinator` zip). Everything else in that document still holds.

## The problem

Backing up 3.84 GB of data currently needs roughly **11.5 GB of free space**,
because the same bytes exist three times at once:

| Copy | Where | Why it exists |
|---|---|---|
| ~3.9 GB | staging directory | every blob is copied there before packaging |
| ~3.8 GB | system-generated zip | `NSFileCoordinator(.forUploading)` builds it in its own temp location |
| ~3.8 GB | final package | `archive()` then `copyItem`s it out |

There is also **no free-space check anywhere in the export path** (the import
path has one). A user who lacks the space gets a generic Foundation I/O error
several minutes in, during `Packaging…`, with nothing saying the disk was the
problem.

This is a hard failure: it does not make the backup slow, it makes it
impossible.

## Goal

Peak local usage ≈ **one copy** — the finished package — while keeping:

- a single self-contained `.minisbak` file on the destination (users can copy
  it off a NAS by hand; this was a deliberate decision when chunked upload was
  removed on 2026-08-16 and it stands);
- resumability of an interrupted export;
- the existing on-disk format, so already-shipped packages stay restorable.

## Approach

Write the zip **sequentially, as content is produced**, instead of staging
everything and archiving at the end. Each blob is: read from its original file
→ (optionally compressed) → (optionally encrypted) → appended to the package →
its temporary copy deleted immediately.

Only one blob's temporary copy exists at a time, so peak usage is the package
plus the largest single member.

**The original files are never touched.** Everything deleted along the way is
our own temporary copy.

### Per-entry pipeline

```
plaintext source
  → [deflate]      only when the content is actually compressible
  → [AES-GCM]      only for encrypted backups; always AFTER compression
  → zip entry      appended to the package
  → delete temp
```

Compress-then-encrypt is the only order that works: ciphertext has no
exploitable redundancy, so compressing it afterwards achieves nothing.

### Compression: per-entry choice

zip lets each entry pick its method, which `NSFileCoordinator` never exposed:

| Content | Method | Why |
|---|---|---|
| `.jsonl` records, manifest | deflate | plain text, often compresses 5–10× |
| images / video / audio / archives | **store** | already compressed; deflate costs full CPU to save ~0–2% |
| anything encrypted | **store** | ciphertext is incompressible by construction |
| everything else | deflate | modest default |

Selected by extension from the blob's logical path — no need to sniff content.

This is why a large export spends minutes in `Packaging…` today: it is
recompressing tens of thousands of already-compressed attachments.

A `store` entry has a further benefit — its final size is known before writing,
so it needs no temporary copy at all: read, hash, and write in one pass.

### Encryption moves earlier

Today encryption runs **after** every category has been exported: it walks the
staging tree and rewrites each file in place. That cannot work once blobs are
deleted as they are written — by then there is nothing left to walk.

So encryption moves into the per-entry pipeline, immediately before the entry
is appended. `BackupCrypto.encryptFile` already streams file-to-file in 4 MiB
GCM segments with a path-derived AAD, so it fits without format changes.

Two things improve as a side effect:

- **Plaintext never reaches disk.** Today the staging tree holds plaintext
  chats and API keys until the encryption pass overwrites them. In the new
  flow the only temporary copy written is already ciphertext.
- **One full read/write pass disappears.** The current encryption pass re-reads
  and rewrites the entire staging tree (~7.7 GB of I/O for a 3.84 GB backup).

`manifest.json` and `rescue.json` stay plaintext, exactly as now and for the
same reasons (§2.1, and rescue being useless to someone who has lost the
passphrase).

`integrity` continues to record **ciphertext** hashes, so a package can be
verified without the passphrase.

### No data descriptors

zip can defer an entry's CRC and sizes to a trailer (bit 3). We deliberately
do **not**, because it would break resume — see below. Since every entry's
bytes pass through our hands before the header is written, the real CRC and
sizes are known up front.

### Resume: read it back out of the package

An interrupted export leaves a partial, header-terminated zip. Resume is
derived **entirely from that file**, with no external bookkeeping:

1. Walk the local-header chain from offset 0. Each header carries the entry
   name and both sizes, so the next header's offset is exact.
2. Stop at the first header that is incomplete or whose data runs past EOF.
   Truncate the package to that header's start offset.
3. The names of the surviving entries — blob entries are named by SHA-256 —
   are the set already written.
4. Continue the export, skipping those.

Deriving this from the file rather than from a journal is what makes it robust
against the failure modes that matter: a killed process, unflushed buffers, or
a recorded offset that disagrees with what actually landed. The file is the
only source of truth about the file.

This is precisely why data descriptors are excluded. With sizes deferred, a
header does not say how far to jump, so the scan would have to hunt for the
next `PK\x03\x04` signature — a byte sequence that occurs by chance inside
compressed data, making the scan unreliable exactly when it is needed.

Cost: the scan reads headers and seeks past payloads, so it is seconds even on
a multi-gigabyte package. CRCs are verified for the last few entries by default
(corruption can only be at the tail); full verification is available but not
the default.

Requires the export's traversal order to be stable across runs, which it is
(session ids are sorted).

## zip64 — a pre-existing bug, fixed here

`BackupZipExtractor` has **no zip64 support at all** (verified: no reference to
zip64, the 0x0001 extra field, or the `0xFFFFFFFF` sentinel anywhere in the
reader).

This is not a consequence of the streaming change. It is broken in shipped
code today:

> `NSFileCoordinator` correctly emits zip64 once a package exceeds 4 GB. Such
> a package exports, uploads and sits on the NAS looking perfectly healthy —
> **and our reader cannot restore it.**

The most recent real backup was **3.84 GB**, i.e. 4% below the boundary. A few
more large attachments cross it, and the failure surfaces at restore time —
the one moment a backup must work.

So the reader fix ships **first, on its own**, ahead of the streaming work:

- zip64 End of Central Directory record + locator;
- `0xFFFFFFFF` / `0xFFFF` sentinels in central and local headers resolved from
  the zip64 extended information extra field (0x0001);
- 64-bit sizes, offsets and entry counts throughout.

The writer then emits zip64 from the start — never as a later "when we need
it" addition, because the bug it prevents is invisible on small packages and
only appears on the large ones that matter most.

## Free-space check

Before starting, estimate the package size and compare against
`volumeAvailableCapacityForImportantUsage`. If short, fail immediately with the
numbers (`needs about X GB, Y GB free`) rather than after minutes of work with
a generic I/O error.

The estimate is deliberately rough — total blob bytes plus a margin — because
being approximately right before the work starts is far more useful than being
exactly right after it fails.

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **zip64 correctness** | High | Ships first, separately; synthetic >4 GB round-trip test; small packages must stay byte-compatible with the current reader |
| Writer/reader disagreement | Medium | Both are ours and in one codebase; every export is parsed back with the real reader before being reported as successful; full export→restore run on real device data |
| Resume truncation lands mid-entry | Medium | Truncate to a header boundary only; verify the last surviving entry parses before continuing; otherwise fall back to the previous good boundary |
| Deleting a temp copy too early | Low | Only ever our own temp copy; the user's original file is never touched, so worst case a blob is re-exported |

Note the earlier concern that "the reader might not read what we write" was
overstated: both sides are ours, so a mismatch is a compile-time-adjacent
problem caught by round-trip tests, not a runtime surprise. What is genuinely
lost is `NSFileCoordinator` acting as a free third-party check on the reader —
recovered by the round-trip test.

## Sequencing

| Step | Peak | Ships |
|---|---|---|
| 0. Reader zip64 + free-space check | 11.5 GB | Independently — fixes an existing restore failure |
| 1. Streaming writer + per-entry compression | ~7.7 GB | With round-trip tests |
| 2. Blob write-then-delete + encryption moved into the pipeline | ≈ package size | After 1 is verified on-device |

Step 0 is worth shipping on its own: it repairs restores that are broken today,
regardless of whether the rest lands.

## Explicitly unchanged

- `.minisbak` stays a standard zip. Restore of existing packages is unaffected.
- The destination still receives one self-contained file.
- rclone still uploads a complete local file (`operations/copyfile` takes a
  path), which is why peak cannot go below one package without abandoning the
  single-file format — a separate product decision, not taken here.
