package com.example.opendash.dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WifiConnStatus { IDLE, REQUESTING, CONNECTED, ERROR }

data class WifiState(
    val status: WifiConnStatus = WifiConnStatus.IDLE,
    val ssid: String = "",
    val error: String? = null,
)

/**
 * Programmatically connects to the dash WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * bindProcessToNetwork() routes all UDP/TCP from this process through
 * the dash network so packets reach 192.168.1.1 even if the phone
 * has another network (cellular) available.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 *
 * Android 11 / OEM fallback:
 * - never authenticate with a prefix-only SSID such as RE_
 * - poll the active Wi-Fi SSID when NetworkCallback transportInfo is redacted
 * - reuse an already-connected matching Wi-Fi network when the system dialog is suppressed
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 30_000  // ms — Android shows system dialog within this
        private const val RECONNECT_DELAY  = 8_000L
        // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
        private const val WifiManagerUnknownSsid = "<unknown ssid>"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork:
     * that routed the WHOLE app through the dash's no-internet WiFi, so routing,
     * geocoding and shared-link resolution all failed while connected ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog.
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes OpenDash work on
     *   any rider's dash without hardcoding their SSID. When false, exact-match.
     */
    fun connect(ssid: String, password: String = "", prefixMatch: Boolean = false) {
        wantConnected    = true
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        requestNetwork()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    @SuppressLint("MissingPermission")
    fun findDashSsid(prefix: String): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.scanResults
            .mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } }
            .firstOrNull { it.startsWith(prefix) }
            .also { DebugLog.i(TAG) { "Scan lookup for prefix '${maskSsid(prefix)}*' -> ${it?.let(::maskSsid) ?: "not found"}" } }
    } catch (e: Exception) {
        DebugLog.w(TAG) { "Scan lookup failed: ${e.message}" }; null
    }

    fun disconnect() {
        DebugLog.i(TAG) { "Disconnect requested" }
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun requestNetwork() {
        release()
        DebugLog.i(TAG) {
            "Requesting WiFi: '${maskSsid(pendingSsid)}' " +
                "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"})"
        }
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
            network = activeNetwork
            resolvedSsid = activeSsid
            DebugLog.i(TAG) { "Using already-connected matching WiFi '${maskSsid(activeSsid)}'" }
            onSsidResolved?.invoke(activeSsid)
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
            return
        }

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@DashWifiManager.network = network
                reconnectJob?.cancel()
                val resolved = resolveSsid(network)
                when {
                    resolved.isNotBlank() -> {
                        resolvedSsid = resolved
                        DebugLog.i(TAG) { "WiFi callback available; resolved SSID '${maskSsid(resolved)}'" }
                        onSsidResolved?.invoke(resolved)
                        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
                    }
                    !pendingPrefix -> {
                        DebugLog.i(TAG) { "WiFi callback available for exact SSID '${maskSsid(pendingSsid)}'" }
                        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = pendingSsid)
                    }
                    else -> {
                        DebugLog.w(TAG) { "WiFi callback available but SSID is redacted; waiting for fallback resolution" }
                        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Canonical place to read the connected SSID — REQUIRED for auth, since the
                // dash validates the SSID inside the encrypted handshake (DashAuth). Without
                // the real SSID (prefix-discovery), every auth is rejected.
                val info = caps.transportInfo as? WifiInfo ?: run {
                    DebugLog.w(TAG) { "Capabilities changed without readable WiFi info; fallback polling remains active" }
                    return
                }
                val ssid = info.ssid.orEmpty().trim('"')
                if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) {
                    DebugLog.w(TAG) { "Capabilities changed with redacted WiFi SSID; fallback polling remains active" }
                    return
                }
                if (ssid == resolvedSsid) return
                resolvedSsid = ssid
                this@DashWifiManager.network = network
                DebugLog.i(TAG) { "Resolved dash SSID via capabilities: '${maskSsid(ssid)}'" }
                onSsidResolved?.invoke(ssid)
                _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
            }

            override fun onUnavailable() {
                DebugLog.w(TAG) { "WiFi unavailable — SSID not found or user declined" }
                this@DashWifiManager.network = null
                _state.value = WifiState(
                    status = WifiConnStatus.ERROR,
                    ssid   = pendingSsid,
                    error  = "Could not connect to '$pendingSsid' — network not found or wrong password",
                )
                // Don't auto-retry onUnavailable; user must try again
                wantConnected = false
            }

            override fun onLost(network: Network) {
                DebugLog.w(TAG) { "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms" }
                this@DashWifiManager.network = null
                _state.value = WifiState(
                    status = WifiConnStatus.REQUESTING,
                    ssid   = pendingSsid,
                    error  = "Link lost — reconnecting…",
                )
                if (wantConnected) scheduleReconnect()
            }
        }

        networkCallback = cb
        try {
            cm.requestNetwork(request, cb, Handler(Looper.getMainLooper()), CONNECT_TIMEOUT)
            startAndroid11SsidPolling()
        } catch (e: Exception) {
            DebugLog.e(TAG, { "requestNetwork threw: ${e.message}" }, e)
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (wantConnected) requestNetwork()
        }
    }

    /** Read the connected network's SSID (strips the surrounding quotes Android adds). */
    private fun resolveSsid(network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val info = caps.transportInfo as? WifiInfo ?: return ""
        return info.ssid.orEmpty().trim('"').let { if (it == WifiManagerUnknownSsid) "" else it }
    }

    private fun startAndroid11SsidPolling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        scope.launch {
            delay(5_000)
            repeat(6) { attempt ->
                if (_state.value.status == WifiConnStatus.CONNECTED && resolvedSsid != null) return@launch
                findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
                    network = activeNetwork
                    resolvedSsid = activeSsid
                    DebugLog.i(TAG) { "Android 11 SSID fallback #${attempt + 1} resolved '${maskSsid(activeSsid)}'" }
                    onSsidResolved?.invoke(activeSsid)
                    _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
                    return@launch
                }
                delay(2_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findAlreadyConnectedDashNetwork(): Pair<Network, String>? {
        val activeSsid = readActiveWifiSsid()?.takeIf(::matchesPendingSsid) ?: return null
        val wifiNetwork = cm.allNetworks.firstOrNull { candidate ->
            val caps = cm.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val candidateSsid = (caps.transportInfo as? WifiInfo)
                ?.ssid
                .orEmpty()
                .trim('"')
                .takeIf { it.isNotBlank() && it != WifiManagerUnknownSsid }
            candidateSsid == null || candidateSsid == activeSsid
        } ?: return null
        return wifiNetwork to activeSsid
    }

    @SuppressLint("MissingPermission")
    private fun readActiveWifiSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.connectionInfo
            ?.ssid
            .orEmpty()
            .trim('"')
            .takeIf { it.isNotBlank() && it != WifiManagerUnknownSsid }
    }.getOrNull()

    private fun matchesPendingSsid(ssid: String): Boolean =
        if (pendingPrefix) ssid.startsWith(pendingSsid) else ssid == pendingSsid

    private fun maskSsid(ssid: String): String {
        if (ssid.isBlank()) return ""
        if (ssid.length <= 4) return ssid.take(2) + "**"
        return ssid.take(3) + "****" + ssid.takeLast(2)
    }

    private fun release() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }
}
