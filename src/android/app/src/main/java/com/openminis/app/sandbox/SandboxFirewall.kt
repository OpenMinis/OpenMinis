package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app sandbox firewall. No LSPosed / NMS reverse-engineering.
 *
 * Policy is always published for guest scripts. `--strict wifi-only` may
 * [ConnectivityManager.bindProcessToNetwork] to Wi-Fi. Process-wide uid DROP
 * is never applied automatically — it would also kill the LLM HTTP client.
 */
object SandboxFirewall {

    enum class Policy(val wire: String) {
        ALLOW("allow"),
        WIFI_ONLY("wifi-only"),
        LOG("log"),
        DENY("deny");

        companion object {
            fun parse(raw: String?): Policy = when (raw?.trim()?.lowercase()) {
                "wifi-only", "wifi", "wlan" -> WIFI_ONLY
                "log", "audit" -> LOG
                "deny", "block", "off", "cut" -> DENY
                else -> ALLOW
            }
        }
    }

    private const val PREFS = "sandbox_firewall"
    private const val KEY_POLICY = "policy"

    @Volatile
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    fun policy(context: Context): Policy {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POLICY, Policy.ALLOW.wire)
        return Policy.parse(raw)
    }

    fun setPolicy(context: Context, policy: Policy) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POLICY, policy.wire)
            .apply()
        syncHttpProxy(context, policy)
    }

    fun syncHttpProxy(context: Context, policy: Policy = policy(context)) {
        val root = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (_: Throwable) {
            null
        }
        when (policy) {
            Policy.LOG -> SandboxHttpProxy.start(root, deny = false)
            Policy.DENY -> SandboxHttpProxy.start(root, deny = true)
            else -> SandboxHttpProxy.stop()
        }
        if (PRootKernel.isBooted) {
            PRootKernel.updateProxy(context)
            HostEventBridge.launch { ExecutionCoordinator.broadcastProxyChange() }
        }
    }

    fun snapshot(context: Context): JSONObject {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val json = JSONObject()
            .put("policy", policy(app).wire)
            .put("uid", Process.myUid())
            .put("pid", Process.myPid())
        if (cm == null) {
            json.put("error", "no_connectivity_manager")
            return json
        }
        // minSdk 26 (N): ConnectivityManager.restrictBackgroundStatus is API 24,
        // so the old `SDK_INT >= 24` guard was always true and dead.
        json.put("restrict_background_status", restrictName(cm.restrictBackgroundStatus))
        json.put("bound_network", cm.boundNetworkForProcess != null)
        json.put("networks", networksJson(cm))
        json.put("active", networkJson(cm, cm.activeNetwork))
        json.put(
            "note",
            "uid iptables DROP is not auto-applied (same UID as the LLM). Use set log|cut for sandbox http_proxy; wifi-only --strict binds the process to Wi-Fi.",
        )
        json.put("http_proxy", SandboxHttpProxy.envBlock()?.get("http_proxy") ?: JSONObject.NULL)
        json.put("proxy_running", SandboxHttpProxy.isRunning())
        json.put("recent", SandboxHttpProxy.recentJson(8))
        return json
    }

    @Synchronized
    fun applyStrict(context: Context, policy: Policy): JSONObject {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return JSONObject().put("error", "no_connectivity_manager")
        wifiCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
            wifiCallback = null
        }
        return when (policy) {
            Policy.ALLOW, Policy.LOG -> {
                cm.bindProcessToNetwork(null)
                JSONObject().put("applied", policy.wire).put("bound", false)
            }
            Policy.WIFI_ONLY -> {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                    }
                    override fun onLost(network: Network) {
                        cm.bindProcessToNetwork(null)
                    }
                }
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.requestNetwork(req, cb)
                wifiCallback = cb
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    cm.bindProcessToNetwork(active)
                }
                JSONObject()
                    .put("applied", "wifi-only")
                    .put("bound", cm.boundNetworkForProcess != null)
            }
            Policy.DENY -> {
                cm.bindProcessToNetwork(null)
                JSONObject()
                    .put("applied", "deny")
                    .put("enforcement", "advisory")
                    .put(
                        "hint",
                        "Process-wide DROP needs VpnService or `su iptables -m owner --uid-owner ${Process.myUid()}`; not applied automatically.",
                    )
            }
        }
    }

    private fun networksJson(cm: ConnectivityManager): JSONArray {
        val arr = JSONArray()
        for (n in cm.allNetworks) {
            arr.put(networkJson(cm, n))
        }
        return arr
    }

    private fun networkJson(cm: ConnectivityManager, network: Network?): JSONObject {
        val json = JSONObject()
        if (network == null) return json.put("present", false)
        json.put("present", true)
        val nc = cm.getNetworkCapabilities(network) ?: return json.put("capabilities", JSONObject.NULL)
        json.put("wifi", nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        json.put("cellular", nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        json.put("vpn", nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        json.put("ethernet", nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        json.put("internet", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        json.put("validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        json.put("not_metered", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        return json
    }

    private fun restrictName(status: Int): String = when (status) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
        else -> "unknown"
    }
}
