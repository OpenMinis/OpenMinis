//
//  AlarmOffload.m
//  MinisApp
//
//  Native offload handler for `apple-alarm`.
//  Subcommands: set, timer, list, cancel
//
//  Requires iOS 26+ (AlarmKit). Calls AlarmOffloadBridge.swift for actual
//  AlarmKit API interactions (AlarmKit is Swift-only).
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import "NativeOffloadUtils.h"
#include "kernel/native_offload.h"
#include <unistd.h>
#include <dlfcn.h>

// Swift bridge — generated header
#if __has_include("Minis-Swift.h")
#import "Minis-Swift.h"
#elif __has_include("MinisApp-Swift.h")
#import "MinisApp-Swift.h"
#endif

static NSString *const TOOL_NAME = @"apple-alarm";

static NSString *const HELP_TEXT =
    @"apple-alarm - Set alarms and timers on iOS (AlarmKit, iOS 26+)\n"
     "\n"
     "USAGE:\n"
     "  apple-alarm <command> [options]\n"
     "\n"
     "COMMANDS:\n"
     "  set         Set an alarm for a specific time\n"
     "  timer       Set a countdown timer\n"
     "  list        List pending alarms and timers\n"
     "  cancel      Cancel a pending alarm or timer\n"
     "\n"
     "COMMON OPTIONS:\n"
     "  --help, -h           Show this help message\n"
     "  --compact            Minimize JSON output\n"
     "  -q, --quiet          Output only data field\n"
     "\n"
     "SET OPTIONS:\n"
     "  --time <HH:MM|ISO>   Alarm time (required)\n"
     "  --label <text>       Alarm label\n"
     "  --repeat <mode>      Repeat: none, daily, weekdays (default: none)\n"
     "\n"
     "TIMER OPTIONS:\n"
     "  --duration <value>   Duration in seconds or shorthand (e.g. 5m, 1h) (required)\n"
     "  --label <text>       Timer label\n"
     "\n"
     "CANCEL OPTIONS:\n"
     "  --id <identifier>    Alarm/timer ID to cancel (required unless --all)\n"
     "  --all                Cancel all pending alarms and timers\n"
     "\n"
     "EXAMPLES:\n"
     "  apple-alarm set --time 07:30 --label \"Wake up\" --repeat daily\n"
     "  apple-alarm set --time 2026-02-25T14:00\n"
     "  apple-alarm timer --duration 5m --label \"Tea\"\n"
     "  apple-alarm timer --duration 300\n"
     "  apple-alarm list\n"
     "  apple-alarm cancel --id <alarm-id>\n"
     "  apple-alarm cancel --all\n"
     "\n"
     "DEEP LINK:\n"
     "  minis://views/alarm              Open the alarm management page in Minis\n";

// ── Duration parsing ──

/// Parse duration string: plain seconds, or shorthand like "5m", "1h", "1h30m"
static NSTimeInterval parseDuration(NSString *str) {
    if (!str) return 0;

    // Try plain number (seconds)
    NSScanner *scanner = [NSScanner scannerWithString:str];
    double val = 0;
    if ([scanner scanDouble:&val] && scanner.isAtEnd) {
        return val;
    }

    // Parse shorthand: e.g. "1h30m", "5m", "2h"
    NSTimeInterval total = 0;
    NSRegularExpression *regex =
        [NSRegularExpression regularExpressionWithPattern:@"(\\d+)([hms])"
                                                 options:0 error:nil];
    NSArray<NSTextCheckingResult *> *matches =
        [regex matchesInString:str options:0 range:NSMakeRange(0, str.length)];

    for (NSTextCheckingResult *match in matches) {
        NSInteger num = [[str substringWithRange:[match rangeAtIndex:1]] integerValue];
        NSString *unit = [str substringWithRange:[match rangeAtIndex:2]];
        if ([unit isEqualToString:@"h"]) total += num * 3600;
        else if ([unit isEqualToString:@"m"]) total += num * 60;
        else if ([unit isEqualToString:@"s"]) total += num;
    }
    return total > 0 ? total : 0;
}

// ── Time parsing for --time argument ──

/// Parse HH:MM format into today's date at that time, or fall through to ISO parse.
static NSDate *parseAlarmTime(NSString *str) {
    if (!str) return nil;

    // Try HH:MM format
    NSRegularExpression *hmRegex =
        [NSRegularExpression regularExpressionWithPattern:@"^(\\d{1,2}):(\\d{2})$"
                                                 options:0 error:nil];
    NSTextCheckingResult *match =
        [hmRegex firstMatchInString:str options:0 range:NSMakeRange(0, str.length)];
    if (match) {
        NSInteger hour = [[str substringWithRange:[match rangeAtIndex:1]] integerValue];
        NSInteger minute = [[str substringWithRange:[match rangeAtIndex:2]] integerValue];

        NSCalendar *cal = [NSCalendar currentCalendar];
        NSDateComponents *comps = [cal components:(NSCalendarUnitYear | NSCalendarUnitMonth |
                                                    NSCalendarUnitDay)
                                         fromDate:[NSDate date]];
        comps.hour = hour;
        comps.minute = minute;
        comps.second = 0;
        NSDate *date = [cal dateFromComponents:comps];

        // If the time is in the past, schedule for tomorrow
        if ([date compare:[NSDate date]] != NSOrderedDescending) {
            date = [cal dateByAddingUnit:NSCalendarUnitDay value:1 toDate:date options:0];
        }
        return date;
    }

    // Fall through to ISO parsing
    return noff_parse_date(str);
}

// ── Ensure authorization ──

// Serial queue to prevent concurrent authorization dialogs
static dispatch_queue_t authQueue(void) {
    static dispatch_queue_t q = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        q = dispatch_queue_create("com.openminis.alarm.auth", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

static BOOL ensureAuthorization(int stdout_fd, NSString *action, BOOL compact, BOOL quiet) {
    __block BOOL authorized = NO;
    __block NSError *authError = nil;

    dispatch_sync(authQueue(), ^{
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);

        [AlarmOffloadBridge requestAuthorizationWithCompletion:^(BOOL granted, NSError *error) {
            authorized = granted;
            authError = error;
            dispatch_semaphore_signal(sem);
        }];

        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));
    });

    if (!authorized) {
        NSString *msg = authError
            ? [NSString stringWithFormat:@"AlarmKit authorization failed: %@. "
                "To grant access, open Settings > Privacy & Security > Alarms and enable Minis.",
                authError.localizedDescription]
            : @"AlarmKit authorization denied. "
               "To grant access, open Settings > Privacy & Security > Alarms and enable Minis.";
        NSDictionary *err = noff_json_error(TOOL_NAME, action,
                                             NOFF_ERR_AUTHORIZATION_DENIED, msg);
        noff_emit_json(stdout_fd, err, compact, quiet);
    }
    return authorized;
}

// ── AlarmKit availability ──
//
// `@available(iOS 26.0, *)` is necessary but NOT sufficient. When the iPad
// build runs in compatibility mode on visionOS ("Designed for iPad"), the
// version check passes — visionOS reports a mapped iOS version >= 26 — but
// AlarmKit does not exist on that platform at all. Entering any Swift code
// whose signature mentions an AlarmKit type then traps in the runtime while
// demangling the generic metadata (swift_getTypeByMangledName ->
// swift::fatalError), which aborts the process rather than throwing.
//
// The probe is a class lookup rather than a dlopen of the binary path: no
// hardcoded filesystem path, no leaked handle, and it asks the loaded-image
// tables the same question the Swift runtime would.
//
// `AlarmManager` itself cannot be the probe — AlarmKit is almost entirely
// pure Swift (its .swiftinterface contains exactly one @objc, a synthesized
// deinit), so AlarmManager has no ObjC class to look up. But the framework
// does export a small number of ObjC-visible classes, and AKAlarmAuthorization
// is one of them; per the SDK .tbd it is exported by AlarmKit and by no other
// framework, so finding it means AlarmKit is loadable and nothing else does.
//
// Because the class is an implementation detail rather than API, treat a
// missing lookup as "cannot use AlarmKit" only in the safe direction: if a
// future OS renames it we fall back to the existing NOT_AVAILABLE path, i.e.
// alarms report unsupported instead of aborting the process. That is a
// degradation, never a crash — the failure this guard exists to prevent.
//
// Seen as a crash on xrOS 27 in 1.14(11) via AlarmOffloadBridge.listAlarms.
BOOL alarmkit_is_usable(void) {
    static BOOL usable = NO;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        usable = NSClassFromString(@"AKAlarmAuthorization") != nil;
    });
    return usable;
}

// ── AlarmKit commands (iOS 26+) ──

#if __IPHONE_OS_VERSION_MAX_ALLOWED >= 260000

static int cmd_set_alarmkit(int argc, char **argv, int stdout_fd,
                            int stderr_fd, BOOL compact, BOOL quiet) {
    if (@available(iOS 26.0, *)) {
        NSString *timeStr = noff_find_arg(argc, argv, "--time");
        if (!timeStr) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            NSDictionary *err = noff_json_error(TOOL_NAME, @"set",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 @"Required: --time <HH:MM|ISO>");
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }

        NSDate *fireDate = parseAlarmTime(timeStr);
        if (!fireDate) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            NSDictionary *err = noff_json_error(TOOL_NAME, @"set",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 @"Invalid time format. Use HH:MM or ISO 8601.");
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }

        if (!ensureAuthorization(stdout_fd, @"set", compact, quiet)) {
            return NOFF_EXIT_AUTH_DENIED;
        }

        NSString *label = noff_find_arg(argc, argv, "--label") ?: @"Alarm";
        NSString *repeatMode = noff_find_arg(argc, argv, "--repeat") ?: @"none";
        NSString *alarmId = [[NSUUID UUID] UUIDString];

        __block NSDictionary *resultData = nil;
        __block NSError *schedError = nil;
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);

        [AlarmOffloadBridge scheduleAlarmWithId:alarmId
                                      fireDate:fireDate
                                         label:label
                                    repeatMode:repeatMode
                                    completion:^(NSDictionary *data, NSError *error) {
            resultData = data;
            schedError = error;
            dispatch_semaphore_signal(sem);
        }];

        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC));

        if (schedError || !resultData) {
            NSString *msg = schedError
                ? [NSString stringWithFormat:@"Failed to schedule alarm: %@", schedError.localizedDescription]
                : @"Failed to schedule alarm (timeout).";
            NSDictionary *err = noff_json_error(TOOL_NAME, @"set",
                                                 NOFF_ERR_INTERNAL_ERROR, msg);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }

        // Merge fire date into result
        NSMutableDictionary *data = [resultData mutableCopy];
        data[@"time"] = noff_format_date(fireDate);
        data[@"view_url"] = @"minis://views/alarm";
        data[@"hint"] = @"Alarm is now visible on the Minis home screen. Open minis://views/alarm to manage alarms.";
        noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"set", data), compact, quiet);
        return NOFF_EXIT_SUCCESS;
    }
    NSDictionary *err = noff_json_error(TOOL_NAME, @"set",
                                         NOFF_ERR_NOT_AVAILABLE,
                                         @"AlarmKit requires iOS 26.0 or later");
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_NOT_AVAILABLE;
}

static int cmd_timer_alarmkit(int argc, char **argv, int stdout_fd,
                              int stderr_fd, BOOL compact, BOOL quiet) {
    if (@available(iOS 26.0, *)) {
        NSString *durationStr = noff_find_arg(argc, argv, "--duration");
        if (!durationStr) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            NSDictionary *err = noff_json_error(TOOL_NAME, @"timer",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 @"Required: --duration <seconds|5m|1h>");
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }

        NSTimeInterval duration = parseDuration(durationStr);
        if (duration <= 0) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            NSDictionary *err = noff_json_error(TOOL_NAME, @"timer",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 @"Invalid duration. Use seconds or shorthand (5m, 1h).");
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }

        if (!ensureAuthorization(stdout_fd, @"timer", compact, quiet)) {
            return NOFF_EXIT_AUTH_DENIED;
        }

        NSString *label = noff_find_arg(argc, argv, "--label") ?: @"Timer";
        NSString *timerId = [[NSUUID UUID] UUIDString];

        __block NSDictionary *resultData = nil;
        __block NSError *schedError = nil;
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);

        [AlarmOffloadBridge scheduleTimerWithId:timerId
                                      duration:duration
                                         label:label
                                    completion:^(NSDictionary *data, NSError *error) {
            resultData = data;
            schedError = error;
            dispatch_semaphore_signal(sem);
        }];

        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC));

        if (schedError || !resultData) {
            NSString *msg = schedError
                ? [NSString stringWithFormat:@"Failed to schedule timer: %@", schedError.localizedDescription]
                : @"Failed to schedule timer (timeout).";
            NSDictionary *err = noff_json_error(TOOL_NAME, @"timer",
                                                 NOFF_ERR_INTERNAL_ERROR, msg);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }

        // Merge computed fires_at
        NSMutableDictionary *data = [resultData mutableCopy];
        data[@"fires_at"] = noff_format_date([NSDate dateWithTimeIntervalSinceNow:duration]);
        data[@"view_url"] = @"minis://views/alarm";
        data[@"hint"] = @"Timer is now visible on the Minis home screen. Open minis://views/alarm to manage alarms.";
        noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"timer", data), compact, quiet);
        return NOFF_EXIT_SUCCESS;
    }
    NSDictionary *err = noff_json_error(TOOL_NAME, @"timer",
                                         NOFF_ERR_NOT_AVAILABLE,
                                         @"AlarmKit requires iOS 26.0 or later");
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_NOT_AVAILABLE;
}

static int cmd_list_alarmkit(int argc, char **argv, int stdout_fd,
                             BOOL compact, BOOL quiet) {
    if (@available(iOS 26.0, *)) {
        if (!ensureAuthorization(stdout_fd, @"list", compact, quiet)) {
            return NOFF_EXIT_AUTH_DENIED;
        }

        __block NSArray *alarmList = nil;
        __block NSError *listError = nil;
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);

        [AlarmOffloadBridge listAlarmsWithCompletion:^(NSArray *alarms, NSError *error) {
            alarmList = alarms;
            listError = error;
            dispatch_semaphore_signal(sem);
        }];

        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC));

        if (listError) {
            NSString *msg = [NSString stringWithFormat:@"Failed to list alarms: %@",
                             listError.localizedDescription];
            NSDictionary *err = noff_json_error(TOOL_NAME, @"list",
                                                 NOFF_ERR_INTERNAL_ERROR, msg);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }

        NSArray *items = alarmList ?: @[];
        NSDictionary *data = @{
            @"alarms": items,
            @"count": @(items.count),
            @"backend": @"alarmkit",
            @"view_url": @"minis://views/alarm",
        };
        noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"list", data), compact, quiet);
        return NOFF_EXIT_SUCCESS;
    }
    NSDictionary *err = noff_json_error(TOOL_NAME, @"list",
                                         NOFF_ERR_NOT_AVAILABLE,
                                         @"AlarmKit requires iOS 26.0 or later");
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_NOT_AVAILABLE;
}

static int cmd_cancel_alarmkit(int argc, char **argv, int stdout_fd,
                               int stderr_fd, BOOL compact, BOOL quiet) {
    if (@available(iOS 26.0, *)) {
        BOOL cancelAll = noff_has_flag(argc, argv, "--all");
        NSString *cancelId = noff_find_arg(argc, argv, "--id");

        if (!cancelAll && !cancelId) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            NSDictionary *err = noff_json_error(TOOL_NAME, @"cancel",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 @"Required: --id <identifier> or --all");
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }

        if (!ensureAuthorization(stdout_fd, @"cancel", compact, quiet)) {
            return NOFF_EXIT_AUTH_DENIED;
        }

        if (cancelAll) {
            __block NSInteger cancelledCount = 0;
            __block NSError *cancelError = nil;
            dispatch_semaphore_t sem = dispatch_semaphore_create(0);

            [AlarmOffloadBridge cancelAllAlarmsWithCompletion:^(NSInteger count, NSError *error) {
                cancelledCount = count;
                cancelError = error;
                dispatch_semaphore_signal(sem);
            }];

            dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC));

            if (cancelError) {
                NSString *msg = [NSString stringWithFormat:@"Failed to cancel alarms: %@",
                                 cancelError.localizedDescription];
                NSDictionary *err = noff_json_error(TOOL_NAME, @"cancel",
                                                     NOFF_ERR_INTERNAL_ERROR, msg);
                noff_emit_json(stdout_fd, err, compact, quiet);
                return NOFF_EXIT_ERROR;
            }

            NSDictionary *data = @{
                @"cancelled_all": @YES,
                @"cancelled_count": @(cancelledCount),
                @"backend": @"alarmkit",
            };
            noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"cancel", data),
                           compact, quiet);
        } else {
            __block BOOL success = NO;
            __block NSError *cancelError = nil;
            dispatch_semaphore_t sem = dispatch_semaphore_create(0);

            [AlarmOffloadBridge cancelAlarmWithId:cancelId
                                      completion:^(BOOL ok, NSError *error) {
                success = ok;
                cancelError = error;
                dispatch_semaphore_signal(sem);
            }];

            dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC));

            if (cancelError || !success) {
                NSString *msg = cancelError
                    ? [NSString stringWithFormat:@"Failed to cancel alarm: %@", cancelError.localizedDescription]
                    : @"Failed to cancel alarm: invalid ID or not found.";
                NSDictionary *err = noff_json_error(TOOL_NAME, @"cancel",
                                                     NOFF_ERR_INTERNAL_ERROR, msg);
                noff_emit_json(stdout_fd, err, compact, quiet);
                return NOFF_EXIT_ERROR;
            }

            NSDictionary *data = @{
                @"cancelled_id": cancelId,
                @"backend": @"alarmkit",
            };
            noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"cancel", data),
                           compact, quiet);
        }
        return NOFF_EXIT_SUCCESS;
    }
    NSDictionary *err = noff_json_error(TOOL_NAME, @"cancel",
                                         NOFF_ERR_NOT_AVAILABLE,
                                         @"AlarmKit requires iOS 26.0 or later");
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_NOT_AVAILABLE;
}

#endif /* __IPHONE_OS_VERSION_MAX_ALLOWED >= 260000 */

// ── Main handler ──

static int alarm_handler(int argc, char **argv,
                          int stdin_fd, int stdout_fd, int stderr_fd) {
    if (noff_has_flag(argc, argv, "--help") || noff_has_flag(argc, argv, "-h")) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        return NOFF_EXIT_SUCCESS;
    }

    BOOL compact = noff_has_flag(argc, argv, "--compact");
    BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");

    NSString *subcmd = noff_get_subcommand(argc, argv);
    if (!subcmd) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"unknown",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"No command specified. Use --help for usage.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    // Route to AlarmKit (iOS 26+ only)
#if __IPHONE_OS_VERSION_MAX_ALLOWED >= 260000
    // Nested, not `&&`: combining the two into one condition makes clang stop
    // treating it as an availability guard (-Wunsupported-availability-guard)
    // and lose the compile-time checking of the calls inside.
    if (@available(iOS 26.0, *)) {
        if (alarmkit_is_usable()) {
            if ([subcmd isEqualToString:@"set"])    return cmd_set_alarmkit(argc, argv, stdout_fd, stderr_fd, compact, quiet);
            if ([subcmd isEqualToString:@"timer"])  return cmd_timer_alarmkit(argc, argv, stdout_fd, stderr_fd, compact, quiet);
            if ([subcmd isEqualToString:@"list"])   return cmd_list_alarmkit(argc, argv, stdout_fd, compact, quiet);
            if ([subcmd isEqualToString:@"cancel"]) return cmd_cancel_alarmkit(argc, argv, stdout_fd, stderr_fd, compact, quiet);
        }
    }
#endif

    // iOS < 26, or a platform with no AlarmKit at all (visionOS compat mode).
    // The two need different wording: telling a Vision Pro user to upgrade iOS
    // is advice they cannot act on, and this string is read by the agent.
    if ([subcmd isEqualToString:@"set"] || [subcmd isEqualToString:@"timer"] ||
        [subcmd isEqualToString:@"list"] || [subcmd isEqualToString:@"cancel"]) {
        NSString *reason = @"apple-alarm requires iOS 26.0 or later. Please upgrade your iOS version.";
#if __IPHONE_OS_VERSION_MAX_ALLOWED >= 260000
        if (@available(iOS 26.0, *)) {
            // Version is fine, so the framework itself is what's missing.
            reason = @"apple-alarm is not available on this platform: "
                      "AlarmKit is not present (for example when the iPad app "
                      "runs in compatibility mode on visionOS).";
        }
#endif
        NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                             NOFF_ERR_NOT_AVAILABLE, reason);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_NOT_AVAILABLE;
    }

    noff_emit_help(stderr_fd, HELP_TEXT);
    NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                         NOFF_ERR_INVALID_ARGS,
                                         [NSString stringWithFormat:@"Unknown command '%@'. Valid commands: set, timer, list, cancel. Use --help for details.", subcmd]);
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_INVALID_ARGS;
}

void alarm_offload_register(void) {
    int err = native_offload_add_handler("apple-alarm", alarm_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/apple-alarm");
        NSLog(@"NativeOffloads: apple-alarm handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register apple-alarm handler (err=%d)", err);
    }
}
