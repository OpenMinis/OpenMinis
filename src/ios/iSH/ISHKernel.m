//
//  ISHKernel.m
//  MinisApp
//
//  Objective-C wrapper for iSH kernel initialization and control
//

#import "ISHKernel.h"
#import "CurrentRoot.h"
// [GH#175] For sizeof(sockaddr_un.sun_path) — the Darwin HOST limit that
// iSH's unchecked sprintf in fs/sock.c writes into. Safe to include here:
// this file does not pull in ish/fs/sock.h, so there is no clash with iSH's
// guest-ABI socket structs.
#include <sys/un.h>
#include "ish/kernel/init.h"
#include "ish/kernel/task.h"
#include "ish/kernel/calls.h"
#include "ish/kernel/fs.h"
#include "ish/fs/fake.h"
#include "ish/fs/tty.h"
#include "ish/fs/dev.h"
#include "ish/fs/devices.h"
#include "ish/fs/path.h"
#include "ish/fs/fd.h"
#include "ish/emu/cpu.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>     // realpath
#include <errno.h>
#include <sys/syslimits.h> // PATH_MAX
#include <signal.h>
#include <setjmp.h>
#include <unistd.h>     // usleep
#import <mach/mach.h>   // task_info / TASK_VM_INFO (phys_footprint)
#include <execinfo.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <resolv.h>
#import <SystemConfiguration/SystemConfiguration.h>
#import "FFmpegOffload.h"
#import "CalendarOffload.h"
#import "LocationOffload.h"
#import "WeatherOffload.h"
#import "VisionOffload.h"
#import "OpenOffload.h"
#import "ClipboardOffload.h"
#import "HealthKitOffload.h"
#import "PhotosOffload.h"
#import "MapsOffload.h"
#import "NLPOffload.h"
#import "AlarmOffload.h"
#import "MediaOffload.h"
#import "SpeakOffload.h"
#import "SpeechOffload.h"
#import "DeviceOffload.h"
#import "HomeKitOffload.h"
#import "NotificationOffload.h"
#import "PlayerOffload.h"
#import "ModelUseOffload.h"
#import "RemindersOffload.h"
#import "BluetoothOffload.h"
#import "NFCOffload.h"
#import "SessionsOffload.h"
#import "ConfigOffload.h"
#import "BrowserUseOffload.h"
#import "DebugOffload.h"

NSNotificationName const ISHProcessExitedNotification = @"ISHProcessExited";
NSNotificationName const ISHTerminalOutputNotification = @"ISHTerminalOutput";

// Global reference to shared instance for C callbacks
static ISHKernel *g_sharedKernel = nil;

// External hooks
extern void (*exit_hook)(struct task *task, int code);

// [T-ios-ish-af-unix-sandbox GH#175] iSH translates every guest AF_UNIX address
// into a real HOST path `<sock_tmp_prefix><pid>.<socket_id>` and binds that
// (fs/sock.c). The compiled-in default is "/tmp/ishsock" — the iOS host /tmp,
// which is outside the App sandbox. See setUpUnixSocketPrefix for the full story.
extern const char *sock_tmp_prefix;

#pragma mark - Fork memory guard

// [fork-guard] Stall guest fork() while the app's memory footprint is too high.
//
// Every guest process lives inside Minis.app's own address space, so a burst
// like `xargs -P 15` running 32MB Go binaries adds ~480MB to the app's iOS
// footprint and gets the whole app SIGKILLed by Jetsam.
//
// We measure phys_footprint (TASK_VM_INFO) — the metric Jetsam actually uses to
// decide what to kill. An earlier version of this guard used
// os_proc_available_memory() (remaining headroom) with a 400MB reserve, but on
// an 8GB device the per-process limit is ~1.5-2GB, so headroom never fell
// anywhere near 400MB and the guard never fired once. Measuring what we have
// spent, not what is nominally left, is the check that trips in practice.
//
// We *block* rather than returning _EAGAIN: busybox xargs (what the Alpine
// rootfs ships) does not retry a failed fork — it calls bb_simple_perror_msg
// and exits 126, silently dropping that work item. Blocking the forking thread
// applies the same backpressure without losing work: the guest simply sees a
// slow fork() and the burst self-throttles to what the device can sustain.
//
// Safe to block here: sys_clone calls the guard before task_create_ and holds
// no kernel locks at that point (pids_lock is taken later), and it runs on the
// guest thread that asked to fork, so only that thread waits.
//
// [T-ish-forkguard-stall] THRESHOLD AND GATING, after a field report of every
// trivial guest command (`true`, `echo hi`) taking a flat ~10s.
//
// The 500MB ceiling this replaces was derived in 59714a04 from a single
// `xargs -P 10` stress run that peaked at ~317MB, with a crash at ~358MB. That
// commit's own closing note — "Still unverified on device: whether the guard
// now actually engages ... That test is what would confirm the threshold is
// right" — is exactly what went wrong: the number was never checked against
// ordinary steady-state use on a large device.
//
// Two independent defects, both visible in field logs:
//
// 1. A DEVICE-INDEPENDENT CEILING against a device-dependent limit. Jetsam's
//    per-process budget scales with installed RAM, but 500MB did not. On a 4GB
//    iPhone 11 the app idles at 84-145MB and the guard never fires; on a 12GB
//    device it idles at 885MB-1.0GB and the guard fires on EVERY fork. Same
//    binary, same workload, opposite behaviour — the ceiling was calibrated on
//    one device class and silently mis-set for the other. Scaling off physical
//    RAM restores the intent (engage before the kill zone, stay out of the way
//    below it) on both.
//
// 2. A WAIT THAT NEVER DENIES ANYTHING. On timeout the guard logs and forks
//    anyway, by design — busybox xargs would drop the work item on a failed
//    fork. But that makes the 10s a pure delay, not backpressure: with a
//    footprint that is high because the app legitimately holds that much (not
//    because a burst is in flight), nothing is going to fall in 10s, so every
//    fork pays the full penalty and then proceeds regardless. Field log: 272
//    stalls, every one of them a timeout ending in "allowing fork anyway".
//
// The fix keeps the guard's real purpose — throttling a *burst* that is
// actively inflating the footprint — and drops the cost when there is no burst:
//
//   - Gate on system memory PRESSURE first. Waiting is only meaningful when the
//     OS is actually short of memory; the field logs show pressure=normal with
//     3-4.5GB free the entire time the guard was stalling. Under normal
//     pressure we allow immediately.
//   - Scale the footprint ceiling to the device, so "high for this hardware"
//     means the same thing everywhere.
//   - Cap the wait at 2s. The guard exists to let *exiting* guest processes
//     return memory; that happens in hundreds of milliseconds or not at all,
//     and since a timeout forks anyway, a longer wait buys nothing.
//
// Threads are exempt (handled in sys_clone) since they add no separate address
// space.
//
// Ceiling = 25% of physical RAM, clamped to [500MB, 2GB]:
//   4GB device  -> 1.0GB   (idle 84-145MB, so still far below — no change in
//                           behaviour for the class where the guard was quiet)
//   8GB device  -> 2.0GB   (clamp)
//   12GB device -> 2.0GB   (clamp; idle 885MB-1.0GB now sits below it)
// The lower clamp keeps a small device from setting a ceiling so low that
// ordinary operation trips it; the upper clamp keeps a large device from
// setting one so high the guard can never engage before Jetsam does.
#define MINIS_FORK_GUARD_FOOTPRINT_FRACTION 4          // 1/4 of physical RAM
#define MINIS_FORK_GUARD_FLOOR_BYTES   (500ULL * 1024 * 1024)
#define MINIS_FORK_GUARD_CEIL_BYTES    (2048ULL * 1024 * 1024)
#define MINIS_FORK_GUARD_POLL_US       (50 * 1000)     // 50ms between checks
#define MINIS_FORK_GUARD_MAX_WAIT_US   (2 * 1000000)   // give up after 2s

static atomic_ullong g_fork_guard_stalls = 0;

// System-wide memory pressure, kept current by a dispatch source (0 = normal,
// non-zero = warn/critical). Mirrors the mechanism HangDetector.m uses for its
// [MemMonitor] readout; a private source is used rather than reaching into that
// file's statics so the two stay independent.
static atomic_uint_fast32_t g_fork_guard_pressure = ATOMIC_VAR_INIT(0);
static dispatch_source_t g_fork_guard_pressure_source = nil;

// Footprint ceiling for this device, computed once at install.
static uint64_t g_fork_guard_max_footprint = MINIS_FORK_GUARD_FLOOR_BYTES;

static uint64_t minis_fork_guard_compute_ceiling(void) {
    uint64_t ram = [NSProcessInfo processInfo].physicalMemory;
    if (ram == 0)
        return MINIS_FORK_GUARD_FLOOR_BYTES;
    uint64_t ceiling = ram / MINIS_FORK_GUARD_FOOTPRINT_FRACTION;
    if (ceiling < MINIS_FORK_GUARD_FLOOR_BYTES)
        ceiling = MINIS_FORK_GUARD_FLOOR_BYTES;
    if (ceiling > MINIS_FORK_GUARD_CEIL_BYTES)
        ceiling = MINIS_FORK_GUARD_CEIL_BYTES;
    return ceiling;
}

static void minis_fork_guard_start_pressure_source(void) {
    if (g_fork_guard_pressure_source != nil)
        return;
    g_fork_guard_pressure_source = dispatch_source_create(
        DISPATCH_SOURCE_TYPE_MEMORYPRESSURE, 0,
        DISPATCH_MEMORYPRESSURE_NORMAL | DISPATCH_MEMORYPRESSURE_WARN |
        DISPATCH_MEMORYPRESSURE_CRITICAL,
        dispatch_get_global_queue(QOS_CLASS_UTILITY, 0));
    if (g_fork_guard_pressure_source == nil)
        return;
    dispatch_source_set_event_handler(g_fork_guard_pressure_source, ^{
        unsigned long flags = dispatch_source_get_data(g_fork_guard_pressure_source);
        uint32_t level = 0;
        if (flags & DISPATCH_MEMORYPRESSURE_CRITICAL) level = 2;
        else if (flags & DISPATCH_MEMORYPRESSURE_WARN) level = 1;
        atomic_store_explicit(&g_fork_guard_pressure, level, memory_order_relaxed);
    });
    dispatch_resume(g_fork_guard_pressure_source);
}

// Current phys_footprint in bytes, or 0 if unavailable. Matches the TASK_VM_INFO
// pattern already used by HangDetector.m and BrowserResourceMonitor.swift.
static uint64_t minis_current_phys_footprint(void) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    kern_return_t kr = task_info(mach_task_self(), TASK_VM_INFO,
                                 (task_info_t) &info, &count);
    if (kr != KERN_SUCCESS)
        return 0;
    return (uint64_t) info.phys_footprint;
}

static int minis_fork_memory_guard(void) {
    // A 0 reading means task_info failed; treat that as "no information" and
    // allow the fork rather than stalling every process spawn.
    uint64_t footprint = minis_current_phys_footprint();
    if (footprint == 0 || footprint < g_fork_guard_max_footprint)
        return 0;

    // [T-ish-forkguard-stall] Above the ceiling, but is the SYSTEM actually
    // short of memory? Waiting only helps when memory is genuinely contended;
    // a high footprint under normal pressure just means the app legitimately
    // holds that much, and nothing is going to hand it back inside the wait.
    // The field logs are unambiguous on this: every one of the 272 stalls ran
    // with pressure=normal and 3-4.5GB free, waited the full timeout, and then
    // forked anyway. Allowing immediately here is what removes the per-fork
    // penalty; the wait below is preserved for the case it was written for —
    // real pressure, where backing off can let exiting processes return memory.
    // A dispatch memory-pressure source only fires on TRANSITIONS, so if the
    // system was already under pressure when the source was created we may not
    // have been told yet and would read a stale 0. Treat a footprint far past
    // the ceiling as its own evidence of trouble and fall back to the wait,
    // independent of what the source has reported. Without this the guard
    // would be fully disabled in exactly the situation it exists for: a burst
    // that inflates the footprint faster than pressure notifications arrive.
    uint32_t pressure = atomic_load_explicit(&g_fork_guard_pressure, memory_order_relaxed);
    BOOL farOverCeiling = footprint > g_fork_guard_max_footprint +
                                      (g_fork_guard_max_footprint / 2);   // 1.5x
    if (pressure == 0 && !farOverCeiling)
        return 0;

    uint64_t n = atomic_fetch_add_explicit(&g_fork_guard_stalls, 1, memory_order_relaxed) + 1;
    uint64_t first_footprint = footprint;
    useconds_t waited_us = 0;

    // Wait for the footprint to fall as earlier guest processes exit. Bounded so
    // a workload whose memory never comes back stalls briefly instead of hanging.
    while (waited_us < MINIS_FORK_GUARD_MAX_WAIT_US) {
        usleep(MINIS_FORK_GUARD_POLL_US);
        waited_us += MINIS_FORK_GUARD_POLL_US;

        footprint = minis_current_phys_footprint();

        // Pressure clearing is the signal we were waiting for, so stop waiting
        // even if our own footprint is unchanged. Guarded by the same 1.5x
        // check as the entry test: when we got here on the far-over-ceiling
        // path (pressure never reported), a still-0 pressure reading is not
        // news and must not short-circuit the wait on the first poll.
        if (atomic_load_explicit(&g_fork_guard_pressure, memory_order_relaxed) == 0 &&
            footprint <= g_fork_guard_max_footprint + (g_fork_guard_max_footprint / 2))
            return 0;
        if (footprint == 0 || footprint < g_fork_guard_max_footprint) {
            // Log the first stall of a burst and then every 64th, so a throttled
            // workload doesn't flood the log through LoggingManager.
            if (n == 1 || (n % 64) == 0) {
                NSLog(@"ISHKernel: [ForkGuard] fork resumed after %ums — footprint %.1fMB -> %.1fMB (stalls=%llu)",
                      waited_us / 1000, (double) first_footprint / (1024.0 * 1024.0),
                      (double) footprint / (1024.0 * 1024.0), n);
            }
            return 0;
        }
    }

    // Timed out. Allowing the fork risks Jetsam, but denying it would make
    // busybox xargs drop the work item outright — a silent wrong answer is
    // worse than a risky one, so allow it and leave a loud trace.
    NSLog(@"ISHKernel: [ForkGuard] WARNING timed out after %ums with footprint still %.1fMB "
          @"(max %.0fMB, pressure=%u) — allowing fork anyway (stalls=%llu)",
          waited_us / 1000, (double) footprint / (1024.0 * 1024.0),
          (double) g_fork_guard_max_footprint / (1024.0 * 1024.0), pressure, n);
    return 0;
}

#pragma mark - JIT Crash Recovery Handler

// Thread-local JIT recovery state (defined in asbestos.c)
extern __thread volatile sig_atomic_t in_jit;
extern __thread volatile uint64_t jit_saved_pc;

// Assembly trampoline: returns INT_JIT_CRASH via fiber_exit (defined in entry.S)
extern void jit_crash_trampoline(void);

// cpu_state field offsets (must match cpu-offsets.h)
#define CRASH_CPU_pc 272
#define CRASH_CPU_segfault_addr 832
#define CRASH_CPU_segfault_was_write 840
#define CRASH_LOCAL_jit_exit_sp 920

/// Handles SIGSEGV/SIGBUS from JIT code (stale TLB pointers from concurrent CoW).
/// Uses ucontext PC manipulation to redirect to jit_crash_trampoline.
static void jit_crash_handler(int sig, siginfo_t *info, void *ctx) {
#ifdef __aarch64__
    if ((sig == SIGSEGV || sig == SIGBUS) && in_jit) {
        ucontext_t *uc = (ucontext_t *)ctx;

        // _cpu is in x1 — pointer to cpu_state within fiber_frame
        uint64_t cpu_ptr = uc->uc_mcontext->__ss.__x[1];

        // Reconstruct guest segfault_addr from registers
        uint64_t x7 = uc->uc_mcontext->__ss.__x[7];
        uint64_t x10 = uc->uc_mcontext->__ss.__x[10];
        uint64_t guest_addr = (x7 - x10) & 0xffffffffffffULL;

        // Determine read/write from host ESR
        uint64_t esr = uc->uc_mcontext->__es.__esr;
        int was_write = (esr & 0x40) != 0;

        // Write crash info directly to cpu_state
        *(uint64_t *)(cpu_ptr + CRASH_CPU_segfault_addr) = guest_addr;
        *(int *)(cpu_ptr + CRASH_CPU_segfault_was_write) = was_write;
        *(uint64_t *)(cpu_ptr + CRASH_CPU_pc) = (uint64_t)jit_saved_pc;

        // Restore SP to the value saved by fiber_enter
        uint64_t exit_sp = *(uint64_t *)(cpu_ptr + CRASH_LOCAL_jit_exit_sp);
        uc->uc_mcontext->__ss.__sp = exit_sp;

        // Redirect execution to crash trampoline
        uc->uc_mcontext->__ss.__pc = (uint64_t)jit_crash_trampoline;

        // Unblock signal so it can fire again
        sigset_t unblock;
        sigemptyset(&unblock);
        sigaddset(&unblock, sig);
        sigprocmask(SIG_UNBLOCK, &unblock, NULL);

        return;
    }
#endif
    // Check if this is an iSH guest thread by testing the thread-local `in_jit` variable.
    // `in_jit` is only ever set on iSH execution threads. For non-iSH threads (e.g. Swift
    // async tasks, networking, UI), we must NOT intercept the signal — doing so kills the
    // thread via pthread_exit, which crashes the app (FOUNDATION 1 termination).
    // Instead, reset the signal to default and re-raise so the system crash reporter
    // handles it normally.
    //
    // Note: `in_jit` is 0 here (the in_jit==1 case was handled above). We use a separate
    // thread-local marker set when iSH threads start, to distinguish iSH threads with
    // in_jit==0 (between JIT runs) from non-iSH threads (never set).
    extern __thread int ish_thread_marker;
    if (!ish_thread_marker) {
        // Not an iSH thread — restore default handler and re-raise
        struct sigaction sa_default = {0};
        sa_default.sa_handler = SIG_DFL;
        sigaction(sig, &sa_default, NULL);
        raise(sig);
        return;
    }

    // Non-JIT crash in an iSH guest thread.
    // Unlike upstream (standalone process where _exit is fine), we're embedded
    // in the app — _exit would kill the entire app. Instead, log diagnostics
    // and terminate only this guest thread.
    char buf[512];
    int len;
    ucontext_t *uc = (ucontext_t *)ctx;
    len = snprintf(buf, sizeof(buf), "\n=== iSH GUEST CRASH: signal %d ===\nfault addr: %p\n", sig, info->si_addr);
    write(STDERR_FILENO, buf, len);
#ifdef __aarch64__
    len = snprintf(buf, sizeof(buf),
        "pc:  0x%llx\nlr:  0x%llx\nsp:  0x%llx\n"
        "x0:  0x%llx\nx1:  0x%llx\nx2:  0x%llx\n"
        "x7:  0x%llx\nx28: 0x%llx\n",
        uc->uc_mcontext->__ss.__pc, uc->uc_mcontext->__ss.__lr,
        uc->uc_mcontext->__ss.__sp,
        uc->uc_mcontext->__ss.__x[0], uc->uc_mcontext->__ss.__x[1],
        uc->uc_mcontext->__ss.__x[2],
        uc->uc_mcontext->__ss.__x[7], uc->uc_mcontext->__ss.__x[28]);
    write(STDERR_FILENO, buf, len);
#endif
    void *bt[20];
    int n = backtrace(bt, 20);
    backtrace_symbols_fd(bt, n, STDERR_FILENO);

    // Block all signals and suspend this thread forever.
    // pthread_exit() triggers SIGTRAP on iOS in certain thread states.
    sigset_t all;
    sigfillset(&all);
    pthread_sigmask(SIG_BLOCK, &all, NULL);
    select(0, NULL, NULL, NULL, NULL);
    __builtin_unreachable();
}

/// Custom die handler for embedded iSH: logs the fatal message and terminates
/// only the current thread instead of calling abort() which kills the entire app.
static void embedded_die_handler(const char *msg) {
    // Log to stderr (captured by LoggingManager)
    char buf[4096];
    int len = snprintf(buf, sizeof(buf), "\n=== iSH FATAL: %s ===\n", msg);
    write(STDERR_FILENO, buf, len);
    NSLog(@"ISHKernel: die() called: %s", msg);

    // Block all signals and suspend this thread forever.
    // pthread_exit() triggers SIGTRAP on iOS in certain thread states,
    // crashing the entire app. The iSH task thread is expendable — parking
    // it is safe and avoids taking down the process.
    sigset_t all;
    sigfillset(&all);
    pthread_sigmask(SIG_BLOCK, &all, NULL);
    select(0, NULL, NULL, NULL, NULL);  // sleep forever
    __builtin_unreachable();
}

static void install_jit_crash_handler(void) {
    static char altstack[SIGSTKSZ];
    stack_t ss = {.ss_sp = altstack, .ss_size = SIGSTKSZ};
    sigaltstack(&ss, NULL);

    struct sigaction sa = {0};
    sa.sa_sigaction = jit_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGTRAP, &sa, NULL);
    // Note: SIGABRT intentionally NOT handled — it's used by the system
    // (assert(), Swift runtime, malloc failure) and intercepting it prevents
    // normal crash reporting and can mask OOM conditions.
    NSLog(@"ISHKernel: JIT crash recovery handler installed");
}

// Forward declaration of private methods for C callbacks
@interface ISHKernel()
- (void)handleCommandOutputInternal:(NSString *)output;
@end

#pragma mark - TTY Driver

// TTY write callback - sends output to UI
static int ish_tty_write(struct tty *tty, const void *buf, size_t len, bool blocking) {
    if (len == 0) return 0;

    NSData *data = [NSData dataWithBytes:buf length:len];
    NSString *text = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];

    dispatch_async(dispatch_get_main_queue(), ^{
        // If we're waiting for command completion, buffer the output
        if (g_sharedKernel) {
            [g_sharedKernel handleCommandOutputInternal:text];
        }

        // Call output callback if set
        if (g_sharedKernel.outputCallback) {
            g_sharedKernel.outputCallback(data);
        } else {
            NSLog(@"ISHKernel: Warning - no output callback set");
        }

        // Also post notification
        [[NSNotificationCenter defaultCenter]
            postNotificationName:ISHTerminalOutputNotification
            object:nil
            userInfo:@{@"data": data}];
    });

    return (int)len;
}

static int ish_tty_init(struct tty *tty) {
    return 0;
}

static void ish_tty_cleanup(struct tty *tty) {
}

// Define TTY driver operations
static struct tty_driver_ops ish_console_ops = {
    .init = ish_tty_init,
    .write = ish_tty_write,
    .cleanup = ish_tty_cleanup,
};

// Define console TTY driver (used for init process stdio)
DEFINE_TTY_DRIVER(ish_console_driver, &ish_console_ops, TTY_CONSOLE_MAJOR, 8);

// PTY driver for interactive shell sessions (uses pty_open_fake)
static struct tty_driver ish_pty_driver = {.ops = &ish_console_ops};

#pragma mark - Process Exit Handler

static void handle_process_exit(struct task *task, int code) {
    // Only notify for init (parent == NULL) and direct children of init
    // (parent->parent == NULL). Grandchildren and deeper descendants are
    // ignored to avoid pids_lock contention with sys_wait4/wait_for.
    if (task->parent != NULL && task->parent->parent != NULL)
        return;

    pid_t pid = task->pid;
    dispatch_async(dispatch_get_main_queue(), ^{
        [[NSNotificationCenter defaultCenter]
            postNotificationName:ISHProcessExitedNotification
            object:nil
            userInfo:@{@"pid": @(pid), @"code": @(code)}];
    });
}

#pragma mark - ISHKernel Implementation

@implementation ISHKernel {
    BOOL _isBooted;
    struct tty *_consoleTTY;
    NSString *_rootPath;    // Host filesystem path to fakefs root (contains data/ + meta.db)
    NSString *_dataPath;    // Host filesystem path to fakefs data/ directory
    NSString *_dnsHostPath; // Host path to resolv.conf (Library/MinisChat/dns/resolv.conf)

    // Command execution state
    NSMutableString *_commandOutputBuffer;
    ISHCommandCompletionCallback _commandCompletionCallback;
    NSTimer *_commandTimeoutTimer;
    dispatch_queue_t _commandQueue;
}

+ (ISHKernel *)shared {
    static ISHKernel *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[ISHKernel alloc] init];
        g_sharedKernel = instance;
    });
    return instance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _isBooted = NO;
        _consoleTTY = NULL;
        _commandOutputBuffer = [NSMutableString new];
        _commandQueue = dispatch_queue_create("com.minisapp.ish.command", DISPATCH_QUEUE_SERIAL);
    }
    return self;
}

- (BOOL)isBooted {
    return _isBooted;
}

- (int)bootWithRootPath:(NSString *)rootPath {
    if (_isBooted) {
        NSLog(@"ISHKernel: Already booted");
        return 0;
    }

    int err;

    // 0. Install JIT crash recovery handler (must be before any JIT code runs)
    install_jit_crash_handler();

    // Override die() to terminate only the iSH thread instead of abort()ing the app.
    extern void (*die_handler)(const char *msg);
    die_handler = embedded_die_handler;

    // 1. Mount root filesystem
    _rootPath = rootPath;
    _dataPath = [rootPath stringByAppendingPathComponent:@"data"];
    err = mount_root(&fakefs, _dataPath.fileSystemRepresentation);
    if (err < 0) {
        NSLog(@"ISHKernel: mount_root failed: %d", err);
        return err;
    }
    NSLog(@"ISHKernel: Root filesystem mounted at %@", _dataPath);

    // Register the rootfs's canonical (symlink-resolved) host path with
    // fakefs so fakefs_bind_mount_resolve_path can suppress
    // self-containing bind mounts. F_GETPATH on Apple platforms returns
    // the canonical path (e.g. /Users/<user>/Library/Containers/<bundle>
    // /Data/Documents/alpine-rootfs/data), so we must canonicalize via
    // realpath() — otherwise the ancestor check below misses on macOS
    // where the user's home tree happens to contain the sandbox.
    char canonical_data_path[PATH_MAX];
    if (realpath(_dataPath.fileSystemRepresentation, canonical_data_path) != NULL) {
        NSLog(@"ISHKernel: rootfs canonical data path = %s", canonical_data_path);
        fakefs_set_rootfs_data_path(canonical_data_path);
    } else {
        NSLog(@"ISHKernel: realpath(_dataPath) failed (errno=%d), bind-mount cycle guard disabled", errno);
    }

    // 2. Become init process (PID 1)
    err = become_first_process();
    if (err < 0) {
        NSLog(@"ISHKernel: become_first_process failed: %d", err);
        return err;
    }
    current->thread = pthread_self();
    NSLog(@"ISHKernel: Init process created (PID 1)");

    // 3. Create device nodes
    [self createDeviceNodes];

    // 3.5. Apply rootfs overlay patches (e.g. fetch-polyfill.js)
    FsApplyOverlay();

    // 4. Mount proc and devpts filesystems
    do_mount(&procfs, "proc", "/proc", "", 0);
    do_mount(&devptsfs, "devpts", "/dev/pts", "", 0);
    NSLog(@"ISHKernel: /proc and /dev/pts mounted");

    // 5. Bind-mount and configure DNS
    [self mountDnsConfig];

    // 6. Set exit hook
    exit_hook = handle_process_exit;

    // 6.2. Point guest AF_UNIX sockets at the app's sandboxed temp directory.
    // MUST run before any guest process starts (see setUpUnixSocketPrefix).
    [self setUpUnixSocketPrefix];

    // 6.5. Install the fork memory guard so guest process bursts can't drive
    // the app's footprint into the Jetsam limit (see minis_fork_memory_guard).
    // [T-ish-forkguard-stall] Compute the device-scaled ceiling and start the
    // pressure source BEFORE installing the guard, so the very first fork sees
    // real values rather than the conservative defaults.
    g_fork_guard_max_footprint = minis_fork_guard_compute_ceiling();
    minis_fork_guard_start_pressure_source();
    ish_set_fork_guard(minis_fork_memory_guard);
    NSLog(@"ISHKernel: [ForkGuard] installed — max footprint %.0fMB "
          @"(device RAM %.0fMB), waits only under system memory pressure, cap %ums",
          (double) g_fork_guard_max_footprint / (1024.0 * 1024.0),
          (double) [NSProcessInfo processInfo].physicalMemory / (1024.0 * 1024.0),
          MINIS_FORK_GUARD_MAX_WAIT_US / 1000);

    // 7. Register TTY driver and set up console device
    tty_drivers[TTY_CONSOLE_MAJOR] = &ish_console_driver;
    set_console_device(TTY_CONSOLE_MAJOR, 1);
    NSLog(@"ISHKernel: TTY driver registered, console device set");

    // 8. Create stdio for init process (stdin/stdout/stderr connected to console)
    err = create_stdio("/dev/console", TTY_CONSOLE_MAJOR, 1);
    if (err < 0) {
        NSLog(@"ISHKernel: create_stdio failed: %d (non-fatal)", err);
    } else {
        NSLog(@"ISHKernel: stdio created for init process");
    }

    // 9. Register native binary bindings (ffmpeg, etc.)
    ffmpeg_offload_register();
    calendar_offload_register();
    location_offload_register();
    weather_offload_register();
    vision_offload_register();
    open_offload_register();
    clipboard_offload_register();
    healthkit_offload_register();
    photos_offload_register();
    maps_offload_register();
    nlp_offload_register();
    alarm_offload_register();
    media_offload_register();
    speak_offload_register();
    speech_offload_register();
    device_offload_register();
    homekit_offload_register();
    notification_offload_register();
    player_offload_register();
    model_use_offload_register();
    reminders_offload_register();
    bluetooth_offload_register();
    nfc_offload_register();
    sessions_offload_register();
    browser_use_offload_register();
    config_offload_register();
    // Registered in every build: the `minis-debug logs` subcommand reads the
    // app's own runtime log in-process (OSLogStore + LoggingManager) and must
    // work on Release devices (T-ios-minis-debug-logs-oslogstore). The
    // RPC-backed subcommands inside the handler stay DEBUG-gated and
    // self-report as unavailable in Release.
    debug_offload_register();

    _isBooted = YES;
    NSLog(@"ISHKernel: Kernel initialized successfully");

    return 0;
}

- (void)createDeviceNodes {
    // Create /dev directory structure
    generic_mkdirat(AT_PWD, "/dev", 0755);
    generic_mkdirat(AT_PWD, "/dev/pts", 0755);

    // TTY devices
    generic_mknodat(AT_PWD, "/dev/tty1", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 1));
    generic_mknodat(AT_PWD, "/dev/tty2", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 2));
    generic_mknodat(AT_PWD, "/dev/tty3", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 3));
    generic_mknodat(AT_PWD, "/dev/tty4", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 4));
    generic_mknodat(AT_PWD, "/dev/tty5", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 5));
    generic_mknodat(AT_PWD, "/dev/tty6", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 6));
    generic_mknodat(AT_PWD, "/dev/tty7", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 7));
    generic_mknodat(AT_PWD, "/dev/tty", S_IFCHR|0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_TTY_MINOR));
    generic_mknodat(AT_PWD, "/dev/console", S_IFCHR|0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_CONSOLE_MINOR));
    generic_mknodat(AT_PWD, "/dev/ptmx", S_IFCHR|0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_PTMX_MINOR));

    // Memory devices
    generic_mknodat(AT_PWD, "/dev/null", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_NULL_MINOR));
    generic_mknodat(AT_PWD, "/dev/zero", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_ZERO_MINOR));
    generic_mknodat(AT_PWD, "/dev/full", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_FULL_MINOR));
    generic_mknodat(AT_PWD, "/dev/random", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_RANDOM_MINOR));
    generic_mknodat(AT_PWD, "/dev/urandom", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_URANDOM_MINOR));

    NSLog(@"ISHKernel: Device nodes created");
}

- (void)refreshDns {
    if (!_dnsHostPath) {
        NSLog(@"ISHKernel: [DNS] Cannot refresh DNS — dnsHostPath not set yet");
        return;
    }
    NSLog(@"ISHKernel: [DNS] Refreshing DNS configuration");
    [self configureDns];
}

/// Build resolv.conf content from iOS system resolver state.
- (void)configureDns {
    // [T-ios-log-noise-reduction] This routine ran on every network change and
    // emitted ~10 NSLog lines each pass (~1.5k lines/day). Collapsed into a
    // single summary line at the end; the failure paths (res_ninit fail,
    // inet_ntop fail) still log individually since those are diagnostic.
    NSMutableString *resolvConf = [NSMutableString new];

    struct __res_state res;
    memset(&res, 0, sizeof(res));

    NSMutableArray<NSString *> *searchDomains = [NSMutableArray new];
    int serverCount = 0;
    int written = 0;
    BOOL initOk = (res_ninit(&res) == 0);

    if (initOk) {
        // Search domains
        for (int i = 0; i < MAXDNSRCH && res.dnsrch[i] != NULL; i++) {
            NSString *domain = [NSString stringWithUTF8String:res.dnsrch[i]];
            if (domain.length > 0) {
                [searchDomains addObject:domain];
            }
        }
        if (searchDomains.count > 0) {
            [resolvConf appendFormat:@"search %@\n", [searchDomains componentsJoinedByString:@" "]];
        }

        // Nameservers
        union res_sockaddr_union servers[NI_MAXSERV];
        serverCount = res_getservers(&res, servers, NI_MAXSERV);

        for (int i = 0; i < serverCount; i++) {
            if (servers[i].sin.sin_len == 0) {
                continue;
            }

            char addrStr[INET6_ADDRSTRLEN];
            const char *result = NULL;
            int family = servers[i].sin.sin_family;

            if (family == AF_INET) {
                result = inet_ntop(AF_INET, &servers[i].sin.sin_addr, addrStr, sizeof(addrStr));
            } else if (family == AF_INET6) {
                result = inet_ntop(AF_INET6, &servers[i].sin6.sin6_addr, addrStr, sizeof(addrStr));
            }

            if (result != NULL) {
                [resolvConf appendFormat:@"nameserver %s\n", addrStr];
                written++;
            } else {
                NSLog(@"ISHKernel: [DNS] server[%d] inet_ntop failed (family=%d, sin_len=%d)",
                      i, family, servers[i].sin.sin_len);
            }
        }

        res_nclose(&res);
    } else {
        NSLog(@"ISHKernel: [DNS] res_ninit failed — skipping system DNS");
    }

    // Fall back to public DNS if no system servers were found.
    BOOL usedFallback = NO;
    if ([resolvConf rangeOfString:@"nameserver"].location == NSNotFound) {
        usedFallback = YES;
        [resolvConf appendString:@"nameserver 8.8.8.8\n"];
        [resolvConf appendString:@"nameserver 8.8.4.4\n"];
    }

    NSLog(@"ISHKernel: [DNS] configured: search=%lu servers=%d/%d fallback=%@",
          (unsigned long)searchDomains.count, written, serverCount, usedFallback ? @"yes" : @"no");
    [self writeResolvConf:resolvConf];
}

/// Write resolv.conf to the host-side file that is bind-mounted into iSH.
/// Because /etc/resolv.conf is a file-level bind mount (symlink to the host
/// file), any write here is instantly visible to iSH processes — no VFS
/// or meta.db interaction needed.
- (void)writeResolvConf:(NSString *)content {
    NSData *data = [content dataUsingEncoding:NSUTF8StringEncoding];
    NSError *error = nil;

    BOOL ok = [data writeToFile:_dnsHostPath options:NSDataWritingAtomic error:&error];
    if (!ok) {
        NSLog(@"ISHKernel: [DNS] atomic write failed (%@) — retrying", error);
        error = nil;
        ok = [data writeToFile:_dnsHostPath options:0 error:&error];
    }

    if (ok) {
        NSLog(@"ISHKernel: [DNS] write OK — %lu bytes at %@", (unsigned long)data.length, _dnsHostPath);
    } else {
        NSLog(@"ISHKernel: [DNS] write FAILED — %@", error);
    }
}

/// [T-ios-ish-af-unix-sandbox GH#175] Point guest AF_UNIX sockets at the app's
/// own temp directory instead of the host's `/tmp`.
///
/// iSH does not implement Unix sockets in the guest — it translates each guest
/// address into a REAL host path `<sock_tmp_prefix><pid>.<socket_id>` and binds
/// that (`deps/ish/fs/sock.c:280`). The compiled-in default is `/tmp/ishsock`
/// (`fs/sock.c:229`), i.e. the iOS host `/tmp`, which no sandboxed app may
/// write. Every guest `bind()` therefore failed with EPERM: Terraform/OpenTofu
/// provider handshakes could never start (GH#175), and the minis-mcp-cli daemon
/// had to fall back to loopback TCP.
///
/// Upstream iSH fixed this in 2019 (`7704024a`) inside `app/AppDelegate.m`.
/// Minis does not compile that file — this class is its equivalent, and it
/// mirrors every other init from it (do_mount, DNS, exit_hook, tty_drivers,
/// create_stdio) EXCEPT this one line. So this is a re-alignment with upstream,
/// not a new mechanism, which is also why the fix belongs here rather than in
/// the cross-platform C core: `fs/sock.c` must not depend on Foundation, and
/// patching the fork there would conflict on the next upstream sync.
///
/// Notes for anyone touching this:
///   * `strdup` is load-bearing. `NSTemporaryDirectory()` returns an autoreleased
///     NSString; storing its `.UTF8String` in a global raw pointer would dangle
///     as soon as the pool drains.
///   * `NSTemporaryDirectory()` already ends in `/`, and `ishsock` is a FILENAME
///     PREFIX, not a directory — `sock.c` appends `<pid>.<id>` straight onto it.
///     Hence `stringByAppendingString:`, not `stringByAppendingPathComponent:`.
///   * Simulator is excluded, matching upstream: it is not sandboxed the same
///     way, and its container paths are longer (see the length check below).
///   * Must run BEFORE any guest process starts, since the prefix is read on
///     every bind. It is process-global and written once, so there is no
///     thread-safety or rootfs-reset concern: a reset re-runs boot, which
///     re-runs this, and the value does not depend on rootfs state.
- (void)setUpUnixSocketPrefix {
#if TARGET_OS_SIMULATOR
    // Upstream skips the simulator too — /tmp is writable there, and the
    // simulator's container path is long enough to risk the sun_path limit.
    NSLog(@"ISHKernel: [UnixSock] simulator — keeping default prefix '%s'", sock_tmp_prefix);
#else
    NSString *tempDir = NSTemporaryDirectory();

    // [GH#175] Drop the `/private` prefix if present. This is NOT cosmetic — it
    // is what makes the path fit at all. `NSTemporaryDirectory()` returns the
    // resolved form:
    //   /private/var/mobile/Containers/Data/Application/<uuid>/tmp/ishsock  = 96 bytes
    // and 96 + 12 (worst-case "<pid>.<id>") = 108 > 104, so the very first
    // device run of this fix hit the guard below and refused to install the
    // prefix. `/var` is a symlink to `/private/var`, so the short form names the
    // SAME directory:
    //   /var/mobile/Containers/Data/Application/<uuid>/tmp/ishsock           = 88 bytes
    // 88 + 12 = 100, which fits with 4 bytes to spare.
    if ([tempDir hasPrefix:@"/private/"]) {
        tempDir = [tempDir substringFromIndex:strlen("/private")];
    }

    NSString *prefix = [tempDir stringByAppendingString:@"ishsock"];

    // `struct sockaddr_un.sun_path` is only 104 bytes on Darwin, and
    // `sock.c`'s sprintf into it is UNCHECKED. The margin above is only 4
    // bytes, so if a future OS lengthens the container layout this must fail
    // loudly rather than smash the stack past the end of sun_path.
    const size_t kSunPathMax = sizeof(((struct sockaddr_un *)0)->sun_path);
    const size_t kSuffixWorstCase = 12; // "<pid>.<id>" + NUL
    if (prefix.UTF8String == NULL) {
        NSLog(@"ISHKernel: [UnixSock] ⚠️ temp dir unrepresentable — keeping default prefix");
        return;
    }
    size_t prefixLen = strlen(prefix.UTF8String);
    if (prefixLen + kSuffixWorstCase >= kSunPathMax) {
        NSLog(@"ISHKernel: [UnixSock] ⚠️ prefix too long for sun_path "
              @"(%zu + %zu >= %zu) — keeping default; guest AF_UNIX will fail",
              prefixLen, kSuffixWorstCase, kSunPathMax);
        return;
    }

    sock_tmp_prefix = strdup(prefix.UTF8String);
    NSLog(@"ISHKernel: [UnixSock] prefix → %s (%zu/%zu bytes of sun_path)",
          sock_tmp_prefix, prefixLen, kSunPathMax);
#endif
}

/// Set up /etc/resolv.conf as a file-level bind mount pointing to a host file.
/// Called once during boot, after the root filesystem and init process are ready.
/// The host file lives in Library/MinisChat/dns/resolv.conf and can be freely
/// updated by the app at any time — changes are instantly visible inside iSH.
- (void)mountDnsConfig {
    NSFileManager *fm = [NSFileManager defaultManager];

    // Determine host path: Library/MinisChat/dns/resolv.conf
    NSString *library = [NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, YES) firstObject];
    NSString *dnsDir = [library stringByAppendingPathComponent:@"MinisChat/dns"];
    _dnsHostPath = [dnsDir stringByAppendingPathComponent:@"resolv.conf"];

    // Ensure directory and seed file exist before bind mount
    if (![fm fileExistsAtPath:dnsDir]) {
        [fm createDirectoryAtPath:dnsDir withIntermediateDirectories:YES attributes:nil error:nil];
    }
    if (![fm fileExistsAtPath:_dnsHostPath]) {
        // Seed with fallback DNS so the file is never empty
        [@"nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
         writeToFile:_dnsHostPath atomically:YES encoding:NSUTF8StringEncoding error:nil];
        NSLog(@"ISHKernel: [DNS] seeded host resolv.conf at %@", _dnsHostPath);
    }

    // Bind mount: /etc/resolv.conf -> Library/MinisChat/dns/resolv.conf
    int err = fakefs_bind_mount("/etc/resolv.conf", _dnsHostPath.fileSystemRepresentation, false);
    if (err < 0) {
        NSLog(@"ISHKernel: [DNS] bind mount FAILED (%d) — writing via VFS fallback", err);
        // Fallback: write directly through VFS like original iSH
        struct task *prev = current;
        current = pid_get_task(1);
        if (current) {
            NSString *seed = [NSString stringWithContentsOfFile:_dnsHostPath encoding:NSUTF8StringEncoding error:nil];
            if (seed) {
                struct fd *fd = generic_open("/etc/resolv.conf", O_WRONLY_ | O_CREAT_ | O_TRUNC_, 0666);
                if (!IS_ERR(fd)) {
                    fd->ops->write(fd, seed.UTF8String, [seed lengthOfBytesUsingEncoding:NSUTF8StringEncoding]);
                    fd_close(fd);
                    NSLog(@"ISHKernel: [DNS] VFS fallback write OK");
                }
            }
            current = prev;
        } else {
            current = prev;
        }
        // Keep _dnsHostPath set so refreshDns can still update the host file
        // (even though it won't auto-appear in iSH without the bind mount)
        return;
    }
    NSLog(@"ISHKernel: [DNS] bind mount OK: /etc/resolv.conf -> %@", _dnsHostPath);

    // Now populate with actual system DNS
    [self configureDns];
}

- (int)executeCommand:(NSArray<NSString *> *)command {
    if (command.count == 0) {
        NSLog(@"ISHKernel: Empty command");
        return -1;
    }

    if (!_isBooted) {
        NSLog(@"ISHKernel: Kernel not booted");
        return -1;
    }

    // Spawn shell as a child of init process
    // This must be done in a background thread
    NSArray<NSString *> *commandCopy = [command copy];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        // Create a new child process of init
        int err = become_new_init_child();
        if (err < 0) {
            NSLog(@"ISHKernel: become_new_init_child failed: %d", err);
            return;
        }

        // Create a pseudo-terminal (PTY) for the shell session.
        // This gives the shell a proper controlling terminal with job control,
        // unlike a console TTY which is shared with init and can't be acquired
        // as a controlling terminal by the child process.
        struct tty *tty = pty_open_fake(&ish_pty_driver);
        if (IS_ERR(tty)) {
            NSLog(@"ISHKernel: pty_open_fake failed: %ld", PTR_ERR(tty));
            return;
        }

        // Store TTY reference for input/resize (thread-safe: only read after this point)
        self->_consoleTTY = tty;

        // Set terminal window size
        struct winsize_ winsize = {.row = 24, .col = 200, .xpixel = 0, .ypixel = 0};
        tty_set_winsize(tty, winsize);
        NSLog(@"ISHKernel: PTY created (pts/%d), terminal size %dx%d", tty->num, winsize.col, winsize.row);

        // Connect stdio to the PTY slave device
        NSString *stdioFile = [NSString stringWithFormat:@"/dev/pts/%d", tty->num];
        err = create_stdio(stdioFile.fileSystemRepresentation, TTY_PSEUDO_SLAVE_MAJOR, tty->num);
        if (err < 0) {
            NSLog(@"ISHKernel: create_stdio failed: %d", err);
            return;
        }

        // Build argv (with V8 flags injection for node)
        char argv[16384];
        size_t pos = 0;
        int exec_argc = 0;

        const char *exec_path = commandCopy[0].UTF8String;
        const char *base = strrchr(exec_path, '/');
        base = base ? base + 1 : exec_path;
        bool is_node = (strcmp(base, "node") == 0);

        if (is_node) {
            // Copy argv[0] first
            size_t len = strlen(exec_path) + 1;
            memcpy(argv + pos, exec_path, len);
            pos += len;
            exec_argc++;
            // Inject V8 flags to work around scope corruption in emulation
            static const char *v8_flags[] = {
                "--jitless",
                "--no-lazy",
                "--no-expose-wasm",
                "--max-old-space-size=512",
            };
            for (int fi = 0; fi < (int)(sizeof(v8_flags)/sizeof(v8_flags[0])); fi++) {
                len = strlen(v8_flags[fi]) + 1;
                if (pos + len >= sizeof(argv)) break;
                memcpy(argv + pos, v8_flags[fi], len);
                pos += len;
                exec_argc++;
            }
            // Copy remaining args (skip argv[0])
            for (NSUInteger ai = 1; ai < commandCopy.count; ai++) {
                const char *carg = commandCopy[ai].UTF8String;
                len = strlen(carg) + 1;
                if (pos + len >= sizeof(argv)) break;
                memcpy(argv + pos, carg, len);
                pos += len;
                exec_argc++;
            }
        } else {
            for (NSString *arg in commandCopy) {
                const char *carg = arg.UTF8String;
                size_t len = strlen(carg) + 1;
                if (pos + len >= sizeof(argv)) break;
                memcpy(argv + pos, carg, len);
                pos += len;
                exec_argc++;
            }
        }
        argv[pos] = '\0';

        // Build environment variables
        char envp_buf[8192];
        size_t envp_pos = 0;

#define KERNEL_ENVP_APPEND(s) do { \
    const char *_s = (s); \
    size_t _len = strlen(_s) + 1; \
    if (envp_pos + _len < sizeof(envp_buf) - 256) { \
        memcpy(envp_buf + envp_pos, _s, _len); \
        envp_pos += _len; \
    } \
} while(0)

        KERNEL_ENVP_APPEND("TERM=xterm-256color");
        KERNEL_ENVP_APPEND("HOME=/root");
        KERNEL_ENVP_APPEND("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin");
        KERNEL_ENVP_APPEND("LANG=C.UTF-8");
        KERNEL_ENVP_APPEND("CHARSET=UTF-8");
        KERNEL_ENVP_APPEND("ENV=/etc/profile");
        // Mirrors ISHShellExecutor: route browser-open calls to the in-app
        // preview shim. Needed for interactive terminal sessions too, where
        // Python's webbrowser module picks $BROWSER before $DISPLAY probing.
        KERNEL_ENVP_APPEND("BROWSER=/usr/local/bin/minis-open");

        // Inject device timezone so iSH userspace sees local time.
        // Use POSIX TZ format with a fixed name to avoid abbreviations like "GMT+8"
        // which contain +/- and confuse musl's TZ parser.
        {
            NSTimeZone *tz = [NSTimeZone systemTimeZone];
            NSInteger secs = tz.secondsFromGMT;
            NSInteger hrs  = secs / 3600;
            NSInteger mins = labs(secs % 3600) / 60;
            NSString *posixTZ;
            if (mins != 0) {
                posixTZ = [NSString stringWithFormat:@"LCL%+ld:%02ld",
                           (long)-hrs, (long)mins];
            } else {
                posixTZ = [NSString stringWithFormat:@"LCL%+ld",
                           (long)-hrs];
            }
            NSString *tzEnv = [NSString stringWithFormat:@"TZ=%@", posixTZ];
            KERNEL_ENVP_APPEND(tzEnv.UTF8String);
        }

        // Inject runtime compatibility env vars (matches xX_main_Xx.h / kernel/exec.c)
        KERNEL_ENVP_APPEND("GODEBUG=asyncpreemptoff=1");
        KERNEL_ENVP_APPEND("GOMAXPROCS=2");
        KERNEL_ENVP_APPEND("NO_COLOR=1");
        KERNEL_ENVP_APPEND("PYTHONMALLOC=malloc");
        KERNEL_ENVP_APPEND("PYTHONDONTWRITEBYTECODE=1");

        // Node-specific: LD_PRELOAD for zero_free.so
        if (is_node) {
            KERNEL_ENVP_APPEND("LD_PRELOAD=/lib/zero_free.so");
        }

        // Append custom environment variables
        NSDictionary<NSString *, NSString *> *customEnv = self->_customEnvironment;
        if (customEnv) {
            for (NSString *key in customEnv) {
                NSString *entry = [NSString stringWithFormat:@"%@=%@", key, customEnv[key]];
                KERNEL_ENVP_APPEND(entry.UTF8String);
            }
        }
        envp_buf[envp_pos] = '\0'; // double-NUL terminate

#undef KERNEL_ENVP_APPEND

        const char *envp = envp_buf;

        // Execute command
        err = do_execve(exec_path, exec_argc, argv, envp);
        if (err < 0) {
            NSLog(@"ISHKernel: do_execve failed: %d", err);
            return;
        }

        NSLog(@"ISHKernel: Starting shell process (PID %d)", current->pid);

        // Start task - this runs the emulator loop and doesn't return
        task_start(current);
    });

    NSLog(@"ISHKernel: Shell launch initiated: %@", command[0]);
    return 0;
}

- (void)sendInput:(NSData *)data {
    if (_consoleTTY && data.length > 0) {
        tty_input(_consoleTTY, data.bytes, data.length, false);
    }
}


- (void)sendInputString:(NSString *)input {
    NSData *data = [input dataUsingEncoding:NSUTF8StringEncoding];
    [self sendInput:data];
}

- (void)setTerminalSize:(int)columns rows:(int)rows {
    if (_consoleTTY) {
        struct winsize_ winsize;
        winsize.row = rows;
        winsize.col = columns;
        winsize.xpixel = 0;
        winsize.ypixel = 0;
        tty_set_winsize(_consoleTTY, winsize);
        NSLog(@"ISHKernel: Terminal size updated to %dx%d", columns, rows);
    } else {
        NSLog(@"ISHKernel: Warning - cannot set terminal size, TTY not initialized");
    }
}

- (int)bindMountPath:(NSString *)linuxPath toHostPath:(NSString *)hostPath {
    return [self bindMountPath:linuxPath toHostPath:hostPath readOnly:NO];
}

- (int)bindMountPath:(NSString *)linuxPath toHostPath:(NSString *)hostPath readOnly:(BOOL)readOnly {
    if (!_isBooted) {
        NSLog(@"ISHKernel: bindMount failed — kernel not booted");
        return -1;
    }
    int err = fakefs_bind_mount(linuxPath.fileSystemRepresentation,
                                hostPath.fileSystemRepresentation,
                                readOnly ? true : false);
    if (err < 0) {
        NSLog(@"ISHKernel: bindMount %@ -> %@ (ro=%d) failed: %d",
              linuxPath, hostPath, readOnly, err);
    }
    return err;
}

- (int)bindUnmountPath:(NSString *)linuxPath {
    if (!_isBooted) {
        return -1;
    }
    return fakefs_bind_unmount(linuxPath.fileSystemRepresentation);
}

#pragma mark - Command Execution with Completion

- (void)executeCommandAndWait:(NSString *)commandString
                      timeout:(NSTimeInterval)timeout
                   completion:(ISHCommandCompletionCallback)completion {
    if (!_isBooted) {
        dispatch_async(dispatch_get_main_queue(), ^{
            NSError *error = [NSError errorWithDomain:@"ISHKernel"
                                                 code:-1
                                             userInfo:@{NSLocalizedDescriptionKey: @"Kernel not booted"}];
            completion(nil, error);
        });
        return;
    }

    if (_commandCompletionCallback) {
        dispatch_async(dispatch_get_main_queue(), ^{
            NSError *error = [NSError errorWithDomain:@"ISHKernel"
                                                 code:-2
                                             userInfo:@{NSLocalizedDescriptionKey: @"Another command is already executing"}];
            completion(nil, error);
        });
        return;
    }

    dispatch_async(_commandQueue, ^{
        // Clear buffer and set callback
        [self->_commandOutputBuffer setString:@""];
        self->_commandCompletionCallback = completion;

        // Set timeout if specified
        if (timeout > 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self->_commandTimeoutTimer = [NSTimer scheduledTimerWithTimeInterval:timeout
                                                                              repeats:NO
                                                                                block:^(NSTimer *timer) {
                    [self completeCommandWithOutput:nil
                                              error:[NSError errorWithDomain:@"ISHKernel"
                                                                        code:-3
                                                                    userInfo:@{NSLocalizedDescriptionKey: @"Command timed out"}]];
                }];
            });
        }

        // Send command with newline
        NSString *commandWithNewline = [commandString stringByAppendingString:@"\n"];
        [self sendInputString:commandWithNewline];

        NSLog(@"ISHKernel: Executing command and waiting: %@", commandString);
    });
}

- (void)handleCommandOutputInternal:(NSString *)output {
    if (!_commandCompletionCallback) {
        return;
    }

    // Append to buffer
    [_commandOutputBuffer appendString:output];

    // Check if we've received a shell prompt (indicating command completion)
    // Common prompt patterns: "$ ", "# ", "root@minis:", etc.
    NSString *buffer = _commandOutputBuffer;

    // Look for prompt patterns at the end of the buffer
    // We look for patterns like:
    // - "$ " or "# " at the end
    // - "username@hostname:path$ " or similar
    // - Any line ending with "$ " or "# "
    NSArray<NSString *> *promptPatterns = @[
        @"\\$\\s*$",           // $ at end with optional whitespace
        @"#\\s*$",             // # at end with optional whitespace
        @"[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+:[^$#]*[$#]\\s*$",  // user@host:path$ or #
        @"\\[.*\\][$#]\\s*$"   // [some context]$ or #
    ];

    for (NSString *pattern in promptPatterns) {
        NSError *error = nil;
        NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:pattern
                                                                               options:NSRegularExpressionAnchorsMatchLines
                                                                                 error:&error];
        if (regex) {
            NSRange range = NSMakeRange(0, buffer.length);
            NSTextCheckingResult *match = [regex firstMatchInString:buffer options:0 range:range];

            if (match) {
                // Found a prompt - command is complete
                NSLog(@"ISHKernel: Detected command completion (prompt pattern: %@)", pattern);

                // Extract the output (everything before the prompt)
                NSString *commandOutput = buffer;
                if (match.range.location > 0) {
                    commandOutput = [buffer substringToIndex:match.range.location];
                }

                // Clean up the output:
                // 1. Remove the echoed command line (first line)
                // 2. Remove trailing whitespace
                NSArray<NSString *> *lines = [commandOutput componentsSeparatedByString:@"\n"];
                if (lines.count > 1) {
                    // Skip first line (echoed command) and last empty lines
                    NSMutableArray<NSString *> *outputLines = [NSMutableArray array];
                    for (NSInteger i = 1; i < lines.count; i++) {
                        NSString *line = lines[i];
                        if (line.length > 0 || i < lines.count - 1) {
                            [outputLines addObject:line];
                        }
                    }
                    commandOutput = [outputLines componentsJoinedByString:@"\n"];
                }
                commandOutput = [commandOutput stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];

                [self completeCommandWithOutput:commandOutput error:nil];
                return;
            }
        }
    }

    // If buffer is getting too large without a prompt, something might be wrong
    if (buffer.length > 100000) {
        NSLog(@"ISHKernel: Warning - command output buffer exceeded 100KB without prompt detection");
        [self completeCommandWithOutput:buffer
                                  error:[NSError errorWithDomain:@"ISHKernel"
                                                            code:-4
                                                        userInfo:@{NSLocalizedDescriptionKey: @"Output too large without prompt"}]];
    }
}

- (void)completeCommandWithOutput:(NSString *)output error:(NSError *)error {
    if (!_commandCompletionCallback) {
        return;
    }

    ISHCommandCompletionCallback callback = _commandCompletionCallback;
    _commandCompletionCallback = nil;

    // Cancel timeout timer
    if (_commandTimeoutTimer) {
        [_commandTimeoutTimer invalidate];
        _commandTimeoutTimer = nil;
    }

    // Call completion on main queue
    dispatch_async(dispatch_get_main_queue(), ^{
        callback(output, error);
    });

    NSLog(@"ISHKernel: Command completed with %@ (output: %ld chars)",
          error ? @"error" : @"success",
          (long)output.length);
}

@end

#pragma mark - Fakefs change tracker bridge

@implementation ISHFakefsChangeEvent {
    NSString *_linuxPath;
    int _op;
    int64_t _timestampNs;
    uint64_t _fsContext;
}
- (instancetype)initWithLinuxPath:(NSString *)linuxPath
                               op:(int)op
                      timestampNs:(int64_t)ts
                        fsContext:(uint64_t)ctx {
    if ((self = [super init])) {
        _linuxPath = [linuxPath copy];
        _op = op;
        _timestampNs = ts;
        _fsContext = ctx;
    }
    return self;
}
- (NSString *)linuxPath { return _linuxPath; }
- (int)op { return _op; }
- (int64_t)timestampNs { return _timestampNs; }
- (uint64_t)fsContext { return _fsContext; }
@end

@implementation ISHKernel (FakefsChange)

- (void)installFakefsChangeHandler:(void (^)(NSArray<ISHFakefsChangeEvent *> *))handler {
    if (handler == nil) {
        // Detach by installing a no-op handler — fakefs C layer treats nil
        // as "ignore call", so we install an empty block to clear behavior.
        fakefs_install_change_consumer(^(const struct fakefs_change_event *batch, int count) {
            (void)batch; (void)count;
        });
        return;
    }
    void (^handlerCopy)(NSArray<ISHFakefsChangeEvent *> *) = [handler copy];
    fakefs_install_change_consumer(^(const struct fakefs_change_event *batch, int count) {
        if (count <= 0) return;
        NSMutableArray<ISHFakefsChangeEvent *> *events = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
        for (int i = 0; i < count; i++) {
            NSString *path = [NSString stringWithUTF8String:batch[i].linux_path];
            if (path == nil) continue;
            ISHFakefsChangeEvent *evt = [[ISHFakefsChangeEvent alloc]
                initWithLinuxPath:path
                               op:batch[i].op
                      timestampNs:batch[i].timestamp_ns
                        fsContext:batch[i].fs_context];
            [events addObject:evt];
        }
        handlerCopy(events);
    });
}

- (uint64_t)fakefsChangeDroppedCount {
    return fakefs_change_dropped_count();
}

@end

#pragma mark - Path translate hook bridge

/* Block storage for the registered ISHPathTranslateHandler. Atomically swapped
 * on install; the C trampoline below reads it on every hot-path translation. */
static _Atomic(void *) g_path_translate_block = NULL;

static bool ish_path_translate_trampoline(const char *guest_path,
                                          uint64_t fs_context,
                                          char *out_host_path,
                                          size_t out_size) {
    void *raw = atomic_load_explicit(&g_path_translate_block, memory_order_acquire);
    if (raw == NULL || guest_path == NULL || out_host_path == NULL || out_size == 0)
        return false;
    /* No retain/release dance: installPathTranslateHandler: never releases
     * a previously-installed block (see comment there), so this pointer
     * is guaranteed to outlive the trampoline call. */
    ISHPathTranslateHandler handler = (__bridge ISHPathTranslateHandler)raw;
    bool ok = false;
    @autoreleasepool {
        NSString *guest = [NSString stringWithUTF8String:guest_path];
        if (guest != nil) {
            NSString *host = handler(guest, fs_context);
            if (host != nil) {
                const char *cstr = host.fileSystemRepresentation;
                if (cstr != NULL) {
                    size_t len = strlen(cstr);
                    if (len + 1 <= out_size) {
                        memcpy(out_host_path, cstr, len + 1);
                        ok = true;
                    }
                }
            }
        }
    }
    return ok;
}

#pragma mark - Reverse path translate bridge

static _Atomic(void *) g_path_reverse_block = NULL;

static bool ish_path_reverse_trampoline(const char *host_path,
                                        char *out_guest_path,
                                        size_t out_size) {
    void *raw = atomic_load_explicit(&g_path_reverse_block, memory_order_acquire);
    if (raw == NULL || host_path == NULL || out_guest_path == NULL || out_size == 0)
        return false;
    ISHPathReverseHandler handler = (__bridge ISHPathReverseHandler)raw;
    bool ok = false;
    @autoreleasepool {
        NSString *host = [NSString stringWithUTF8String:host_path];
        if (host != nil) {
            NSString *guest = handler(host);
            if (guest != nil) {
                const char *cstr = [guest cStringUsingEncoding:NSUTF8StringEncoding];
                if (cstr != NULL) {
                    size_t len = strlen(cstr);
                    if (len + 1 <= out_size) {
                        memcpy(out_guest_path, cstr, len + 1);
                        ok = true;
                    }
                }
            }
        }
    }
    return ok;
}

#pragma mark - CPU Throttle (C-only hot path, no ObjC messaging)

// sleep_ratio = (1 - dutyCycle) / dutyCycle.  0 = disabled (foreground).
// Stored as fixed-point Q16: ratio_q16 = (int)(sleep_ratio * 65536).
static _Atomic int g_throttle_ratio_q16 = 0;
static _Atomic uint64_t g_throttle_tick_total = 0;
static _Atomic uint64_t g_throttle_sleep_total = 0;
static _Atomic uint64_t g_throttle_sleep_ns_total = 0;
static _Atomic uint64_t g_throttle_elapsed_ns_total = 0;
static _Atomic uint64_t g_throttle_last_log_ts = 0;

#define THROTTLE_LOG_INTERVAL_NS (60ULL * 1000000000ULL)
#define THROTTLE_DEBT_CLAMP_NS   (2000ULL * 1000000ULL) // was 100ms: debts above the old clamp were silently forgiven; 2s allows real braking at poke cadence
// [T-ish-throttle-deepred] Deep-brake clamp: with duty ≤5% the 2s clamp is
// not enough when the poke timer itself degrades. RED braking assumes a
// 100ms work quantum between pokes; if the governor's utility-QoS timer is
// delayed (thermal pressure, scheduler starvation) the quantum grows and
// per-thread duty rises with it — 500ms quanta against 2s sleeps is 20%
// duty, and N hot threads multiply that into kill territory. A 10s clamp
// keeps even a degraded 500ms quantum at ~4.8% duty. Foreground-return and
// signal latency stay bounded by the 100ms slice checks below.
#define THROTTLE_DEEP_CLAMP_NS   (10000ULL * 1000000ULL)
#define THROTTLE_DEEP_RATIO_Q16  ((int)((0.95 / 0.05) * 65536))  // duty ≤ 5%
#define THROTTLE_EAGER_DEBT_NS   (20ULL * 1000000ULL)   // pay every tick once debt passes this (bursts must not run 10 ticks ahead)

static int ish_throttle_trampoline(void) {
    int ratio = atomic_load_explicit(&g_throttle_ratio_q16, memory_order_relaxed);
    if (ratio <= 0) return 0;

    atomic_fetch_add_explicit(&g_throttle_tick_total, 1, memory_order_relaxed);

    static __thread uint64_t last_ts = 0;
    static __thread uint64_t owed_ns = 0;
    static __thread unsigned tick_count = 0;
    uint64_t now = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);

    if (last_ts == 0) {
        // [T-ish-throttle-coldstart] First tick of a NEW thread while
        // throttling is active. Previously this tick only set the baseline,
        // so a thread forked in the RED zone got a full-speed head start
        // until its second tick — and a fan-out of fresh workers could
        // overshoot the window faster than the governor's next sample could
        // react. Seed a starting debt instead: the recent global average
        // sleep quantum (what peer threads currently pay per cycle), or —
        // with no history yet — one 10ms timer quantum's worth of debt at
        // the current ratio. Deliberately coarse: this closes the loophole,
        // it does not model the thread's actual usage.
        last_ts = now;
        uint64_t sleeps = atomic_load_explicit(&g_throttle_sleep_total, memory_order_relaxed);
        uint64_t slept  = atomic_load_explicit(&g_throttle_sleep_ns_total, memory_order_relaxed);
        uint64_t seed = sleeps > 0 ? slept / sleeps
                                   : ((10000000ULL * (uint64_t)ratio) >> 16);
        if (seed > THROTTLE_DEBT_CLAMP_NS) seed = THROTTLE_DEBT_CLAMP_NS;
        owed_ns = seed;
        tick_count = 0;
    } else {
        uint64_t elapsed = now - last_ts;
        last_ts = now;

        // A long gap means the thread was blocked/descheduled, not burning
        // CPU — it owes nothing for time it did not run.
        if (elapsed > 500000000ULL) { tick_count = 0; owed_ns = 0; return 0; }

        atomic_fetch_add_explicit(&g_throttle_elapsed_ns_total, elapsed, memory_order_relaxed);

        // Accumulate sleep debt: owed += elapsed * ratio / 65536
        owed_ns += (elapsed * (uint64_t)ratio) >> 16;
    }

    // Pay every 10 ticks normally, or immediately once the debt passes the
    // eager threshold.
    if (owed_ns < THROTTLE_EAGER_DEBT_NS && ++tick_count < 10) return 0;
    tick_count = 0;
    if (owed_ns == 0) return 0;

    uint64_t clamp = ratio >= THROTTLE_DEEP_RATIO_Q16 ? THROTTLE_DEEP_CLAMP_NS
                                                      : THROTTLE_DEBT_CLAMP_NS;
    if (owed_ns > clamp) owed_ns = clamp;

    atomic_fetch_add_explicit(&g_throttle_sleep_total, 1, memory_order_relaxed);
    atomic_fetch_add_explicit(&g_throttle_sleep_ns_total, owed_ns, memory_order_relaxed);

    // Periodic log every 10s (only one thread wins the CAS)
    uint64_t last_log = atomic_load_explicit(&g_throttle_last_log_ts, memory_order_relaxed);
    if (now - last_log >= THROTTLE_LOG_INTERVAL_NS) {
        if (atomic_compare_exchange_weak_explicit(&g_throttle_last_log_ts, &last_log, now,
                                                   memory_order_relaxed, memory_order_relaxed)) {
            uint64_t ticks = atomic_load_explicit(&g_throttle_tick_total, memory_order_relaxed);
            uint64_t sleeps = atomic_load_explicit(&g_throttle_sleep_total, memory_order_relaxed);
            uint64_t slept_ns = atomic_load_explicit(&g_throttle_sleep_ns_total, memory_order_relaxed);
            uint64_t worked_ns = atomic_load_explicit(&g_throttle_elapsed_ns_total, memory_order_relaxed);
            uint64_t avg_tick_us = ticks > 0 ? (worked_ns / ticks / 1000) : 0;
            uint64_t avg_sleep_us = sleeps > 0 ? (slept_ns / sleeps / 1000) : 0;
            double actual_duty = (worked_ns + slept_ns) > 0
                ? (double)worked_ns / (double)(worked_ns + slept_ns) * 100.0
                : 0.0;
            NSLog(@"ISHKernel: [Throttle] ticks=%llu sleeps=%llu avgTick=%lluµs avgSleep=%lluµs duty=%.0f%%",
                  ticks, sleeps, avg_tick_us, avg_sleep_us, actual_duty);
        }
    }

    // Pay in 100ms slices, re-checking BETWEEN slices so neither of these is
    // ever blocked longer than one slice:
    //  - foreground return (ratio → 0),
    //  - signal delivery: receive_signals() runs only after this hook
    //    returns, so a pending SIGKILL/SIGTERM would otherwise wait out the
    //    full clamped sleep (up to 10s in deep RED) — that would visibly
    //    slow guest process kills and killProcessGroup's TERM→wait→KILL
    //    escalation. current->pending is read unlocked as a hint; a racy
    //    read only means one extra slice of latency.
    uint64_t remaining = owed_ns;
    while (remaining > 0) {
        if (atomic_load_explicit(&g_throttle_ratio_q16, memory_order_relaxed) <= 0)
            break;
        if (current != NULL && current->pending != 0)
            break;
        uint64_t slice = remaining > 100000000ULL ? 100000000ULL : remaining;
        struct timespec ts = { .tv_sec = 0, .tv_nsec = (long)slice };
        nanosleep(&ts, NULL);
        remaining -= slice;
    }
    owed_ns = 0;
    // [T-ish-throttle-sleepbias] Re-stamp AFTER sleeping so the sleep is not
    // billed as "work" on the next tick. The old code stamped before the
    // sleep, inflating elapsed by the sleep itself and compounding debt on
    // wall-clock instead of CPU-time — one reason measured duty drifted from
    // the target.
    last_ts = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
    return 0;
}

@implementation ISHKernel (Scheduler)

- (void)enableCPUThrottleWithDutyCycle:(float)dutyCycle {
    if (dutyCycle <= 0.0f) dutyCycle = 0.01f;
    if (dutyCycle >= 1.0f) { [self disableCPUThrottle]; return; }
    float ratio = (1.0f - dutyCycle) / dutyCycle;
    int ratio_q16 = (int)(ratio * 65536.0f);
    if (ratio_q16 <= 0) ratio_q16 = 1;
    atomic_store_explicit(&g_throttle_tick_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_sleep_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_sleep_ns_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_elapsed_ns_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_last_log_ts, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_ratio_q16, ratio_q16, memory_order_release);
    ish_set_timer_tick_hook(ish_throttle_trampoline);
    NSLog(@"ISHKernel: [Throttle] ENABLED dutyCycle=%.0f%% ratio_q16=%d", dutyCycle * 100, ratio_q16);
}

- (void)disableCPUThrottle {
    uint64_t ticks = atomic_load_explicit(&g_throttle_tick_total, memory_order_relaxed);
    uint64_t sleeps = atomic_load_explicit(&g_throttle_sleep_total, memory_order_relaxed);
    uint64_t sleep_ns = atomic_load_explicit(&g_throttle_sleep_ns_total, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_ratio_q16, 0, memory_order_release);
    uint64_t avg_us = sleeps > 0 ? (sleep_ns / sleeps / 1000) : 0;
    NSLog(@"ISHKernel: [Throttle] DISABLED — ticks=%llu sleeps=%llu avgSleep=%lluµs totalSleep=%.1fs",
          ticks, sleeps, avg_us, (double)sleep_ns / 1e9);
}

#pragma mark - Background CPU Governor (closed-loop sliding window)

// [T-ish-bg-cpu-governor] Closed-loop governor per
// docs/ish-bg-cpu-governor-design.md. iOS 26 background budget (empirical,
// IPS 2026-08-02): 48 CPU-s per 60s sliding window, enforced by kill.
// Sense what iOS bills (process-wide CPU via proc_pid_rusage), drive the
// Q16 throttle ratio as feedback so actuator inaccuracy cannot break safety.

// 10 Hz cadence: the sensor is cheap, and the same tick doubles as the
// BRAKE DRIVER — chained guest blocks never return to the dispatch loop's
// cycle-based INT_TIMER raiser (verified on device: a busy shell loop ran
// at 99% straight through RED with the hook never firing), so the governor
// must cpu_poke() running guests to force the tick hook to run. Poke
// cadence bounds the work quantum between sleeps: 100ms work + up to 2s
// clamped debt ≈ 5% duty floor in RED.
#define GOV_CADENCE_NS      (100ULL * 1000000ULL)   // 10 Hz sample + poke
#define GOV_SLOTS           601                     // 60s of samples + newest
#define GOV_RATE_LOOKBACK   10                      // R over last 10 samples (1s)
#define GOV_SOFT_CPU_S      30.0                    // GREEN below (predicted W)
#define GOV_HARD_CPU_S      38.0                    // RED at/above (predicted W)
#define GOV_RED_EXIT_CPU_S  32.0                    // leave RED below (actual W)
#define GOV_YELLOW_MIN_DUTY 0.15                    // YELLOW floor
#define GOV_RED_DUTY        0.03                    // RED hard brake
#define GOV_PREDICT_S       1.0                     // rate-prediction horizon
#define GOV_STARTUP_CAP_NS  (2ULL * 1000000000ULL)  // first 2s: duty capped at 0.5
#define GOV_LOG_INTERVAL_NS (5ULL * 1000000000ULL)  // periodic [Governor] status line

static dispatch_queue_t  g_gov_queue;
static dispatch_source_t g_gov_timer;
static uint64_t g_gov_cpu_ns[GOV_SLOTS];   // cumulative process CPU (ns), ring
static uint64_t g_gov_wall_ns[GOV_SLOTS];  // wall stamp per sample (see gov_tick)
static int      g_gov_head;                // next write slot
static int      g_gov_count;               // valid samples
static int      g_gov_zone;                // 0 GREEN / 1 YELLOW / 2 RED
static uint64_t g_gov_begin_ts;
static uint64_t g_gov_last_log_ts;

// Total process CPU time in ns via public mach APIs (libproc.h is not in
// the iOS SDK): MACH_TASK_BASIC_INFO carries user/system time of TERMINATED
// threads, TASK_THREAD_TIMES_INFO of LIVE threads — the sum is what iOS
// bills the process for. µs resolution is plenty at a 250ms cadence.
static uint64_t gov_process_cpu_ns(void) {
    mach_task_basic_info_data_t basic;
    mach_msg_type_number_t bcount = MACH_TASK_BASIC_INFO_COUNT;
    task_thread_times_info_data_t times;
    mach_msg_type_number_t tcount = TASK_THREAD_TIMES_INFO_COUNT;
    if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO,
                  (task_info_t)&basic, &bcount) != KERN_SUCCESS)
        return 0;
    if (task_info(mach_task_self(), TASK_THREAD_TIMES_INFO,
                  (task_info_t)&times, &tcount) != KERN_SUCCESS)
        return 0;
    uint64_t us = (uint64_t)basic.user_time.seconds   * 1000000ULL + basic.user_time.microseconds
                + (uint64_t)basic.system_time.seconds * 1000000ULL + basic.system_time.microseconds
                + (uint64_t)times.user_time.seconds   * 1000000ULL + times.user_time.microseconds
                + (uint64_t)times.system_time.seconds * 1000000ULL + times.system_time.microseconds;
    return us * 1000ULL;
}

static const char *gov_zone_name(int zone) {
    return zone == 2 ? "RED" : (zone == 1 ? "YELLOW" : "GREEN");
}

// [T-ish-throttle-poke] Force every running guest CPU out of chained block
// execution so the INT_TIMER tick hook actually runs. cpu_poke only sets an
// atomic flag (the same channel signal delivery uses), so this is safe under
// pids_lock and O(MAX_PID) over a flat array — ~µs per sweep.
//
// [T-ish-gov-trylock] TRYLOCK, never a blocking lock. The sweep itself is
// cheap, but ACQUIRING pids_lock is not when a guest is holding it: this
// runs on a 10 Hz dispatch timer, so a blocking wait puts the governor queue
// in the same queue as everything else contending for that lock. The
// 2026-08-23 20:11 crash caught this thread parked in __psynch_mutexwait
// behind a wedged waitpid — one more waiter making the convoy worse, for
// work that is pure optimisation.
//
// Skipping is free: the throttle ratio was already published with an atomic
// store BEFORE this call, so accuracy does not depend on the poke at all.
// The poke only makes chained guest code notice the new ratio sooner, and
// cpu_poke is a single idempotent atomic store — so a skipped tick is picked
// up by the next one 100ms later. Missing a poke costs latency, never
// correctness.
static void gov_poke_all_tasks(void) {
    if (trylock(&pids_lock) != 0)
        return;  // contended — next tick (100ms) will do it
    for (int i = 1; i < MAX_PID; i++) {
        struct pid *pid = pid_get(i);
        if (pid == NULL || pid->task == NULL) continue;
        cpu_poke(&pid->task->cpu);
    }
    unlock(&pids_lock);
}

// One controller step. Runs on g_gov_queue only.
static void gov_tick(void) {
    uint64_t now = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
    uint64_t cpu = gov_process_cpu_ns();
    // Sensor failure (rusage returned 0): skip the sample entirely — pushing
    // a 0 would make the unsigned W subtraction underflow into permanent RED.
    if (cpu == 0) return;

    g_gov_cpu_ns[g_gov_head] = cpu;
    g_gov_wall_ns[g_gov_head] = now;
    int newest = g_gov_head;
    g_gov_head = (g_gov_head + 1) % GOV_SLOTS;
    if (g_gov_count < GOV_SLOTS) g_gov_count++;

    // W: CPU-s consumed across the window (up to 60s; shorter right after
    // begin, which matches the OS opening a fresh budget at backgrounding).
    //
    // [T-gov-wall-window] Walk back by WALL TIME, not sample count: dispatch
    // timers stall under suspension/pressure, so "600 samples ago" can be
    // wall-hours old. Counting samples would compress pre-suspension burn
    // into a fake 60s window and hold the throttle in RED for up to a
    // minute after resume, even though RunningBoard's real window drained
    // to ~zero during the suspension. Stop at the newest sample that is
    // ≥60s old (or the oldest available).
    int span = g_gov_count - 1;
    if (span < 1) return;  // need two samples
    int oldest = newest;
    for (int back = 1; back <= span; back++) {
        int idx = (newest - back + GOV_SLOTS) % GOV_SLOTS;
        oldest = idx;
        if (now - g_gov_wall_ns[idx] >= 60ULL * 1000000000ULL)
            break;
    }
    // Underflow-safe deltas: thread exit migrates time between the two
    // task_info counters with µs rounding, so the sum can dip momentarily.
    uint64_t base = g_gov_cpu_ns[oldest];
    double W = cpu > base ? (double)(cpu - base) / 1e9 : 0.0;

    // R: burn rate over the last ~1s (CPU-s per wall-s; >1 means multi-core),
    // over the ACTUAL wall span of the lookback samples (same stall logic).
    int rb = span < GOV_RATE_LOOKBACK ? span : GOV_RATE_LOOKBACK;
    int ridx = (newest - rb + GOV_SLOTS) % GOV_SLOTS;
    uint64_t rbase = g_gov_cpu_ns[ridx];
    uint64_t rwall = now - g_gov_wall_ns[ridx];
    double R = (cpu > rbase && rwall > 0)
        ? (double)(cpu - rbase) / (double)rwall : 0.0;
    double Wpred = W + R * GOV_PREDICT_S;

    // Zone selection with hysteresis: RED is entered on prediction, exited
    // only when ACTUAL W has drained below GOV_RED_EXIT_CPU_S. YELLOW→GREEN
    // needs 1 CPU-s of slack below the soft line — without it, Wpred
    // hovering at the boundary flaps zones at 10 Hz and every flap emits a
    // transition log line.
    int zone = g_gov_zone;
    if (zone == 2) {
        if (W < GOV_RED_EXIT_CPU_S)
            zone = (Wpred >= GOV_SOFT_CPU_S) ? 1 : 0;
    } else {
        if (Wpred >= GOV_HARD_CPU_S)      zone = 2;
        else if (Wpred >= GOV_SOFT_CPU_S) zone = 1;
        else if (zone == 1 && Wpred >= GOV_SOFT_CPU_S - 1.0) zone = 1;
        else                              zone = 0;
    }

    double duty;
    switch (zone) {
        case 2:  duty = GOV_RED_DUTY; break;
        case 1:  duty = 1.0 - ((Wpred - GOV_SOFT_CPU_S) / (GOV_HARD_CPU_S - GOV_SOFT_CPU_S))
                             * (1.0 - GOV_YELLOW_MIN_DUTY);
                 if (duty < GOV_YELLOW_MIN_DUTY) duty = GOV_YELLOW_MIN_DUTY;
                 break;
        default: duty = 1.0; break;
    }

    // Transition guard: for the first 2s after backgrounding cap duty at 0.5
    // in case some iOS version opens the budget window earlier than the
    // transition we observe.
    if (now - g_gov_begin_ts < GOV_STARTUP_CAP_NS && duty > 0.5) duty = 0.5;

    int ratio_q16 = 0;
    if (duty < 1.0) {
        float ratio = (float)((1.0 - duty) / duty);
        ratio_q16 = (int)(ratio * 65536.0f);
        if (ratio_q16 <= 0) ratio_q16 = 1;
    }
    atomic_store_explicit(&g_throttle_ratio_q16, ratio_q16, memory_order_release);

    // While braking, poke all running guests every tick — without this the
    // ratio is write-only for CPU-bound (chained) guest code.
    if (ratio_q16 > 0)
        gov_poke_all_tasks();

    double t = (double)(now - g_gov_begin_ts) / 1e9;
    if (zone != g_gov_zone) {
        NSLog(@"ISHKernel: [Governor] zone %s→%s t=+%.1fs W=%.1fs R=%.2f pred=%.1fs duty=%.0f%%",
              gov_zone_name(g_gov_zone), gov_zone_name(zone), t, W, R, Wpred, duty * 100);
        g_gov_zone = zone;
        g_gov_last_log_ts = now;
    } else if (now - g_gov_last_log_ts >= GOV_LOG_INTERVAL_NS) {
        NSLog(@"ISHKernel: [Governor] t=+%.1fs W=%.1fs R=%.2f pred=%.1fs zone=%s duty=%.0f%%",
              t, W, R, Wpred, gov_zone_name(zone), duty * 100);
        g_gov_last_log_ts = now;
    }
}

- (void)beginBackgroundCPUGovernor {
    if (g_gov_timer) return;  // idempotent
    if (!g_gov_queue)
        g_gov_queue = dispatch_queue_create("com.openminis.ish.cpugovernor",
            dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0));

    g_gov_head = 0;
    g_gov_count = 0;
    g_gov_zone = 0;
    g_gov_begin_ts = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
    g_gov_last_log_ts = g_gov_begin_ts;

    // Fresh throttle stats so the cold-start seed reflects THIS background
    // stint, and install the hook (ratio stays 0 until the first gov_tick
    // decides — GREEN start means full speed, per design).
    atomic_store_explicit(&g_throttle_tick_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_sleep_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_sleep_ns_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_elapsed_ns_total, 0, memory_order_relaxed);
    atomic_store_explicit(&g_throttle_last_log_ts, 0, memory_order_relaxed);
    ish_set_timer_tick_hook(ish_throttle_trampoline);

    dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, g_gov_queue);
    dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, 0),
                              GOV_CADENCE_NS, 50ULL * 1000000ULL /* 50ms leeway */);
    dispatch_source_set_event_handler(timer, ^{ gov_tick(); });
    // Serialize the final ratio reset onto the governor queue so a
    // concurrent in-flight gov_tick cannot re-arm the throttle after end.
    dispatch_source_set_cancel_handler(timer, ^{
        atomic_store_explicit(&g_throttle_ratio_q16, 0, memory_order_release);
    });
    g_gov_timer = timer;
    dispatch_resume(timer);
    NSLog(@"ISHKernel: [Governor] BEGIN budget=48s/60s ceiling=%.0fs soft=%.0fs redExit=%.0fs cadence=%llums",
          GOV_HARD_CPU_S, GOV_SOFT_CPU_S, GOV_RED_EXIT_CPU_S, GOV_CADENCE_NS / 1000000ULL);
}

- (void)endBackgroundCPUGovernor {
    if (!g_gov_timer) return;
    uint64_t sleeps = atomic_load_explicit(&g_throttle_sleep_total, memory_order_relaxed);
    uint64_t sleep_ns = atomic_load_explicit(&g_throttle_sleep_ns_total, memory_order_relaxed);
    double t = (double)(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) - g_gov_begin_ts) / 1e9;
    dispatch_source_cancel(g_gov_timer);
    g_gov_timer = nil;
    NSLog(@"ISHKernel: [Governor] END after %.1fs — lastZone=%s sleeps=%llu totalSleep=%.1fs",
          t, gov_zone_name(g_gov_zone), sleeps, (double)sleep_ns / 1e9);
}

@end

@implementation ISHKernel (PathReverse)

- (void)installPathReverseHandler:(ISHPathReverseHandler)handler {
    if (handler == nil) {
        fakefs_set_path_reverse_hook(NULL);
        atomic_store_explicit(&g_path_reverse_block, NULL, memory_order_release);
        return;
    }
    ISHPathReverseHandler copied = [handler copy];
    void *raw = (void *)CFBridgingRetain(copied);  /* retained forever */
    atomic_store_explicit(&g_path_reverse_block, raw, memory_order_release);
    fakefs_set_path_reverse_hook(ish_path_reverse_trampoline);
}

@end

@implementation ISHKernel (PathTranslate)

- (void)installPathTranslateHandler:(ISHPathTranslateHandler)handler {
    /* Lifetime model: every installed block is retained forever. The
     * trampoline runs on iSH worker threads with no available lock or
     * RCU-like primitive — releasing a replaced block while another
     * thread is mid-call is racy. In practice installPathTranslateHandler:
     * is expected to be called at most a handful of times (typically once
     * at boot), so the leak is bounded and trivial. The C-level hook
     * function pointer can still be cleared (handler == nil) to disable
     * dispatch without touching block memory.
     *
     * Sequence:
     *   - detach (handler == nil): clear hook fn ptr FIRST so no new
     *     trampoline call dispatches, then swap block pointer to NULL.
     *     The previously-installed block stays retained (leaked).
     *   - attach: store new block pointer FIRST, then install the
     *     trampoline. A concurrent trampoline that loaded NULL before
     *     the swap simply returns false and falls through. */
    if (handler == nil) {
        fakefs_set_path_translate_hook(NULL);
        atomic_store_explicit(&g_path_translate_block, NULL, memory_order_release);
        return;
    }
    ISHPathTranslateHandler copied = [handler copy];
    void *raw = (void *)CFBridgingRetain(copied);  /* retained forever */
    atomic_store_explicit(&g_path_translate_block, raw, memory_order_release);
    fakefs_set_path_translate_hook(ish_path_translate_trampoline);
}

@end
