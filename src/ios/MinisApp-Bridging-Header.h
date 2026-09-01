//
//  MinisApp-Bridging-Header.h
//  MinisApp
//
//  Bridging header for importing C code into Swift
//

#ifndef MinisApp_Bridging_Header_h
#define MinisApp_Bridging_Header_h

// CommonCrypto: PBKDF2 for the backup passphrase KDF (BackupCrypto.swift).
// CryptoKit has no PBKDF2 and the SDK ships no Argon2, so this is the
// dependency-free path sanctioned by docs/backup-restore-design.md §5.2.
#import <CommonCrypto/CommonKeyDerivation.h>

// iSH umbrella header (includes all necessary headers)
#include "ish/ish.h"

// Additional headers not in umbrella
#include "fs/devices.h"

// ISH Kernel Wrapper
#import "ISHKernel.h"

// ISH Shell Executor (pipe-based process execution)
#import "ISHShellExecutor.h"

// Native Offloads (native offload handlers for iSH commands)
#import "FFmpegOffload.h"
#import "NativeOffloadUtils.h"
#import "CalendarOffload.h"
#import "LocationOffload.h"
#import "WeatherOffload.h"
#import "VisionOffload.h"
#import "OpenOffload.h"
#import "ClipboardOffload.h"
#import "AlarmOffload.h"

// Auto Touch (debug-only simulated touch/scroll/input)
#import "AutoTouch.h"

// Hang Detector (main-thread hang detector with stack capture).
// Available in both Debug and Release; runtime-gated by HangDetector +start.
#import "HangDetector.h"

// Signal/exception handler — captures crash stacks to disk for next-launch report.
#import "CrashSignalHandler.h"

// Safe KVC wrapper — @try/@catch for private WebKit preference keys.
#import "SafeKVCSetTrue.h"

// NSFileHandle write wrapper that catches NSException (avoids process abort
// when the reader thread hits a closed pipe / invalid fd / full disk).
#import "FileHandleSafeWrite.h"
#import "ObjCExceptionCatcher.h"

// Reentrancy guard for -[NSTextContainer setSize:] to prevent TextKit1
// fillLayoutHole storms (0x8BADF00D scene-update watchdog).
#import "NSTextContainerSetSizeGuard.h"

// CppJieba Chinese word segmentation (ObjC++ wrapper)
#import "JiebaWrapper.h"

// Recorder for -[NSConcreteMutableAttributedString attribute:atIndex:
// effectiveRange:] — captures the last 10 queries into a tiny ring so
// the HangDetector dump can show what the typesetter was asking about
// at stall time.
#import "AttributeQueryRecorder.h"

#endif /* MinisApp_Bridging_Header_h */

// rclone static library (deps/frameworks/Rclone.xcframework) — see RcloneBridge.swift
#import "librclone.h"
