# Building Minis

Minis ships a full Linux sandbox inside the app, so a first build is not just
"open the project and press Run": the native dependencies (iSH on iOS, PRoot on
Android, FFmpeg, LAME) and the Alpine rootfs are **built from source by the
scripts in `deps/`**, not committed as binaries. Budget ~30–60 minutes for the
first build; afterwards the artifacts are cached on disk and normal builds are
fast.

Read the section for your platform end to end before starting — the steps are
ordered by dependency, and skipping one produces confusing link errors later.

---

## Common setup

Clone with submodules — the iSH and PRoot forks are submodules, and a clone
without them will fail at the native build step:

```sh
git clone --recurse-submodules https://github.com/OpenMinis/OpenMinis.git
cd OpenMinis

# Already cloned without --recurse-submodules?
git submodule update --init --recursive
```

| Submodule | Repository | Used by |
|---|---|---|
| `deps/ish` | [OpenMinis/ish-arm64](https://github.com/OpenMinis/ish-arm64) | iOS sandbox kernel |
| `deps/proot` | [OpenMinis/proot](https://github.com/OpenMinis/proot) | Android sandbox |

### Build-time customization

Some values are injected at build time and are **not** in this repository.
Copy the templates before building:

```sh
cp src/ios/Configs/ProviderCustomization.xcconfig.example \
   src/ios/Configs/ProviderCustomization.xcconfig

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Leaving the values empty is fine — **the app compiles and runs**. A value is
only required by the feature that uses it, and that feature fails loudly at
runtime when it is missing. API-key based sign-in works without any
customization.

### `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`

Only relevant if you want to **sign in with Claude OAuth credentials** rather
than an Anthropic API key.

When a request is authenticated with OAuth, Anthropic's endpoint expects the
system prompt to begin with the identifying line that Claude Code itself
sends; without it the request is rejected. The build injects that line from
this value, so OAuth sign-in fails at runtime while it is empty.

We do not ship a value. Supply your own if you need this path — other
open-source projects that talk to the same endpoint declare the same
identifier, for example
[claude-relay-service](https://github.com/Wei-Shaw/claude-relay-service),
which you can consult for the exact wording.

Everything else — Anthropic API keys, and every other provider — works
without setting this.

---

## iOS

### Requirements

| Tool | Version / notes |
|---|---|
| macOS | Apple Silicon strongly recommended (see the simulator note below) |
| Xcode | With the iOS SDK; the project targets **iOS 26.2** and **Swift 6.0** |
| Homebrew packages | `brew install ninja llvm libarchive pkg-config` |
| Python 3 + Meson | `pip3 install meson` |

`llvm` is needed to compile the guest VDSO, `libarchive` to unpack the rootfs,
and Meson/Ninja to build the iSH kernel.

### 1. Build the native dependencies

Run these from the repository root, **in this order** — FFmpeg links against
LAME, so LAME must exist first or MP3 encoding is silently dropped:

```sh
./deps/build_lame.sh          # → deps/lame-build/lib/libmp3lame.a
./deps/build_ffmpeg.sh        # → deps/frameworks/*.framework  (LGPL config)
./deps/build_ish.sh           # → deps/libs/*.a, deps/include/, deps/resources/
./deps/prepare_alpine_rootfs.sh   # → deps/resources/alpine-rootfs.zip
```

What each produces:

- **`build_lame.sh`** — LAME 3.100 as a static library for arm64.
- **`build_ffmpeg.sh`** — FFmpeg 6.1.2 as per-library `.framework` bundles plus
  an umbrella `FFmpeg.framework`. Configured **LGPL**: do not add
  `--enable-gpl` or `--enable-nonfree` — see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
- **`build_ish.sh`** — `libish`, `libish_emu`, `libfakefs` from the `deps/ish`
  submodule, plus headers and the VDSO.
- **`prepare_alpine_rootfs.sh`** — downloads Alpine aarch64 minirootfs and
  converts it to iSH's fakefs format.

The Xcode project references `deps/libs/`, `deps/include/`, `deps/frameworks/`
and `deps/resources/` relative to the project, so nothing needs to be copied
by hand.

### 2. Build the app

```sh
open src/ios/Minis.xcodeproj
```

Select the **Minis** scheme and build. For a device build, set your own team
under *Signing & Capabilities* — the project ships with an empty
`DEVELOPMENT_TEAM`.

From the command line:

```sh
xcodebuild -project src/ios/Minis.xcodeproj -scheme Minis \
           -configuration Debug -destination 'generic/platform=iOS' \
           CODE_SIGNING_ALLOWED=NO build
```

> **Simulator builds need simulator-architecture dependencies.** The scripts
> above build for **device arm64**. Linking a simulator build against them
> fails with `building for 'iOS-simulator', but linking in object file built
> for 'iOS'` (or a missing-symbol error for x86_64 on Intel Macs). Build for a
> device destination, or rebuild the native deps for the simulator SDK.

### Targets

`Minis` (app), `MinisShare` (share extension), `AgentWidgetExtension`,
`MinisFileProvider`, plus `MinisTests` / `MinisUITests`.

---

## Android

### Requirements

| Tool | Version / notes |
|---|---|
| JDK | **17** (`sourceCompatibility`/`targetCompatibility` are 17) |
| Android SDK | **compileSdk 36**, targetSdk 35, **minSdk 26** |
| Android NDK | **r28+** — set `$ANDROID_NDK_HOME`, or install via Android Studio |
| CMake | 3.22.1 (install through the SDK Manager) |
| Shell tools | `curl`, `tar`, `make`, `awk`, `sed` |

Gradle itself comes from the wrapper (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0) —
do not install it separately.

Only `arm64-v8a` is built (`abiFilters`), so use an arm64 device or emulator
image.

### 1. Build the native dependencies

```sh
./deps/build_proot.sh              # → assets/proot-aarch64, jniLibs/arm64-v8a/*.so
./scripts/prepare_android_sandbox.sh   # → assets/alpine-minirootfs.tar.gz
```

- **`build_proot.sh`** cross-compiles a static `libtalloc` and the
  `deps/proot` fork with the NDK, then installs the binary into the app's
  `assets/` and `jniLibs/arm64-v8a/`.
- **`prepare_android_sandbox.sh`** downloads the Alpine aarch64 minirootfs into
  `assets/`.

Both write into `src/android/app/src/main/`, and their outputs are gitignored —
they are build artifacts, so rerun the scripts rather than committing them.

The small JNI libraries in `src/main/cpp/` (`pty_bridge`, the crash handler,
`jieba_jni`) are built by CMake as part of the normal Gradle build; no separate
step is needed.

### 2. Build the app

```sh
cd src/android
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/
./gradlew :app:installDebug           # install onto a connected device
```

Release builds are configured with the debug signing config, so no keystore is
required to produce one locally.

### Tests

```sh
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:connectedAndroidTest     # instrumented; needs a device/emulator
```

---

## Troubleshooting

**`deps/ish` or `deps/proot` is empty** — the submodules were not initialised:
`git submodule update --init --recursive`.

**iOS: `Undefined symbols … _vstats_version` / `symbol(s) not found`** — FFmpeg
was not built, or was built for a different architecture than the one you are
linking. Rerun `./deps/build_ffmpeg.sh` and build for a device destination.

**iOS: `linking in object file built for 'iOS'` on a simulator build** — see
the simulator note above.

**iOS: MP3 encoding unavailable** — `build_lame.sh` did not run before
`build_ffmpeg.sh`. Rerun both in order.

**Android: `Android NDK not found`** — set `ANDROID_NDK_HOME` to your NDK r28+
installation, e.g.
`export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/28.0.12433566`.

**Android: app starts but the shell does not** — the sandbox assets are
missing. Rerun `./deps/build_proot.sh` and
`./scripts/prepare_android_sandbox.sh`, then rebuild.

**A feature throws about a missing configuration value** — that value comes
from the customization file; see [Build-time customization](#build-time-customization).

---

## Licensing note

Minis is **GPLv3** because it links iSH (GPLv3) and PRoot (GPLv2). If you
change how the native dependencies are built, keep FFmpeg on its LGPL
configuration and preserve the vendored `LICENSE` files. See
[LICENSE](LICENSE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
