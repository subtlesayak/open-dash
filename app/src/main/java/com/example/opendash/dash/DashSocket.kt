package com.example.opendash.dash

import android.util.Log
import com.example.opendash.dash.protocol.K1GPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP sockets for the Tripper Dash, matching better-dash exactly:
 *   TX  – bound to :2000, SO_BROADCAST, sends to 192.168.1.255:2000.
 *         The bike IP is never used for the control plane.
 *   RX  – bound to :2002. Must be open BEFORE the first TX packet, both to
 *         catch the early pubkey reply and because unanswered dash→phone
 *         packets generate ICMP port-unreachable, which confuses the dash's
 *         protocol state machine.
 *   RTP – ephemeral, sends H.264 to 192.168.1.1:5000.
 *
 * Every control packet gets the rolling K1G seq byte patched on send.
 */
class DashSocket(private val network: android.net.Network? = null) : AutoCloseable {
    companion object {
        const val DASH_IP    = "192.168.1.1"
        const val BROADCAST  = "192.168.1.255"
        const val CTRL_PORT  = 2000
        const val RX_PORT    = 2002
        const val RTP_PORT   = 5000
        private const val BUF             = 65535
        private const val RECV_TIMEOUT_MS = 500
        private const val TAG             = "DashSocket"
    }

    private val broadcastAddr: InetAddress = InetAddress.getByName(BROADCAST)
    private val dashAddr:      InetAddress = InetAddress.getByName(DASH_IP)
    private val txSocket:  DatagramSocket
    private val rxSocket:  DatagramSocket
    private val rtpSocket: DatagramSocket

    private val seq = AtomicInteger(0)

    init {
        var tx:  DatagramSocket? = null
        var rx:  DatagramSocket? = null
        var rtp: DatagramSocket? = null
        try {
            tx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.broadcast = true
                it.bind(InetSocketAddress(CTRL_PORT))
                network?.bindSocket(it)
            }
            rx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.soTimeout = RECV_TIMEOUT_MS
                it.bind(InetSocketAddress(RX_PORT))
                network?.bindSocket(it)
            }
            rtp = DatagramSocket().also { network?.bindSocket(it) }
            Log.i(TAG, "Sockets open — TX :$CTRL_PORT→$BROADCAST:$CTRL_PORT (broadcast), RX :$RX_PORT, RTP→$DASH_IP:$RTP_PORT")
            txSocket  = tx
            rxSocket  = rx
            rtpSocket = rtp
        } catch (e: Exception) {
            tx?.close(); rx?.close(); rtp?.close()
            throw e
        }
    }

    /** Send a K1G control packet (seq patched here, like K1GTx in the reference). */
    fun send(data: ByteArray) {
        val pkt = K1GPacket.patchSeq(data, seq.getAndIncrement())
        Log.d(TAG, "TX →$BROADCAST:$CTRL_PORT  ${pkt.size}B  ${pkt.hex()}")
        // UDP fire-and-forget: a dropped/unreachable link (ENETUNREACH, EBADF) must never
        // crash the app — the session will fail and reconnect.
        try {
            txSocket.send(DatagramPacket(pkt, pkt.size, broadcastAddr, CTRL_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "TX send failed (link down?): ${e.message}")
        }
    }

    fun sendRtp(data: ByteArray) {
        try {
            rtpSocket.send(DatagramPacket(data, data.size, dashAddr, RTP_PORT))
        } catch (e: Exception) {
            Log.d(TAG, "RTP send failed (link down?): ${e.message}")
        }
    }

    /**
     * Blocks up to RECV_TIMEOUT_MS; returns null on timeout. Throws on a genuine socket
     * error (closed/unreachable) — the caller's receive loop catches it and ends the
     * session instead of letting the exception crash the whole app.
     */
    suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val buf = DatagramPacket(ByteArray(BUF), BUF)
        return@withContext try {
            rxSocket.receive(buf)
            val bytes = buf.data.copyOf(buf.length)
            Log.d(TAG, "RX ←${buf.address?.hostAddress}:${buf.port}  ${bytes.size}B  ${bytes.hex()}")
            bytes
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        Log.d(TAG, "Sockets closed")
        runCatching { txSocket.close() }
        runCatching { rxSocket.close() }
        runCatching { rtpSocket.close() }
    }
}

private fun ByteArray.hex(max: Int = 64): String =
    take(max).joinToString(" ") { "%02X".format(it) }.let {
        if (size > max) "$it …(+${size - max})" else it
    }
