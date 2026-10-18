package com.openminis.app.config.fields

import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.sandbox.ContainerDns.DnsMode

/**
 * [T-container-network-dns] (OpenMinis#396) minis-config fields for the
 * sandbox DNS, bound to the same storage as Settings > Rootfs > Container
 * Network:
 *
 *   minis-config get network.dnsMode                      → "auto" | "custom"
 *   minis-config set network.dnsMode custom
 *   minis-config get network.dnsCustomServers             → one address per line
 *   minis-config set network.dnsCustomServers '"223.5.5.5, 2400:3200::1"'
 *   minis-config set network.dnsCustomServers '["223.5.5.5","2400:3200::1"]'
 *
 * The server list takes a string (newline / comma / space separated) or an
 * array of strings, and every entry must be an IPv4 or IPv6 literal; one bad
 * entry rejects the whole write with the offending entries named, so a typo
 * never reaches resolv.conf. An empty list is allowed (custom mode then
 * follows the system until servers are added — see ContainerDns.plan).
 * [onChanged] re-applies resolv.conf after every successful write.
 */
internal object ContainerDnsFields {

    const val MODE_PATH = "network.dnsMode"
    const val SERVERS_PATH = "network.dnsCustomServers"

    fun build(store: ContainerDns.Store, onChanged: () -> Unit): List<ConfigField> = listOf(
        ClosureField(
            path = MODE_PATH,
            displayName = "Sandbox DNS mode",
            description = "auto (default): the sandbox's /etc/resolv.conf follows the system DNS and is rewritten on " +
                "every network change — keep this under a proxy's Fake-IP / TUN mode. custom: resolv.conf lists exactly " +
                "$SERVERS_PATH, and network changes no longer touch it. custom with an empty server list behaves like auto. " +
                "Applied immediately.",
            valueSchema = ConfigSchema.StrEnum(DnsMode.values().map { it.wire }),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = { ConfigValue.Str(store.mode().wire) },
            writer = { v ->
                store.save(DnsMode.fromWire((v as ConfigValue.Str).value), store.rawServers())
                onChanged()
            },
        ),
        ClosureField(
            path = SERVERS_PATH,
            displayName = "Sandbox custom DNS servers",
            description = "IPv4 / IPv6 addresses used when $MODE_PATH is custom: a string separated by newlines, commas " +
                "or spaces (quote it: '\"223.5.5.5, 2400:3200::1\"'), or a JSON array of strings. Every entry must be an " +
                "IP literal — hostnames are rejected. The sandbox resolver (musl) uses the first " +
                "${ContainerDns.RESOLVER_MAX_SERVERS}. Stored one per line. Applied immediately when mode is custom.",
            valueSchema = ConfigSchema.Json,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = { ConfigValue.Str(store.rawServers()) },
            writer = { v ->
                val raw = serversText(v)
                val parsed = ContainerDns.parse(raw)
                if (!parsed.isValid) {
                    throw ConfigError.InvalidValue(
                        "not an IPv4 / IPv6 address: ${parsed.invalid.joinToString(", ")}",
                    )
                }
                store.save(store.mode(), raw)
                onChanged()
            },
        ),
    )

    /** A string, or an array of strings joined one per line. */
    private fun serversText(v: ConfigValue): String = when (v) {
        is ConfigValue.Str -> v.value
        is ConfigValue.Arr -> v.value.joinToString("\n") { item ->
            (item as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("array entries must be strings (IP addresses)")
        }
        ConfigValue.Null -> ""
        else -> throw ConfigError.InvalidValue("expected a string or an array of IP address strings")
    }
}
