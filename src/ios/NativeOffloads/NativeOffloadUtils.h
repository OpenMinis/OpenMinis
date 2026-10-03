//
//  NativeOffloadUtils.h
//  MinisApp
//
//  Shared utilities for native offload CLI tools.
//  Provides arg parsing, JSON envelope construction, date parsing,
//  and output helpers used by all apple-* commands.
//

#ifndef NativeOffloadUtils_h
#define NativeOffloadUtils_h

#import <Foundation/Foundation.h>

// ── Error codes ──
extern NSString *const NOFF_ERR_AUTHORIZATION_DENIED;
extern NSString *const NOFF_ERR_AUTHORIZATION_NOT_DETERMINED;
extern NSString *const NOFF_ERR_NOT_AVAILABLE;
extern NSString *const NOFF_ERR_INVALID_ARGS;
extern NSString *const NOFF_ERR_NO_DATA;
extern NSString *const NOFF_ERR_INTERNAL_ERROR;

// ── Exit codes ──
enum {
    NOFF_EXIT_SUCCESS     = 0,
    NOFF_EXIT_ERROR       = 1,
    NOFF_EXIT_INVALID_ARGS = 2,
    NOFF_EXIT_AUTH_DENIED = 3,
    NOFF_EXIT_NOT_AVAILABLE = 4,
};

// ── Argument helpers ──

/// Find the value following a named argument (e.g. --start <value>).
/// Returns nil if not found.
NSString *_Nullable noff_find_arg(int argc, char **argv, const char *name);

/// Check if a flag is present (e.g. --compact, -q, --help).
BOOL noff_has_flag(int argc, char **argv, const char *name);

/// Get the subcommand (first non-flag argument after argv[0]).
/// Returns nil if no subcommand found.
NSString *_Nullable noff_get_subcommand(int argc, char **argv);

/// Collect all positional arguments (non-flag, non-option-value args after subcommand).
NSArray<NSString *> *noff_positional_args(int argc, char **argv);

// ── Date parsing ──

/// Parse a date string: ISO 8601, relative (-7d, -2h, -30m), or --today.
/// Returns nil on parse failure.
NSDate *_Nullable noff_parse_date(NSString *str);

/// Format a date as ISO 8601 with timezone.
NSString *noff_format_date(NSDate *date);

// ── All-day event helpers ──  [T-calendar-all-day]

/// YES when `str` is exactly a calendar date with no time component
/// (`YYYY-MM-DD`, 10 characters). A caller that receives a bare date for an
/// event's --start/--end treats the event as all-day: EventKit stores an
/// all-day event by its day range, and a bare date carries no time to lose.
BOOL noff_is_date_only_string(NSString *_Nullable str);

/// Normalise an inclusive [start, end] pair to EventKit's all-day convention in
/// the local calendar: `*outStart` is 00:00:00 of start's day, `*outEnd` is
/// 23:59:59 of end's day. An end before start collapses to a single day.
/// Computed with calendar arithmetic (not `+ 86399`) so a DST transition inside
/// the range cannot shift the boundary onto the wrong day.
void noff_all_day_bounds(NSDate *start, NSDate *end,
                         NSDate *_Nonnull *_Nonnull outStart,
                         NSDate *_Nonnull *_Nonnull outEnd);

// ── JSON output ──

/// Build a success envelope: {ok:true, tool, action, data, timestamp}.
NSDictionary *noff_json_envelope(NSString *tool, NSString *action, id data);

/// Build an error envelope: {ok:false, tool, action, error:{code,message}, timestamp}.
NSDictionary *noff_json_error(NSString *tool, NSString *action,
                               NSString *code, NSString *message);

/// Serialize dict to JSON and write to fd.
/// If compact=YES, no whitespace. If quiet=YES, emit only the "data" field.
void noff_emit_json(int fd, NSDictionary *dict, BOOL compact, BOOL quiet);

// ── Help output ──

/// Write a help string to stderr_fd.
void noff_emit_help(int stderr_fd, NSString *helpText);

// ── Main thread dispatch ──

/// Synchronously dispatch a block on the main thread and return the result.
/// Safe to call from any thread. If already on main thread, executes directly.
///
/// WARNING: this parks the CALLING thread on the main queue. If the block calls
/// a system API that itself blocks (XPC to a daemon, dispatch_sync into another
/// subsystem), the main thread is held for the whole duration and the app is
/// killed by the 10s scene-update watchdog (0x8BADF00D). Only use it for work
/// that genuinely requires the main thread — UIKit views — and that cannot
/// block. For anything else prefer running on the offload's own thread, or
/// `noff_dispatch_main_sync_timeout` when a main-thread hop is unavoidable.
id _Nullable noff_dispatch_main_sync(id _Nullable (^_Nonnull block)(void));

/// Bounded-wait variant: dispatches `block` to the main queue and waits at most
/// `timeoutSeconds`. Returns the block's result, or `nil` if the wait expired
/// (in which case `timedOut`, when non-NULL, is set to YES and the block may
/// still run later — it must therefore not capture anything it mutates
/// unsafely, and callers must treat the result as best-effort).
///
/// Exists so an unresponsive system daemon degrades into a null result instead
/// of consuming the whole watchdog budget and killing the process.
id _Nullable noff_dispatch_main_sync_timeout(NSTimeInterval timeoutSeconds,
                                              BOOL *_Nullable timedOut,
                                              id _Nullable (^_Nonnull block)(void));

// ── Guest stub creation ──

/// Create a stub executable in the guest filesystem so the shell can
/// find the command via PATH. Call from *_offload_register() after
/// registering the handler.  `guest_path` is e.g. "/usr/local/bin/apple-clipboard".
/// Safe to call multiple times; existing files are left untouched.
void noff_ensure_guest_stub(const char *guest_path);

// ── Path resolution ──

/// Convert a guest (Linux) absolute path to the corresponding host (iOS) path.
/// e.g. "/var/minis/offloads" → "…/Documents/alpine-rootfs/data/var/minis/offloads"
/// Returns nil if the path is empty.
NSString *_Nullable noff_resolve_host_path(NSString *guestPath);

// ── Read stdin ──

/// Read all available data from stdin_fd (non-blocking, up to 1MB).
/// Returns nil if stdin_fd < 0 or no data.
NSString *_Nullable noff_read_stdin(int stdin_fd);

// ── ObjC exception safety ──

/// Execute a block, catching any ObjC NSException. Returns YES if no exception.
/// Use from Swift to safely call UIKit methods that may throw NSInternalInconsistencyException.
BOOL noff_try_objc(void (NS_NOESCAPE ^_Nonnull block)(void));

#endif /* NativeOffloadUtils_h */
