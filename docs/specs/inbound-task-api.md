# Inbound Task API (Android)

A small HTTP API on the Android app so an external assistant can send a prompt and have Minis start it as a task. It is built into **release** builds. It is **off** until someone turns it on in the app.

This is not the debug server. The debug server is JSON-RPC, starts only in debug builds, and is left unchanged. See [debug-server-api.md](debug-server-api.md) and `DebugServer.kt`.

| | Debug server | Inbound task API |
|---|---|---|
| Builds | Debug only (`BuildConfig.DEV_TOOLS`) | Debug and release |
| Default | On, in debug builds | **Off** until the in-app switch |
| Port | 8321 in this document; the Android process binds **5321** | **9473** |
| Auth | Loopback (adb forward) is exempt; LAN sends `X-Minis-Token` or a bearer token | **Bearer token on every request**, including loopback |
| What it can do | Inspect the running app | Start one task from a prompt, and read that task's status |

The listener reuses the scheduled-task path (`minis-scheduled create --prompt` with target `new` or `follow-up`, then `minis-scheduled run --id`). A request becomes a one-shot task row and is fired immediately through `ScheduledAgentRunner`. The row is stored **disabled** with its single trigger already consumed, so AlarmManager does not fire the same prompt a second time.

Upstream context: [OpenMinis#488](https://github.com/OpenMinis/OpenMinis/issues/488) asks for a remote way to reach a phone that is running Minis (LAN or Tailscale, with a token). This API is that reachability for one action — start a task — not a browser UI. [OpenMinis#279](https://github.com/OpenMinis/OpenMinis/issues/279) asks to append an assistant message without running a model. This API does the opposite: the prompt is a real user turn and the model runs.

## Turning it on

1. Open Minis → **Settings** → **Network** → **Inbound Task API**.
2. Turn on **Allow inbound tasks**.
3. Copy the access token. Regenerating it replaces the token immediately; anything still using the old one gets `401`.
4. Leave Minis running. A silent notification (“Inbound task API is on”) keeps the process listening after you leave the app. Force-stopping Minis stops the listener until the next time the app process starts (opening the app, or a reboot / update while the switch is still on).

The token is a 32-byte hex string (64 characters). It lives in the app’s private preferences (`inbound_task_api`), which are excluded from Android backup and device transfer. It is not written to logcat or the in-app log.

## Port and bind

The listener binds **`0.0.0.0:9473`** (every IPv4 interface), not `127.0.0.1`.

An assistant on another machine reaches the phone over Tailscale or the LAN. Those packets arrive on the phone’s Tailscale address (the `100.x.y.z` address) or its Wi-Fi address. They are **not** delivered to `127.0.0.1`. A loopback-only socket would accept `adb forward` and nothing else, which is the wrong default for this API.

`0.0.0.0` still accepts loopback, so this works too:

```sh
adb forward tcp:9473 tcp:9473
```

Loopback is **not** exempt. `adb forward` needs the same bearer token as Tailscale.

Binding only the Tailscale address was rejected: that address changes, Tailscale is sometimes off, and a USB-forwarded client would be locked out at the same time. One stable port on every IPv4 interface, gated by the token, is the bind.

The cost of `0.0.0.0` is that any interface can attempt a connection, including a hostile Wi-Fi. The switch defaults off, every request needs the token, and the port should stay off the public internet. Prefer **Tailscale** or another private relay over a router port-forward. The token is a shared secret, not a substitute for keeping the listener off a public address. Use the phone’s Tailscale IPv4 (shown for that device in the Tailscale clients on your other machine) or its LAN IPv4. This socket is IPv4.

## Authentication

Every request, including `GET /v1/health` and requests to an unknown path, must send:

```http
Authorization: Bearer <token>
```

The scheme is case-insensitive. `X-Minis-Token`, `Basic`, and `?token=` in the URL are ignored, so a token placed in a query string is not treated as a login (and the listener does not log the request target). A missing or wrong token is `401` with `{"error":"unauthorized"}` and `WWW-Authenticate: Bearer realm="minis-inbound"`. The check hashes both strings and compares the digests in constant time (`MessageDigest.isEqual`). An empty stored token never matches.

There is no CORS preflight. This is an API for curl and other assistants, not a page in a browser.

## Limits

| Limit | Value | Response |
|---|---|---|
| Body | 256 KiB (`Content-Length`), rejected **before** the body is read | `413` `body_too_large` |
| Missing `Content-Length` on `POST` | — | `411` `length_required` |
| Prompt | 100,000 characters after trimming the ends | `400` `prompt_too_long` |
| Label | 120 characters, no control characters | `400` `label_too_long` or `bad_request` |
| Session id | 1–80 characters, `A-Za-z0-9_-` (a chat UUID fits) | `400` `session_invalid` |
| Header line | 8 KiB, at most 64 headers | `400` `bad_request` |

`Content-Type` may be omitted. If it is set, it must be `application/json` or `text/json`, and any charset must be UTF-8 (`415` otherwise). The body is decoded as UTF-8.

Logs from this listener record the task id, the target (`new` or `follow-up`), and the status word. They do not record the token, the `Authorization` header, or the prompt.

## Endpoints

Base URL: `http://<phone-ipv4>:9473`

### `GET /v1/health`

Confirms the listener is up. Does not start a task.

```json
{ "ok": true, "port": 9473, "auth": "bearer" }
```

### `POST /v1/tasks`

Starts a task and returns its id. The call returns when the chat exists and the prompt has been dispatched. The model keeps running after the response.

```json
{
  "prompt": "Summarize the notes in the workspace",
  "session": "6d0f1234-abcd-4000-8000-000000000001",
  "label": "from-laptop"
}
```

| Field | Required | Meaning |
|---|---|---|
| `prompt` | yes | The user message. Blank is rejected. Leading and trailing whitespace is removed. |
| `session` | no | Omit, or send `""` / `null`, for a **new chat**. A chat id appends the prompt to that chat (follow-up). An unknown id is `404` `session_not_found` and no task is kept. |
| `label` | no | Short name on the Scheduled Tasks row. Default is “Inbound”. |

`202 Accepted`:

```json
{
  "id": "0b1c2d3e-1111-4222-8333-444455556666",
  "sessionId": "6d0f1234-abcd-4000-8000-000000000001",
  "status": "running",
  "target": "follow-up"
}
```

`target` is `new` or `follow-up`. `id` is the scheduled-task id (the same id `minis-scheduled run --id` uses). `sessionId` is the chat the prompt was sent to. The prompt is not repeated in the response.

If the app cannot start the run, the response is `503` `not_started` or `unavailable` and the half-created row is deleted.

The task shows up under Scheduled Tasks as a disabled one-shot (“once after 0s”) because the inbound call **is** the run. Turning that row back on in the task editor uses the normal “enable a relative trigger” behavior and can schedule it again.

### `GET /v1/tasks/{id}`

Status of a task. `{id}` is the id from `POST`. Unknown id is `404`.

```json
{
  "id": "0b1c2d3e-1111-4222-8333-444455556666",
  "label": "from-laptop",
  "status": "completed",
  "target": "new",
  "sessionId": "a1b2c3d4-0000-4000-8000-000000000002",
  "ok": true
}
```

| `status` | Meaning |
|---|---|
| `running` | This process is in the agent turn |
| `accepted` | The row exists, this process is not in the turn, and no finished run is recorded yet (just dispatched, or the process died mid-run). `sessionId` may already be set |
| `completed` | The latest recorded run succeeded (`ok: true`) |
| `failed` | The latest recorded run did not succeed (`ok: false`) |

The prompt and the model’s text are not in this response. Open `sessionId` in the app, or use the debug server’s chat methods on a debug build, to read the transcript.

### Errors

```json
{ "error": "unauthorized" }
```

| HTTP | `error` |
|---|---|
| 400 | `bad_request`, `invalid_json`, `prompt_required`, `prompt_too_long`, `label_too_long`, `session_invalid` |
| 401 | `unauthorized` |
| 404 | `not_found`, `session_not_found` |
| 405 | `method_not_allowed` |
| 411 | `length_required` |
| 413 | `body_too_large` |
| 415 | `unsupported_media_type` |
| 503 | `unavailable`, `not_started` |

## Examples

Replace the host with the phone’s Tailscale or LAN IPv4, and the token with the one shown in Settings. Do not put the token in the URL.

```sh
export MINIS_HOST=100.64.0.2
export MINIS_TOKEN='paste-the-token-from-settings'

# Listener up?
curl -sS "http://${MINIS_HOST}:9473/v1/health" \
  -H "Authorization: Bearer ${MINIS_TOKEN}"

# New chat. Prints the task id and the new session id.
curl -sS -X POST "http://${MINIS_HOST}:9473/v1/tasks" \
  -H "Authorization: Bearer ${MINIS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Summarize the notes in the workspace","label":"from-laptop"}'

# Follow-up in an existing chat.
curl -sS -X POST "http://${MINIS_HOST}:9473/v1/tasks" \
  -H "Authorization: Bearer ${MINIS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Now turn that into a checklist","session":"PASTE_SESSION_ID"}'

# Status. Use the id from the POST response.
curl -sS "http://${MINIS_HOST}:9473/v1/tasks/PASTE_TASK_ID" \
  -H "Authorization: Bearer ${MINIS_TOKEN}"
```

Over USB, with the token still required:

```sh
adb forward tcp:9473 tcp:9473
curl -sS -X POST "http://127.0.0.1:9473/v1/tasks" \
  -H "Authorization: Bearer ${MINIS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Say hello in one sentence"}'
```

## Security

- Default off. Turning it on is an explicit action and shows a persistent notification.
- Bearer token on every request, including from this phone. Regenerating revokes the previous token at once.
- Constant-time compare of SHA-256 digests. The token is not logged, not returned by any endpoint, and not included in Android backup.
- Body size is capped from `Content-Length` before the bytes are stored. Prompts are not written to the listener’s log.
- Bind is every IPv4 interface so Tailscale and the LAN work. **Do not publish port 9473 on a public router.** Reach the phone through Tailscale (or another private relay). A public forward plus a stolen token is enough to spend model quota and run the agent’s tools.
- The listener can start the same agent a person can start from the composer, including tools. Treat the token like a password for that phone.
- No browser CORS. No loopback exemption (unlike the debug server).
