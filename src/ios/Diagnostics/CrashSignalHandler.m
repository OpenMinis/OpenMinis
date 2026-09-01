#import "CrashSignalHandler.h"

#import <dlfcn.h>
#import <execinfo.h>
#import <fcntl.h>
#import <signal.h>
#import <string.h>
#import <unistd.h>


#pragma mark - Async-signal-safe helpers

static char g_crashStackPath[1024] = {0};
static volatile sig_atomic_t g_handlingSignal = 0;
static volatile sig_atomic_t g_handlingException = 0;


// Async-signal-safe integer-to-string (decimal). Returns pointer into buf.
static char *itoa_safe(unsigned long val, char *buf, size_t bufLen) {
    char *p = buf + bufLen - 1;
    *p = '\0';
    if (val == 0) { *--p = '0'; return p; }
    while (val > 0 && p > buf) {
        *--p = '0' + (val % 10);
        val /= 10;
    }
    return p;
}

// Async-signal-safe hex-to-string. Returns pointer into buf.
static char *htoa_safe(uintptr_t val, char *buf, size_t bufLen) {
    static const char hex[] = "0123456789abcdef";
    char *p = buf + bufLen - 1;
    *p = '\0';
    if (val == 0) { *--p = '0'; return p; }
    while (val > 0 && p > buf) {
        *--p = hex[val & 0xf];
        val >>= 4;
    }
    *--p = 'x';
    *--p = '0';
    return p;
}

static void write_str(int fd, const char *s) {
    if (s) write(fd, s, strlen(s));
}

#pragma mark - Stack capture + write (async-signal-safe)

static void capture_and_write_stack(int sig) {
    if (g_crashStackPath[0] == '\0') return;

    // O_EXCL: if the exception handler already wrote this file (NSException
    // → abort → SIGABRT), keep the richer exception report instead of
    // overwriting it with just a signal stack.
    int fd = open(g_crashStackPath, O_WRONLY | O_CREAT | O_EXCL, 0644);
    if (fd < 0) {
        // File exists (from exception handler) — append signal info instead.
        fd = open(g_crashStackPath, O_WRONLY | O_APPEND);
    }
    if (fd < 0) return;

    // Signal name
    const char *sigName = "UNKNOWN";
    switch (sig) {
        case SIGABRT: sigName = "SIGABRT"; break;
        case SIGSEGV: sigName = "SIGSEGV"; break;
        case SIGBUS:  sigName = "SIGBUS"; break;
        case SIGTRAP: sigName = "SIGTRAP"; break;
        case SIGFPE:  sigName = "SIGFPE"; break;
        case SIGILL:  sigName = "SIGILL"; break;
    }
    write_str(fd, "Signal: ");
    write_str(fd, sigName);
    write_str(fd, "\n");

    // backtrace() is NOT strictly async-signal-safe per POSIX, but it is
    // safe on Darwin (Apple's implementation uses frame-pointer walking,
    // no malloc). All major iOS crash reporters (PLCrashReporter, KSCrash)
    // use it in signal handlers.
    void *frames[64];
    int count = backtrace(frames, 64);

    char numBuf[24];
    for (int i = 0; i < count; i++) {
        // Frame index
        char *idx = itoa_safe(i, numBuf, sizeof(numBuf));
        write_str(fd, "  #");
        write_str(fd, idx);
        write_str(fd, " ");

        // dladdr for image name + offset (safe in signal handler on Darwin)
        Dl_info info;
        if (dladdr(frames[i], &info) != 0) {
            const char *img = "?";
            if (info.dli_fname) {
                const char *slash = strrchr(info.dli_fname, '/');
                img = slash ? slash + 1 : info.dli_fname;
            }
            write_str(fd, img);
            write_str(fd, " +");
            if (info.dli_fbase) {
                uintptr_t off = (uintptr_t)frames[i] - (uintptr_t)info.dli_fbase;
                char *hex = htoa_safe(off, numBuf, sizeof(numBuf));
                write_str(fd, hex);
            } else {
                write_str(fd, "?");
            }
            if (info.dli_sname) {
                write_str(fd, " ");
                write_str(fd, info.dli_sname);
            }
        } else {
            char *hex = htoa_safe((uintptr_t)frames[i], numBuf, sizeof(numBuf));
            write_str(fd, hex);
        }
        write_str(fd, "\n");
    }

    close(fd);
}

#pragma mark - Signal handler

static void crash_signal_handler(int sig, siginfo_t *info, void *ucontext) {
    // Re-entrancy guard — only prevents recursive signal-in-signal.
    // If this fires a second time (e.g. crash inside our handler), fall
    // through to SIG_DFL so ReportCrash still captures a Mach exception.
    if (__sync_val_compare_and_swap(&g_handlingSignal, 0, 1) != 0) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    capture_and_write_stack(sig);

    // Always restore default handler and re-raise. This ensures:
    // 1. ReportCrash captures the Mach exception → TestFlight gets the crash
    // 2. MetricKit MXCrashDiagnostic fires on next launch
    // 3. No risk of infinite loop from chaining to an unknown prev handler
    signal(sig, SIG_DFL);
    raise(sig);
}

#pragma mark - NSException handler

static NSUncaughtExceptionHandler *g_prevExceptionHandler = nil;

static void uncaught_exception_handler(NSException *exception) {
    if (__sync_val_compare_and_swap(&g_handlingException, 0, 1) != 0) return;

    // [T-ios-mac-uncaught-nsexception] Emit the name/reason through NSLog FIRST,
    // before touching the filesystem.
    //
    // Two reasons this is not redundant with the crash file below. (1) NSLog is
    // what LoggingManager captures into the daily log, so the reason survives in
    // a log the user can export immediately — the crash file is only surfaced on
    // the NEXT launch, and only if one happens. (2) On macOS the App Store crash
    // report strips `Application Specific Information`, which is exactly where
    // the NSException reason would otherwise appear: the report for OpenMinis'
    // Mac crashes showed only `_crashOnException:` with no reason at all. This
    // line is the one that makes the next occurrence diagnosable.
    //
    // userInfo is included because NSInvalidArgumentException from KVC carries
    // the offending key there, which usually names the culprit outright.
    NSLog(@"🛑 [UncaughtException] %@ — %@ | userInfo=%@ | callStack=%@",
          exception.name,
          exception.reason ?: @"(no reason)",
          exception.userInfo ?: @{},
          exception.callStackSymbols ?: @[]);

    if (g_crashStackPath[0] != '\0') {
        int fd = open(g_crashStackPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            // Exception info (using ObjC here is acceptable — we're not in
            // a signal handler, just an exception handler on the throwing thread)
            NSString *header = [NSString stringWithFormat:@"NSException: %@ — %@\n",
                                exception.name, exception.reason ?: @"(no reason)"];
            write_str(fd, header.UTF8String);

            NSArray<NSNumber *> *addrs = exception.callStackReturnAddresses;
            char numBuf[24];
            for (NSUInteger i = 0; i < addrs.count && i < 64; i++) {
                uintptr_t addr = addrs[i].unsignedLongValue;
                char *idx = itoa_safe(i, numBuf, sizeof(numBuf));
                write_str(fd, "  #");
                write_str(fd, idx);
                write_str(fd, " ");

                Dl_info info;
                if (dladdr((const void *)addr, &info) != 0) {
                    const char *img = "?";
                    if (info.dli_fname) {
                        const char *slash = strrchr(info.dli_fname, '/');
                        img = slash ? slash + 1 : info.dli_fname;
                    }
                    write_str(fd, img);
                    write_str(fd, " +");
                    if (info.dli_fbase) {
                        uintptr_t off = addr - (uintptr_t)info.dli_fbase;
                        char *hex = htoa_safe(off, numBuf, sizeof(numBuf));
                        write_str(fd, hex);
                    }
                    if (info.dli_sname) {
                        write_str(fd, " ");
                        write_str(fd, info.dli_sname);
                    }
                }
                write_str(fd, "\n");
            }
            close(fd);
        }
    }

    if (g_prevExceptionHandler) {
        g_prevExceptionHandler(exception);
    }
}

#pragma mark - Install

@implementation CrashSignalHandler

+ (void)install {
    // [T-ios-mac-uncaught-nsexception] Idempotent. `install` is now called from
    // MinisApp.init() as well as CrashReporter.onAppLaunch(), and re-installing
    // would set g_prevExceptionHandler to OUR OWN handler — every uncaught
    // exception would then recurse into itself instead of reaching Apple's
    // reporter. The guard is deliberately not a dispatch_once: a failed first
    // attempt (no Application Support directory) should be retryable.
    static BOOL installed = NO;
    if (installed) return;

    // Compute path once
    NSArray<NSString *> *dirs = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory, NSUserDomainMask, YES);
    if (dirs.count == 0) return;
    installed = YES;
    NSString *path = [dirs[0] stringByAppendingPathComponent:@"last_crash_stack.txt"];
    strlcpy(g_crashStackPath, path.fileSystemRepresentation, sizeof(g_crashStackPath));

    // Install signal handlers. We don't save previous handlers because
    // our handler always restores SIG_DFL + re-raises, which lets
    // ReportCrash (Mach exception port) capture the crash normally.
    int signals[] = { SIGABRT, SIGSEGV, SIGBUS, SIGTRAP, SIGFPE, SIGILL };
    for (int i = 0; i < 6; i++) {
        struct sigaction action = {0};
        action.sa_sigaction = crash_signal_handler;
        action.sa_flags = SA_SIGINFO;
        sigemptyset(&action.sa_mask);
        sigaction(signals[i], &action, NULL);
    }

    // Install NSException handler
    g_prevExceptionHandler = NSGetUncaughtExceptionHandler();
    NSSetUncaughtExceptionHandler(uncaught_exception_handler);
}

+ (NSString *)crashStackPath {
    if (g_crashStackPath[0] == '\0') return @"";
    return [NSString stringWithUTF8String:g_crashStackPath];
}

@end
