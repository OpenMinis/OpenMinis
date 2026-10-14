package com.openminis.app.ssh

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import com.openminis.app.tools.AtomicFileWrite
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-ssh-backend] JSch implementation of [SshBackend].
 *
 * Channel model (aicode's "exec/SFTP 双通道隔离"): one pooled [Session] per
 * host, and every operation opens its OWN channel (ChannelExec per command,
 * ChannelSftp per transfer). SSH multiplexes channels over the single TCP
 * connection, so a long-running remote command never blocks a concurrent
 * file transfer — and a dead channel never takes the session down with it.
 *
 * Host key verification is TOFU via [TofuHostKeyRepository] wired into
 * JSch's StrictHostKeyChecking=yes path: the check happens DURING key
 * exchange, before any credential is offered, so a changed key aborts the
 * handshake instead of leaking the password to a possible MITM.
 *
 * All methods block — call from Dispatchers.IO only (SshTool.execute does).
 */
class JschSshBackend(
    override val store: SshConfigStore,
    override val knownHosts: SshKnownHosts,
) : SshBackend {

    private class Pooled(val session: Session, var lastUsed: Long)

    private val pool = ConcurrentHashMap<String, Pooled>()
    private val poolLock = Any()

    // ---------------------------------------------------------------- test

    override fun test(hostRef: String): String {
        val cfg = resolve(hostRef)
        val session = acquire(cfg)
        val version = runCatching { session.serverVersion }.getOrNull() ?: "unknown"
        val fp = knownHosts.fingerprintFor(hostPortOf(cfg)) ?: "(unpinned)"
        return "connected to ${cfg.label} · server=$version · hostKey=$fp"
    }

    // ---------------------------------------------------------------- exec

    override fun exec(hostRef: String, command: String, timeoutMs: Long, cwd: String?): SshExecResult {
        if (command.isBlank()) throw SshFailure.Config("command must not be blank")
        val cfg = resolve(hostRef)
        val wrapped = SshCommandWrap.wrapCwd(cwd, command)
        val session = acquire(cfg)
        val started = System.currentTimeMillis()
        val channel = openChannel(session, "exec", cfg) as ChannelExec
        try {
            channel.setCommand(wrapped)
            channel.setPty(false)
            val outIn: InputStream = runCatching { channel.inputStream }
                .getOrElse { throw SshFailure.Protocol("cannot open stdout stream: ${it.message}") }
            val errIn: InputStream = runCatching { channel.errStream }
                .getOrElse { throw SshFailure.Protocol("cannot open stderr stream: ${it.message}") }
            runCatching { channel.connect(CHANNEL_TIMEOUT_MS) }
                .onFailure { throw SshFailure.Network(cfg.label, it.message) }

            // [T-ssh-blocking-readers] Was a Thread.sleep(POLL_MS) +
            // available() busy poll: on a byte-immediately stream every 25ms
            // wakeup was pure latency + CPU, and on a quiet one the loop
            // spun anyway. Two blocking reader threads drain each stream as
            // the bytes arrive and exit on EOF (channel close), so the wait
            // becomes a join with the remaining deadline budget.
            // Same arm point as the old loop: the deadline starts ticking
            // once the channel is connected, not at method entry.
            val deadline = System.currentTimeMillis() + timeoutMs
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val outTotal = java.util.concurrent.atomic.AtomicLong()
            val errTotal = java.util.concurrent.atomic.AtomicLong()
            val outReader = startStreamReader(outIn, stdout, outTotal, "ssh-stdout")
            val errReader = startStreamReader(errIn, stderr, errTotal, "ssh-stderr")
            val timedOut = !awaitWithDeadline(outReader, errReader, deadline)
            val exit = if (timedOut) -1 else channel.exitStatus
            return SshExecResult(
                exitCode = exit,
                stdout = sinkText(stdout),
                stderr = sinkText(stderr),
                timedOut = timedOut,
                durationMs = System.currentTimeMillis() - started,
                hostLabel = cfg.label,
                truncated = outTotal.get() > MAX_CAPTURE || errTotal.get() > MAX_CAPTURE,
            )
        } finally {
            // Disconnecting the channel EOFs both streams, which releases the
            // reader threads even on the timeout path (they are daemons
            // regardless).
            runCatching { channel.disconnect() }
        }
    }

    /**
     * Blocking drain of [inp] into [sink] on a daemon thread.
     *
     * `read` returns -1 at stream EOF — JSch flips that when the channel
     * closes, so no polling is needed. The total byte count is tracked
     * separately from the capped sink so `truncated` keeps the old semantics
     * (the sink holds at most MAX_CAPTURE bytes but counting continues).
     */
    private fun startStreamReader(
        inp: InputStream,
        sink: ByteArrayOutputStream,
        counter: java.util.concurrent.atomic.AtomicLong,
        name: String,
    ): Thread = Thread {
        val buf = ByteArray(8192)
        while (true) {
            val n = runCatching { inp.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            counter.addAndGet(n.toLong())
            synchronized(sink) {
                val room = MAX_CAPTURE - sink.size()
                if (room > 0) sink.write(buf, 0, minOf(n, room))
            }
        }
    }.apply {
        isDaemon = true
        this.name = name
        start()
    }

    /** Join both readers within [deadline]; true when both drained before it. */
    private fun awaitWithDeadline(out: Thread, err: Thread, deadline: Long): Boolean {
        val budget = deadline - System.currentTimeMillis()
        if (budget <= 0) return false
        out.join(budget)
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0 && err.isAlive) return false
        err.join(maxOf(remaining, 0L))
        return !out.isAlive && !err.isAlive
    }

    private fun sinkText(sink: ByteArrayOutputStream): String =
        synchronized(sink) { sink.toString("UTF-8") }

    // ---------------------------------------------------------------- sftp

    override fun ls(hostRef: String, path: String): List<SshLsEntry> {
        val cfg = resolve(hostRef)
        val session = acquire(cfg)
        return withSftp(cfg, session) { ch ->
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(path.ifBlank { "." }) as java.util.Vector<ChannelSftp.LsEntry>
            entries.mapNotNull { e ->
                val name = e.filename
                if (name == "." || name == "..") return@mapNotNull null
                SshLsEntry(
                    name = name,
                    isDir = e.attrs.isDir,
                    size = e.attrs.size,
                    mtimeEpochSec = e.attrs.mTime.toLong(),
                )
            }
        }
    }

    override fun get(hostRef: String, remotePath: String, localFile: File): Long {
        val cfg = resolve(hostRef)
        val session = acquire(cfg)
        return withSftp(cfg, session) { ch ->
            stageDownload({ out -> ch.get(remotePath, out) }, localFile)
        }
    }

    /**
     * Stage an SFTP download into a unique sibling temp file and only then
     * swap it onto [localFile].
     *
     * The old implementation opened `localFile.outputStream()` directly,
     * which truncates the target to zero bytes BEFORE the first byte of the
     * transfer arrives — so a mid-transfer failure (network drop,
     * SftpException, full disk) destroyed the previous download. Now the
     * temp file absorbs the partial data, [AtomicFileWrite.moveIntoPlace]
     * does the rename-or-copy swap (never deleting the target first), and
     * the temp file is removed on every path. The error surface is
     * unchanged: transfer failures propagate to the caller as before.
     */
    internal fun stageDownload(transfer: (OutputStream) -> Unit, localFile: File): Long {
        localFile.parentFile?.mkdirs()
        // Unique + clearly not the real artifact: `report.pdf` never becomes
        // `report.pdf.minis-sftp-1a2b3c4d.tmp` in a listing that matters.
        val tmp = File(
            localFile.parentFile,
            localFile.name + STAGE_PREFIX + UUID.randomUUID().toString().substring(0, 8) + STAGE_SUFFIX,
        )
        try {
            tmp.outputStream().use { out -> transfer(out) }
            if (!AtomicFileWrite.moveIntoPlace(tmp, localFile)) {
                throw SshFailure.Protocol(
                    "could not replace ${localFile.name} with the downloaded copy; previous file kept",
                )
            }
            return localFile.length()
        } finally {
            // Every failure path (transfer threw, replace refused) must leave
            // no half-written staging file behind.
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    override fun put(hostRef: String, localFile: File, remotePath: String): Long {
        if (!localFile.isFile) throw SshFailure.Config("local file not found: ${localFile.path}")
        val cfg = resolve(hostRef)
        val session = acquire(cfg)
        return withSftp(cfg, session) { ch ->
            localFile.inputStream().use { inp -> ch.put(inp, remotePath) }
            localFile.length()
        }
    }

    // ----------------------------------------------------------- host mgmt

    override fun forgetHostKey(hostRef: String) {
        val cfg = resolve(hostRef)
        evict(cfg.id)
        knownHosts.forget(hostPortOf(cfg))
        store.pinFingerprint(cfg.id, "")
    }

    override fun removeHost(hostRef: String): Boolean {
        val cfg = store.find(hostRef) ?: return false
        evict(cfg.id)
        knownHosts.forget(hostPortOf(cfg))
        return store.remove(cfg.id)
    }

    override fun close() {
        synchronized(poolLock) {
            for (p in pool.values) runCatching { p.session.disconnect() }
            pool.clear()
        }
    }

    // -------------------------------------------------------------- internals

    private fun resolve(hostRef: String): SshHostConfig =
        store.find(hostRef) ?: throw SshFailure.UnknownHost(hostRef)

    private fun hostPortOf(cfg: SshHostConfig): String =
        SshKnownHosts.hostPort(cfg.host, cfg.port)

    private fun acquire(cfg: SshHostConfig): Session {
        val now = System.currentTimeMillis()
        synchronized(poolLock) {
            pool[cfg.id]?.let { p ->
                if (p.session.isConnected && now - p.lastUsed < IDLE_EVICT_MS) {
                    p.lastUsed = now
                    return p.session
                }
                runCatching { p.session.disconnect() }
                pool.remove(cfg.id)
            }
        }
        // openSession deliberately runs OUTSIDE poolLock (a connect can take
        // up to CONNECT_TIMEOUT_MS and must not stall other hosts), which
        // leaves a race: two concurrent acquires for the same cfg.id can both
        // miss above and both connect. Re-check under the lock and make the
        // LOSER disconnect its own session — the old code just overwrote
        // pool[cfg.id], orphaning the first connected Session forever (never
        // disconnected, identity still registered in JSch).
        val session = openSession(cfg)
        synchronized(poolLock) {
            val existing = pool[cfg.id]
            if (existing != null && existing.session.isConnected) {
                runCatching { session.disconnect() }
                existing.lastUsed = System.currentTimeMillis()
                return existing.session
            }
            // A stale not-connected entry (inserted by a third thread between
            // our check and our lock) gets replaced and torn down too.
            if (existing != null) runCatching { existing.session.disconnect() }
            pool[cfg.id] = Pooled(session, System.currentTimeMillis())
            if (pool.size > MAX_POOL) {
                val oldest = pool.entries.minByOrNull { it.value.lastUsed }
                if (oldest != null && oldest.key != cfg.id) {
                    runCatching { oldest.value.session.disconnect() }
                    pool.remove(oldest.key)
                }
            }
        }
        return session
    }

    private fun evict(hostId: String) {
        synchronized(poolLock) {
            pool.remove(hostId)?.let { runCatching { it.session.disconnect() } }
        }
    }

    private fun openSession(cfg: SshHostConfig): Session {
        val creds = store.credentialsFor(cfg.id)
            ?: throw SshFailure.Config("No stored credentials for '${cfg.name}' — re-run add_host with auth material.")
        val jsch = JSch()
        if (cfg.authType == SshAuthType.PRIVATE_KEY) {
            val pem = creds.privateKeyPem
                ?: throw SshFailure.Config("Host '${cfg.name}' uses private_key auth but no key is stored. Re-run add_host.")
            runCatching {
                jsch.addIdentity("minis-${cfg.id}", pem.toByteArray(), null, creds.passphrase?.toByteArray())
            }.onFailure { throw SshFailure.Config("Private key rejected: ${it.message}") }
        } else if (creds.password == null) {
            throw SshFailure.Config("Host '${cfg.name}' uses password auth but no password is stored. Re-run add_host.")
        }

        val hp = hostPortOf(cfg)
        val tofu = TofuHostKeyRepository(knownHosts, hp)
        val session = runCatching { jsch.getSession(cfg.username, cfg.host, cfg.port) }
            .getOrElse { throw SshFailure.Network(cfg.label, it.message) }
        if (cfg.authType == SshAuthType.PASSWORD) session.setPassword(creds.password)
        session.userInfo = CredsUserInfo(creds.password, creds.passphrase)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.hostKeyRepository = tofu
        runCatching {
            session.setServerAliveInterval(KEEPALIVE_MS)
            session.setServerAliveCountMax(2)
        }
        runCatching { session.connect(CONNECT_TIMEOUT_MS) }
            .onFailure { throw mapConnectFailure(cfg, tofu, it) }

        // Surface the TOFU pin in host metadata so list_hosts can show it.
        runCatching {
            val fp = knownHosts.fingerprintFor(hp)
            if (fp != null && fp != cfg.hostKeyFingerprint) store.pinFingerprint(cfg.id, fp)
        }
        return session
    }

    private fun mapConnectFailure(cfg: SshHostConfig, tofu: TofuHostKeyRepository, e: Throwable): SshFailure {
        val msg = e.message.orEmpty()
        val mismatch = tofu.lastMismatch
        return when {
            mismatch != null ->
                SshFailure.HostKeyMismatch(cfg.label, knownHosts.fingerprintFor(tofu.hostKeyString) ?: "?", mismatch)
            msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ||
                msg.contains("USERAUTH", true) -> SshFailure.Auth(cfg.label, msg)
            msg.contains("UnknownHost", true) || msg.contains("Connection refused", true) ||
                msg.contains("Network is unreachable", true) || msg.contains("timeout", true) ||
                msg.contains("SocketException", true) || msg.contains("No route to host", true) ->
                SshFailure.Network(cfg.label, msg)
            else -> SshFailure.Protocol(msg.ifBlank { e.javaClass.simpleName })
        }
    }

    private fun openChannel(session: Session, type: String, cfg: SshHostConfig): com.jcraft.jsch.Channel {
        return runCatching { session.openChannel(type) }
            .getOrElse { throw SshFailure.Network(cfg.label, "cannot open $type channel: ${it.message}") }
    }

    private fun <T> withSftp(cfg: SshHostConfig, session: Session, block: (ChannelSftp) -> T): T {
        val ch = openChannel(session, "sftp", cfg) as ChannelSftp
        try {
            runCatching { ch.connect(CHANNEL_TIMEOUT_MS) }
                .onFailure { throw SshFailure.Network(cfg.label, "sftp connect: ${it.message}") }
            return block(ch)
        } catch (e: SftpException) {
            throw when (e.id) {
                ChannelSftp.SSH_FX_NO_SUCH_FILE ->
                    SshFailure.Protocol("remote path not found: ${e.message}")
                ChannelSftp.SSH_FX_PERMISSION_DENIED ->
                    SshFailure.Protocol("remote permission denied: ${e.message}")
                else -> SshFailure.Protocol(e.message ?: "sftp error id=${e.id}")
            }
        } catch (e: JSchException) {
            throw SshFailure.Network(cfg.label, e.message)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    /**
     * Bridges JSch's [HostKeyRepository] onto [SshKnownHosts].
     *
     * check() runs during key exchange: unknown key → NOT_INCLUDED, JSch
     * prompts [CredsUserInfo.promptYesNo] (always true = trust-on-first-use)
     * and then calls add(), which pins the fingerprint. A CHANGED verdict
     * makes JSch abort the handshake before authentication.
     */
    internal class TofuHostKeyRepository(
        private val knownHosts: SshKnownHosts,
        val hostKeyString: String,
    ) : HostKeyRepository {

        @Volatile
        var lastMismatch: String? = null
            private set

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            val fp = SshKnownHosts.fingerprint(key)
            return when (val d = knownHosts.decide(hostKeyString, fp)) {
                is SshKnownHosts.Decision.New -> HostKeyRepository.NOT_INCLUDED
                is SshKnownHosts.Decision.Match -> HostKeyRepository.OK
                is SshKnownHosts.Decision.Mismatch -> {
                    lastMismatch = fp
                    HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostKey: HostKey?, ui: UserInfo?) {
            // mwiede-JSch exposes getKey() as the base64 STRING of the blob;
            // check() receives raw bytes. Decode so both paths fingerprint
            // the identical blob and produce the same SHA256:<b64> pin.
            val b64 = hostKey?.key ?: return
            val blob = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return
            knownHosts.record(hostKeyString, SshKnownHosts.fingerprint(blob))
        }

        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "MinisUltra-TOFU"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /**
     * Non-interactive [UserInfo]. promptYesNo=true is the TOFU accept for an
     * unseen host key; password/passphrase are answered from stored
     * credentials so keyboard-interactive servers work without a UI.
     */
    internal class CredsUserInfo(
        private val password: String?,
        private val passphrase: String?,
    ) : UserInfo {
        override fun getPassphrase(): String? = passphrase
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean = password != null
        override fun promptPassphrase(message: String?): Boolean = passphrase != null
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) = Unit
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val CHANNEL_TIMEOUT_MS = 10_000
        const val KEEPALIVE_MS = 15_000
        const val IDLE_EVICT_MS = 5 * 60_000L
        const val MAX_CAPTURE = 200_000
        private const val MAX_POOL = 6
        // POLL_MS gone with the busy-poll loop ([T-ssh-blocking-readers]):
        // exec now waits on blocking reader threads, not timed wakeups.
        /** Sibling staging prefix/suffix for downloads — never a real artifact name. */
        internal const val STAGE_PREFIX = ".minis-sftp-"
        internal const val STAGE_SUFFIX = ".tmp"
    }
}

/**
 * Process-wide holder so ChatViewModel needs no constructor plumbing
 * (same shape as other context-derived singletons). Tests build a
 * [SshBackend] fake and pass it to SshTool.route directly.
 */
object SshEngine {

    @Volatile
    private var instance: SshBackend? = null

    fun backend(context: Context): SshBackend {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: JschSshBackend(
                SshConfigStore.forContext(context),
                SshKnownHosts(File(context.applicationContext.filesDir, "ssh/known_hosts.json")),
            ).also { instance = it }
        }
    }

    /** Disconnect pooled sessions (app shutdown / settings hook). */
    fun shutdown() {
        synchronized(this) {
            runCatching { instance?.close() }
            instance = null
        }
    }
}
