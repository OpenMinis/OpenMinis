# iOS Sandbox Environment: iSH Virtualization Summary

## Overview

MinisApp uses a customized fork of [iSH](https://github.com/OpenMinis/ish-arm64) (OpenMinis/ish-arm64) to provide a full Linux sandbox execution environment on iOS. The iSH kernel runs an Alpine Linux (aarch64) guest inside the app process, giving the AI agent a real shell with networking, filesystem, and process management — while native offloads bridge guest commands to iOS frameworks for hardware and system access.

---

## 1. iSH Virtualization Capabilities

### 1.1 Emulation Engine

| Property | Value |
|---|---|
| Guest Architecture | ARM64 (aarch64) |
| Engine | Asbestos (threaded-code JIT interpreter) |
| Guest OS | Alpine Linux aarch64 |
| Upstream Fork | `OpenMinis/ish-arm64`, branch `feature-arm64` |
| Build System | Meson (cross-compile for iOS arm64) |
| iOS Deployment Target | 14.0+ |

**Feature flags**: `GUEST_ARM64=1`, `ENGINE_ASBESTOS=1`, `KERNEL_ISH=1`

### 1.2 Kernel Capabilities

- **Process management**: fork, exec, exit, PID tracking, parent-child hierarchy, signal handling (SIGTERM, SIGKILL, etc.), pthread support
- **Filesystem (fakefs)**: SQLite-based virtual FS with metadata (`meta.db`), permissions, ownership, symlinks, device nodes
- **Networking**: AF_INET/AF_INET6/AF_LOCAL, TCP/UDP/RAW sockets, DNS resolution, full host network stack passthrough
- **Devices**: TTY (`/dev/tty1-7`, `/dev/console`, `/dev/ptmx`), memory devices (`/dev/null`, `/dev/zero`, `/dev/random`, `/dev/urandom`), PTY support
- **Syscalls**: 100+ Linux syscalls — mmap, brk, poll/epoll, pipes, eventfd, inotify, etc.
- **JIT safety**: SIGSEGV/SIGBUS crash recovery handler for stale TLB pointers from CoW

### 1.3 Build Output (`deps/libs/`)

| Library | Purpose |
|---|---|
| `libish.a` | Core kernel (init, process, memory, syscalls) |
| `libish_emu.a` | ARM64 emulator (Asbestos engine) |
| `libfakefs.a` | SQLite-based virtual filesystem |
| `libvdso.so.elf` | ARM64 guest VDSO |

Build via: `deps/build_ish.sh`

---

## 2. iOS Integration Layer

### 2.1 ISHKernel (`src/ios/iSH/ISHKernel.m`)

Objective-C singleton wrapping the C iSH kernel. Handles:

- **Boot sequence**: mount rootfs → init PID 1 → create device nodes → mount procfs/devpts → configure DNS → register TTY driver → register native offload handlers
- **Command execution**: `executeCommand(_:)` (interactive shell with PTY), `executeCommandAndWait(_:timeout:completion:)` (run-to-completion)
- **I/O**: `sendInput(_:)`, `sendInputString(_:)`, real-time `outputCallback`
- **Terminal control**: `setTerminalSize(columns:rows:)`
- **Mounts**: `bindMountPath(_:toHostPath:)`, `bindUnmountPath(_:)`
- **DNS**: `refreshDns()` — reads iOS resolver and writes `/etc/resolv.conf`

### 2.2 RootfsManager (`src/ios/iSH/RootfsManager.swift`)

Manages the Alpine Linux rootfs lifecycle:

- Extracts bundled rootfs to `~/Documents/alpine-rootfs/`
- Architecture tag tracking and mismatch handling
- Rootfs reset with optional user data backup
- Pre-creates `/var/minis/` subdirectories
- Registers metadata in fakefs `meta.db` for bind-mount visibility
- Default environment: `TERM=xterm-256color`, standard `PATH`

### 2.3 ISHShellExecutor (`src/ios/iSH/ISHShellExecutor.m`)

Process-level execution wrapper:

- Line-by-line output capture callbacks
- Exit code and duration tracking
- Timeout and process signal support (SIGKILL)
- Synchronous and asynchronous modes
- Custom environment variable injection

### 2.4 ISHExecutionCoordinator (`src/ios/Agent/ISHExecutionCoordinator.swift`)

Serialized, session-aware command dispatcher:

- **One command at a time** across all sessions (FIFO queue)
- Automatic session-based mount/remount of `/var/minis/` directories
- Prompt detection (regex-based: `$ `, `# `, `user@host:path$ `, etc.)
- Preemption if command exceeds 10 minutes with waiters
- Buffer limit: 100KB max output without prompt
- Echo removal and whitespace trimming

---

## 3. Mount System

### 3.1 Bind Mount Mechanism

Uses `fakefs_bind_mount(linux_path, host_path)` — creates a symlink in fakefs `data/` pointing to host persistent storage. Parent directories must exist in `meta.db` (`ensureFakefsMetadata` + `ensureParentDirsInMetaDB`).

### 3.2 Per-Session Mounts (`/var/minis/`)

Each agent session mounts its own persistent directories:

| Guest Path | Host Path | Purpose |
|---|---|---|
| `/var/minis/attachments/` | `Library/MinisChat/minis/{sessionId}/attachments/` | Input files for commands |
| `/var/minis/offloads/` | `Library/MinisChat/minis/{sessionId}/offloads/` | Output from native offloads |
| `/var/minis/workspace/` | `Library/MinisChat/minis/{sessionId}/workspace/` | Session working directory |
| `/var/minis/browser/` | `Library/MinisChat/minis/{sessionId}/browser/` | Web browsing files |

### 3.3 Global Mounts

| Guest Path | Host Path | Purpose |
|---|---|---|
| `/var/minis/memory/` | `Library/MinisChat/minis/memory/` | Shared memory across sessions |
| `/var/minis/skills/` | `Library/MinisChat/minis/skills/` | Stored skill definitions |

### 3.4 DNS Mount

| Guest Path | Host Path |
|---|---|
| `/etc/resolv.conf` | `Library/MinisChat/dns/resolv.conf` |

Updated in real-time from iOS system resolver (falls back to `8.8.8.8`, `8.8.4.4`).

### 3.5 Path Resolution

- **Guest → Host**: Linux path `/foo/bar` → `~/Documents/alpine-rootfs/data/foo/bar`
- **Host → Guest**: Bind mounts make host files appear at `/var/minis/...`
- **minis:// URL scheme**: Resolved by `MinisImageProvider` to local images

---

## 4. Native Offload System

### 4.1 Architecture

When a guest process calls `execve()` on a registered command path (e.g., `/usr/local/bin/apple-calendar`), the iSH kernel intercepts the call and routes it to a native iOS handler instead of executing the binary. Communication uses JSON envelopes over pipes, with real-time I/O redirection.

```
Guest process calls execve("/usr/local/bin/apple-calendar", args)
  → iSH kernel intercepts (native_offload.c)
    → Dispatches to registered Objective-C handler (CalendarOffload)
      → iOS Framework API (EventKit)
        → JSON result returned via pipe
```

### 4.2 Offload Handlers (22 total)

#### Media & Audio

| Handler | Guest Command | iOS Framework | Capabilities |
|---|---|---|---|
| FFmpegOffload | `ffmpeg` | FFmpeg.framework | Video/audio encode, transcode, network streams (HTTP/HLS/TLS) |
| MediaOffload | `apple-media` | AVFoundation | Audio playback, recording |
| SpeakOffload | `apple-speak` | AVSpeechSynthesizer | Text-to-speech |
| SpeechOffload | `apple-speech` | SFSpeechRecognizer | Speech-to-text |
| PlayerOffload | `apple-player` | AVFoundation | Advanced media playback |

#### Apple Services

| Handler | Guest Command | iOS Framework | Capabilities |
|---|---|---|---|
| CalendarOffload | `apple-calendar` | EventKit | Read/write calendar events |
| ContactsOffload | `apple-contacts` | Contacts | Contact management |
| MapsOffload | `apple-maps` | MapKit | Maps, directions, location search |
| PhotosOffload | `apple-photos` | Photos | Photo/video library access |
| HealthKitOffload | `apple-health` | HealthKit | Health data read/write |
| HomeKitOffload | `apple-home` | HomeKit | Home automation control |

#### System Access

| Handler | Guest Command | iOS Framework | Capabilities |
|---|---|---|---|
| LocationOffload | `apple-location` | CoreLocation | GPS positioning |
| DeviceOffload | `apple-device` | UIKit/various | Battery, model, system info |
| ClipboardOffload | `apple-clipboard` | UIPasteboard | Copy/paste (text, images) |
| WeatherOffload | `apple-weather` | WeatherKit | Local weather data |
| NotificationOffload | `apple-notification` | UserNotifications | Schedule local notifications |
| AlarmOffload | `apple-alarm` | EventKit (Reminders) | Alarms and reminders |

#### Intelligence

| Handler | Guest Command | iOS Framework | Capabilities |
|---|---|---|---|
| VisionOffload | `apple-vision` | Vision | Image recognition, OCR, text detection |
| NLPOffload | `apple-nlp` | NaturalLanguage | Language detection, sentiment, tokenization |

#### Utilities

| Handler | Guest Command | iOS Framework | Capabilities |
|---|---|---|---|
| OpenOffload | `apple-open` | UIApplication | Open URLs, open in other apps |

### 4.3 Shared Utilities (`NativeOffloadUtils`)

- Argument parsing (named args, flags, positional args)
- Date parsing (ISO 8601, relative dates, `--today`)
- JSON envelope construction
- Async dispatch to main thread
- Guest stub creation
- Host path resolution (`/var/minis/...` → host path)
- stdin reading

---

## 5. Agent Execution Flow

```
┌─────────────────────────────────────────────────────┐
│  iOS App (SwiftUI)                                  │
│  └─ AIChatViewModel                                 │
│     └─ tool_use: execute_command("pip install ...")  │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│  ISHExecutionCoordinator                            │
│  ├─ Serialize (FIFO queue, one-at-a-time)           │
│  ├─ Mount session dirs to /var/minis/               │
│  └─ Inject env vars from EnvVarStore (Keychain)     │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│  ISHKernel / ISHShellExecutor                       │
│  ├─ Spawn /bin/sh with PTY                          │
│  ├─ Capture output (line callbacks)                 │
│  └─ Detect prompt → command complete                │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│  iSH Kernel (C)                                     │
│  ├─ Process mgmt (fork/exec/exit)                   │
│  ├─ Fakefs (SQLite metadata, bind mounts)           │
│  ├─ Networking (TCP/UDP via host stack)              │
│  └─ Native offload interception (execve hook)       │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│  Asbestos Engine (ARM64 JIT Interpreter)            │
│  └─ Alpine Linux aarch64 guest userspace            │
│     ├─ apk, python, pip, node, etc.                 │
│     └─ /usr/local/bin/apple-* (offload stubs)       │
└──────────────────┬──────────────────────────────────┘
                   ▼ (if offload)
┌─────────────────────────────────────────────────────┐
│  Native Offload Handlers (Objective-C)              │
│  └─ iOS Frameworks (EventKit, Photos, Vision, ...)  │
└─────────────────────────────────────────────────────┘
```

---

## 6. Security & Isolation

- iSH runs in its own pthread (background thread)
- Kernel uses thread-local `current` task pointer for process isolation
- Main thread communication via `dispatch_async`
- JIT exception handler prevents guest crashes from crashing the app
- Fakefs provides filesystem isolation from host (SQLite metadata layer)
- Bind mounts are explicit and scoped to `/var/minis/`
- Environment variables stored in Keychain (SecureEnclave)
- Guest processes cannot access arbitrary host paths — only bind-mounted directories
- 100KB output buffer limit prevents memory exhaustion
- 10-minute command timeout with preemption
