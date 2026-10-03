#!/usr/bin/env python3
"""minis-mcp-cli — MCP client CLI for the Minis agent (iSH / PRoot).

Subcommands:
  list [--all] [--pretty]                       list configured servers
  tools <server> [--refresh] [--pretty]         list a server's tools
  refresh <server> [--pretty]                   force-reconnect + re-list tools
  info <server> [--pretty]                      show a server's config
  ping <server> [--pretty]                      reachability check
  call <server> <tool> ['{...}'] [--input '{}'] [k=v ...] invoke a tool
  add --name N (--url U [--header "K: V"] | --command C [--args "..."] [--env "K=V"]) [--note ...]
      [--oauth-client-id ID --oauth-auth-endpoint URL --oauth-token-endpoint URL
       [--oauth-client-secret S] [--oauth-scopes "..."] [--oauth-redirect-uri URI]]
  remove <server>
  enable <server>
  disable <server>
  shutdown                                      stop the background daemon

All structured output is JSON on stdout (use --pretty for indentation).
Errors print the unified envelope {"error","code","server"} and exit non-zero.
Diagnostics go to /var/minis/mcp-servers/mcp-cli.log, never to stdout.

list / tools / ping / call run through a self-forked daemon that keeps MCP
server connections warm (per-server 10-minute idle TTL); the first such call
spawns the daemon, later calls reuse it. IPC is 127.0.0.1 loopback TCP (iSH's
fakefs cannot host an AF_UNIX socket), with the daemon's ephemeral port
published in /tmp/minis-mcp-daemon.port. info / add / remove / enable / disable
stay pure-local (servers.json only) and never touch the daemon.
"""

import json
import os
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from transport.http import MCPError  # noqa: E402
from utils import config  # noqa: E402

LOG_PATH = "/var/minis/mcp-servers/mcp-cli.log"

PID_FILE = "/tmp/minis-mcp-daemon.pid"
PORT_FILE = "/tmp/minis-mcp-daemon.port"  # daemon publishes its 127.0.0.1 port here
LOCK_FILE = "/tmp/minis-mcp-daemon.lock"  # cold-start fork guard (one winner forks)
CONN_TIMEOUT = 310.0  # socket recv timeout, slightly above the 300s RPC timeout
LOCK_STALE_SECONDS = 12.0  # reclaim a cold-start lock older than this (crashed start)


def _log(msg):
    try:
        os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    except OSError:
        pass


def _emit(obj, pretty):
    if pretty:
        print(json.dumps(obj, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(obj, ensure_ascii=False))


def _fail(message, code, server=None, pretty=False):
    _log("ERROR [%s] %s (server=%s)" % (code, message, server))
    _emit({"error": message, "code": code, "server": server}, pretty)
    sys.exit(1)


def _require_server(name, pretty):
    server = config.get_server(name)
    if server is None:
        _fail("server not found: %s" % name, "NOT_FOUND", name, pretty)
    return server


# --- daemon lifecycle + IPC -------------------------------------------------

def _read_daemon_port():
    """Return the daemon's published 127.0.0.1 port, or None if absent/bad."""
    try:
        with open(PORT_FILE, "r", encoding="utf-8") as f:
            return int(f.read().strip())
    except (ValueError, OSError):
        return None


def daemon_alive():
    """True if the daemon's port file is present and its recorded PID is live."""
    if not os.path.exists(PORT_FILE) or not os.path.exists(PID_FILE):
        return False
    try:
        with open(PID_FILE, "r", encoding="utf-8") as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)  # signal 0: liveness probe only
        return True
    except (ValueError, OSError):
        return False


def _wait_for_port(deadline):
    """Block until the daemon publishes its port file, or the deadline passes.
    Returns True if the port appeared."""
    while time.time() < deadline:
        if os.path.exists(PORT_FILE):
            time.sleep(0.05)  # let bind()+listen()+port publish settle
            return True
        time.sleep(0.1)
    return False


def _acquire_cold_start_lock():
    """Atomically claim the right to fork the daemon. Returns the lock fd on
    success, or None if another caller already holds it (this caller should just
    wait for the winner's port file). A lock older than LOCK_STALE_SECONDS is
    reclaimed so a crashed cold-start can't deadlock every future call."""
    try:
        return os.open(LOCK_FILE, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        pass
    try:
        age = time.time() - os.stat(LOCK_FILE).st_mtime
    except OSError:
        age = 0
    if age > LOCK_STALE_SECONDS:
        # Stale lock from a cold-start that died before publishing the port.
        try:
            os.unlink(LOCK_FILE)
        except OSError:
            pass
        try:
            return os.open(LOCK_FILE, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except OSError:
            return None
    return None


def maybe_start_daemon():
    """Fork the daemon if it isn't running. A cold-start lock ensures only one
    of N concurrent callers forks; the rest wait for that daemon's port file.
    The forking parent waits up to 8s for the port file, then returns. The child
    becomes the daemon and never returns to CLI logic."""
    if daemon_alive():
        return
    # Clear a stale port file left by a daemon that exited uncleanly so we don't
    # connect to a dead/recycled port.
    if os.path.exists(PORT_FILE):
        try:
            os.unlink(PORT_FILE)
        except OSError:
            pass

    lock_fd = _acquire_cold_start_lock()
    if lock_fd is None:
        # Another caller is forking the daemon; just wait for its port file.
        if not _wait_for_port(time.time() + 8):
            _log("waited for peer-started daemon but no port within 8s")
        return

    pid = os.fork()
    if pid > 0:
        # Winner parent: wait for our child's daemon to publish, then release the
        # lock so future cold-starts (after this daemon exits) can fork again.
        if not _wait_for_port(time.time() + 8):
            _log("daemon did not start within 8s; running without daemon")
        os.close(lock_fd)
        try:
            os.unlink(LOCK_FILE)
        except OSError:
            pass
        return

    # Child: detach and become the daemon. Never fall through to CLI logic.
    # The inherited lock_fd is closed by the closerange() sweep below; the parent
    # owns unlinking LOCK_FILE.
    try:
        os.setsid()
        devnull = open(os.devnull, "r+")
        os.dup2(devnull.fileno(), sys.stdin.fileno())
        os.dup2(devnull.fileno(), sys.stdout.fileno())
        os.dup2(devnull.fileno(), sys.stderr.fileno())
        # Close every other inherited fd. The CLI is launched from a shell whose
        # stdout is a pipe the caller reads until EOF; if the long-lived daemon
        # keeps that inherited pipe open, the caller never sees EOF and hangs
        # forever even though the CLI already printed its result and exited.
        # setsid() detaches the TTY but does NOT close fds, so do it explicitly.
        # Cap the range so we don't iterate a huge SC_OPEN_MAX; the daemon opens
        # its listening socket later, well after this point.
        try:
            soft_max = os.sysconf("SC_OPEN_MAX")
        except (ValueError, OSError, AttributeError):
            soft_max = 1024
        max_fd = min(soft_max if soft_max and soft_max > 0 else 1024, 4096)
        dn = devnull.fileno()
        if dn >= 3:
            os.closerange(3, dn)
            os.closerange(dn + 1, max_fd)
        else:
            os.closerange(3, max_fd)
        with open(PID_FILE, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
        import logging
        daemon_log = "/var/minis/mcp-servers/mcp-daemon.log"
        try:
            os.makedirs(os.path.dirname(daemon_log), exist_ok=True)
        except OSError:
            pass
        logging.basicConfig(
            filename=daemon_log,
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(message)s",
        )
        from daemon import DaemonServer
        DaemonServer(PORT_FILE, PID_FILE).run()
    except Exception as exc:  # noqa: BLE001 - log; the child must not leak out
        _log("daemon init failed: %s" % exc)
    finally:
        os._exit(0)


def send_to_daemon(request, timeout=CONN_TIMEOUT):
    """Send one newline-delimited JSON request over 127.0.0.1, return the parsed
    reply dict."""
    port = _read_daemon_port()
    if port is None:
        return {"ok": False, "error": "daemon not running", "code": "NO_DAEMON"}
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect(("127.0.0.1", port))
        sock.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode())
        buf = b""
        while b"\n" not in buf:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        if not buf.strip():
            return {"ok": False, "error": "empty daemon reply", "code": "NO_DAEMON"}
        return json.loads(buf.split(b"\n")[0])
    except ConnectionRefusedError:
        return {"ok": False, "error": "daemon not running", "code": "NO_DAEMON"}
    except socket.timeout:
        return {"ok": False, "error": "daemon request timed out", "code": "TIMEOUT"}
    except OSError as exc:
        return {"ok": False, "error": str(exc), "code": "NO_DAEMON"}
    finally:
        try:
            sock.close()
        except OSError:
            pass


def call_daemon(request, pretty):
    """Ensure the daemon is up, send the request, unwrap into _emit/_fail. One
    retry if the daemon wasn't ready yet right after a fresh fork."""
    maybe_start_daemon()
    resp = send_to_daemon(request)
    if not resp.get("ok") and resp.get("code") == "NO_DAEMON":
        time.sleep(0.5)
        resp = send_to_daemon(request)
    if not resp.get("ok"):
        _fail(resp.get("error", "daemon error"), resp.get("code", "MCP_ERROR"),
              request.get("server"), pretty)
    return resp.get("result")


# --- argument helpers -------------------------------------------------------

def _pop_flag(args, name):
    """Remove a boolean flag; return True if present."""
    if name in args:
        args.remove(name)
        return True
    return False


def _pop_opt(args, name):
    """Remove `--name value` and return value, or None. Repeatable callers use
    _pop_opt_all."""
    if name in args:
        i = args.index(name)
        if i + 1 < len(args):
            val = args[i + 1]
            del args[i:i + 2]
            return val
        del args[i:i + 1]
    return None


def _pop_opt_all(args, name):
    """Remove every `--name value` and return the list of values."""
    out = []
    while name in args:
        v = _pop_opt(args, name)
        if v is None:
            break
        out.append(v)
    return out


# --- subcommands ------------------------------------------------------------

def cmd_list(args, pretty):
    show_all = _pop_flag(args, "--all")
    result = call_daemon({"cmd": "list", "all": show_all}, pretty)
    _emit(result, pretty)


def cmd_info(args, pretty):
    name = args[0] if args else None
    if not name:
        _fail("usage: info <server>", "PARSE_ERROR", None, pretty)
    server = _require_server(name, pretty)
    _emit({"name": name, "config": server}, pretty)


def cmd_tools(args, pretty, force_refresh=False):
    # [T-mcp-tools-refresh] `tools --refresh` / the `refresh` alias evict the
    # daemon's live session first, so the tools list reflects tools the remote
    # server added after we first connected (fresh initialize + tools/list).
    refresh = _pop_flag(args, "--refresh") or force_refresh
    name = args[0] if args else None
    if not name:
        _fail("usage: tools <server> [--refresh]", "PARSE_ERROR", None, pretty)
    result = call_daemon({"cmd": "tools", "server": name, "refresh": refresh}, pretty)
    _emit(result, pretty)


def cmd_ping(args, pretty):
    name = args[0] if args else None
    if not name:
        _fail("usage: ping <server>", "PARSE_ERROR", None, pretty)
    result = call_daemon({"cmd": "ping", "server": name}, pretty)
    _emit(result, pretty)


def _looks_like_json(token):
    """A positional argument that is meant as a JSON payload: an object, or an
    array passed by mistake (rejected below with a clear message)."""
    t = token.strip()
    return (t.startswith("{") and t.endswith("}")) or (t.startswith("[") and t.endswith("]"))


def _parse_json_object(raw, label, name, pretty):
    """Parse `raw` as a JSON object or fail with a PARSE_ERROR naming `label`."""
    try:
        parsed = json.loads(raw)
    except ValueError as exc:
        _fail("invalid %s '%s': %s" % (label, raw, exc), "PARSE_ERROR", name, pretty)
    if not isinstance(parsed, dict):
        _fail("%s must be a JSON object {...} (got %s)" % (label, type(parsed).__name__),
              "PARSE_ERROR", name, pretty)
    return parsed


def cmd_call(args, pretty):
    """[GH#292] Arguments come from, in order: a positional JSON object OR
    --input (not both), then key=value pairs that extend / override it. The
    positional form is what users and agents reach for first
    (`call <server> <tool> '{"query":"x"}'`); it used to fail with
    "unexpected argument". It is detected before the key=value loop, so a JSON
    payload containing '=' (a URL query) is never split as a pair."""
    if len(args) < 2:
        _fail("usage: call <server> <tool> ['{\"k\":\"v\"}'] [--input '{}'] [key=value ...]. "
              "Run 'minis-mcp-cli --help' for examples.", "PARSE_ERROR", None, pretty)
    name, tool = args[0], args[1]
    rest = args[2:]
    raw_input = _pop_opt(rest, "--input")
    pos = next((i for i, token in enumerate(rest) if _looks_like_json(token)), None)
    raw_pos = rest.pop(pos).strip() if pos is not None else None
    if raw_pos is not None and raw_input:
        _fail("cannot specify both positional JSON and --input. Use one or the other.",
              "PARSE_ERROR", name, pretty)
    arguments = {}
    if raw_input:
        arguments.update(_parse_json_object(raw_input, "--input JSON", name, pretty))
    if raw_pos is not None:
        arguments.update(_parse_json_object(raw_pos, "positional JSON argument", name, pretty))
    # key=value pairs extend / override the JSON object.
    for token in rest:
        if "=" in token:
            k, v = token.split("=", 1)
            arguments[k] = v
        else:
            _fail("unexpected argument: %s. Expected a JSON object '{\"...\"}' or a key=value pair. "
                  "Example: minis-mcp-cli call %s %s '{\"query\":\"test\"}'" % (token, name, tool),
                  "PARSE_ERROR", name, pretty)
    result = call_daemon({"cmd": "call", "server": name, "tool": tool, "args": arguments}, pretty)
    _emit(result, pretty)


def cmd_shutdown(args, pretty):
    """Stop the background daemon (no-op if it isn't running)."""
    if not daemon_alive():
        _emit({"shutdown": False, "running": False}, pretty)
        return
    resp = send_to_daemon({"cmd": "shutdown"}, timeout=10)
    if not resp.get("ok"):
        _fail(resp.get("error", "shutdown failed"), resp.get("code", "MCP_ERROR"), None, pretty)
    _emit(resp.get("result", {"shutdown": True}), pretty)


def cmd_add(args, pretty):
    name = _pop_opt(args, "--name")
    if not name:
        _fail("add requires --name", "PARSE_ERROR", None, pretty)
    url = _pop_opt(args, "--url")
    command = _pop_opt(args, "--command")
    note = _pop_opt(args, "--note")
    headers_raw = _pop_opt_all(args, "--header")
    args_raw = _pop_opt(args, "--args")
    env_raw = _pop_opt_all(args, "--env")
    startup_timeout_raw = _pop_opt(args, "--startup-timeout")
    # [T-mcp-cli-oauth-flags] Static OAuth config (HTTP servers). Any of the
    # three core flags marks the server as OAuth; the trio is then required
    # so a half-configured server fails at add-time, not at authorize-time.
    oauth_client_id = _pop_opt(args, "--oauth-client-id")
    oauth_client_secret = _pop_opt(args, "--oauth-client-secret")
    oauth_auth_endpoint = _pop_opt(args, "--oauth-auth-endpoint")
    oauth_token_endpoint = _pop_opt(args, "--oauth-token-endpoint")
    oauth_scopes = _pop_opt(args, "--oauth-scopes")
    oauth_redirect = _pop_opt(args, "--oauth-redirect-uri")

    server = {"enabled": True}
    if note:
        server["note"] = note
    if startup_timeout_raw is not None:
        # Validate up front so a bad value is rejected at add-time (AI-visible)
        # rather than silently ignored later. Store the resolved integer as the
        # primary field (resolve_* already coerced/validated it).
        resolved, warning = config.resolve_startup_timeout(
            {"startupTimeoutSeconds": startup_timeout_raw})
        if warning:
            _fail("--startup-timeout: %s" % warning, "PARSE_ERROR", name, pretty)
        server["startupTimeoutSeconds"] = resolved
    if url:
        server["url"] = url
        headers = {}
        for h in headers_raw:
            if ":" in h:
                k, v = h.split(":", 1)
                headers[k.strip()] = v.strip()
        if headers:
            server["headers"] = headers
    elif command:
        server["command"] = command
        if args_raw:
            server["args"] = args_raw.split()
        env = {}
        for e in env_raw:
            if "=" in e:
                k, v = e.split("=", 1)
                env[k] = v
        if env:
            server["env"] = env
    else:
        _fail("add requires --url or --command", "PARSE_ERROR", name, pretty)

    # [T-mcp-cli-oauth-flags] Assemble the non-secret oauth object (schema
    # mirrors MCPStore.swift's MCPOAuthConfig; mode is always "static" here —
    # dynamic discovery/DCR is a future native-side feature).
    wants_oauth = any(v is not None for v in
                      (oauth_client_id, oauth_auth_endpoint, oauth_token_endpoint))
    seeded_secret = False
    if wants_oauth:
        if not url:
            _fail("OAuth flags apply to HTTP servers only (--url)", "PARSE_ERROR", name, pretty)
        missing = [flag for flag, v in (
            ("--oauth-client-id", oauth_client_id),
            ("--oauth-auth-endpoint", oauth_auth_endpoint),
            ("--oauth-token-endpoint", oauth_token_endpoint),
        ) if not v]
        if missing:
            _fail("OAuth config incomplete, missing: %s" % ", ".join(missing),
                  "PARSE_ERROR", name, pretty)
        oauth = {
            "mode": "static",
            "clientId": oauth_client_id,
            "authorizationEndpoint": oauth_auth_endpoint,
            "tokenEndpoint": oauth_token_endpoint,
        }
        if oauth_scopes:
            oauth["scopes"] = oauth_scopes
        if oauth_redirect:
            oauth["redirectURI"] = oauth_redirect
        server["oauth"] = oauth
        # Client Secret must NOT enter servers.json (it syncs via iCloud).
        # The Keychain is the authority, but the CLI runs in the guest and
        # cannot write it — so seed a handoff file next to the token bridge
        # (<oauth-dir>/<name>.secret, chmod 600, never synced). The native
        # side imports it into the Keychain and deletes the file the next
        # time the server's edit form is opened (or Authorize is tapped).
        if oauth_client_secret:
            try:
                secret_dir = os.path.join(config.CONFIG_DIR, "oauth")
                os.makedirs(secret_dir, exist_ok=True)
                secret_path = os.path.join(secret_dir, "%s.secret" % name)
                with open(secret_path, "w", encoding="utf-8") as f:
                    f.write(oauth_client_secret)
                try:
                    os.chmod(secret_path, 0o600)
                except OSError:
                    pass
                seeded_secret = True
            except OSError as exc:
                _fail("failed to seed client secret: %s" % exc, "MCP_ERROR", name, pretty)
    elif oauth_client_secret or oauth_scopes or oauth_redirect:
        _fail("OAuth flags require --oauth-client-id, --oauth-auth-endpoint and "
              "--oauth-token-endpoint", "PARSE_ERROR", name, pretty)

    config.upsert_server(name, server)
    out = {"added": name, "config": server}
    if wants_oauth:
        out["oauth_note"] = (
            "OAuth config saved%s. The interactive PKCE login cannot run from "
            "the CLI — open Minis Settings > MCP Integrations > %s and tap "
            "Authorize to complete sign-in."
            % (" (client secret seeded for the app to import)" if seeded_secret else "",
               name))
    _emit(out, pretty)


def cmd_remove(args, pretty):
    name = args[0] if args else None
    if not name:
        _fail("usage: remove <server>", "PARSE_ERROR", None, pretty)
    if not config.remove_server(name):
        _fail("server not found: %s" % name, "NOT_FOUND", name, pretty)
    _emit({"removed": name}, pretty)


def cmd_set_enabled(args, pretty, enabled):
    name = args[0] if args else None
    if not name:
        _fail("usage: %s <server>" % ("enable" if enabled else "disable"), "PARSE_ERROR", None, pretty)
    if not config.set_enabled(name, enabled):
        _fail("server not found: %s" % name, "NOT_FOUND", name, pretty)
    _emit({"server": name, "enabled": enabled}, pretty)


USAGE = """minis-mcp-cli — MCP (Model Context Protocol) client for the Minis agent.

Usage: minis-mcp-cli <command> [args] [--pretty]

Commands:
  list [--all]                          List configured servers (--all includes disabled).
  tools <server> [--refresh]            List a server's tools. --refresh forces a
                                        reconnect first, picking up tools the
                                        remote added after we last connected.
  refresh <server>                      Alias for `tools <server> --refresh`.
  info <server>                         Show a server's stored config.
  ping <server>                         Reachability check (initialize handshake).
  call <server> <tool> ['{"k":"v"}'] [--input '{}'] [key=value ...]
                                        Invoke a tool. Pass the arguments as a JSON
                                        object (directly or via --input, not both)
                                        and/or key=value pairs; trailing key=value
                                        pairs extend/override the JSON object.
  add --name <n> --url <url> [--header "K: V" ...] [--note "..."]
  add --name <n> --command <cmd> [--args "..."] [--env "K=V" ...] [--note "..."]
      [--startup-timeout <seconds>]
  add ... --oauth-client-id <id> --oauth-auth-endpoint <url> --oauth-token-endpoint <url>
      [--oauth-client-secret <secret>] [--oauth-scopes "s1 s2"] [--oauth-redirect-uri <uri>]
                                        Configure Static OAuth (PKCE) on an HTTP
                                        server. Redirect defaults to
                                        http://localhost:54546/callback. The
                                        client secret is NOT stored in
                                        servers.json — it is seeded for the app
                                        to import into the Keychain. NOTE: the
                                        CLI only prepares the config; the
                                        interactive login must be completed in
                                        Minis Settings > MCP Integrations >
                                        <server> > Authorize.
                                        Add (or overwrite) an HTTP or STDIO server.
                                        --startup-timeout: how long to wait for a
                                        STDIO server's first initialize (default 60s;
                                        raise it for slow-starting servers).
  remove <server>                       Delete a server.
  enable <server>                       Enable a server.
  disable <server>                      Disable a server.
  shutdown                              Stop the background daemon.

Global flags:
  --pretty                              Pretty-print JSON output.
  --help, -h                            Show this usage.

Files:
  Servers:  /var/minis/mcp-servers/servers.json   (mcpServers object, Claude-Desktop compatible)
  Log:      /var/minis/mcp-servers/mcp-cli.log

Examples:
  minis-mcp-cli list --pretty
  minis-mcp-cli tools notion
  minis-mcp-cli call notion search '{"query":"meeting"}'
  minis-mcp-cli call notion search --input '{"query":"meeting"}'
  minis-mcp-cli call notion search query="meeting" page_size=10
  minis-mcp-cli call notion search '{"query":"meeting"}' page_size=20
  minis-mcp-cli add --name notion --url https://mcp.notion.so/mcp --header "Authorization: Bearer $NOTION_TOKEN"
  minis-mcp-cli add --name github --command npx --args "-y @modelcontextprotocol/server-github" --env "GITHUB_TOKEN=$GITHUB_TOKEN"
  minis-mcp-cli add --name atlassian --command uvx --args "mcp-atlassian" --startup-timeout 120
  minis-mcp-cli add --name gworkspace --url https://my-gws-mcp.example.com/mcp \
      --oauth-client-id "1234.apps.googleusercontent.com" --oauth-client-secret "GOCSPX-..." \
      --oauth-auth-endpoint "https://accounts.google.com/o/oauth2/auth" \
      --oauth-token-endpoint "https://oauth2.googleapis.com/token" \
      --oauth-scopes "openid email https://www.googleapis.com/auth/calendar"

Server startup timeout (Minis config, not part of the MCP protocol):
  A STDIO server's first `initialize` handshake must complete within its
  startup timeout or the server is declared failed. Default 60s; set a larger
  per-server value for servers that install/compile on first launch.

    "mcpServers": { "atlassian": { "command": "uvx", "args": ["mcp-atlassian"],
                                   "startupTimeoutSeconds": 120 } }

  Accepted range: 1..900 seconds. The primary field is `startupTimeoutSeconds`;
  for configs migrated from other MCP clients these aliases are also read, in
  descending priority: startup_timeout_sec, startupTimeout, handshakeTimeout,
  initializeTimeout. An out-of-range / non-numeric value is rejected (add) or
  ignored with a warning (daemon), never silently applied.
"""


def _print_usage():
    """Print usage to stdout (human-facing; not the JSON channel)."""
    print(USAGE.rstrip())


def main():
    argv = sys.argv[1:]
    # Help / no-args: print usage to stdout and exit 0 (not the JSON error path).
    if not argv or argv[0] in ("--help", "-h", "help"):
        _print_usage()
        sys.exit(0)
    pretty = _pop_flag(argv, "--pretty")
    cmd = argv[0]
    rest = argv[1:]
    try:
        if cmd == "list":
            cmd_list(rest, pretty)
        elif cmd == "tools":
            cmd_tools(rest, pretty)
        elif cmd == "refresh":
            # High-frequency intent alias (CLI-design-for-LLM-priors): a model
            # or user asking to "refresh" a server's tools shouldn't need to
            # know it's spelled `tools --refresh`.
            cmd_tools(rest, pretty, force_refresh=True)
        elif cmd == "info":
            cmd_info(rest, pretty)
        elif cmd == "ping":
            cmd_ping(rest, pretty)
        elif cmd == "call":
            cmd_call(rest, pretty)
        elif cmd == "add":
            cmd_add(rest, pretty)
        elif cmd == "remove":
            cmd_remove(rest, pretty)
        elif cmd == "enable":
            cmd_set_enabled(rest, pretty, True)
        elif cmd == "disable":
            cmd_set_enabled(rest, pretty, False)
        elif cmd == "shutdown":
            cmd_shutdown(rest, pretty)
        else:
            # Keep the JSON error on stdout for programmatic callers; add a
            # human hint on stderr pointing at --help.
            sys.stderr.write("Run 'minis-mcp-cli --help' for usage.\n")
            _fail("unknown subcommand: %s" % cmd, "PARSE_ERROR", None, pretty)
    except MCPError as exc:
        _fail(exc.message, exc.code, None, pretty)
    except Exception as exc:  # noqa: BLE001 - last-resort, never crash to stdout
        _log("UNEXPECTED %s: %s" % (type(exc).__name__, exc))
        _fail("internal error: %s" % exc, "MCP_ERROR", None, pretty)


if __name__ == "__main__":
    main()
