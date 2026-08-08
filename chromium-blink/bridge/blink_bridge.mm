// blink_bridge.mm — embed implementation of the blink_bridge C API.
//
// Compiled into content_shell_framework.framework (added to content/shell
// BUILD.gn as source_set("blink_bridge")). Embeds Chromium's browser process
// into a host app (OpenMinis): BlinkBridgeInitialize runs the content main
// (single-process, in-process renderer per Blinker Fluid's patches), and each
// BlinkBridgeCreateView creates a content::Shell whose WebContents view is
// handed to the host for embedding.

#import <UIKit/UIKit.h>

#include <memory>
#include <string>

#include "base/command_line.h"
#include "base/functional/bind.h"
#include "base/json/write_json.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/sys_string_conversions.h"
#include "base/strings/utf_string_conversions.h"
#include "base/values.h"
#include "content/public/app/content_main.h"
#include "content/public/app/content_main_runner.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_observer.h"
#include "content/shell/app/shell_main_delegate.h"
#include "content/shell/browser/shell.h"
#include "content/shell/browser/shell_content_browser_client.h"
#include "net/base/net_errors.h"
#include "third_party/blink/public/common/isolated_worlds/isolated_world_ids.h"
#include "third_party/blink/public/common/user_agent/user_agent_metadata.h"
#include "ui/gfx/geometry/size.h"
#include "url/gurl.h"
#include "url/url_constants.h"

#include "content/shell/bridge/blink_bridge.h"

namespace {

// Last diagnostic error.
std::string g_last_error;

void SetError(const char* message) {
  g_last_error = message ? message : "unknown";
  if (message) {
    NSLog(@"BlinkBridge: %s", message);
  }
}

// Synthesized command line. --blink-embed makes ShellBrowserMainParts skip
// auto-creating windows (host app creates shells on demand); --js-flags=--jitless
// keeps V8 in interpreter mode (stable on iOS, per Blinker Fluid).
const char* const kEmbedArgv[] = {
    "Minis",
    "--blink-embed",
    "--js-flags=--jitless",
    nullptr,
};
const int kEmbedArgc = 3;

std::unique_ptr<content::ContentMainRunner> g_main_runner;
std::unique_ptr<content::ShellMainDelegate> g_main_delegate;

// ---------------------------------------------------------------------------
// WebContentsObserver forwarding navigation events to the host app.
// ---------------------------------------------------------------------------

class BlinkBridgeObserver : public content::WebContentsObserver {
 public:
  BlinkBridgeObserver(content::WebContents* web_contents, BlinkBridgeView* view)
      : content::WebContentsObserver(web_contents), view_(view) {}

  void DidStartNavigation(
      content::NavigationHandle* navigation_handle) override {
    if (!navigation_handle->IsInMainFrame()) {
      return;
    }
    Emit("didStartProvisionalNavigation",
         navigation_handle->GetURL().spec().c_str());
  }

  void DidFinishNavigation(
      content::NavigationHandle* navigation_handle) override {
    if (!navigation_handle->IsInMainFrame()) {
      return;
    }
    if (navigation_handle->GetNetErrorCode() != net::OK) {
      std::string payload = "{\"event\":\"didFailNavigation\",\"url\":\"";
      payload += JsonEscape(navigation_handle->GetURL().spec());
      payload += "\",\"error\":\"";
      payload += JsonEscape(net::ErrorToString(navigation_handle->GetNetErrorCode()));
      payload += "\"}";
      EmitRaw(payload.c_str());
      return;
    }
    if (navigation_handle->HasCommitted()) {
      Emit("didFinishNavigation", navigation_handle->GetURL().spec().c_str());
    }
  }

  void DidStartLoading() override {
    EmitBool("loadingStateChanged", true);
  }

  void DidStopLoading() override {
    EmitBool("loadingStateChanged", false);
  }

  void RenderProcessGone(const content::ChildProcessTerminationInfo& info) override {
    EmitRaw("{\"event\":\"renderProcessGone\"}");
  }

 private:
  BlinkBridgeView* view_;

  static std::string JsonEscape(const std::string& input) {
    std::string out;
    for (char c : input) {
      switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
          if (static_cast<unsigned char>(c) < 0x20) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", c);
            out += buf;
          } else {
            out += c;
          }
      }
    }
    return out;
  }

  void Emit(const char* event, const char* url) {
    std::string payload = "{\"event\":\"";
    payload += event;
    payload += "\",\"url\":\"";
    payload += JsonEscape(url ? url : "");
    payload += "\"}";
    EmitRaw(payload.c_str());
  }

  void EmitBool(const char* event, bool value) {
    std::string payload = "{\"event\":\"";
    payload += event;
    payload += "\",\"loading\":";
    payload += value ? "true" : "false";
    payload += "}";
    EmitRaw(payload.c_str());
  }

  void EmitRaw(const char* payload) {
    if (!view_ || !view_->event_cb) {
      return;
    }
    view_->event_cb(view_->event_ctx, payload);
  }
};

}  // namespace

// ---------------------------------------------------------------------------
// Bridge view (one per tab).
// ---------------------------------------------------------------------------

struct BlinkBridgeView {
  content::Shell* shell = nullptr;
  UIView* native_view = nil;
  std::unique_ptr<BlinkBridgeObserver> observer;
  BlinkBridgeCallback event_cb = nullptr;
  void* event_ctx = nullptr;
  std::string pending_ua;
};

// ---------------------------------------------------------------------------
// C API
// ---------------------------------------------------------------------------

extern "C" {

int BlinkBridgeInitialize(const char* bundle_path, const char* tmp_path) {
  if (g_main_runner) {
    return 0;  // already initialized
  }

  @autoreleasepool {
    g_main_delegate = std::make_unique<content::ShellMainDelegate>();
    content::ContentMainParams params(g_main_delegate.get());
    params.argc = kEmbedArgc;
    params.argv = kEmbedArgv;
    g_main_runner = content::ContentMainRunner::Create();
    int rv = content::RunContentProcess(std::move(params), g_main_runner.get());
    if (rv != 0) {
      SetError("RunContentProcess failed");
      return rv;
    }
  }
  return 0;
}

BlinkBridgeView* BlinkBridgeCreateView(int width, int height) {
  content::ShellContentBrowserClient* client =
      content::ShellContentBrowserClient::Get();
  if (!client || !client->browser_context()) {
    SetError("BlinkBridgeCreateView: browser context not ready");
    return nullptr;
  }

  content::Shell* shell = content::Shell::CreateNewWindow(
      client->browser_context(), GURL(url::kAboutBlankURL), nullptr,
      gfx::Size(width, height));
  if (!shell || !shell->web_contents()) {
    SetError("BlinkBridgeCreateView: Shell::CreateNewWindow failed");
    return nullptr;
  }

  auto* view = new BlinkBridgeView();
  view->shell = shell;
  view->native_view = shell->GetContentView();
  if (!view->native_view) {
    delete view;
    SetError("BlinkBridgeCreateView: no native view");
    return nullptr;
  }
  view->native_view.frame =
      CGRectMake(0, 0, width > 0 ? width : 390, height > 0 ? height : 844);
  view->observer = std::make_unique<BlinkBridgeObserver>(
      shell->web_contents(), view);
  return view;
}

void* BlinkBridgeGetNativeView(BlinkBridgeView* view) {
  return view ? (__bridge void*)view->native_view : nullptr;
}

void BlinkBridgeSetViewFrame(BlinkBridgeView* view,
                             int x, int y, int w, int h) {
  if (!view || !view->native_view) {
    return;
  }
  view->native_view.frame = CGRectMake(x, y, w, h);
}

void BlinkBridgeLoadURL(BlinkBridgeView* view, const char* url) {
  if (!view || !view->shell || !url) {
    return;
  }
  GURL gurl(url);
  if (!gurl.is_valid()) {
    SetError("BlinkBridgeLoadURL: invalid URL");
    return;
  }
  view->shell->LoadURL(gurl);
}

void BlinkBridgeGoBack(BlinkBridgeView* view) {
  if (view && view->shell) {
    view->shell->GoBackOrForward(-1);
  }
}

void BlinkBridgeGoForward(BlinkBridgeView* view) {
  if (view && view->shell) {
    view->shell->GoBackOrForward(1);
  }
}

void BlinkBridgeReload(BlinkBridgeView* view) {
  if (view && view->shell) {
    view->shell->Reload();
  }
}

void BlinkBridgeStop(BlinkBridgeView* view) {
  if (view && view->shell && view->shell->web_contents()) {
    view->shell->web_contents()->Stop();
  }
}

void BlinkBridgeEvaluateJS(BlinkBridgeView* view,
                           const char* js,
                           BlinkBridgeCallback cb,
                           void* ctx) {
  if (!view || !view->shell || !view->shell->web_contents() || !js) {
    if (cb) {
      cb(ctx, "{\"ok\":false,\"error\":\"no web contents\"}");
    }
    return;
  }
  content::RenderFrameHost* frame =
      view->shell->web_contents()->GetPrimaryMainFrame();
  if (!frame) {
    if (cb) {
      cb(ctx, "{\"ok\":false,\"error\":\"no main frame\"}");
    }
    return;
  }

  std::u16string script = base::SysUTF8ToUTF16(js);
  frame->ExecuteJavaScriptForTests(
      script,
      base::BindOnce(
          [](BlinkBridgeCallback cb, void* ctx, base::Value result) {
            if (!cb) {
              return;
            }
            if (result.is_none()) {
              cb(ctx, "{\"ok\":true}");
              return;
            }
            std::string json;
            if (result.is_string()) {
              json = result.GetString();
            } else if (auto w = base::WriteJson(result)) {
              json = *w;
            } else {
              json = result.is_bool()
                         ? (result.GetBool() ? "true" : "false")
                         : (result.is_int() ? base::NumberToString(result.GetInt())
                                            : "\"<unserializable>\"");
            }
            std::string payload = "{\"ok\":true,\"value\":";
            payload += json;
            payload += "}";
            cb(ctx, payload.c_str());
          },
          cb, ctx),
      ISOLATED_WORLD_ID_GLOBAL);
}

void BlinkBridgeCaptureSnapshot(BlinkBridgeView* view,
                                BlinkBridgeCallback cb,
                                void* ctx) {
  if (!view || !view->native_view) {
    if (cb) {
      cb(ctx, "{\"ok\":false,\"error\":\"no view\"}");
    }
    return;
  }
  UIView* v = view->native_view;
  CGRect bounds = v.bounds;
  if (bounds.size.width < 1 || bounds.size.height < 1) {
    bounds = CGRectMake(0, 0, 390, 844);
  }
  UIGraphicsImageRenderer* renderer =
      [[UIGraphicsImageRenderer alloc] initWithSize:bounds.size];
  UIImage* image = [renderer imageWithActions:^(UIGraphicsImageRendererContext* ctx) {
    [v drawViewHierarchyInRect:bounds afterScreenUpdates:YES];
  }];
  NSData* png = UIImagePNGRepresentation(image);
  if (!png) {
    if (cb) {
      cb(ctx, "{\"ok\":false,\"error\":\"snapshot render failed\"}");
    }
    return;
  }
  NSString* b64 = [png base64EncodedStringWithOptions:0];
  if (cb) {
    std::string payload = "{\"ok\":true,\"png_base64\":\"";
    payload += b64.UTF8String ? b64.UTF8String : "";
    payload += "\"}";
    cb(ctx, payload.c_str());
  }
}

const char* BlinkBridgeGetURL(BlinkBridgeView* view) {
  if (!view || !view->shell || !view->shell->web_contents()) {
    return "";
  }
  const GURL& url = view->shell->web_contents()->GetVisibleURL();
  static std::string cached;
  cached = url.valid() ? url.spec() : "";
  return cached.c_str();
}

const char* BlinkBridgeGetTitle(BlinkBridgeView* view) {
  if (!view || !view->shell || !view->shell->web_contents()) {
    return "";
  }
  static std::string cached;
  cached = base::UTF16ToUTF8(view->shell->web_contents()->GetTitle());
  return cached.c_str();
}

int BlinkBridgeIsLoading(BlinkBridgeView* view) {
  if (!view || !view->shell || !view->shell->web_contents()) {
    return 0;
  }
  return view->shell->web_contents()->IsLoading() ? 1 : 0;
}

int BlinkBridgeCanGoBack(BlinkBridgeView* view) {
  if (!view || !view->shell || !view->shell->web_contents()) {
    return 0;
  }
  return view->shell->web_contents()->GetController().CanGoBack() ? 1 : 0;
}

int BlinkBridgeCanGoForward(BlinkBridgeView* view) {
  if (!view || !view->shell || !view->shell->web_contents()) {
    return 0;
  }
  return view->shell->web_contents()->GetController().CanGoForward() ? 1 : 0;
}

void BlinkBridgeSetUserAgent(BlinkBridgeView* view, const char* ua) {
  if (!view || !view->shell || !view->shell->web_contents() || !ua) {
    return;
  }
  blink::UserAgentOverride override_ua;
  override_ua.ua_string_override = ua;
  view->shell->web_contents()->SetUserAgentOverride(override_ua, true);
}

void BlinkBridgeSetEventCallback(BlinkBridgeView* view,
                                 BlinkBridgeCallback cb,
                                 void* ctx) {
  if (!view) {
    return;
  }
  view->event_cb = cb;
  view->event_ctx = ctx;
}

void BlinkBridgeDestroyView(BlinkBridgeView* view) {
  if (!view) {
    return;
  }
  view->observer.reset();
  if (view->shell) {
    view->shell->Close();
  }
  view->shell = nullptr;
  view->native_view = nil;
  delete view;
}

const char* BlinkBridgeLastError(void) {
  return g_last_error.c_str();
}

}  // extern "C"
