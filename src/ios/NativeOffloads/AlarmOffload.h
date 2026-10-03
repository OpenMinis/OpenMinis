//
//  AlarmOffload.h
//  MinisApp
//
//  Native offload handler for `apple-alarm` — AlarmKit (iOS 26+).
//

#ifndef AlarmOffload_h
#define AlarmOffload_h

/// Register the apple-alarm native handler.
void alarm_offload_register(void);

/// True when AlarmKit can actually be entered on this process.
///
/// `#available(iOS 26.0, *)` is NOT enough: an iPad build running in
/// compatibility mode on visionOS reports a mapped iOS version >= 26 and
/// passes that check, but AlarmKit does not exist there, and merely
/// referencing one of its types aborts in swift_getTypeByMangledName.
/// Every entry into AlarmKit-typed Swift — the shell command AND the app's
/// own alarm UI — must pass this first. Cached after the first call.
BOOL alarmkit_is_usable(void);

#endif /* AlarmOffload_h */
