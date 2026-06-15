package com.example.northstar.dash

import android.util.Log
import com.example.northstar.dash.protocol.DashCommands
import com.example.northstar.dash.protocol.K1GPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * Tripper Dash session, sequenced to match better-dash (tripper_app_like_nav.py):
 *   1. Open sockets (RX :2002 bound first).
 *   2. Send initial burst on :2000 (includes q3c.e request-auth).
 *   3. RX loop ingests 07 00 / 07 03 → sends q3c.d → waits for 07 01 01.
 *   4. Nav entry: route-card ×4 → projectionFrame → z2 (once) → route-card.
 *   5. Start RTP + 4 Hz projection heartbeat + 1 Hz route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, 09 06 IDR-decoded acks,
 * and 09 00 button events.
 */
class DashSession(private val scope: CoroutineScope) {
    companion object {
        private const val TAG           = "DashSession"
        private const val AUTH_TIMEOUT  = 15_000L
        private const val BURST_PAUSE   = 20L
        private const val PROJ_HB_MS     = 250L   // 4 Hz
        private const val ROUTE_CARD_MS  = 1_000L // 1 Hz keep-alive
        private const val HOSTNAME       = "Northstar"
    }

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0

    var onButton: ((Byte) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    @Volatile var destinationName: String = "Northstar"

    private var sessionJob: Job? = null
    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navInfoJob: Job? = null

    // Live nav-info pushed to the dash bubble at ~1 Hz (set by NavEngine output).
    @Volatile private var navManeuver = DashCommands.NAV_MANEUVER_CONTINUE
    @Volatile private var navPrimaryDist = 0
    @Volatile private var navPrimaryUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navTotalDist = 0
    @Volatile private var navTotalUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navEta: String? = null
    @Volatile private var navActive = false
    @Volatile private var navChromeEnabled = false

    /** Push the latest turn-by-turn figures; sent to the dash at 1 Hz. */
    fun updateNavInfo(
        maneuver: Int, primaryDist: Int, primaryUnit: Int,
        totalDist: Int, totalUnit: Int, etaHHMM: String? = null,
    ) {
        navManeuver = maneuver
        navPrimaryDist = primaryDist
        navPrimaryUnit = primaryUnit
        navTotalDist = totalDist
        navTotalUnit = totalUnit
        navEta = etaHHMM
        navActive = true
        navChromeEnabled = true
    }

    /**
     * Route card with the LIVE nav figures patched in. The template's captured
     * values (7.9 km / glyph 0x3C / ETA 03:03) must never reach the dash once
     * real guidance is running — the card repeats at 1 Hz and would stomp the
     * activeNavPacket numbers every second.
     */
    private fun liveRouteCard(projectionOn: Boolean): ByteArray =
        if (navActive) DashCommands.routeCard(
            destinationName, projectionOn,
            maneuver = navManeuver,
            primaryUnit = navPrimaryUnit,
            totalDist = navTotalDist,
            totalUnit = navTotalUnit,
            etaHHMM = navEta,
        )
        else DashCommands.routeCard(destinationName, projectionOn)

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String, network: android.net.Network? = null) {
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        Log.i(TAG, "connect() — ssid='$ssid' network=$network")
        sessionJob = scope.launch(Dispatchers.IO) { runSession(ssid, network) }
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        _state.value = DashState.STREAMING
        // Projection heartbeat is always needed. Route-card/nav-info keepalives are
        // only for active navigation; idle wallpaper mode should stay chrome-free.
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
    }

    fun sendRtp(packet: ByteArray) { socket?.sendRtp(packet) }

    fun updateRouteCard(name: String) {
        destinationName = name.ifBlank { "Northstar" }
        navActive = false   // new destination — old figures are stale until the next updateNavInfo
        navChromeEnabled = destinationName != "Northstar"
        if (navChromeEnabled && (_state.value == DashState.READY || _state.value == DashState.STREAMING)) {
            scope.launch(Dispatchers.IO) {
                socket?.send(liveRouteCard(projectionOn = true))
            }
        } else if (_state.value == DashState.READY || _state.value == DashState.STREAMING) {
            scope.launch(Dispatchers.IO) {
                socket?.send(DashCommands.projectionStop())
                delay(40)
                socket?.send(DashCommands.projectionOff())
                delay(40)
                socket?.send(DashCommands.projectionFrame())
                delay(40)
                socket?.send(DashCommands.projectionOn())
            }
        }
    }

    fun disconnect() {
        // Cancel the session coroutine FIRST so it can't race past auth and flip state to
        // READY after we tear down (which would re-trigger streaming on a dead socket).
        sessionJob?.cancel(); sessionJob = null
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel(); navInfoJob?.cancel()
        navActive = false
        navChromeEnabled = false
        socket?.let {
            runCatching { it.send(DashCommands.projectionStop()) }
            runCatching { it.send(DashCommands.projectionOff()) }
            it.close()
        }
        socket = null
        _state.value = DashState.IDLE
        Log.i(TAG, "Disconnected")
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun runSession(ssid: String, network: android.net.Network? = null) {
        try {
            _state.value = DashState.CONNECTING
            val sock = try {
                DashSocket(network).also { socket = it }
            } catch (e: java.net.BindException) {
                fail("Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            authConfirmed = false
            authRetries = 0

            // RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(sock)
            // 1 Hz status heartbeat throughout the session.
            launchStatusHeartbeat(sock)

            _state.value = DashState.AUTHENTICATING
            Log.i(TAG, "Sending initial burst…")
            for (pkt in DashCommands.initialBurst(HOSTNAME)) {
                sock.send(pkt)
                delay(BURST_PAUSE)
            }

            Log.i(TAG, "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…")
            val deadline = System.currentTimeMillis() + AUTH_TIMEOUT
            while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)

            if (!authConfirmed) {
                fail("Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.")
                return
            }
            Log.i(TAG, "Authenticated ✓")

            if (navChromeEnabled) enterNavMode(sock) else enterIdleProjectionMode(sock)
            _state.value = DashState.READY
            Log.i(TAG, "READY ✓")

        } catch (e: Exception) {
            Log.e(TAG, "Session error", e)
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Nav entry in the exact phone order (nav_open_ok.pcap):
     *   route-card ×4 (establishes destination) → projectionFrame
     *   → z2 once → route-card confirmation.
     */
    private suspend fun enterNavMode(sock: DashSocket) {
        sock.send(DashCommands.navContext()); delay(40)
        sock.send(DashCommands.emptyLists()); delay(40)

        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.navPlaceholder()); delay(10)
        sock.send(DashCommands.navStart()); delay(40)                 // z2, ONCE
        sock.send(DashCommands.routeCard(destinationName, projectionOn = true))
        Log.i(TAG, "Nav mode kick sent")
    }

    /**
     * Idle wallpaper mode: open projection without route-card/nav-start chrome.
     * Active navigation still uses [enterNavMode] unchanged.
     */
    private suspend fun enterIdleProjectionMode(sock: DashSocket) {
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.projectionOn()); delay(40)
        Log.i(TAG, "Idle projection kick sent")
    }

    private fun launchReceiveLoop(sock: DashSocket) {
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = try {
                    sock.receive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Link dropped (EBADF/ENETUNREACH) — end the loop cleanly instead of
                    // crashing the app; DashWifiManager handles reconnect.
                    Log.w(TAG, "RX loop stopped — socket error: ${e.message}")
                    onError?.invoke("Lost connection to dash")
                    break
                } ?: continue
                dispatchIncoming(pkt, sock)
            }
        }
    }

    private fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val tlvs = K1GPacket.parseIncoming(pkt)
        // Dump the full raw packet for anything that ISN'T just the per-frame decode
        // acks (09 06 55 / 09 04 55) — those fire ~8×/s and would drown the log. This
        // captures joystick events, telemetry, and any unknown TLV in full hex so a
        // single `adb logcat -s DashSession` session is enough to reverse the protocol.
        val onlyAcks = tlvs.isNotEmpty() && tlvs.all {
            it.type == 0x09 && (it.sub == 0x06 || it.sub == 0x04) &&
                it.value.firstOrNull()?.toInt() == 0x55
        }
        if (!onlyAcks) Log.i(TAG, "RX RAW (${pkt.size}B): ${pkt.toHexFull()}")
        for (tlv in tlvs) {
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        Log.i(TAG, "Got RSA pubkey — sending q3c.d")
                        sock.send(ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true }
                    AuthEvent.Rejected -> {
                        authRetries++
                        Log.w(TAG, "Auth rejected — retry #$authRetries")
                        auth?.reset()
                        if (authRetries <= 5) sock.send(DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── 09 06 55: per-IDR frame-decoded notify → mandatory q3c.L2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x06 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                sock.send(DashCommands.frameDecodedIdr())
                continue
            }
            // ── 09 04 55: P-frame decoded → q3c.K2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x04 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                sock.send(DashCommands.frameDecodedP())
                continue
            }
            // ── 09 00: button / joystick event → echo ack + notify UI ──
            if (tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()  // 0900 0001 <code>
                Log.i(TAG, "JOYSTICK 09 00 → code 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}  full=${tlv.value.toHexFull()}")
                sock.send(DashCommands.buttonAck(btn))
                scope.launch(Dispatchers.Main) { onButton?.invoke(btn) }
                continue
            }
            // ── 0F: vehicle-secure telemetry (AES-256-CBC under the session key,
            //    IV = first 16 bytes). This is the dash's instrument-cluster data
            //    (likely trip/odo/fuel/speed/temp). The better-dash reference only
            //    logs these as ciphertext — we actually DECRYPT with our session key
            //    and log the plaintext for field-mapping (P1b). It arrives over our
            //    own session, so plain `adb logcat -s DashSession` captures it — no
            //    root, no monitor mode. ──
            if (tlv.type == 0x0F) {
                val key = auth?.sessionKey
                val plain = key?.let { aesDecryptCbc(tlv.value, it) }
                Log.i(TAG, "DASH TELEMETRY 0F sub=0x%02X enc(%dB)=%s  dec=%s".format(
                    tlv.sub, tlv.value.size, tlv.value.toHexFull(),
                    plain?.toHexFull() ?: "<key=${key != null}; decrypt failed>"))
                continue
            }
            // ── 0C xx: dash → app telemetry (trip/odo/fuel/temp — P1b) ──
            if (tlv.type == 0x0C) {
                Log.i(TAG, "DASH TELEMETRY 0C sub=0x%02X (%dB) val=%s"
                    .format(tlv.sub, tlv.value.size, tlv.value.toHexFull()))
                continue
            }
            // Log every OTHER incoming event (e.g. joystick in nav view, or the dash's
            // 'exit navigation' selection) in FULL so its TLV can be identified + mapped.
            Log.i(TAG, "DASH EVENT type=0x%02X sub=0x%02X (%dB) val=%s"
                .format(tlv.type, tlv.sub, tlv.value.size, tlv.value.toHexFull()))
        }
    }

    private fun launchStatusHeartbeat(sock: DashSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                runCatching { sock.send(DashCommands.heartbeat()) }
                // Keep the dash clock correct — it only shows what the phone feeds it.
                if (n++ % 30 == 0) runCatching { sock.send(DashCommands.timeSync()) }
                delay(1_000)
            }
        }
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(DashCommands.projectionFrame())
                delay(PROJ_HB_MS)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navChromeEnabled) socket?.send(liveRouteCard(projectionOn = true))
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchNavInfo() {
        navInfoJob?.cancel()
        navInfoJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navChromeEnabled && navActive) {
                    socket?.send(
                        DashCommands.activeNavPacket(
                            maneuver = navManeuver,
                            primaryDist = navPrimaryDist,
                            primaryUnit = navPrimaryUnit,
                            totalDist = navTotalDist,
                            totalUnit = navTotalUnit,
                        )
                    )
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, "ERROR — $msg")
        rxJob?.cancel(); heartbeatJob?.cancel()
        socket?.close(); socket = null
        _state.value = DashState.ERROR
        onError?.invoke(msg)
    }

    /** Full hex dump (no truncation) — used for protocol-capture logging. */
    private fun ByteArray.toHexFull(): String =
        joinToString(" ") { "%02X".format(it) }

    /** AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key. */
    private fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (ivAndCt.size <= 16) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
        )
        cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
    }.getOrNull()
}
