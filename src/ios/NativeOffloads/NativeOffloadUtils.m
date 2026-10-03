//
//  NativeOffloadUtils.m
//  MinisApp
//
//  Shared utilities for native offload CLI tools.
//

#import "NativeOffloadUtils.h"
#include "kernel/fs.h"
#include "fs/fd.h"
#include "fs/path.h"
#include <unistd.h>
#include <sys/select.h>

// ── Error code constants ──
NSString *const NOFF_ERR_AUTHORIZATION_DENIED       = @"authorization_denied";
NSString *const NOFF_ERR_AUTHORIZATION_NOT_DETERMINED = @"authorization_not_determined";
NSString *const NOFF_ERR_NOT_AVAILABLE              = @"not_available";
NSString *const NOFF_ERR_INVALID_ARGS               = @"invalid_args";
NSString *const NOFF_ERR_NO_DATA                    = @"no_data";
NSString *const NOFF_ERR_INTERNAL_ERROR             = @"internal_error";

// ── Argument helpers ──

NSString *_Nullable noff_find_arg(int argc, char **argv, const char *name) {
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], name) == 0) {
            return [NSString stringWithUTF8String:argv[i + 1]];
        }
    }
    return nil;
}

BOOL noff_has_flag(int argc, char **argv, const char *name) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], name) == 0) return YES;
    }
    return NO;
}

NSString *_Nullable noff_get_subcommand(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (argv[i][0] != '-') {
            return [NSString stringWithUTF8String:argv[i]];
        }
        // Skip argument value for known option patterns (--key value)
        if (argv[i][0] == '-' && argv[i][1] == '-' && i + 1 < argc && argv[i + 1][0] != '-') {
            i++; // skip value
        }
    }
    return nil;
}

NSArray<NSString *> *noff_positional_args(int argc, char **argv) {
    NSMutableArray *args = [NSMutableArray array];
    BOOL foundSubcommand = NO;
    for (int i = 1; i < argc; i++) {
        if (argv[i][0] == '-') {
            // Skip option and its value
            if (argv[i][1] == '-' && i + 1 < argc && argv[i + 1][0] != '-') {
                i++;
            }
            continue;
        }
        if (!foundSubcommand) {
            foundSubcommand = YES;
            continue; // skip the subcommand itself
        }
        [args addObject:[NSString stringWithUTF8String:argv[i]]];
    }
    return args;
}

// ── Date parsing ──

NSDate *_Nullable noff_parse_date(NSString *str) {
    if (!str || str.length == 0) return nil;

    // Relative: -7d, -2h, -30m
    if ([str hasPrefix:@"-"] && str.length >= 2) {
        unichar unit = [str characterAtIndex:str.length - 1];
        NSString *numStr = [str substringWithRange:NSMakeRange(1, str.length - 2)];
        NSInteger num = [numStr integerValue];
        if (num > 0) {
            NSTimeInterval interval = 0;
            switch (unit) {
                case 'd': interval = num * 86400; break;
                case 'h': interval = num * 3600; break;
                case 'm': interval = num * 60; break;
                default: break;
            }
            if (interval > 0) {
                return [NSDate dateWithTimeIntervalSinceNow:-interval];
            }
        }
    }

    // ISO 8601 variants
    NSISO8601DateFormatter *iso = [[NSISO8601DateFormatter alloc] init];
    iso.formatOptions = NSISO8601DateFormatWithInternetDateTime
                      | NSISO8601DateFormatWithFractionalSeconds;
    NSDate *d = [iso dateFromString:str];
    if (d) return d;

    // Without fractional seconds
    iso.formatOptions = NSISO8601DateFormatWithInternetDateTime;
    d = [iso dateFromString:str];
    if (d) return d;

    // Date-only or datetime without timezone
    NSDateFormatter *fmt = [[NSDateFormatter alloc] init];
    fmt.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];
    fmt.timeZone = [NSTimeZone localTimeZone];
    for (NSString *pattern in @[@"yyyy-MM-dd'T'HH:mm:ss",
                                 @"yyyy-MM-dd'T'HH:mm",
                                 @"yyyy-MM-dd"]) {
        fmt.dateFormat = pattern;
        d = [fmt dateFromString:str];
        if (d) return d;
    }

    return nil;
}

NSString *noff_format_date(NSDate *date) {
    if (!date) return @"";
    NSISO8601DateFormatter *fmt = [[NSISO8601DateFormatter alloc] init];
    fmt.formatOptions = NSISO8601DateFormatWithInternetDateTime;
    fmt.timeZone = [NSTimeZone localTimeZone];
    return [fmt stringFromDate:date];
}

// ── All-day event helpers ──  [T-calendar-all-day]

BOOL noff_is_date_only_string(NSString *_Nullable str) {
    if (str.length != 10) return NO;
    // Positional check rather than a formatter: a formatter is lenient about
    // trailing text and would also accept "2026-12-01" as the prefix of a
    // datetime, which is exactly the case this must reject.
    for (NSUInteger i = 0; i < 10; i++) {
        unichar c = [str characterAtIndex:i];
        if (i == 4 || i == 7) {
            if (c != '-') return NO;
        } else if (c < '0' || c > '9') {
            return NO;
        }
    }
    return noff_parse_date(str) != nil;
}

void noff_all_day_bounds(NSDate *start, NSDate *end,
                         NSDate *_Nonnull *_Nonnull outStart,
                         NSDate *_Nonnull *_Nonnull outEnd) {
    NSCalendar *cal = [NSCalendar currentCalendar];
    NSDate *startDay = [cal startOfDayForDate:start];
    NSDate *endDay = [cal startOfDayForDate:end ?: start];
    if ([endDay compare:startDay] == NSOrderedAscending) endDay = startDay;
    // 23:59:59 of the last day = (next midnight) - 1s, via calendar units so a
    // 23h/25h DST day still lands on its own last second.
    NSDate *nextMidnight = [cal dateByAddingUnit:NSCalendarUnitDay value:1 toDate:endDay options:0];
    NSDate *endOfDay = [nextMidnight dateByAddingTimeInterval:-1];
    *outStart = startDay;
    *outEnd = endOfDay ?: endDay;
}

// ── JSON output ──

NSDictionary *noff_json_envelope(NSString *tool, NSString *action, id data) {
    return @{
        @"ok": @YES,
        @"tool": tool,
        @"action": action,
        @"data": data ?: [NSNull null],
        @"timestamp": noff_format_date([NSDate date]),
    };
}

NSDictionary *noff_json_error(NSString *tool, NSString *action,
                               NSString *code, NSString *message) {
    return @{
        @"ok": @NO,
        @"tool": tool,
        @"action": action,
        @"error": @{
            @"code": code,
            @"message": message,
        },
        @"timestamp": noff_format_date([NSDate date]),
    };
}

void noff_emit_json(int fd, NSDictionary *dict, BOOL compact, BOOL quiet) {
    id output = dict;
    if (quiet) {
        // In quiet mode, emit only the "data" field for success, or "error" for failure
        if ([dict[@"ok"] boolValue]) {
            output = dict[@"data"];
        } else {
            output = dict[@"error"];
        }
    }

    if (!output || output == [NSNull null]) {
        dprintf(fd, "{}\n");
        return;
    }

    NSJSONWritingOptions opts = 0;
    if (!compact) {
        opts = NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys;
    }

    NSError *err = nil;
    NSData *json = nil;
    @try {
        json = [NSJSONSerialization dataWithJSONObject:output options:opts error:&err];
    } @catch (NSException *e) {
        NSLog(@"NativeOffloads: JSON serialization exception: %@", e.reason);
    }
    if (!json) {
        dprintf(fd, "{\"error\":\"json_serialization_failed\"}\n");
        return;
    }

    NSString *str = [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding];
    dprintf(fd, "%s\n", str.UTF8String);
}

// ── Help output ──

void noff_emit_help(int stderr_fd, NSString *helpText) {
    dprintf(stderr_fd, "%s\n", helpText.UTF8String);
}

// ── Main thread dispatch ──

id _Nullable noff_dispatch_main_sync(id _Nullable (^_Nonnull block)(void)) {
    if ([NSThread isMainThread]) {
        return block();
    }
    __block id result = nil;
    dispatch_sync(dispatch_get_main_queue(), ^{
        result = block();
    });
    return result;
}

id _Nullable noff_dispatch_main_sync_timeout(NSTimeInterval timeoutSeconds,
                                              BOOL *_Nullable timedOut,
                                              id _Nullable (^_Nonnull block)(void)) {
    if (timedOut) *timedOut = NO;
    // Already on main: a bounded wait is impossible (we'd deadlock waiting on
    // ourselves) and also unnecessary — run inline, matching the unbounded variant.
    if ([NSThread isMainThread]) {
        return block();
    }

    // The result box is heap-allocated and captured by BOTH the async block and
    // this frame, so a late-running block after a timeout writes into a still-live
    // object rather than a dead stack slot.
    NSMutableArray *box = [NSMutableArray arrayWithCapacity:1];
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        id r = block();
        @synchronized (box) { if (r) [box addObject:r]; }
        dispatch_semaphore_signal(sem);
    });

    dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW,
                                             (int64_t)(timeoutSeconds * NSEC_PER_SEC));
    if (dispatch_semaphore_wait(sem, deadline) != 0) {
        if (timedOut) *timedOut = YES;
        return nil;
    }
    @synchronized (box) { return box.firstObject; }
}

// ── Read stdin ──

// ── Guest stub creation ──

void noff_ensure_guest_stub(const char *guest_path) {
    // Ensure parent directories exist (e.g. /usr/local/bin)
    char parent[256];
    strncpy(parent, guest_path, sizeof(parent) - 1);
    parent[sizeof(parent) - 1] = '\0';

    // Walk through the path and mkdir each component
    for (char *p = parent + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            generic_mkdirat(AT_PWD, parent, 0755);
            *p = '/';
        }
    }

    // Create the stub file with execute permission.
    // O_CREAT without O_EXCL: if it already exists, just opens it.
    struct fd *fd = generic_open(guest_path, O_CREAT_ | O_WRONLY_, 0755);
    if (fd && !IS_ERR(fd)) {
        fd_close(fd);
    }
}

// ── Path resolution ──

NSString *_Nullable noff_resolve_host_path(NSString *guestPath) {
    if (guestPath.length == 0) return nil;

    // Idempotence guard [T-offload-double-path-translate]: exec_handler
    // (native_offload.c) translates every absolute-path argv entry
    // guest→host (bind mounts first, then fakefs data root) BEFORE the
    // in-process handler runs, so `--image <path>`-style arguments arrive
    // here already host-side. Re-mapping such a path nests it under the
    // data root a second time (…/data/private/var/…/data/…) and every
    // read/write on it fails with ENOENT. Any path already inside the app
    // sandbox or the shared app-group container is host-side — return it
    // unchanged. Genuine guest paths (constructed strings, stdin-derived
    // paths that never went through argv translation) still fall through
    // to the mapping below.
    NSString *home = NSHomeDirectory();
    NSString *privateHome = [@"/private" stringByAppendingString:home];
    if ([guestPath hasPrefix:home] || [guestPath hasPrefix:privateHome] ||
        [guestPath hasPrefix:@"/var/mobile/Containers/Shared/"] ||
        [guestPath hasPrefix:@"/private/var/mobile/Containers/Shared/"]) {
        return guestPath;
    }

    // Documents/alpine-rootfs/data/ is the fakefs data root
    NSString *documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *dataRoot = [documents stringByAppendingPathComponent:@"alpine-rootfs/data"];

    // Strip leading "/" from guest path and append to host data root
    NSString *relative = guestPath;
    while ([relative hasPrefix:@"/"]) {
        relative = [relative substringFromIndex:1];
    }
    return [dataRoot stringByAppendingPathComponent:relative];
}

// ── Read stdin ──

NSString *_Nullable noff_read_stdin(int stdin_fd) {
    if (stdin_fd < 0) return nil;

    NSMutableData *data = [NSMutableData data];
    char buf[4096];
    ssize_t n;

    // Use select with a short timeout to check if data is available
    fd_set fds;
    struct timeval tv = { .tv_sec = 0, .tv_usec = 100000 }; // 100ms
    FD_ZERO(&fds);
    FD_SET(stdin_fd, &fds);

    while (select(stdin_fd + 1, &fds, NULL, NULL, &tv) > 0) {
        n = read(stdin_fd, buf, sizeof(buf));
        if (n <= 0) break;
        [data appendBytes:buf length:n];
        if (data.length > 1024 * 1024) break; // 1MB cap

        FD_ZERO(&fds);
        FD_SET(stdin_fd, &fds);
        tv.tv_sec = 0;
        tv.tv_usec = 50000; // 50ms for subsequent reads
    }

    if (data.length == 0) return nil;
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}

// ── ObjC exception safety ──

BOOL noff_try_objc(void (NS_NOESCAPE ^block)(void)) {
    @try {
        block();
        return YES;
    } @catch (NSException *e) {
        NSLog(@"noff_try_objc: caught ObjC exception: %@ — %@", e.name, e.reason);
        return NO;
    }
}
