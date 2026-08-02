# Third-Party Licenses

OpenMinis bundles, links, or depends on the following third-party components. Versions reflect the current source tree; license types were verified against each project's repository (GitHub license metadata / LICENSE files).

## Native C/C++ dependencies (`deps/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [iSH](https://github.com/OpenMinis/ish-arm64) (ARM64 fork) | git submodule `deps/ish` | **GPL-3.0** (post-`0e3a414` contributions also under GPL-2.0), with an App Store distribution exception (`LICENSE.IOS`) | x86 Linux usermode emulation on iOS; core reason the app is GPLv3 |
| [proot](https://github.com/OpenMinis/proot) (fork) | git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, `proot-aarch64`) |
| [FFmpeg](https://ffmpeg.org) | 6.1.2, built by `deps/build_ffmpeg.sh` | **LGPL-2.1-or-later** (built without `--enable-gpl` / `--enable-nonfree`) | Dynamic frameworks on iOS; keep the LGPL configuration |
| [LAME](https://lame.sourceforge.io) | 3.100, vendored at `deps/lame-3.100` | **LGPL-2.0-or-later** | MP3 encoder, linked into FFmpeg via `--enable-libmp3lame` |
| [talloc](https://talloc.samba.org) (Samba) | vendored at `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | vendored (iOS `Vendor/cppjieba`, Android `jieba_jni`) | **MIT** | Chinese word segmentation (header-only + dictionaries) |
| Alpine Linux minirootfs | downloaded at build time by `deps/prepare_alpine_rootfs.sh` | Aggregate of package licenses (musl **MIT**, BusyBox **GPL-2.0**, etc.) | Not stored in this repo; bundled into app builds as the default rootfs |

## iOS — Swift Package Manager dependencies

Direct packages declared in `src/ios/Minis.xcodeproj`:

| Package | Version | Repository | License |
|---|---|---|---|
| SwiftAnthropic | 2.2.0 (exact) | https://github.com/jamesrochabrun/SwiftAnthropic | **MIT** |
| swift-cmark (`cmark-gfm`, `cmark-gfm-extensions`) | 0.7.1 | https://github.com/swiftlang/swift-cmark | **BSD-2-Clause** (with some MIT-licensed vendored files, see its `COPYING`) |
| SwiftMath | 1.7.3 | https://github.com/mgriebling/SwiftMath | **MIT** |
| RealTimeCutVADLibrary | 1.0.14 | https://github.com/helloooideeeeea/RealTimeCutVADLibrary | **MIT** |

Transitive packages (pinned in `Package.resolved`), all **Apache-2.0**, maintained by Apple / the Swift Server Workgroup: `async-http-client`, `swift-algorithms`, `swift-asn1`, `swift-async-algorithms`, `swift-atomics`, `swift-certificates`, `swift-collections`, `swift-crypto`, `swift-distributed-tracing`, `swift-http-structured-headers`, `swift-http-types`, `swift-log`, `swift-nio` (+ `-extras`, `-http2`, `-ssl`, `-transport-services`), `swift-numerics`, `swift-service-context`, `swift-service-lifecycle`, `swift-system`.

## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, webkit, exifinterface) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp + okhttp-sse | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| multiplatform-markdown-renderer (+ m3) — mikepenz | 0.33.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| Shizuku API + provider (dev.rikka.shizuku) | 13.1.5 | **MIT** |

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | iOS bundle / Android `assets/jieba/` | **MIT** (cppjieba distribution) |

## Removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.
