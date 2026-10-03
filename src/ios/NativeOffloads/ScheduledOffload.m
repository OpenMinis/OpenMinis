//
//  ScheduledOffload.m
//  MinisApp
//
//  Native offload handler for `minis-scheduled` (T-p2-minis-scheduled).
//  Contract mirrors Android's ScheduledTaskOffloadHandler (list / create /
//  delete / enable / disable / run, same flags) plus the iOS-side additions
//  --trigger / --after / --interval / --count / --of and the
//  child-of-current target. Argument strings are handed to the Swift bridge
//  untouched; all validation lives there.
//

#import <Foundation/Foundation.h>
#import "NativeOffloadUtils.h"
#include "kernel/native_offload.h"
#include <unistd.h>

#if __has_include("Minis-Swift.h")
#import "Minis-Swift.h"
#else
@interface ScheduledOffloadBridge : NSObject
+ (NSDictionary * _Nonnull)createWithArgs:(NSDictionary * _Nonnull)args;
+ (NSDictionary * _Nonnull)list;
+ (NSDictionary * _Nonnull)deleteWithId:(NSString * _Nonnull)id;
+ (NSDictionary * _Nonnull)setEnabledWithId:(NSString * _Nonnull)id enabled:(BOOL)enabled;
+ (NSDictionary * _Nonnull)runWithId:(NSString * _Nonnull)id;
@end
#endif

static NSString *const TOOL_NAME = @"minis-scheduled";

static NSString *const HELP_TEXT =
    @"minis-scheduled - Schedule prompts to run later in Minis (best effort while the app is alive)\n"
     "\n"
     "USAGE:\n"
     "  minis-scheduled list\n"
     "  minis-scheduled create --prompt \"...\" [--label L] [--target new|follow-up|rerun|child-of-current]\n"
     "                         [--trigger once|loop|cron|on-completion]\n"
     "                         [--after 30m]                          # once, relative\n"
     "                         [--interval 10m] [--count N]           # loop\n"
     "                         [--time HH:MM] [--repeat once|daily|weekdays|custom --days mon,tue,...]\n"
     "                         [--start YYYY-MM-DD] [--end YYYY-MM-DD] # cron window\n"
     "                         [--of <jobId>]                         # on-completion\n"
     "                         [--session <id>] [--message <id>] [--model <entry_id>] [--disabled]\n"
     "                         [--thinking off|low|medium|high|xhigh]\n"
     "  minis-scheduled delete  --id <jobId|label>\n"
     "  minis-scheduled enable  --id <jobId|label>\n"
     "  minis-scheduled disable --id <jobId|label>\n"
     "  minis-scheduled run     --id <jobId|label>     # fire now, off-schedule\n"
     "\n"
     "TRIGGER (inferred when --trigger is omitted: --after→once, --interval→loop, --time→cron, --of→on-completion):\n"
     "  once           fire once after --after <duration> (30m, 2h, 90s) or at the next --time HH:MM\n"
     "  loop           fire every --interval (>= 60s), --count N times (default: until deleted)\n"
     "  cron           fire at --time on --repeat daily (default) | weekdays | custom --days\n"
     "  on-completion  fire when job --of <jobId> finishes; {{result}} in --prompt is replaced by its result\n"
     "\n"
     "TARGET:\n"
     "  new              (default) a new top-level chat, visible in the session list\n"
     "  follow-up        appended as a new turn to --session (default: the chat this command runs in)\n"
     "  rerun            re-run --session from user message --message\n"
     "  child-of-current a hidden helper session under the current chat; its final answer is\n"
     "                   posted back into the current chat when it finishes\n"
     "\n"
     "IMPORTANT: timers live in the app process only. If Minis is killed by the system the timer is\n"
     "gone; a reminder notification registered at creation is the only thing that survives, and it\n"
     "says the task was DUE, not that it ran. For guaranteed background execution use an Apple\n"
     "Shortcuts automation instead.\n"
     "\n"
     "EXAMPLES:\n"
     "  minis-scheduled create --prompt \"Check the build status\" --interval 10m --count 6 --label build-watch --target follow-up\n"
     "  minis-scheduled create --prompt \"Remind me to drink water\" --after 30m\n"
     "  minis-scheduled create --prompt \"Morning summary of my notes\" --time 09:00 --repeat weekdays\n"
     "  minis-scheduled list\n"
     "  minis-scheduled delete --id build-watch\n";

static NSString *arg(int argc, char **argv, const char *name) {
    NSString *v = noff_find_arg(argc, argv, name);
    return (v && v.length > 0) ? v : nil;
}

static void put(NSMutableDictionary *d, NSString *key, NSString *v) {
    if (v) d[key] = v;
}

static int cmd_create(int argc, char **argv, int stdout_fd, int stderr_fd, BOOL compact, BOOL quiet) {
    NSMutableDictionary *args = [NSMutableDictionary dictionary];
    put(args, @"prompt",   arg(argc, argv, "--prompt")   ?: arg(argc, argv, "-p"));
    put(args, @"label",    arg(argc, argv, "--label")    ?: arg(argc, argv, "-l"));
    put(args, @"trigger",  arg(argc, argv, "--trigger"));
    put(args, @"after",    arg(argc, argv, "--after"));
    put(args, @"interval", arg(argc, argv, "--interval"));
    put(args, @"count",    arg(argc, argv, "--count"));
    put(args, @"time",     arg(argc, argv, "--time")     ?: arg(argc, argv, "-t"));
    put(args, @"repeat",   arg(argc, argv, "--repeat")   ?: arg(argc, argv, "-r"));
    put(args, @"days",     arg(argc, argv, "--days"));
    put(args, @"start",    arg(argc, argv, "--start"));
    put(args, @"end",      arg(argc, argv, "--end"));
    put(args, @"of",       arg(argc, argv, "--of"));
    put(args, @"target",   arg(argc, argv, "--target"));
    put(args, @"session",  arg(argc, argv, "--session"));
    put(args, @"message",  arg(argc, argv, "--message"));
    put(args, @"model",    arg(argc, argv, "--model")    ?: arg(argc, argv, "-m"));
    // [T-scheduled-thinking-level] Optional; absent leaves the level alone.
    put(args, @"thinking", arg(argc, argv, "--thinking"));
    args[@"disabled"] = @(noff_has_flag(argc, argv, "--disabled"));

    NSDictionary *data = [ScheduledOffloadBridge createWithArgs:args];
    if ([data[@"ok"] isKindOfClass:[NSNumber class]] && ![data[@"ok"] boolValue]) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"create", NOFF_ERR_INVALID_ARGS,
                                             data[@"message"] ?: @"invalid arguments");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"create", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_with_id(NSString *action, int argc, char **argv, int stdout_fd, int stderr_fd,
                       BOOL compact, BOOL quiet) {
    NSString *jobId = arg(argc, argv, "--id") ?: arg(argc, argv, "--label");
    if (!jobId) {
        NSArray *pos = noff_positional_args(argc, argv);
        if (pos.count >= 2) jobId = pos[1];   // `minis-scheduled delete <id>`
    }
    if (!jobId) {
        NSDictionary *err = noff_json_error(TOOL_NAME, action, NOFF_ERR_INVALID_ARGS, @"--id <jobId|label> required");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSDictionary *data;
    if ([action isEqualToString:@"delete"])       data = [ScheduledOffloadBridge deleteWithId:jobId];
    else if ([action isEqualToString:@"enable"])  data = [ScheduledOffloadBridge setEnabledWithId:jobId enabled:YES];
    else if ([action isEqualToString:@"disable"]) data = [ScheduledOffloadBridge setEnabledWithId:jobId enabled:NO];
    else                                          data = [ScheduledOffloadBridge runWithId:jobId];
    if ([data[@"ok"] isKindOfClass:[NSNumber class]] && ![data[@"ok"] boolValue]) {
        NSDictionary *err = noff_json_error(TOOL_NAME, action, NOFF_ERR_INVALID_ARGS,
                                             data[@"message"] ?: @"failed");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, action, data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int scheduled_handler(int argc, char **argv, int stdin_fd, int stdout_fd, int stderr_fd) {
    if (noff_has_flag(argc, argv, "--help") || noff_has_flag(argc, argv, "-h")) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        return NOFF_EXIT_SUCCESS;
    }
    BOOL compact = noff_has_flag(argc, argv, "--compact");
    BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");
    NSString *sub = noff_get_subcommand(argc, argv) ?: @"list";

    if ([sub isEqualToString:@"list"]) {
        noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"list", [ScheduledOffloadBridge list]), compact, quiet);
        return NOFF_EXIT_SUCCESS;
    }
    if ([sub isEqualToString:@"create"] || [sub isEqualToString:@"add"]) {
        return cmd_create(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    }
    if ([sub isEqualToString:@"delete"] || [sub isEqualToString:@"remove"] || [sub isEqualToString:@"rm"] || [sub isEqualToString:@"cancel"]) {
        return cmd_with_id(@"delete", argc, argv, stdout_fd, stderr_fd, compact, quiet);
    }
    if ([sub isEqualToString:@"enable"])  return cmd_with_id(@"enable", argc, argv, stdout_fd, stderr_fd, compact, quiet);
    if ([sub isEqualToString:@"disable"]) return cmd_with_id(@"disable", argc, argv, stdout_fd, stderr_fd, compact, quiet);
    if ([sub isEqualToString:@"run"])     return cmd_with_id(@"run", argc, argv, stdout_fd, stderr_fd, compact, quiet);

    noff_emit_help(stderr_fd, HELP_TEXT);
    NSDictionary *err = noff_json_error(TOOL_NAME, sub, NOFF_ERR_INVALID_ARGS,
                                         [NSString stringWithFormat:@"Unknown command '%@'. Valid: list, create, delete, enable, disable, run.", sub]);
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_INVALID_ARGS;
}

void scheduled_offload_register(void) {
    int err = native_offload_add_handler("minis-scheduled", scheduled_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/minis-scheduled");
        NSLog(@"NativeOffloads: minis-scheduled handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register minis-scheduled handler (err=%d)", err);
    }
}
