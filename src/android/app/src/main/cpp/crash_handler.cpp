// T283 — native crash → file (NDK signal handler).
//
// Registered at app startup from MinisApp.onCreate via JNI. Catches
// fatal signals raised inside JNI / proot / pty_bridge / any other
// native code, writes a one-shot text report to the configured logs
// dir, then hands the signal to the handler that was installed before
// us — debuggerd — so the system tombstone is also generated and the
// app exits like normal.
//
// [T-android-crash-observability] That last step used to be
// `signal(sig, SIG_DFL); raise(sig)`, and the comment claimed it let
// Android "still produce a tombstone". It does not. A tombstone is
// written by debuggerd's OWN signal handler, which lives inside this
// process and which our sigaction() call replaced. Resetting to
// SIG_DFL and re-raising therefore skips debuggerd entirely: the
// kernel just applies the default action and kills us. No tombstone,
// no backtrace, and — worst of all — no "Abort message:" line, which
// for a SIGABRT is the single most useful field there is.
//
// The observable result was two user crash reports nine hours apart
// that both said only "SIGABRT, si_code=-1, self-abort" with nothing
// to act on, while /data/tombstones/ held no matching entry. We were
// eating the evidence for the crash we were trying to diagnose.
//
// Two changes fix that:
//   1. Save the previous handler per signal and CHAIN to it instead of
//      SIG_DFL, so debuggerd runs and writes the real tombstone.
//   2. Write a small unwound backtrace into our own summary, so the
//      file the user can actually find and send (app logs dir) carries
//      frame addresses even when nobody can fetch the tombstone.
//
// Strict async-signal-safety: only signal-safe libc calls inside the
// handler (open/write/close/snprintf are safe; printf/malloc are not).
// Neither _Unwind_Backtrace nor dladdr() is on POSIX's async-signal-safe
// list, so both are called only after the summary text is already
// written and only on the first entry into the handler — a hang or
// fault inside either can no longer cost us the report, at worst only
// the backtrace section appended below it.

#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <ctime>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/log.h>
#include <unwind.h>
#include <dlfcn.h>

#define LOG_TAG "MinisCrashHandler"

// Plenty of headroom for "<logs_dir>/native-crash-YYYY-MM-DD_HH-MM-SS.log".
static char g_log_dir[512] = {0};

// Reentrancy guard. If the handler crashes itself, we want the second
// signal to skip straight to SIG_DFL rather than recursing.
static volatile sig_atomic_t g_in_handler = 0;

// [T-android-crash-observability] The handlers installed before us —
// on Android that is debuggerd, which is what actually writes
// /data/tombstones/. Indexed by signal number so each signal chains to
// its own predecessor. NSIG is 65 on bionic; the array is small and
// static, so no allocation happens in the handler.
static struct sigaction g_prev[NSIG];
static volatile sig_atomic_t g_prev_valid[NSIG];

// Frame collector for _Unwind_Backtrace. Fixed capacity, no allocation.
struct BacktraceState {
    void** frames;
    int count;
    int capacity;
};

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    BacktraceState* st = static_cast<BacktraceState*>(arg);
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        if (st->count >= st->capacity) return _URC_END_OF_STACK;
        st->frames[st->count++] = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

// Trailing path component of a shared-object path, so a line reads
// "libfoo.so" rather than "/data/app/~~aBc==/lib/arm64/libfoo.so".
// No allocation; returns a pointer into the original string.
static const char* base_name(const char* path) {
    if (path == nullptr) return nullptr;
    const char* slash = strrchr(path, '/');
    return (slash != nullptr && slash[1] != '\0') ? slash + 1 : path;
}

// Append "  #NN 0x… libfoo.so+0x1234 (symbol)" lines for the current
// stack. Best-effort: an unwind that fails or returns nothing simply
// yields no lines, and the summary above it has already been written to
// disk regardless.
//
// [T-android-crash-dladdr] Why the library name is resolved here rather
// than left to the reader.
//
// A raw PC is an ASLR'd absolute address: it is meaningless outside the
// process that produced it, so a summary carrying only PCs cannot be
// acted on at all without the matching tombstone — and the tombstone is
// exactly the thing users usually cannot fetch (it needs adb, and on
// most retail devices root or a full bugreport). Field reports were
// therefore arriving as seven bare hex numbers, and every one of them
// had to be answered with "please run adb bugreport" before triage
// could even begin — see the 2026-09-10 voice-input crash, where the
// only thing derivable from the summary was how the addresses grouped
// by load base.
//
// dladdr() turns each PC into "which .so, how far in", and the offset
// is stable across runs because it is relative to the library's own
// load base. That is enough to name the faulting library immediately,
// and enough to feed `llvm-addr2line -e <lib>.so <offset>` directly —
// no tombstone, no memory map, no symbol server.
//
// Async-signal-safety: dladdr() takes the loader lock and is not on
// POSIX's async-signal-safe list. That is the same trade-off this file
// already accepts for _Unwind_Backtrace (see the header note), and it
// is safe for the same structural reason: this runs only AFTER the
// summary text is already on disk and only on first entry into the
// handler, so a hang or fault inside it costs at most the backtrace
// section — never the report. Deadlock is possible only if we crashed
// while already holding the loader lock (i.e. inside dlopen); in that
// case we lose these lines and nothing else.
static void write_backtrace(int fd) {
    void* frames[32];
    BacktraceState st = { frames, 0, 32 };
    _Unwind_Backtrace(unwind_cb, &st);
    if (st.count <= 0) return;

    const char* hdr = "\nBacktrace (lib+offset resolved via dladdr; feed the\n"
                      "offset straight to `llvm-addr2line -Cfe <lib>.so <offset>`,\n"
                      "or symbolize the whole file with `ndk-stack -sym <dir>`):\n";
    write(fd, hdr, strlen(hdr));

    for (int i = 0; i < st.count; i++) {
        // Wider than the old 64: a path basename plus a symbol name can
        // legitimately run long, and snprintf truncates rather than
        // overflowing, so the only cost of the extra stack is bytes.
        char line[256];
        int n;

        Dl_info info;
        // dladdr returns non-zero on success. dli_fname/dli_sname may
        // still be null (an anonymous or fully-stripped mapping), so
        // every field is checked before use rather than assumed.
        if (dladdr(frames[i], &info) != 0 && info.dli_fname != nullptr) {
            const char* lib = base_name(info.dli_fname);
            // Offset from the LIBRARY's load base — the run-independent
            // number addr2line wants. dli_fbase is the mapping base.
            const uintptr_t pc = reinterpret_cast<uintptr_t>(frames[i]);
            const uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
            const uintptr_t off = (pc >= base) ? (pc - base) : 0;

            if (info.dli_sname != nullptr) {
                // A resolved symbol is a bonus, not the point: exported
                // names survive stripping, static ones do not, so most
                // app frames will print without this half.
                n = snprintf(line, sizeof(line), "  #%02d %p  %s+0x%lx (%s)\n",
                             i, frames[i], lib, static_cast<unsigned long>(off),
                             info.dli_sname);
            } else {
                n = snprintf(line, sizeof(line), "  #%02d %p  %s+0x%lx\n",
                             i, frames[i], lib, static_cast<unsigned long>(off));
            }
        } else {
            // Unmapped / unknown — keep the frame rather than dropping
            // it, since its position in the stack is still evidence.
            n = snprintf(line, sizeof(line), "  #%02d %p  <unknown>\n",
                         i, frames[i]);
        }

        if (n > 0) write(fd, line, static_cast<size_t>(n));
    }
}

// ── [OpenMinis#363] Abort message capture ───────────────────────────────
//
// For a SIGABRT the single most useful field is the message the aborting
// code passed to abort — "Scudo ERROR: invalid chunk state", a libc
// assertion text, an ART runtime reason. It is what debuggerd prints as
// "Abort message:" in the tombstone. This handler used to write a
// paragraph telling the reader where to go looking for it and never read
// it itself, so the one file a user can actually retrieve (the app's own
// logs dir) carried everything EXCEPT the reason. Three crashes in 24h
// were filed with nothing to act on.
//
// Captured two ways, best-effort, in this order:
//
//   1. __android_log_set_aborter() — the supported hook, resolved with
//      dlsym because minSdk here is 26 and the symbol is __INTRODUCED_IN(30).
//      liblog calls it with the message just before aborting, on the
//      aborting thread, BEFORE the signal is raised, and we copy the string
//      into a static buffer.
//
//      Narrower than it sounds: liblog documents this as the aborter for
//      __android_log_assert() failures, so it catches LOG_ALWAYS_FATAL and
//      friends — NOT a bare abort(), and not Scudo. The API returns void,
//      so there is no previous aborter to chain to; we deliberately do not
//      abort ourselves, leaving liblog's own "highly recommended to abort"
//      contract to the caller, which is what it did before we hooked it.
//
//   2. dlsym("__abort_message") — bionic's own global, which is what
//      debuggerd reads and what android_set_abort_message() fills in. NOT a
//      public ABI: the struct layout could change between releases, so every
//      step is bounds- and null-checked and any surprise yields "no message"
//      rather than a fault. This is the path that covers plain abort(),
//      Scudo and ART aborts, i.e. most of what we actually see.
//
// Neither path helps for a runtime that aborts without going through
// bionic's abort message at all — notably the Go runtime in libgojni.so,
// whose panics simply raise SIGABRT. For those the fallback advice text
// below is still the right output, which is why it is kept rather than
// replaced.
static char g_abort_msg[512] = {0};
static volatile sig_atomic_t g_abort_msg_set = 0;

// Async-signal-safe by construction: a bounded copy into a static buffer,
// no allocation, no locks. Runs on the aborting thread before the signal.
//
// Does NOT abort itself. liblog calls this instead of its default aborter,
// and the default is what performs the abort — but liblog's own contract
// says an aborter "is highly recommended to abort and be noreturn, but is
// not strictly required to", and __android_log_assert() aborts on its own
// after the aborter returns. Calling abort() here would risk a second abort
// on paths where it does not.
static void minis_aborter(const char* msg) {
    if (msg != nullptr && g_abort_msg_set == 0) {
        size_t i = 0;
        for (; i < sizeof(g_abort_msg) - 1 && msg[i] != '\0'; i++) {
            g_abort_msg[i] = msg[i];
        }
        g_abort_msg[i] = '\0';
        g_abort_msg_set = 1;
    }
}

// bionic's `struct abort_msg_t { size_t size; char msg[0]; }`. Declared
// locally rather than included: it is private to bionic and this is a
// deliberate, guarded read of a non-public global.
struct minis_abort_msg_t {
    size_t size;
    char msg[0];
};

// Fallback reader for aborts that never reached the liblog aborter.
// Returns nullptr unless every check passes.
static const char* read_bionic_abort_message() {
    // The symbol is a POINTER to the message block, so dlsym yields its
    // address; dereference once to get the block.
    void* sym = dlsym(RTLD_DEFAULT, "__abort_message");
    if (sym == nullptr) return nullptr;
    minis_abort_msg_t* const* slot =
        reinterpret_cast<minis_abort_msg_t* const*>(sym);
    if (slot == nullptr) return nullptr;
    const minis_abort_msg_t* block = *slot;
    if (block == nullptr) return nullptr;
    // `size` counts the header plus the NUL-terminated text. Anything
    // outside a sane range means the layout is not what we assumed —
    // treat it as "no message" rather than reading arbitrary memory.
    if (block->size <= sizeof(size_t) || block->size > 64 * 1024) return nullptr;
    if (block->msg[0] == '\0') return nullptr;
    return block->msg;
}

// The message to print, or nullptr when neither path produced one.
// Called only AFTER the summary is on disk (dlsym is not async-signal-safe,
// same trade-off this file already documents for _Unwind_Backtrace/dladdr).
static const char* abort_message_or_null() {
    if (g_abort_msg_set != 0 && g_abort_msg[0] != '\0') return g_abort_msg;
    return read_bionic_abort_message();
}

// Signal name lookup — strsignal() is NOT async-signal-safe on all
// libc implementations, so use a hardcoded table.
static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSYS:  return "SIGSYS";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

// [T-android-crash-observability] Pass the signal on to whoever held it
// before us — debuggerd in a normal app process — so the tombstone (and
// with it the "Abort message:" line) still gets written.
//
// SA_SIGINFO handlers are re-invoked with the original siginfo/context
// so debuggerd sees exactly what the kernel delivered. For a plain
// sa_handler we call it directly. Only if there genuinely was no prior
// handler (SIG_DFL/SIG_IGN, e.g. we installed before debuggerd, or on a
// host where it is absent) do we fall back to the old reset-and-raise,
// which at least terminates with the right signal.
static void chain_to_previous(int sig, siginfo_t* info, void* ctx) {
    if (sig > 0 && sig < NSIG && g_prev_valid[sig]) {
        const struct sigaction& prev = g_prev[sig];
        if ((prev.sa_flags & SA_SIGINFO) && prev.sa_sigaction != nullptr) {
            prev.sa_sigaction(sig, info, ctx);
            return;
        }
        if (prev.sa_handler != SIG_DFL && prev.sa_handler != SIG_IGN &&
            prev.sa_handler != nullptr) {
            prev.sa_handler(sig);
            return;
        }
    }
    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

static void crash_signal_handler(int sig, siginfo_t* info, void* ctx) {
    // Reentrancy: if we're already in the handler, hand straight over.
    // Avoids an infinite loop when the handler itself faults, while
    // still letting debuggerd produce the tombstone for the second
    // signal (the old code went to SIG_DFL here and lost it).
    if (g_in_handler) {
        chain_to_previous(sig, info, ctx);
        return;
    }
    g_in_handler = 1;

    if (g_log_dir[0] == 0) {
        chain_to_previous(sig, info, ctx);
        return;
    }

    // Build the per-crash filename. time(NULL) is async-signal-safe;
    // localtime_r is too on bionic. snprintf is documented async-safe
    // by POSIX.
    time_t now = time(nullptr);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);

    char path[640];
    snprintf(path, sizeof(path),
        "%s/native-crash-%04d-%02d-%02d_%02d-%02d-%02d.log",
        g_log_dir,
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        chain_to_previous(sig, info, ctx);
        return;
    }

    const int tid = (int)syscall(SYS_gettid);

    // Thread name — the single most useful missing field. A 16-byte name says
    // WHICH subsystem died (e.g. "Jit thread pool", "native-offload-",
    // "RenderThread"), which a numeric tid alone can never answer after the
    // fact.
    //
    // [T-android-crash-thread-name] Read /proc/self/task/<tid>/comm, NOT
    // /proc/self/comm. `comm` is a per-THREAD attribute, and the /proc/self/
    // shortcut resolves to /proc/<pid>/ — which is the MAIN thread's entry. So
    // the previous code reported the main thread's name for every crash,
    // whichever thread actually died, and it did so silently: the field looked
    // plausible and was simply wrong.
    //
    // It mis-reported exactly the cases the comment above promises to answer —
    // a "Jit thread pool" or "RenderThread" abort was filed as
    // "m.openminis.app". A user report of a SIGABRT on TID 19937 of PID 17499
    // (so definitively not the main thread) still carried Thread:
    // "m.openminis.app", which sends triage looking at the UI thread for a
    // crash that happened somewhere else entirely.
    //
    // openat on the per-thread path is just as async-signal-safe; the tid is
    // already known from gettid() above, and snprintf into a fixed buffer adds
    // no allocation.
    char comm[64] = {0};
    {
        char comm_path[64];
        snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
        int cfd = open(comm_path, O_RDONLY);
        // A vanished thread entry is possible in principle; fall back to the
        // process-wide name rather than reporting nothing, and say which it is.
        if (cfd < 0) {
            cfd = open("/proc/self/comm", O_RDONLY);
            if (cfd >= 0) strncpy(comm, "(main?) ", sizeof(comm) - 1);
        }
        if (cfd >= 0) {
            const size_t used = strlen(comm);
            ssize_t r = read(cfd, comm + used, sizeof(comm) - used - 1);
            if (r > 0) {
                comm[used + r] = 0;
                for (size_t i = 0; i < used + (size_t)r; i++) {
                    if (comm[i] == '\n') comm[i] = 0;
                }
            }
            close(cfd);
        }
    }
    if (comm[0] == 0) strncpy(comm, "(unknown)", sizeof(comm) - 1);

    const int code = info ? info->si_code : 0;

    // si_addr is ONLY a fault address for fault-type si_codes. For SI_USER(0) /
    // SI_QUEUE(-1) / SI_TKILL(-6) the kernel fills the _kill{pid,uid} arm of the
    // SAME siginfo union, so reading si_addr yields (uid << 32) | pid — a
    // meaningless "address" that reads like a wild pointer and sends triage
    // chasing a memory bug that does not exist. Report the union honestly.
    //
    // Measured on arm64/Android 13: abort() -> si_code=-1 with si_addr
    // 0x<uid><pid>; raise() -> -6; kill() -> 0; a genuine SIGSEGV -> si_code 1/2
    // with a real address.
    const bool addr_is_fault = !(code == 0 || code == -1 || code == -6);

    char buf[1600];
    int n;
    if (addr_is_fault) {
        n = snprintf(buf, sizeof(buf),
            "=== Minis Native Crash ===\n"
            "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
            "Signal: %d (%s)\n"
            "si_code: %d\n"
            "Fault addr: %p\n"
            "PID: %d  TID: %d  Thread: %s\n"
            "\n"
            "(Tombstone with full backtrace written by Android system to "
            "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
            tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
            tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
            sig, signal_name(sig), code,
            info ? info->si_addr : nullptr,
            getpid(), tid, comm);
    } else {
        n = snprintf(buf, sizeof(buf),
            "=== Minis Native Crash ===\n"
            "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
            "Signal: %d (%s)\n"
            "si_code: %d (%s)\n"
            "Sent by: pid=%d uid=%d%s\n"
            "Fault addr: n/a (not a fault signal — see note)\n"
            "PID: %d  TID: %d  Thread: %s\n"
            "\n"
            "NOTE: this signal was SENT, not raised by a memory fault, so\n"
            "si_addr carries no address. SIGABRT with si_code=-1 and\n"
            "sender pid == our own pid is the ordinary signature of abort()\n"
            "inside this process (libc assertion, Scudo heap check, ART\n"
            "runtime abort, or a C++ uncaught exception).\n"
            "(Tombstone with full backtrace written by Android system to "
            "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
            tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
            tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
            sig, signal_name(sig),
            code,
            code == 0 ? "SI_USER" : (code == -1 ? "SI_QUEUE/abort()" : "SI_TKILL"),
            info ? info->si_pid : 0, info ? (int)info->si_uid : 0,
            (info && info->si_pid == getpid()) ? " (this process — self-abort)" : "",
            getpid(), tid, comm);
    }
    if (n > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(fd, buf + written, n - written);
            if (w <= 0) break;
            written += w;
        }
    }
    // [OpenMinis#363] The abort message, for the signal class where it is
    // the whole answer. Written AFTER the summary for the same reason the
    // backtrace is: dlsym() is not async-signal-safe, so a fault or hang
    // inside it can cost at most this section, never the report above it.
    if (!addr_is_fault) {
        const char* amsg = abort_message_or_null();
        if (amsg != nullptr) {
            const char* h = "\nAbort message: ";
            write(fd, h, strlen(h));
            // Bounded write: the bionic block is attacker-adjacent memory in
            // the sense that we do not own its layout, so cap what we copy
            // out rather than trusting it to be NUL-terminated in range.
            size_t len = 0;
            while (len < 4096 && amsg[len] != '\0') len++;
            write(fd, amsg, len);
            write(fd, "\n", 1);
        } else {
            // No message — the usual cause is a runtime that raises SIGABRT
            // without going through bionic (the Go runtime in libgojni.so
            // does exactly this). The old advice text is still the best
            // output we have for that case.
            const char* note =
                "\nAbort message: (none captured)\n"
                "The REASON is not in siginfo and no bionic abort message was\n"
                "set — a runtime that aborts on its own (e.g. the Go runtime)\n"
                "leaves none. Look for it in:\n"
                "  * logcat around this timestamp, tags: scudo / libc / DEBUG\n"
                "    (Scudo prints e.g. \"Scudo ERROR: invalid chunk state\")\n"
                "  * the tombstone's \"Abort message:\" line\n"
                "  * exitinfo-*.log in this directory (ApplicationExitInfo\n"
                "    carries the description and the full tombstone)\n";
            write(fd, note, strlen(note));
        }
    }

    // Backtrace LAST, and only after the summary bytes are already on
    // their way to disk: _Unwind_Backtrace is the one call here that is
    // not async-signal-safe, so if it faults or hangs on some device we
    // lose the frames but keep everything above them.
    write_backtrace(fd);
    close(fd);

    // Hand off to debuggerd (see the header comment). This is what
    // actually produces /data/tombstones/ and the "Abort message:"
    // line; the previous SIG_DFL reset silently suppressed both.
    chain_to_previous(sig, info, ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openminis_app_crash_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject /*thiz*/, jstring jLogDir) {
    if (jLogDir == nullptr) return;
    const char* dir = env->GetStringUTFChars(jLogDir, nullptr);
    if (dir == nullptr) return;
    strncpy(g_log_dir, dir, sizeof(g_log_dir) - 1);
    g_log_dir[sizeof(g_log_dir) - 1] = 0;
    env->ReleaseStringUTFChars(jLogDir, dir);

    // mkdir is fine here — we're on the JVM thread, not in a signal.
    mkdir(g_log_dir, 0755);

    // [OpenMinis#363] Hook the liblog aborter first, so a LOG_ALWAYS_FATAL
    // between here and the sigaction() calls below is still captured.
    //
    // Resolved at runtime: minSdk is 26 and the symbol is
    // __INTRODUCED_IN(30), so a direct call would not link for older
    // targets. Absent (API < 30) simply means path (2) — the bionic global —
    // does all the work, which is the majority case anyway.
    using set_aborter_fn = void (*)(void (*)(const char*));
    if (auto set_aborter =
            reinterpret_cast<set_aborter_fn>(dlsym(RTLD_DEFAULT, "__android_log_set_aborter"))) {
        set_aborter(minis_aborter);
    }

    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;
    // SA_ONSTACK: a SIGSEGV from stack overflow leaves no usable stack
    // for the handler, so run it on the alternate stack when the
    // platform has installed one (debuggerd does). Without this the
    // handler for the one crash class that most needs it never runs.
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    // Register for the signals that map to JNI/native bugs we actually
    // want to capture. SIGABRT covers __android_log_assert / abort()
    // from libc; SIGSEGV/BUS/ILL cover most JNI memory bugs; SIGFPE
    // covers integer div-by-zero. SIGSYS catches seccomp violations
    // (proot occasionally trips these on new kernels).
    //
    // [T-android-crash-observability] Each old handler is saved so
    // crash_signal_handler can chain to it. That predecessor is
    // debuggerd — losing it is what cost us every tombstone.
    static const int kSignals[] = {
        SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSYS,
    };
    for (size_t i = 0; i < sizeof(kSignals) / sizeof(kSignals[0]); i++) {
        const int s = kSignals[i];
        struct sigaction old{};
        if (sigaction(s, &sa, &old) == 0) {
            g_prev[s] = old;
            g_prev_valid[s] = 1;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "installed: dir=%s (chaining to prior handlers)", g_log_dir);
}
