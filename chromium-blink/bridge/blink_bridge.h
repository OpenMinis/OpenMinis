// blink_bridge.h — C API between the OpenMinis iOS app and the embedded
// Chromium Blink engine (content_shell_framework.framework).
//
// All functions must be called on the main thread. Callbacks are invoked
// synchronously on the main thread; the pointer passed to the callback is
// valid only for the duration of the call (copy it immediately).
#ifndef BLINK_BRIDGE_H_
#define BLINK_BRIDGE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque handle to one Blink tab/view.
typedef struct BlinkBridgeView BlinkBridgeView;

// Callback invoked with a JSON string payload (see events below).
typedef void (*BlinkBridgeCallback)(void* ctx, const char* payload);

// One-time browser-process initialization. Must be called once, on the main
// thread, before creating any view. `bundle_path` and `tmp_path` are used for
// resource/log path hints (may be NULL). Returns 0 on success.
int BlinkBridgeInitialize(const char* bundle_path, const char* tmp_path);

// Create a Blink-rendered view of the given size. Returns an opaque handle,
// or NULL on failure. The view's UIView is returned by BlinkBridgeGetNativeView.
BlinkBridgeView* BlinkBridgeCreateView(int width, int height);

// Returns the UIView backing the view (borrowed — do not free).
void* BlinkBridgeGetNativeView(BlinkBridgeView* view);

// Resize the view (call on layout).
void BlinkBridgeSetViewFrame(BlinkBridgeView* view, int x, int y, int w, int h);

// Navigation.
void BlinkBridgeLoadURL(BlinkBridgeView* view, const char* url);
void BlinkBridgeGoBack(BlinkBridgeView* view);
void BlinkBridgeGoForward(BlinkBridgeView* view);
void BlinkBridgeReload(BlinkBridgeView* view);
void BlinkBridgeStop(BlinkBridgeView* view);

// Evaluate JavaScript in the page's main world. `cb` receives a JSON payload:
//   {"ok":true,"value":<json>} | {"ok":true} | {"ok":false,"error":"..."}
void BlinkBridgeEvaluateJS(BlinkBridgeView* view,
                           const char* js,
                           BlinkBridgeCallback cb,
                           void* ctx);

// Capture the current view as a PNG. `cb` receives {"ok":true,"png_base64":"..."}
// or {"ok":false,"error":"..."}.
void BlinkBridgeCaptureSnapshot(BlinkBridgeView* view,
                                BlinkBridgeCallback cb,
                                void* ctx);

// State getters (returned strings are static/borrowed — copy immediately).
const char* BlinkBridgeGetURL(BlinkBridgeView* view);
const char* BlinkBridgeGetTitle(BlinkBridgeView* view);
int BlinkBridgeIsLoading(BlinkBridgeView* view);
int BlinkBridgeCanGoBack(BlinkBridgeView* view);
int BlinkBridgeCanGoForward(BlinkBridgeView* view);

// Per-view UA override (applies to subsequent navigations).
void BlinkBridgeSetUserAgent(BlinkBridgeView* view, const char* ua);

// Install the event callback for a view (replaces any previous one).
// Event payloads:
//   {"event":"didStartProvisionalNavigation","url":"..."}
//   {"event":"didFinishNavigation","url":"..."}
//   {"event":"didFailNavigation","url":"...","error":"..."}
//   {"event":"loadingStateChanged","loading":true|false}
//   {"event":"renderProcessGone"}
void BlinkBridgeSetEventCallback(BlinkBridgeView* view,
                                 BlinkBridgeCallback cb,
                                 void* ctx);

// Destroy the view and its shell. Safe to call once.
void BlinkBridgeDestroyView(BlinkBridgeView* view);

// Last error message (static buffer, for diagnostics).
const char* BlinkBridgeLastError(void);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // BLINK_BRIDGE_H_
