#!/usr/bin/env python3
"""daemon.py — the forked, long-lived background process behind minis-mcp-cli.

Responsibilities:
  1. Listen on 127.0.0.1 loopback TCP, accept CLI connections (thread-per-conn).
  2. Maintain an MCPPool that owns each MCP server child process / HTTP session
     and reuses it across calls (no per-call spawn/kill).
  3. Each server has an independent 10-minute idle TTL; an idle server is killed.
  4. Once the pool is empty for a grace period the daemon exits on its own.

IPC is 127.0.0.1 loopback TCP, NOT a Unix-domain socket: iSH's fakefs cannot
create an AF_UNIX socket inode (bind() -> EPERM), so the daemon binds an
ephemeral 127.0.0.1 port and publishes it in a port file the CLI reads.

The daemon writes nothing to stdout (stdio is redirected to /dev/null by the
forking parent in main.py); all diagnostics go to the daemon log file. STDIO MCP
servers are held by their proc.stdin/proc.stdout fds directly — no FIFO, no
intermediate files. _read_reply uses a reader thread joined with a timeout
(signal.alarm is unreliable under iSH/PRoot). Shared iOS (iSH) and Android
(PRoot); keep both copies byte-identical.
"""

import json
import logging
import os
import signal
import socket
import subprocess
import threading
import time

# The daemon is launched as a fresh interpreter (fork of the CLI) whose cwd /
# sys.path may not include the package dir, so anchor imports explicitly.
import sys
sys.path.insert(0, "/usr/local/lib/minis-mcp-cli")

from transport.http import HTTPTransport, MCPError, expand_env  # noqa: E402
from utils import config  # noqa: E402
from utils import deps  # noqa: E402

TTL_SECONDS = 600          # per-server idle TTL: 10 minutes
WATCHDOG_INTERVAL = 30     # TTL scan cadence
DAEMON_EXIT_GRACE = 60     # after the pool is empty, wait this long before exit
RPC_TIMEOUT = 300.0        # per-RPC reply timeout (matches transport modules)
CONN_TIMEOUT = 310.0       # socket recv timeout (slightly above RPC_TIMEOUT)

log = logging.getLogger("mcp-daemon")


# --- STDIO MCP server subprocess --------------------------------------------

class MCPServerProcess:
    """A long-lived STDIO MCP server child. The daemon holds proc.stdin /
    proc.stdout directly and serializes RPCs on this server via _lock."""

    def __init__(self, name, cfg):
        self.name = name
        self.cfg = cfg
        self.proc = None
        self._lock = threading.Lock()
        self._id = 0
        self.last_activity = time.time()

    def _next_id(self):
        self._id += 1
        return self._id

    def start(self):
        command = self.cfg.get("command", "")
        args = self.cfg.get("args", []) or []
        env = dict(os.environ)
        for k, v in (self.cfg.get("env") or {}).items():
            env[k] = expand_env(v)
        deps.ensure_command(command)
        try:
            self.proc = subprocess.Popen(
                [command] + list(args),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                env=env,
                text=True,
                bufsize=1,
                start_new_session=True,  # detach from the daemon's process group
            )
        except FileNotFoundError:
            raise MCPError("CONNECTION_ERROR", "command not found: %s" % command)
        except OSError as exc:
            raise MCPError("CONNECTION_ERROR", str(exc))
        log.info("[%s] spawned pid=%s", self.name, self.proc.pid)
        self._handshake()
        log.info("[%s] initialized", self.name)

    def _send(self, method, params=None, notify=False):
        body = {"jsonrpc": "2.0", "method": method}
        if not notify:
            body["id"] = self._next_id()
        if params is not None:
            body["params"] = params
        try:
            self.proc.stdin.write(json.dumps(body) + "\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError, OSError):
            raise MCPError("STDIO_CRASH", "[%s] child closed stdin" % self.name)
        return body.get("id")

    def _read_reply(self, want_id, timeout=RPC_TIMEOUT):
        result_box = {}

        def reader():
            try:
                for raw in self.proc.stdout:
                    raw = raw.strip()
                    if not raw:
                        continue
                    try:
                        msg = json.loads(raw)
                    except ValueError:
                        continue
                    if msg.get("id") == want_id:
                        result_box["msg"] = msg
                        return
            except (OSError, ValueError):
                pass
            result_box.setdefault("eof", True)

        t = threading.Thread(target=reader, daemon=True)
        t.start()
        t.join(timeout)
        if t.is_alive():
            raise MCPError("TIMEOUT", "[%s] no reply after %ss" % (self.name, timeout))
        if "msg" not in result_box:
            raise MCPError("STDIO_CRASH", "[%s] process exited before replying" % self.name)
        return result_box["msg"]

    def _rpc(self, method, params=None, timeout=RPC_TIMEOUT):
        want_id = self._send(method, params)
        reply = self._read_reply(want_id, timeout=timeout)
        if reply.get("error"):
            err = reply["error"]
            raise MCPError("MCP_ERROR", err.get("message", json.dumps(err)))
        return reply.get("result")

    def _handshake(self):
        # Startup/handshake timeout is per-server configurable (Minis config,
        # not MCP protocol). resolve_startup_timeout returns the effective
        # seconds plus a warning for any invalid value (which we log rather
        # than silently swallow). Default is DEFAULT_STARTUP_TIMEOUT.
        timeout, warning = config.resolve_startup_timeout(self.cfg)
        if warning:
            log.warning("[%s] %s", self.name, warning)
        try:
            self._rpc("initialize", {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "minis-mcp-cli", "version": "1.0.0"},
            }, timeout=timeout)
        except MCPError as exc:
            if exc.code == "TIMEOUT":
                raise MCPError("TIMEOUT", (
                    "[%s] initialization timed out after %ss. This server may "
                    "still be starting. Set mcpServers.%s.startupTimeoutSeconds "
                    "to e.g. %d for slow-starting servers."
                    % (self.name, timeout, self.name, max(timeout * 2, 120)))
                )
            raise
        self._send("notifications/initialized", notify=True)

    def is_alive(self):
        return self.proc is not None and self.proc.poll() is None

    def list_tools(self):
        with self._lock:
            self.last_activity = time.time()
            return (self._rpc("tools/list") or {}).get("tools", [])

    def call_tool(self, tool, arguments):
        with self._lock:
            self.last_activity = time.time()
            return self._rpc("tools/call", {"name": tool, "arguments": arguments or {}})

    def ping(self):
        with self._lock:
            self.last_activity = time.time()
            self._rpc("tools/list", timeout=10)
            return True

    def stop(self):
        """Graceful close: shut stdin so the server reads EOF and exits; fall
        back to terminate/kill (SIGTERM can be unreliable under PRoot)."""
        if not self.proc:
            return
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        log.info("[%s] stopped", self.name)


# --- HTTP MCP session (wraps the existing stateless HTTPTransport) ----------

class MCPHTTPSession:
    """Interface-compatible with MCPServerProcess so the pool treats both the
    same. HTTP is stateless, so there is no child process to keep alive."""

    def __init__(self, name, cfg):
        self.name = name
        self._transport = HTTPTransport(cfg, name)
        self._lock = threading.Lock()
        self.last_activity = time.time()

    def is_alive(self):
        return True

    def list_tools(self):
        with self._lock:
            self.last_activity = time.time()
            return self._transport.list_tools()

    def call_tool(self, tool, arguments):
        with self._lock:
            self.last_activity = time.time()
            return self._transport.call_tool(tool, arguments)

    def ping(self):
        with self._lock:
            self.last_activity = time.time()
            return self._transport.ping()

    def stop(self):
        pass


# --- connection pool --------------------------------------------------------

class MCPPool:
    """Owns one live session per server. STDIO_CRASH / TIMEOUT evict + respawn
    with a single retry; other errors propagate. When the pool empties it calls
    on_empty so the daemon can start its exit grace timer."""

    def __init__(self, on_empty, on_active=None):
        self._pool = {}
        self._lock = threading.Lock()
        self._on_empty = on_empty
        # [T-mcp-daemon-idle-selfkill] GH#233. Notified whenever the pool holds
        # a live session again, so the daemon can clear its exit-grace stamp.
        # Without this the stamp was cleared ONLY by the 30s watchdog, so a
        # session created between ticks stayed invisible to the grace timer.
        self._on_active = on_active or (lambda: None)
        # Fire on_empty only on a real non-empty -> empty TRANSITION. Previously
        # gc() called it on every scan of an empty pool, so the grace timer
        # started ticking at daemon birth rather than when the last server was
        # evicted. Starts True because a fresh pool has never been populated,
        # so its emptiness is not a transition worth reporting.
        self._was_empty = True

    def _make_session(self, name, cfg):
        if config.is_stdio(cfg):
            session = MCPServerProcess(name, cfg)
            session.start()
            return session
        return MCPHTTPSession(name, cfg)

    def get(self, name):
        with self._lock:
            session = self._pool.get(name)
            if session is not None and not session.is_alive():
                log.warning("[%s] process died, evicting", name)
                session.stop()
                del self._pool[name]
                session = None
            if session is None:
                cfg = config.get_server(name)
                if cfg is None:
                    raise MCPError("NOT_FOUND", "server '%s' not found" % name)
                if not cfg.get("enabled", True):
                    raise MCPError("DISABLED", "server '%s' is disabled" % name)
                log.info("[%s] creating new session", name)
                session = self._make_session(name, cfg)
                self._pool[name] = session
            self._was_empty = False
        # [T-mcp-daemon-idle-selfkill] Outside the lock: the pool now holds a
        # live session, so cancel any pending exit grace. This is THE fix — the
        # request path, not just the watchdog, must clear the stamp.
        self._on_active()
        return session

    def call_with_retry(self, name, fn):
        for attempt in range(2):
            session = self.get(name)
            try:
                return fn(session)
            except MCPError as exc:
                if exc.code in ("STDIO_CRASH", "TIMEOUT") and attempt == 0:
                    log.warning("[%s] %s, retrying", name, exc.code)
                    self.evict(name)
                    continue
                raise

    def evict(self, name):
        with self._lock:
            session = self._pool.pop(name, None)
        if session:
            session.stop()

    def gc(self):
        """TTL scan: evict idle-too-long or dead servers. Returns the live count
        and fires on_empty when the pool just became empty."""
        now = time.time()
        to_evict = []
        with self._lock:
            for name, session in list(self._pool.items()):
                if now - session.last_activity > TTL_SECONDS:
                    log.info("[%s] TTL expired, killing", name)
                    to_evict.append(name)
                elif not session.is_alive():
                    log.warning("[%s] process died, evicting", name)
                    to_evict.append(name)
        for name in to_evict:
            self.evict(name)
        # [T-mcp-daemon-idle-selfkill] GH#233. Fire on_empty only on a genuine
        # non-empty -> empty transition (what the docstring always claimed).
        # Firing on every scan of an already-empty pool started the exit grace
        # at daemon birth, so the 60s stamp could already be past due by the
        # time the first call arrived.
        with self._lock:
            alive = len(self._pool)
            just_emptied = alive == 0 and not self._was_empty
            self._was_empty = alive == 0
        if just_emptied:
            self._on_empty()
        return alive

    def alive_servers(self):
        with self._lock:
            return list(self._pool.keys())

    def shutdown(self):
        with self._lock:
            sessions = list(self._pool.values())
            self._pool.clear()
        for session in sessions:
            session.stop()


# --- daemon server ----------------------------------------------------------

class DaemonServer:
    def __init__(self, port_file, pid_file):
        self.port_file = port_file
        self.pid_file = pid_file
        self._running = True
        self._empty_since = None
        self.pool = MCPPool(on_empty=self._on_pool_empty, on_active=self._on_pool_active)

    def _on_pool_empty(self):
        if self._empty_since is None:
            self._empty_since = time.time()
            log.info("pool empty, daemon will exit in %ss if no new calls", DAEMON_EXIT_GRACE)

    def _on_pool_active(self):
        """[T-mcp-daemon-idle-selfkill] GH#233. Cancel a pending exit grace as
        soon as the pool has a live session again.

        This is the core of the fix. `_empty_since` used to be cleared ONLY
        inside the 30s watchdog, so a server created between two ticks never
        reset the timer: the daemon would reach `_empty_since + 60s` while the
        user was actively using it, set `_running = False`, and `pool.shutdown()`
        would close every stdio child's stdin. A Node MCP server reading EOF on
        stdin exits cleanly with rc=0 — exactly the reported symptom, on the
        reported 60-120s schedule (a 60s grace quantised to a 30s tick).
        """
        if self._empty_since is not None:
            self._empty_since = None
            log.info("pool active again, exit grace cancelled")

    def handle_request(self, data):
        cmd = data.get("cmd", "")
        server = data.get("server", "")
        try:
            if cmd == "list":
                servers = config.get_servers()
                alive = self.pool.alive_servers()
                rows = []
                for name, cfg in servers.items():
                    enabled = cfg.get("enabled", True)
                    if not data.get("all") and not enabled:
                        continue
                    rows.append({
                        "name": name,
                        "enabled": enabled,
                        "transport": "http" if config.is_http(cfg) else ("stdio" if config.is_stdio(cfg) else "unknown"),
                        "target": cfg.get("url") or cfg.get("command"),
                        "note": cfg.get("note"),
                        "alive": name in alive,
                    })
                return {"ok": True, "result": {"servers": rows, "count": len(rows)}}

            if cmd == "tools":
                # Optional force-refresh: evict the live session first so the
                # next call re-handshakes (fresh initialize + tools/list) —
                # picks up tools the remote server added after we connected.
                # [T-mcp-tools-refresh]
                if data.get("refresh"):
                    self.pool.evict(server)
                tools = self.pool.call_with_retry(server, lambda s: s.list_tools())
                return {"ok": True, "result": {"server": server, "tools": tools, "count": len(tools)}}

            if cmd == "call":
                tool = data.get("tool", "")
                args = data.get("args") or {}
                result = self.pool.call_with_retry(server, lambda s: s.call_tool(tool, args))
                return {"ok": True, "result": {"server": server, "tool": tool, "result": result}}

            if cmd == "ping":
                start = time.time()
                self.pool.call_with_retry(server, lambda s: s.ping())
                elapsed = int((time.time() - start) * 1000)
                return {"ok": True, "result": {"server": server, "ok": True, "elapsed_ms": elapsed}}

            if cmd == "shutdown":
                self._running = False
                return {"ok": True, "result": {"shutdown": True}}

            return {"ok": False, "error": "unknown cmd: %s" % cmd, "code": "PARSE_ERROR", "server": server}

        except MCPError as exc:
            code = exc.code
            if code == "MCP_ERROR" and "not found" in (exc.message or "").lower():
                code = "TOOL_NOT_FOUND"
            return {"ok": False, "error": exc.message, "code": code, "server": server}
        except Exception as exc:  # noqa: BLE001 - never crash the connection thread
            log.exception("unexpected error handling %s", cmd)
            return {"ok": False, "error": str(exc), "code": "MCP_ERROR", "server": server}

    def _handle_conn(self, conn):
        try:
            conn.settimeout(CONN_TIMEOUT)
            buf = b""
            while b"\n" not in buf:
                chunk = conn.recv(65536)
                if not chunk:
                    break
                buf += chunk
            if not buf.strip():
                return
            try:
                req = json.loads(buf.split(b"\n")[0])
            except ValueError:
                conn.sendall((json.dumps({"ok": False, "error": "invalid JSON", "code": "PARSE_ERROR"}) + "\n").encode())
                return
            resp = self.handle_request(req)
            conn.sendall((json.dumps(resp, ensure_ascii=False) + "\n").encode())
        except (OSError, socket.timeout) as exc:
            log.warning("connection error: %s", exc)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _watchdog(self):
        while self._running:
            time.sleep(WATCHDOG_INTERVAL)
            alive = self.pool.gc()
            if alive > 0:
                self._empty_since = None
            elif self._empty_since is not None:
                if time.time() - self._empty_since >= DAEMON_EXIT_GRACE:
                    log.info("pool empty past grace, daemon exiting")
                    self._running = False
                    break

    def run(self):
        # Loopback TCP on an ephemeral port (AF_UNIX is unavailable on iSH
        # fakefs). Publish the chosen port so the CLI knows where to connect.
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", 0))
        port = srv.getsockname()[1]
        srv.listen(16)
        srv.settimeout(1.0)  # wake periodically so _running flips take effect
        tmp = self.port_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(str(port))
        os.replace(tmp, self.port_file)
        log.info("daemon listening on 127.0.0.1:%s pid=%s ttl=%ss", port, os.getpid(), TTL_SECONDS)

        def _sigterm(*_):
            log.info("SIGTERM received")
            self._running = False
        signal.signal(signal.SIGTERM, _sigterm)

        watchdog = threading.Thread(target=self._watchdog, daemon=True)
        watchdog.start()

        try:
            while self._running:
                try:
                    conn, _ = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                threading.Thread(target=self._handle_conn, args=(conn,), daemon=True).start()
        finally:
            self._cleanup(srv)

    def _cleanup(self, srv):
        log.info("daemon shutting down, stopping all servers")
        self.pool.shutdown()
        try:
            srv.close()
        except OSError:
            pass
        for path in (self.port_file, self.pid_file):
            try:
                os.unlink(path)
            except OSError:
                pass
        log.info("daemon exited cleanly")
