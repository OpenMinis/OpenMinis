package com.openminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-container-network-dns] (OpenMinis#396) Which DNS servers the sandbox's
 * `/etc/resolv.conf` lists.
 *
 * By default the sandbox follows the system: [RootfsManager.refreshDns]
 * copies the active network's nameservers on install, on every boot and on
 * every network change. That is right for most users, and it is the ONLY
 * correct choice under a proxy running Fake-IP / TUN mode (Clash, Surge,
 * sing-box): public resolvers there leak queries and hand back real IPs that
 * bypass the proxy. But some networks (a LAN, Tailscale, a private VPN)
 * advertise resolvers that cannot resolve public names, and then every
 * `apk add` / `pip install` / `git clone` in the sandbox times out.
 *
 * So the choice is the user's: [DnsMode.AUTO] (default, unchanged
 * behaviour) or [DnsMode.CUSTOM], where resolv.conf lists exactly the servers
 * they entered and a network change no longer rewrites it. Everything below
 * except the prefs accessors is pure, so it is unit-tested directly.
 *
 * Storage: SharedPreferences [PREFS]; the keys are the minis-config paths
 * (`network.dnsMode`, `network.dnsCustomServers`), registered in
 * ConfigBuiltins so the Settings UI and the agent share one source of truth.
 */
object ContainerDns {

    const val PREFS = "container_network"
    const val KEY_MODE = "network.dnsMode"
    const val KEY_SERVERS = "network.dnsCustomServers"

    enum class DnsMode(val wire: String) {
        AUTO("auto"),
        CUSTOM("custom"),
        ;

        companion object {
            /** Unknown or missing → AUTO, the behaviour before this setting existed. */
            fun fromWire(raw: String?): DnsMode = values().firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: AUTO
        }
    }

    /**
     * musl (Alpine) reads at most this many `nameserver` lines (MAXNS); the
     * rest are ignored. Extra entries are still written — they do no harm —
     * but the editor says so.
     */
    const val RESOLVER_MAX_SERVERS = 3

    data class ParseResult(
        /** Valid addresses, in the order given, without duplicates. */
        val servers: List<String>,
        /** Entries that are neither IPv4 nor IPv6, verbatim. */
        val invalid: List<String>,
    ) {
        val isValid: Boolean get() = invalid.isEmpty()
    }

    /**
     * Split a user-entered list on newlines, commas, semicolons or spaces.
     * A pasted `nameserver 1.1.1.1` line works too: the keyword is dropped.
     * An IPv6 address may be written in brackets (`[2606:4700::1111]`).
     */
    fun parse(raw: String): ParseResult {
        val servers = LinkedHashSet<String>()
        val invalid = mutableListOf<String>()
        for (token in raw.split(Regex("""[\s,;]+"""))) {
            val t = token.trim()
            if (t.isEmpty() || t.equals("nameserver", ignoreCase = true)) continue
            val addr = t.removePrefix("[").removeSuffix("]")
            if (isValidServer(addr)) servers += addr else invalid += t
        }
        return ParseResult(servers.toList(), invalid)
    }

    fun isValidServer(s: String): Boolean = isValidIpv4(s) || isValidIpv6(s)

    /** Dotted quad, each octet 0-255, no leading zeros (`01` is ambiguous). */
    fun isValidIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    /**
     * RFC 4291 text form: eight 16-bit hex groups, one `::` run of zeros at
     * most, an optional trailing dotted-quad (`::ffff:192.0.2.1`), and an
     * optional `%zone` for link-local addresses, which resolv.conf accepts
     * (`fe80::1%wlan0`). Parsed by hand so nothing ever goes near
     * InetAddress, which would try to RESOLVE a string that is not a literal.
     */
    fun isValidIpv6(s: String): Boolean {
        if (s.isEmpty()) return false
        val pct = s.indexOf('%')
        val addr = if (pct >= 0) {
            val zone = s.substring(pct + 1)
            if (zone.isEmpty() || !zone.all { it.isLetterOrDigit() || it in "._-" }) return false
            s.substring(0, pct)
        } else {
            s
        }
        if (!addr.contains(':')) return false
        val doubleColons = Regex("::").findAll(addr).count()
        if (doubleColons > 1 || addr.contains(":::")) return false
        val compressed = doubleColons == 1
        // A lone leading or trailing ':' (not part of '::') is malformed.
        if (addr.startsWith(":") && !addr.startsWith("::")) return false
        if (addr.endsWith(":") && !addr.endsWith("::")) return false

        val halves = if (compressed) addr.split("::") else listOf(addr)
        val groups = halves.flatMap { h -> if (h.isEmpty()) emptyList() else h.split(':') }
        var count = 0
        for ((i, g) in groups.withIndex()) {
            val last = i == groups.lastIndex
            if (last && g.contains('.')) {
                if (!isValidIpv4(g)) return false
                count += 2
                continue
            }
            if (g.isEmpty() || g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
            count += 1
        }
        return if (compressed) count <= 7 else count == 8
    }

    /** What [RootfsManager.refreshDns] should do. */
    sealed class Plan {
        /** Follow the system (the pre-existing behaviour). */
        object System : Plan()

        /** Write exactly these servers, whatever the network says. */
        data class Custom(val servers: List<String>) : Plan()
    }

    /**
     * Custom mode with no usable server falls back to the system rather than
     * writing a resolv.conf with no nameserver, which would break every
     * lookup — a mode switch saved before the list was filled in must not
     * take the sandbox offline.
     */
    fun plan(mode: DnsMode, rawServers: String?): Plan {
        if (mode != DnsMode.CUSTOM) return Plan.System
        val servers = parse(rawServers.orEmpty()).servers
        return if (servers.isEmpty()) Plan.System else Plan.Custom(servers)
    }

    /**
     * resolv.conf for custom mode. No `search` line: custom means the user's
     * servers only, and a search domain from whichever network happens to be
     * active would reintroduce the system's influence.
     */
    fun customResolvConf(servers: List<String>): String = buildString {
        append("# Written by Minis: Container Network DNS is set to Custom (Settings > Rootfs).\n")
        for (s in servers) append("nameserver ").append(s).append('\n')
    }

    // ── storage ─────────────────────────────────────────────────────────────

    /**
     * Where the two settings live. The prefs-backed [PrefsStore] is the real
     * one; the interface exists so the minis-config fields
     * (config/fields/ContainerDnsFields) can be exercised in a JVM test.
     */
    interface Store {
        fun mode(): DnsMode
        fun rawServers(): String
        /** Persist both. [rawServers] must already be valid. */
        fun save(mode: DnsMode, rawServers: String)
    }

    class PrefsStore(private val context: Context) : Store {
        override fun mode(): DnsMode = mode(context)
        override fun rawServers(): String = rawServers(context)
        override fun save(mode: DnsMode, rawServers: String) = save(context, mode, rawServers)
    }

    /** One address per line: the stored form. */
    fun normalize(raw: String): String = parse(raw).servers.joinToString("\n")

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): DnsMode = DnsMode.fromWire(prefs(context).getString(KEY_MODE, null))

    fun rawServers(context: Context): String = prefs(context).getString(KEY_SERVERS, null).orEmpty()

    fun plan(context: Context): Plan = plan(mode(context), rawServers(context))

    /**
     * Save both settings together. The server list is stored normalised (one
     * address per line) and must be valid — callers validate first and show
     * the error; this refuses rather than persist something unusable.
     */
    fun save(context: Context, mode: DnsMode, rawServers: String) {
        val parsed = parse(rawServers)
        require(parsed.isValid) { "Invalid DNS server(s): ${parsed.invalid.joinToString(", ")}" }
        prefs(context).edit()
            .putString(KEY_MODE, mode.wire)
            .putString(KEY_SERVERS, normalize(rawServers))
            .apply()
    }
}
