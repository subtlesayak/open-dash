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
import android.os.PatternMatcher
import android.util.Log
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
 * Programmatically connects to the Tripper Dash WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * bindProcessToNetwork() routes all UDP/TCP from this process through
 * the Tripper network so packets reach 192.168.1.1 even if the phone
 * has another network (cellular) available.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 30_000  // ms — Android shows system dialog within this
        private const val RECONNECT_DELAY  = 4_000L
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
     *   any rider's Tripper without hardcoding their SSID. When false, exact-match.
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
            .also { Log.i(TAG, "Scan lookup for '$prefix*' → ${it ?: "not found"}") }
    } catch (e: Exception) {
        Log.w(TAG, "Scan lookup failed: ${e.message}"); null
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun requestNetwork() {
        release()
        Log.i(TAG, "Requesting WiFi: '$pendingSsid' " +
            "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"})")
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@DashWifiManager.network = network
                reconnectJob?.cancel()
                // SSID is read in onCapabilitiesChanged (transportInfo isn't populated here
                // yet on Android 13+). Try once anyway, else fall back to the pending value.
                val resolved = resolveSsid(network).takeIf { it.isNotBlank() } ?: pendingSsid
                Log.i(TAG, "WiFi connected ✓  ssid='$resolved' network=$network")
                if (resolved.isNotBlank() && resolved != pendingSsid) onSsidResolved?.invoke(resolved)
                _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Canonical place to read the connected SSID — REQUIRED for auth, since the
                // dash validates the SSID inside the encrypted handshake (DashAuth). Without
                // the real SSID (prefix-discovery), every auth is rejected.
                val info = caps.transportInfo as? WifiInfo ?: return
                val ssid = info.ssid.orEmpty().trim('"')
                if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) return
                if (ssid == resolvedSsid) return
                resolvedSsid = ssid
                this@DashWifiManager.network = network
                Log.i(TAG, "Resolved dash SSID: '$ssid'")
                onSsidResolved?.invoke(ssid)
                _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
            }

            override fun onUnavailable() {
                Log.w(TAG, "WiFi unavailable — SSID not found or user declined")
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
                Log.w(TAG, "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms")
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
            cm.requestNetwork(request, cb, CONNECT_TIMEOUT)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork threw: ${e.message}", e)
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

    private fun release() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }
}
