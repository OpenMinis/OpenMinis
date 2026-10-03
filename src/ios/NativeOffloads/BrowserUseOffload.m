//
//  BrowserUseOffload.m
//  MinisApp
//
//  Native offload handler for `minis-browser-use`.
//  Exposes the in-app browser_use tool (WKWebView automation) as a CLI
//  inside the ish guest. Supports the same action + parameter set as the
//  browser_use agent tool, plus a --help surface.
//
//  Mirrors Android's BrowserUseOffloadHandler.
//

#import <Foundation/Foundation.h>
#import "NativeOffloadUtils.h"
#include "kernel/native_offload.h"
#include <unistd.h>

// Swift bridge — generated header
#if __has_include("Minis-Swift.h")
#import "Minis-Swift.h"
#elif __has_include("MinisApp-Swift.h")
#import "MinisApp-Swift.h"
#endif

static NSString *const TOOL_NAME = @"minis-browser-use";

static NSString *const HELP_TEXT =
    @"minis-browser-use - Drive the in-app WebView from the shell\n"
     "\n"
     "USAGE:\n"
     "  minis-browser-use <action> [options]\n"
     "  minis-browser-use --json '<json>'\n"
     "  minis-browser-use --help\n"
     "\n"
     "ACTIONS:\n"
     "  navigate        --url <url>\n"
     "  screenshot      [--full-page]\n"
     "                  --full-page captures the entire scrollable page\n"
     "                  (capped at 32768px tall) instead of just the viewport\n"
     "  click           --selector <css> | --coordinate-x <n> --coordinate-y <n>\n"
     "  type            --selector <css> --text <text>\n"
     "  get_text        --selector <css>\n"
     "  scroll          [--selector <css>] [--direction up|down] [--amount <px>]\n"
     "  get_page_info\n"
     "  execute_js      --script <js>\n"
     "  find_elements   --selector <css>\n"
     "  hover           --selector <css>\n"
     "  get_readable\n"
     "  set_user_agent  --user-agent mobile_safari|desktop_safari|custom\n"
     "  set_viewport    --width <n> --height <n> | --reset\n"
     "                  Override the viewport for this session only. Without\n"
     "                  --reset it shadows the app-wide default from Browser\n"
     "                  Settings; --reset drops the session override. The\n"
     "                  session override persists across app restarts.\n"
     "  get_backbone    [--max-depth <n>]\n"
     "  fetch           --url <url>\n"
     "  new_tab         [--url <url>]\n"
     "  close_tab       [--tab-id <n>]\n"
     "  list_tabs\n"
     "  get_cookies     --keywords <list> [--fuzzy]\n"
     "  set_cookies     --cookies <json-array> | --cookies-file <path>\n"
     "                  Content (for both: the file holds the SAME JSON array):\n"
     "                    [{\"name\":\"n\",\"value\":\"v\",   // required\n"
     "                      \"domain\":\".x.com\",\"path\":\"/\",  // optional\n"
     "                      \"secure\":true,\"http_only\":true,  // optional\n"
     "                      \"expires\":1893456000}]            // optional unix secs\n"
     "                  domain defaults to the current page host, path to \"/\".\n"
     "                  Aliases accepted: httpOnly, expirationDate, sameSite.\n"
     "                  --cookies-file also accepts a Netscape cookies.txt\n"
     "                  (curl/wget/browser export, TAB-separated).\n"
     "                  Prefer --cookies-file: the JSON's quotes/braces survive\n"
     "                  the shell intact — write the array to a temp file first.\n"
     "  scroll_and_collect --scroll-count <n> --item-selector <css> --keywords <list>\n"
     "  wait_for_dom_stable [--timeout <ms>]\n"
     "\n"
     "COMMON OPTIONS:\n"
     "  --tab-id <n>     Route the action to a specific tab (default: active tab)\n"
     "  --json '<s>'     Pass the full input object as JSON (matches browser_use schema)\n"
     "  --with-base64    Also include image_base64 in the screenshot output.\n"
     "                   Off by default — screenshots are saved to\n"
     "                   /var/minis/browser/ and referenced via image_path +\n"
     "                   minis_url, so shells don't get flooded with base64.\n"
     "  --compact        Minimize JSON output\n"
     "  -q, --quiet      Output only the data field\n"
     "  -h, --help       Show this help message\n"
     "\n"
     "OUTPUT:\n"
     "  JSON envelope with a `data` object containing:\n"
     "    text              Human-readable result the agent would see\n"
     "    success           true / false\n"
     "    page_url          URL after the action (when applicable)\n"
     "    image_path        Linux path of the persisted JPEG, e.g.\n"
     "                      /var/minis/browser/screenshot_<ms>.jpg\n"
     "    minis_url         minis://browser/<filename> — stable reference for\n"
     "                      read_image / downstream tools\n"
     "    image_base64      Base64 JPEG (only when --with-base64 is set)\n"
     "    fetched_file      Filename of the downloaded resource (fetch action)\n"
     "    fetched_path      Linux path of the persisted download under\n"
     "                      /var/minis/browser/\n"
     "    fetched_minis_url minis://browser/<filename> for the download\n"
     "\n"
     "EXAMPLES:\n"
     "  minis-browser-use navigate --url https://example.com\n"
     "  minis-browser-use screenshot\n"
     "  minis-browser-use screenshot --full-page\n"
     "  minis-browser-use click --selector '.btn-primary'\n"
     "  minis-browser-use type --selector 'input[name=q]' --text 'hello'\n"
     "  minis-browser-use execute_js --script 'return document.title'\n"
     "  minis-browser-use --json '{\"action\":\"navigate\",\"url\":\"https://x.com\"}'\n";

// ── Netscape cookies.txt parsing ──

/// Parse a Netscape-format cookie file (the `curl`/`wget`/browser-export TSV)
/// into the same array-of-objects shape `set_cookies` consumes. Returns nil if
/// it doesn't look like Netscape (so the caller can fall through to JSON error).
///
/// Format: one cookie per line, 7 TAB-separated fields:
///   domain  includeSubdomains  path  secure  expires  name  value
/// Lines beginning with `#` are comments — EXCEPT the `#HttpOnly_<domain>`
/// prefix curl uses to mark HttpOnly cookies, which we honor.
static NSArray *parseNetscapeCookies(NSString *text) {
    NSArray *rawLines = [text componentsSeparatedByCharactersInSet:
                         [NSCharacterSet newlineCharacterSet]];
    NSMutableArray *cookies = [NSMutableArray array];
    for (NSString *line in rawLines) {
        NSString *trimmed = [line stringByTrimmingCharactersInSet:
                             NSCharacterSet.whitespaceCharacterSet];
        if (trimmed.length == 0) continue;
        BOOL httpOnly = NO;
        NSString *work = trimmed;
        if ([work hasPrefix:@"#HttpOnly_"]) {
            httpOnly = YES;
            work = [work substringFromIndex:@"#HttpOnly_".length];
        } else if ([work hasPrefix:@"#"]) {
            continue;  // comment / header line
        }
        NSArray *f = [work componentsSeparatedByString:@"\t"];
        if (f.count < 7) continue;  // not a valid data row
        NSString *domain = f[0], *path = f[2], *secure = f[3];
        NSString *expires = f[4], *name = f[5], *value = f[6];
        if (name.length == 0) continue;
        NSMutableDictionary *c = [@{ @"name": name, @"value": value,
                                     @"domain": domain, @"path": path } mutableCopy];
        if ([secure.uppercaseString isEqualToString:@"TRUE"]) c[@"secure"] = @YES;
        if (httpOnly) c[@"http_only"] = @YES;
        long long exp = expires.longLongValue;
        if (exp > 0) c[@"expires"] = @(exp);
        [cookies addObject:c];
    }
    return cookies.count > 0 ? cookies : nil;
}

// ── Build JSON input from argv ──

/// Collect supported option values into a dictionary that matches the
/// browser_use tool schema. `--json` short-circuits everything else.
static NSDictionary *buildInputJson(int argc, char **argv, NSString **errOut) {
    // Explicit --json wins.
    NSString *jsonArg = noff_find_arg(argc, argv, "--json");
    if (jsonArg) {
        NSData *data = [jsonArg dataUsingEncoding:NSUTF8StringEncoding];
        NSError *err = nil;
        id obj = [NSJSONSerialization JSONObjectWithData:data options:0 error:&err];
        if (![obj isKindOfClass:NSDictionary.class]) {
            if (errOut) *errOut = [NSString stringWithFormat:@"--json is not a JSON object: %@",
                                   err.localizedDescription ?: @"unknown"];
            return nil;
        }
        return (NSDictionary *)obj;
    }

    NSString *action = noff_get_subcommand(argc, argv);
    if (!action) {
        if (errOut) *errOut = @"missing <action>. Use --help for usage.";
        return nil;
    }

    NSMutableDictionary *obj = [NSMutableDictionary dictionary];
    obj[@"action"] = action;

    NSString *s;
    if ((s = noff_find_arg(argc, argv, "--url")))            obj[@"url"] = s;
    if ((s = noff_find_arg(argc, argv, "--selector")))       obj[@"selector"] = s;
    if ((s = noff_find_arg(argc, argv, "--text")))           obj[@"text"] = s;
    if ((s = noff_find_arg(argc, argv, "--direction")))      obj[@"direction"] = s;
    if ((s = noff_find_arg(argc, argv, "--script")))         obj[@"script"] = s;
    if ((s = noff_find_arg(argc, argv, "--item-selector")))  obj[@"item_selector"] = s;

    // user-agent: accept both --user-agent and --ua
    s = noff_find_arg(argc, argv, "--user-agent");
    if (!s) s = noff_find_arg(argc, argv, "--ua");
    if (s) obj[@"user_agent"] = s;

    // Integer options: accept hyphen and underscore variants.
    struct { const char *primary; const char *alt; NSString *key; } intOpts[] = {
        {"--coordinate-x", "--x",         @"coordinate_x"},
        {"--coordinate-y", "--y",         @"coordinate_y"},
        {"--amount",       NULL,          @"amount"},
        {"--max-depth",    "--max_depth", @"max_depth"},
        {"--tab-id",       "--tab_id",    @"tab_id"},
        {"--scroll-count", "--scroll_count", @"scroll_count"},
        {"--timeout",      NULL,          @"timeout"},
        {"--width",        NULL,          @"viewport_width"},
        {"--height",       NULL,          @"viewport_height"},
    };
    for (size_t i = 0; i < sizeof(intOpts)/sizeof(intOpts[0]); i++) {
        NSString *raw = noff_find_arg(argc, argv, intOpts[i].primary);
        if (!raw && intOpts[i].alt) raw = noff_find_arg(argc, argv, intOpts[i].alt);
        if (raw) obj[intOpts[i].key] = @(raw.integerValue);
    }

    // Keywords: comma- or whitespace-separated list for get_cookies / scroll_and_collect.
    NSString *keywordsRaw = noff_find_arg(argc, argv, "--keywords");
    if (keywordsRaw) {
        NSCharacterSet *sep = [NSCharacterSet characterSetWithCharactersInString:@", \t\n"];
        NSMutableArray *tokens = [NSMutableArray array];
        for (NSString *t in [keywordsRaw componentsSeparatedByCharactersInSet:sep]) {
            NSString *trimmed = [t stringByTrimmingCharactersInSet:
                NSCharacterSet.whitespaceAndNewlineCharacterSet];
            if (trimmed.length > 0) [tokens addObject:trimmed];
        }
        if (tokens.count > 0) obj[@"keywords"] = tokens;
    }

    // Cookies: a JSON array of cookie objects for set_cookies. Either inline via
    // --cookies '<json>' or, to dodge busybox-ash shell mangling of the quotes /
    // braces / colons in the JSON, from a file via --cookies-file <path>
    // (mirrors `minis-config set --file`). Parse failures are surfaced as an
    // explicit error rather than silently dropped — a dropped array used to
    // reach set_cookies as empty and read as a confusing "array is empty".
    NSString *cookiesFile = noff_find_arg(argc, argv, "--cookies-file");
    NSString *cookiesRaw = noff_find_arg(argc, argv, "--cookies");
    if (cookiesFile) {
        NSError *readErr = nil;
        cookiesRaw = [NSString stringWithContentsOfFile:cookiesFile
                                               encoding:NSUTF8StringEncoding
                                                  error:&readErr];
        if (!cookiesRaw) {
            if (errOut) *errOut = [NSString stringWithFormat:
                @"--cookies-file: could not read '%@': %@",
                cookiesFile, readErr.localizedDescription ?: @"unknown error"];
            return nil;
        }
    }
    if (cookiesRaw) {
        NSData *data = [cookiesRaw dataUsingEncoding:NSUTF8StringEncoding];
        id parsed = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil;
        // Not JSON — accept a Netscape cookies.txt (curl/wget/browser export).
        NSArray *netscape = [parsed isKindOfClass:[NSArray class]] ? nil : parseNetscapeCookies(cookiesRaw);
        if ([parsed isKindOfClass:[NSArray class]]) {
            obj[@"cookies"] = parsed;
        } else if (netscape) {
            obj[@"cookies"] = netscape;
        } else {
            if (errOut) *errOut =
                @"--cookies (or --cookies-file content) must be a JSON ARRAY of cookie "
                 "objects, or a Netscape cookies.txt file. JSON object fields: name + "
                 "value (required); optional domain (defaults to current page host), "
                 "path (defaults to \"/\"), secure (bool), http_only (bool, alias "
                 "httpOnly), expires (unix seconds, alias expirationDate), sameSite. "
                 "e.g. '[{\"name\":\"sid\",\"value\":\"abc\",\"domain\":\".x.com\",\"secure\":true}]'. "
                 "If the shell is mangling the quotes, write the JSON array to a file "
                 "and pass --cookies-file <path>.";
            return nil;
        }
    }

    if (noff_has_flag(argc, argv, "--fuzzy")) obj[@"fuzzy"] = @YES;
    if (noff_has_flag(argc, argv, "--reset")) obj[@"reset"] = @YES;
    if (noff_has_flag(argc, argv, "--full-page") ||
        noff_has_flag(argc, argv, "--full_page") ||
        noff_has_flag(argc, argv, "--fullpage")) obj[@"full_page"] = @YES;

    return obj;
}

// ── Handler ──

static int browser_use_handler(int argc, char **argv,
                               int stdin_fd, int stdout_fd, int stderr_fd) {
    // Bare invocation → show the full help so users discover the command.
    if (argc <= 1 ||
        noff_has_flag(argc, argv, "--help") ||
        noff_has_flag(argc, argv, "-h")) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        return NOFF_EXIT_SUCCESS;
    }

    BOOL compact = noff_has_flag(argc, argv, "--compact");
    BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");
    BOOL withBase64 = noff_has_flag(argc, argv, "--with-base64");

    NSString *buildErr = nil;
    NSDictionary *inputDict = buildInputJson(argc, argv, &buildErr);
    if (!inputDict) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"execute",
                                            NOFF_ERR_INVALID_ARGS,
                                            buildErr ?: @"invalid arguments");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    NSError *jerr = nil;
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:inputDict options:0 error:&jerr];
    if (!jsonData) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"execute",
                                            NOFF_ERR_INTERNAL_ERROR,
                                            jerr.localizedDescription ?: @"failed to serialize input");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }
    NSString *jsonStr = [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];

    __block NSDictionary *resultDict = nil;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    // [T-browser-cli-reclaim-scope] execute returns a token naming this one
    // invocation, so a timeout below cancels only the call it abandons. The
    // old (session id, tab id) key matched every concurrent CLI call, since
    // the session id is process-wide and most commands name no tab.
    uint64_t invocation = [BrowserUseOffloadBridge executeWithJson:jsonStr
                                  withBase64:withBase64
                                  completion:^(NSDictionary *data) {
        resultDict = data;
        dispatch_semaphore_signal(sem);
    }];
    // Navigation alone allows up to 30s; screenshot + snapshot can add more.
    // Give the bridge a generous window.
    long waitErr = dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 90 * NSEC_PER_SEC));
    if (waitErr != 0 || resultDict == nil) {
        // [T-browser-cli-timeout-reclaim] OpenMinis#245. Giving up here used
        // to leave the action running natively. It kept holding the tab's
        // serial slot until the pool's 300s dead-tab ceiling, so for ~5
        // minutes every later command on that tab failed its slot wait. Tell
        // the bridge to cancel it now: the pool rebuilds the wedged tab and
        // frees the slot, so the next command starts on a fresh WebView.
        if (waitErr != 0) {
            [BrowserUseOffloadBridge cancelAndReclaimWithInvocation:invocation];
        }
        NSDictionary *err = noff_json_error(TOOL_NAME, @"execute",
                                            NOFF_ERR_INTERNAL_ERROR,
                                            waitErr != 0
                                                ? @"browser action timed out after 90s; the stuck action was cancelled and, if its tab had hung, the tab was replaced — run list_tabs, then retry"
                                                : @"browser action timed out after 90s");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    BOOL ok = [resultDict[@"success"] boolValue];
    NSString *action = inputDict[@"action"] ?: @"execute";
    if (ok) {
        NSDictionary *envelope = noff_json_envelope(TOOL_NAME, action, resultDict);
        noff_emit_json(stdout_fd, envelope, compact, quiet);
        return NOFF_EXIT_SUCCESS;
    } else {
        NSString *msg = resultDict[@"text"] ?: @"browser action failed";
        NSDictionary *err = noff_json_error(TOOL_NAME, action,
                                            NOFF_ERR_INTERNAL_ERROR, msg);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }
}

void browser_use_offload_register(void) {
    int err = native_offload_add_handler("minis-browser-use", browser_use_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/minis-browser-use");
        NSLog(@"NativeOffloads: minis-browser-use handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register minis-browser-use handler (err=%d)", err);
    }
}
