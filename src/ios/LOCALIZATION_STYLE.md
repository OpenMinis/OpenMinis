# Localization house style

Applies to every locale in `Localizable.xcstrings`. Written down because these
are judgement calls that are invisible in review and easy to silently reverse.

## 1. Prefer the word local internet products use, not the most formal one

When a language has both an official/academy-sanctioned term and a widely-used
loanword, **use the one people actually see in popular apps** (WhatsApp,
Telegram, Instagram, the local banking/e-commerce apps). Language-academy
coinages read as bureaucratic in a consumer app, and users often do not
recognise them at all.

This is a product decision, not a linguistic one: the app should sound like
software people already use, not like a government form.

Applies to the same class of term in every language: file, browser, clipboard,
download/upload, email, link, password, backup, folder, server, online/offline.

### Indonesian (`id`) — decided 2026-08-28

| EN | chosen | rejected | why |
|---|---|---|---|
| File | **File** | ~~Berkas~~ | WhatsApp/Telegram/Google Drive ID all use "file". `Berkas` is correct written Indonesian but reads as officialese. 109 strings. |
| Browser | **Browser** | ~~Peramban~~ | `Peramban` is a Pusat Bahasa coinage; no consumer app uses it. |
| Clipboard | **Clipboard** | ~~Papan Klip~~ | Nobody says *papan klip*. |
| Preview | **Pratinjau** | — | Genuinely common in Indonesian apps; kept. |
| Settings | **Pengaturan** | — | Standard everyday usage (WhatsApp). Not officialese. |
| Download / Upload | **Unduh / Unggah** | — | Both are normal spoken Indonesian. |
| Password | **Kata Sandi** | — | Standard; used by WhatsApp and every ID bank app. |
| Link | **Tautan** | — | Common in-app; `link` is also heard, but `tautan` is not marked. |
| Edit | **Ubah** | ~~Edit~~ | `Edit` as a value identical to the English source is also indistinguishable from a missing translation. |

Deliberately left in English across all locales: product names (Minis, iCloud,
HealthKit, Alpine Linux), protocol/format literals (MCP, SMB, WebDAV, JSON,
HTTP, SSE), and API parameter identifiers (`enable_thinking`,
`reasoning_effort`, `thinkingBudget`, `live_activity.status.*`). "Skill" is a
product term and stays English so it matches the `/` command and `SKILL.md`.

## 2. Register

Formal-but-not-stiff second person: `Anda` (id), `Anda` (ms), `คุณ` (th),
`siz` (tr), the impersonal/`Pan`-neutral phrasing (pl), `dumneavoastră`-neutral
(ro), `você` (pt-BR). Avoid both the intimate form (reads as unprofessional in
a developer tool) and heavy honorifics.

Buttons use the plain imperative, not a nominalised form.

## 3. Things that must survive translation byte-for-byte

- Format specifiers: `%@`, `%lld`, `%1$@` — same count, order and spelling.
  Reorder with positional specifiers only where grammar genuinely requires it.
- Newline structure and leading/trailing whitespace.
- Markdown emphasis markers (`**bold**`) and the arrow/bullet glyphs used in
  multi-line settings footers.

`scripts/check_locale_batch.py` gates all of the above per batch.

## 4. Adding a locale

Five things move together; missing any one fails silently:

1. `Localizable.xcstrings` — use `scripts/add_locale.py`, never `json.dump`
   (a round-trip reflows the whole catalog, producing a huge diff that
   conflicts with any other in-flight change to the file).
2. `project.pbxproj` — `knownRegions`, a `PBXFileReference`, and membership in
   the `InfoPlist.strings` `PBXVariantGroup`. A new `.lproj` does **not** join
   the target automatically.
3. `<locale>.lproj/InfoPlist.strings` — write the permission prompts by hand;
   App Store review reads them.
4. `Info.plist` → `CFBundleLocalizations` — a locale missing here is not
   offered in iOS Settings and system controls fall back to English.
5. The in-app picker in `ContentView.swift`. `Bundle.setLanguage` resolves via
   `path(forResource:ofType:"lproj")` and falls back to the system language in
   silence, so a picker entry without a real `.lproj` looks like a no-op bug.

Note: SwiftUI's `EditButton()` renders from UIKit's own catalog, not ours, so it
cannot be localized from `Localizable.xcstrings` in any language.
