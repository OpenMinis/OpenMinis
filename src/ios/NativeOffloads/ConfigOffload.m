//
//  ConfigOffload.m
//  MinisApp
//
//  Native offload handler for `minis-config`.
//  Subcommands: list-topics, topic-help, get, set, set-batch,
//               audit-list, audit-get, audit-revert.
//
//  Argument format is intentionally low-level — the user-facing CLI
//  shell wrapper at /usr/local/bin/minis-config translates the friendly
//  flag form into these positional arguments before invoking us.
//

#import <Foundation/Foundation.h>
#import "NativeOffloadUtils.h"
#include "kernel/native_offload.h"

#if __has_include("Minis-Swift.h")
#import "Minis-Swift.h"
#else
@interface ConfigOffloadBridge : NSObject
+ (BOOL)isEnabled;
+ (NSDictionary * _Nonnull)disabledErrorEnvelope;
+ (NSArray<NSString *> * _Nonnull)allTopics;
+ (NSArray<NSDictionary *> * _Nonnull)fieldsForTopic:(NSString * _Nonnull)topic;
+ (NSDictionary * _Nonnull)readFieldWithPath:(NSString * _Nonnull)path;
+ (NSDictionary * _Nonnull)readFieldWithPath:(NSString * _Nonnull)path
                                       filter:(NSString * _Nullable)filter;
+ (NSDictionary * _Nonnull)readFieldWithPath:(NSString * _Nonnull)path
                                       filter:(NSString * _Nullable)filter
                                         page:(NSInteger)page
                                     pageSize:(NSInteger)pageSize;
+ (NSDictionary * _Nonnull)writeFieldsWithItems:(NSArray<NSDictionary *> * _Nonnull)items
                                        caption:(NSString * _Nullable)caption
                                       actorRaw:(NSString * _Nonnull)actorRaw
                                      sessionId:(NSString * _Nullable)sessionId;
+ (NSDictionary * _Nonnull)auditListWithLimit:(NSInteger)limit
                                         scope:(NSString * _Nullable)scope;
+ (NSDictionary * _Nonnull)auditGetWithId:(NSString * _Nonnull)idStr;
+ (NSDictionary * _Nonnull)auditRevertWithId:(NSString * _Nonnull)idStr
                                     actorRaw:(NSString * _Nonnull)actorRaw
                                    sessionId:(NSString * _Nullable)sessionId
                             skipConfirmation:(BOOL)skipConfirmation;
@end
#endif

static NSString *const TOOL_NAME = @"minis-config";

// Exit codes — match the CLI contract documented in the user agreement:
//   0   success
//   1   validation_failed / invalid_args
//   124 timeout
//   125 user_rejected
//   126 permission_denied
#define EXIT_TIMEOUT 124
#define EXIT_USER_REJECTED 125
#define EXIT_PERMISSION_DENIED 126

static NSString *const HELP_TEXT =
    @"minis-config - read or change Minis app settings (logged + revertable)\n"
     "\n"
     "USAGE:\n"
     "  minis-config <subcommand> [args]\n"
     "\n"
     "DISCOVERY:\n"
     "  list-topics                  Show all configurable topics.\n"
     "  topic-help <topic>           Show fields under one topic.\n"
     "\n"
     "PATHS:\n"
     "  <topic>.<field> or <topic>.<entry_id>.<field>. Use entry_id exactly as\n"
     "  `get <topic>` prints it — dots and slashes are fine, no escaping, e.g.\n"
     "    get models.<uuid>/glm-5.1.contextWindow\n"
     "\n"
     "FIELD I/O:\n"
     "  get <path> [--filter <kw>] [--page N] [--page-size N]\n"
     "                               Read one field. --filter (-f) does\n"
     "                               case-insensitive AND matching on\n"
     "                               whitespace-separated keywords against the\n"
     "                               JSON form of each array element (or the\n"
     "                               whole value for scalars). Envelope adds\n"
     "                               total/matched/filtered fields.\n"
     "                               --page (-p, default 1) and --page-size\n"
     "                               (-s, default 20, max 100) paginate array\n"
     "                               results. Filter runs first; pagination\n"
     "                               total reflects the post-filter count.\n"
     "                               Pagination is ignored on scalar fields.\n"
     "  set <path> <value-json>      Write one field. Triggers user confirm.\n"
     "  set <path> --file <f>        Read value-json from file <f> instead of an\n"
     "                               argv positional. Use this when the value\n"
     "                               contains quotes / backslashes / newlines /\n"
     "                               $ / backticks (e.g. soul.body) — it bypasses\n"
     "                               shell escaping. Write the JSON to a temp file\n"
     "                               first, then: set <path> --file /tmp/v.json\n"
     "  set <path>.append <elem>     Append one element to an array-typed field\n"
     "                               (e.g. defaults.agentLoopEntries.append \"<id>\").\n"
     "                               <elem> is the JSON-encoded element, not the\n"
     "                               whole array.\n"
     "  set <path>.remove <elem>     Drop every occurrence of <elem> from an\n"
     "                               array-typed field. Returns not_found if\n"
     "                               no element matched.\n"
     "  add <topic> <value-json>     Create a new child in an addable collection\n"
     "                               (e.g. providers, groups). The app generates\n"
     "                               its UUID (returned in applied[0].new). Same\n"
     "                               as `set <topic>.add <value-json>`. Supports\n"
     "                               --file like `set`. e.g.\n"
     "                               add groups '{\"name\":\"Light\",\"entries\":[...]}'\n"
     "  set-batch                    Read JSON array of {path,value_json} from stdin.\n"
     "                               One confirm sheet for the whole batch.\n"
     "                               Each item may use the .append/.remove suffix.\n"
     "\n"
     "AUDIT:\n"
     "  audit-list [--limit N] [--scope <topic>]\n"
     "  audit-get  <audit-id>\n"
     "  audit-revert <audit-id>      Roll back a previous applied entry.\n"
     "\n"
     "FLAGS:\n"
     "  --session <id>               Tag the audit row with the active session.\n"
     "  --actor agent|user|...       Override audit actor (default: agent).\n"
     "  --caption <text>             Caption shown above the confirm sheet.\n"
     "  --help, -h                   Show this help.\n"
     "\n"
     "EXIT CODES:\n"
     "  0    success\n"
     "  1    invalid args / validation failed\n"
     "  124  confirmation timed out (30 s)\n"
     "  125  user rejected\n"
     "  126  permission denied (master switch off, or hidden field)\n"
     "\n"
     "Every write requires user confirmation in-app. The response includes\n"
     "a `user_message` field — relay it (or paraphrase) to the user so they\n"
     "know how to review or revert via Logs → Config Changes.\n";

// ── Helpers ──

static NSString *get_arg(int argc, char **argv, const char *name) {
    return noff_find_arg(argc, argv, name);
}

static int exit_code_from_envelope(NSDictionary *envelope) {
    if ([envelope[@"ok"] boolValue]) return NOFF_EXIT_SUCCESS;
    NSString *err = envelope[@"error"];
    if ([err isEqualToString:@"permission_denied"]) return EXIT_PERMISSION_DENIED;
    if ([err isEqualToString:@"timeout"]) return EXIT_TIMEOUT;
    if ([err isEqualToString:@"user_rejected"]) return EXIT_USER_REJECTED;
    if ([err isEqualToString:@"all_rejected"]) return EXIT_USER_REJECTED;
    return NOFF_EXIT_INVALID_ARGS;
}

// ── Subcommands ──

static int cmd_list_topics(int argc, char **argv, int stdout_fd,
                           BOOL compact, BOOL quiet) {
    if (![ConfigOffloadBridge isEnabled]) {
        NSDictionary *env = [ConfigOffloadBridge disabledErrorEnvelope];
        noff_emit_json(stdout_fd, env, compact, quiet);
        return EXIT_PERMISSION_DENIED;
    }
    NSArray *topics = [ConfigOffloadBridge allTopics];
    NSDictionary *result = noff_json_envelope(TOOL_NAME, @"list-topics",
                                              @{@"topics": topics});
    noff_emit_json(stdout_fd, result, compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_topic_help(int argc, char **argv, int stdout_fd, int stderr_fd,
                          BOOL compact, BOOL quiet) {
    NSString *topic = noff_get_subcommand(argc, argv);
    // The actual topic is argv[2] when subcommand is "topic-help".
    if (argc >= 3) topic = [NSString stringWithUTF8String:argv[2]];
    if (!topic || topic.length == 0
        || [topic isEqualToString:@"topic-help"]) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"topic-help",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"topic-help <topic> requires a topic name. "
                                              "Use list-topics to see available topics.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (![ConfigOffloadBridge isEnabled]) {
        NSDictionary *env = [ConfigOffloadBridge disabledErrorEnvelope];
        noff_emit_json(stdout_fd, env, compact, quiet);
        return EXIT_PERMISSION_DENIED;
    }
    NSArray *fields = [ConfigOffloadBridge fieldsForTopic:topic];
    NSMutableDictionary *payload = [@{@"topic": topic, @"fields": fields} mutableCopy];
    // [T-config-path-dotted-id] Collections say how to spell a child path.
    NSString *pathNote = [ConfigOffloadBridge pathNoteForTopic:topic];
    if (pathNote) payload[@"path_note"] = pathNote;
    NSDictionary *result = noff_json_envelope(TOOL_NAME, @"topic-help", payload);
    noff_emit_json(stdout_fd, result, compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_get(int argc, char **argv, int stdout_fd, int stderr_fd,
                   BOOL compact, BOOL quiet) {
    if (argc < 3) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"get",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"get <path> requires a field path.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSString *path = [NSString stringWithUTF8String:argv[2]];
    NSString *filter = noff_find_arg(argc, argv, "--filter");
    if (!filter) filter = noff_find_arg(argc, argv, "-f");
    NSString *pageRaw = noff_find_arg(argc, argv, "--page");
    if (!pageRaw) pageRaw = noff_find_arg(argc, argv, "-p");
    NSString *pageSizeRaw = noff_find_arg(argc, argv, "--page-size");
    if (!pageSizeRaw) pageSizeRaw = noff_find_arg(argc, argv, "-s");
    // 0 sentinels mean "unset" — the bridge fills in defaults.
    NSInteger page = pageRaw ? MAX(0, [pageRaw integerValue]) : 0;
    NSInteger pageSize = pageSizeRaw ? MAX(0, [pageSizeRaw integerValue]) : 0;
    NSDictionary *envelope = [ConfigOffloadBridge readFieldWithPath:path
                                                              filter:filter
                                                                page:page
                                                            pageSize:pageSize];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

static int cmd_set(int argc, char **argv, int stdout_fd, int stderr_fd,
                   BOOL compact, BOOL quiet) {
    // [T-ios-minis-config-set-shell-escape] (issue #36) `--file <path>` reads
    // the value-json from a file instead of argv[3]. The value normally rides
    // through the shell as a positional arg, so busybox ash mangles embedded
    // double-quotes / backslashes / newlines / $ / backticks before this
    // binary sees them — breaking large free-text writes like soul.body.
    // Reading from a file bypasses shell escaping entirely.
    NSString *fileArg = get_arg(argc, argv, "--file");
    if (argc < 3 || (argc < 4 && !fileArg)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"set",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"set <path> <value-json> requires both arguments "
                                              "(or use: set <path> --file <path-to-value-json>).");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSString *path = [NSString stringWithUTF8String:argv[2]];
    NSString *valueJSON;
    if (fileArg) {
        NSError *readErr = nil;
        valueJSON = [NSString stringWithContentsOfFile:fileArg
                                              encoding:NSUTF8StringEncoding
                                                 error:&readErr];
        if (!valueJSON) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"set",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 [NSString stringWithFormat:
                                                    @"--file: could not read value from '%@': %@",
                                                    fileArg, readErr.localizedDescription ?: @"unknown error"]);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
    } else {
        valueJSON = [NSString stringWithUTF8String:argv[3]];
    }
    NSString *caption = get_arg(argc, argv, "--caption");
    NSString *actor = get_arg(argc, argv, "--actor") ?: @"agent";
    NSString *sessionId = get_arg(argc, argv, "--session");
    NSDictionary *item = @{@"path": path, @"value_json": valueJSON};
    NSDictionary *envelope = [ConfigOffloadBridge writeFieldsWithItems:@[item]
                                                                caption:caption
                                                               actorRaw:actor
                                                              sessionId:sessionId];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

// `add <topic> <value-json>` — create a new child in an addable collection
// (e.g. `add providers …`, `add groups …`). Sugar over `set <topic>.add
// <value-json>`: it reuses the exact same collection-add path in the bridge,
// so the confirm sheet, audit row, redaction, exit codes, and the new child's
// auto-generated UUID (returned in applied[0].new) are all identical. Both
// forms remain valid; this one matches the natural `add <topic>` invocation.
static int cmd_add(int argc, char **argv, int stdout_fd, int stderr_fd,
                   BOOL compact, BOOL quiet) {
    NSString *fileArg = get_arg(argc, argv, "--file");
    if (argc < 3 || (argc < 4 && !fileArg)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"add",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"add <topic> <value-json> requires both arguments "
                                              "(or use: add <topic> --file <path-to-value-json>). "
                                              "Example: add groups '{\"name\":\"Light\",\"entries\":[...]}'.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSString *topic = [NSString stringWithUTF8String:argv[2]];
    NSString *valueJSON;
    if (fileArg) {
        NSError *readErr = nil;
        valueJSON = [NSString stringWithContentsOfFile:fileArg
                                              encoding:NSUTF8StringEncoding
                                                 error:&readErr];
        if (!valueJSON) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"add",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 [NSString stringWithFormat:
                                                    @"--file: could not read value from '%@': %@",
                                                    fileArg, readErr.localizedDescription ?: @"unknown error"]);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
    } else {
        valueJSON = [NSString stringWithUTF8String:argv[3]];
    }
    // Route through the collection-add suffix the bridge already understands.
    NSString *path = [topic stringByAppendingString:@".add"];
    NSString *caption = get_arg(argc, argv, "--caption");
    NSString *actor = get_arg(argc, argv, "--actor") ?: @"agent";
    NSString *sessionId = get_arg(argc, argv, "--session");
    NSDictionary *item = @{@"path": path, @"value_json": valueJSON};
    NSDictionary *envelope = [ConfigOffloadBridge writeFieldsWithItems:@[item]
                                                                caption:caption
                                                               actorRaw:actor
                                                              sessionId:sessionId];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

static int cmd_set_batch(int argc, char **argv, int stdin_fd, int stdout_fd, int stderr_fd,
                         BOOL compact, BOOL quiet) {
    // Read JSON array from stdin; each element {path, value_json}.
    NSMutableData *buf = [NSMutableData data];
    char chunk[4096];
    ssize_t n;
    while ((n = read(stdin_fd, chunk, sizeof(chunk))) > 0) {
        [buf appendBytes:chunk length:(NSUInteger)n];
        // Cap at 1 MB to keep batch sane.
        if (buf.length > (1u << 20)) break;
    }
    NSError *jerr = nil;
    id parsed = buf.length > 0
        ? [NSJSONSerialization JSONObjectWithData:buf options:0 error:&jerr]
        : nil;
    if (![parsed isKindOfClass:[NSArray class]]) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"set-batch",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"set-batch expects a JSON array on stdin "
                                              "with elements {\"path\":..., \"value_json\":...}.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSArray *items = (NSArray *)parsed;
    if (items.count == 0) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"set-batch",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Empty batch.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (items.count > 50) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"set-batch",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Batch capped at 50 items.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSString *caption = get_arg(argc, argv, "--caption");
    NSString *actor = get_arg(argc, argv, "--actor") ?: @"agent";
    NSString *sessionId = get_arg(argc, argv, "--session");
    NSDictionary *envelope = [ConfigOffloadBridge writeFieldsWithItems:items
                                                                caption:caption
                                                               actorRaw:actor
                                                              sessionId:sessionId];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

static int cmd_audit_list(int argc, char **argv, int stdout_fd,
                          BOOL compact, BOOL quiet) {
    NSString *limitRaw = get_arg(argc, argv, "--limit");
    NSInteger limit = limitRaw ? MAX(1, MIN(1000, [limitRaw intValue])) : 100;
    NSString *scope = get_arg(argc, argv, "--scope");
    NSDictionary *envelope = [ConfigOffloadBridge auditListWithLimit:limit scope:scope];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

static int cmd_audit_get(int argc, char **argv, int stdout_fd,
                         BOOL compact, BOOL quiet) {
    if (argc < 3) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"audit-get",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"audit-get <audit-id> requires an id.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSString *idStr = [NSString stringWithUTF8String:argv[2]];
    NSDictionary *envelope = [ConfigOffloadBridge auditGetWithId:idStr];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

static int cmd_audit_revert(int argc, char **argv, int stdout_fd,
                            BOOL compact, BOOL quiet) {
    if (argc < 3) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"audit-revert",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"audit-revert <audit-id> requires an id.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSString *idStr = [NSString stringWithUTF8String:argv[2]];
    NSString *actor = get_arg(argc, argv, "--actor") ?: @"agent-revert";
    NSString *sessionId = get_arg(argc, argv, "--session");
    NSDictionary *envelope = [ConfigOffloadBridge auditRevertWithId:idStr
                                                            actorRaw:actor
                                                           sessionId:sessionId
                                                    skipConfirmation:NO];
    noff_emit_json(stdout_fd, envelope, compact, quiet);
    return exit_code_from_envelope(envelope);
}

// ── Main handler ──

static int config_handler(int argc, char **argv,
                          int stdin_fd, int stdout_fd, int stderr_fd) {
    if (noff_has_flag(argc, argv, "--help") || noff_has_flag(argc, argv, "-h")) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        return NOFF_EXIT_SUCCESS;
    }

    BOOL compact = noff_has_flag(argc, argv, "--compact");
    BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");

    NSString *subcmd = noff_get_subcommand(argc, argv);
    if (!subcmd) {
        // Default to list-topics so the agent can discover quickly.
        return cmd_list_topics(argc, argv, stdout_fd, compact, quiet);
    }
    if ([subcmd isEqualToString:@"list-topics"]) {
        return cmd_list_topics(argc, argv, stdout_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"topic-help"]) {
        return cmd_topic_help(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"get"]) {
        return cmd_get(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"set"]) {
        return cmd_set(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"add"]) {
        return cmd_add(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"set-batch"]) {
        return cmd_set_batch(argc, argv, stdin_fd, stdout_fd, stderr_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"audit-list"]) {
        return cmd_audit_list(argc, argv, stdout_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"audit-get"]) {
        return cmd_audit_get(argc, argv, stdout_fd, compact, quiet);
    } else if ([subcmd isEqualToString:@"audit-revert"]) {
        return cmd_audit_revert(argc, argv, stdout_fd, compact, quiet);
    }

    noff_emit_help(stderr_fd, HELP_TEXT);
    NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                         NOFF_ERR_INVALID_ARGS,
                                         [NSString stringWithFormat:
                                          @"Unknown subcommand '%@'. Use --help.", subcmd]);
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_INVALID_ARGS;
}

void config_offload_register(void) {
    int err = native_offload_add_handler("minis-config", config_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/minis-config");
        NSLog(@"NativeOffloads: minis-config handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register minis-config handler (err=%d)", err);
    }
}
