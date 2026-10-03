//
//  ISHKernel.h
//  MinisApp
//
//  Objective-C wrapper for iSH kernel initialization and control
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

extern NSNotificationName const ISHProcessExitedNotification;
extern NSNotificationName const ISHTerminalOutputNotification;

typedef void (^ISHOutputCallback)(NSData *data);
typedef void (^ISHCommandCompletionCallback)(NSString *output, NSError * _Nullable error);

@interface ISHKernel : NSObject

/// Shared instance
@property (class, readonly) ISHKernel *shared;

/// Whether the kernel has been booted
@property (nonatomic, readonly) BOOL isBooted;

/// Output callback for terminal output
@property (nonatomic, copy, nullable) ISHOutputCallback outputCallback;

/// Custom environment variables to inject into shell execution.
/// Set before calling executeCommand:. Keys and values are strings.
@property (nonatomic, copy, nullable) NSDictionary<NSString *, NSString *> *customEnvironment;

/// Initialize kernel and mount root filesystem
/// @param rootPath fakefs root directory path (containing data/ and meta.db)
/// @return 0 on success, negative error code on failure
- (int)bootWithRootPath:(NSString *)rootPath;

/// Execute a command (starts interactive shell)
/// @param command Command and arguments array, e.g. @[@"/bin/sh", @"-l"]
/// @return 0 on success, negative error code on failure
- (int)executeCommand:(NSArray<NSString *> *)command;

/// Execute a command and wait for completion (detects prompt to know when done)
/// @param commandString Command string to execute
/// @param timeout Timeout in seconds (0 for no timeout)
/// @param completion Completion callback with output or error
- (void)executeCommandAndWait:(NSString *)commandString
                      timeout:(NSTimeInterval)timeout
                   completion:(ISHCommandCompletionCallback)completion;

/// Send input to the terminal
/// @param data Input data (UTF-8 encoded string)
- (void)sendInput:(NSData *)data;

/// Send input string to the terminal
/// @param input Input string
- (void)sendInputString:(NSString *)input;

/// Set terminal window size
/// @param columns Number of columns (character width)
/// @param rows Number of rows (character height)
- (void)setTerminalSize:(int)columns rows:(int)rows;

/// Bind-mount an external host directory onto a fakefs path.
/// After this call, iSH processes accessing linuxPath will transparently
/// read/write files in hostPath. Parent directories must exist in meta.db.
/// @param linuxPath Absolute path in fakefs (e.g. "/var/minis/offloads")
/// @param hostPath  Absolute path on the iOS host filesystem
/// @return 0 on success, negative error code on failure
- (int)bindMountPath:(NSString *)linuxPath toHostPath:(NSString *)hostPath;

/// Bind-mount an external host directory with an explicit read-only flag.
/// When readOnly=YES, the top-level mount directory in meta.db is written
/// with mode 0555, so iSH's standard access_check rejects any create/unlink/
/// rename/rmdir inside the mount with EACCES. No special fakefs plumbing.
/// @param linuxPath Absolute path in fakefs (e.g. "/var/minis/mounts/Obsidian")
/// @param hostPath  Absolute path on the iOS host filesystem
/// @param readOnly  If YES, the mount is exposed as a read-only directory
/// @return 0 on success, negative error code on failure
- (int)bindMountPath:(NSString *)linuxPath toHostPath:(NSString *)hostPath readOnly:(BOOL)readOnly;

/// Remove a bind mount previously created with bindMountPath:toHostPath:
/// @param linuxPath The path previously passed to bindMountPath:toHostPath:
/// @return 0 on success, negative error code on failure
- (int)bindUnmountPath:(NSString *)linuxPath;

/// Re-read system DNS configuration and update /etc/resolv.conf inside iSH.
/// Call this when the network path changes (e.g. WiFi ↔ cellular switch).
- (void)refreshDns;

@end

/// One change event emitted by the fakefs bind-mount tracker. Mirrors
/// `struct fakefs_change_event` from deps/ish/fs/fake.h.
@interface ISHFakefsChangeEvent : NSObject
@property (nonatomic, readonly) NSString *linuxPath;
@property (nonatomic, readonly) int op;          // 0=write 1=unlink 2=rename 3=truncate
@property (nonatomic, readonly) int64_t timestampNs;
/// Opaque per-thread-group fs_context value captured at record time.
/// 0 if no handler is installed or the caller hasn't tagged its task group.
@property (nonatomic, readonly) uint64_t fsContext;
@end

@interface ISHKernel (FakefsChange)

/// Install a handler for fakefs bind-mount file change events.
///
/// The handler runs on a private serial dispatch queue (not the main
/// thread). It MUST NOT block — copy events off and dispatch to your
/// own queue/actor for processing. Producer side in iSH is non-blocking
/// and bounded; if the handler stalls, ring overflow drops oldest events
/// (visible via fakefsChangeDroppedCount).
///
/// Calling this multiple times replaces the handler. Pass nil to detach.
- (void)installFakefsChangeHandler:(void (^)(NSArray<ISHFakefsChangeEvent *> *batch))handler;

/// Number of events dropped due to ring overflow since boot (diagnostic).
- (uint64_t)fakefsChangeDroppedCount;

@end

#pragma mark - Path translate hook

/// Block-form path translate hook for fakefs. Bridges the C-level
/// fakefs_path_translate_hook_t. Return non-nil host absolute path to
/// override the default g_bind_mounts[] lookup; return nil to fall through.
///
/// `guestPath` is leading-slash normalized. `fsContext` is the opaque u64
/// stamped on the calling thread group — the host assigns its meaning.
///
/// Invoked on the calling iSH worker thread, on the hot fs path. MUST be
/// non-blocking and reentrant. Avoid any work beyond a hash lookup.
typedef NSString * _Nullable (^ISHPathTranslateHandler)(NSString *guestPath,
                                                       uint64_t fsContext);

@interface ISHKernel (PathTranslate)

/// Install a host-side hook consulted for every fakefs path translation
/// (open / stat / readdir / unlink / rename / rmdir / mkdir / native offload).
/// Pass nil to detach. Calling multiple times replaces the handler.
- (void)installPathTranslateHandler:(nullable ISHPathTranslateHandler)handler;

@end

/// Reverse path-translate hook: given a host APFS path, return its guest
/// path. Invoked by realfs_getpath() after F_GETPATH resolves a bind-mount
/// symlink to an out-of-fakefs location; without it, readdir entries under
/// hook-routed paths come back with the wrong path and fail meta.db inode
/// lookup. Return nil to fall through to the default behavior.
typedef NSString * _Nullable (^ISHPathReverseHandler)(NSString *hostPath);

@interface ISHKernel (PathReverse)

/// Install the reverse hook. Pass nil to detach.
- (void)installPathReverseHandler:(nullable ISHPathReverseHandler)handler;

@end

#pragma mark - Scheduler Hook

@interface ISHKernel (Scheduler)

/// Enable adaptive CPU throttle for background execution. Measures
/// actual tick duration and dynamically computes nanosleep to hit the
/// target duty cycle on any device.
/// @param dutyCycle Target CPU fraction (0.0–1.0, e.g. 0.6 = 60% CPU)
- (void)enableCPUThrottleWithDutyCycle:(float)dutyCycle;

/// Disable CPU throttle (foreground mode). The hook stays installed
/// but fast-paths on dutyCycle==0 (single atomic load).
- (void)disableCPUThrottle;

/// [T-ish-bg-cpu-governor] Start the closed-loop background CPU governor
/// (see docs/internal/ish-bg-cpu-governor-design.md): samples process-wide CPU at
/// 4 Hz into a 60s sliding window and dynamically drives the throttle
/// ratio (GREEN full speed / YELLOW proportional / RED hard brake) to keep
/// the window under the iOS background kill budget with margin. Call on
/// didEnterBackground. Idempotent.
- (void)beginBackgroundCPUGovernor;

/// Stop the governor and clear any applied throttle. Call on foreground
/// return. Idempotent.
- (void)endBackgroundCPUGovernor;

@end

NS_ASSUME_NONNULL_END
