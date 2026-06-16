package com.example.opendash.dash.video

import java.util.Random

/**
 * RFC 6184 H.264 RTP packetizer tuned for the Tripper Dash.
 *
 * Rules enforced (from better-dash analysis):
 *  - NO STAP-A (type 24) — dash rejects aggregation packets
 *  - FU-A (type 28) fragmentation for NALs larger than MAX_PAYLOAD
 *  - Marker bit only on the LAST RTP packet of each access unit
 *  - 90 kHz RTP clock
 *  - Max payload 1380 bytes (avoids IP fragmentation on 192.168.1.x)
 */
class RtpPacketizer(private val onPacket: (ByteArray) -> Unit) {
    companion object {
        private const val MAX_PAYLOAD = 1380
        private const val PT = 96
    }

    private val rng = Random()
    private var seq   = rng.nextInt(0xFFFF)
    private val ssrc  = rng.nextInt()
    private val tsBase = rng.nextInt().toLong() and 0xFFFFFFFFL

    /**
     * Packetize a single NAL unit.
     * @param nal         raw NAL bytes (no start code)
     * @param endOfAU     true if this is the last NAL in the access unit (triggers marker bit)
     * @param wallClockMs monotonic wall clock in milliseconds
     */
    fun packetize(nal: ByteArray, endOfAU: Boolean, wallClockMs: Long) {
        val ts = (tsBase + wallClockMs * 90L) and 0xFFFFFFFFL
        if (nal.size <= MAX_PAYLOAD) {
            emit(nal, marker = endOfAU, ts = ts)
        } else {
            fuA(nal, endOfAU, ts)
        }
    }

    private fun fuA(nal: ByteArray, endOfAU: Boolean, ts: Long) {
        val nalType  = nal[0].toInt() and 0x1F
        val fuInd    = ((nal[0].toInt() and 0xE0) or 28).toByte()
        var offset   = 1
        var isFirst  = true
        while (offset < nal.size) {
            val remaining = nal.size - offset
            val chunkLen  = minOf(MAX_PAYLOAD - 2, remaining)
            val isLast    = chunkLen >= remaining

            val fuHeader = ((if (isFirst) 0x80 else 0) or
                           (if (isLast)  0x40 else 0) or nalType).toByte()

            val payload = ByteArray(2 + chunkLen)
            payload[0] = fuInd
            payload[1] = fuHeader
            nal.copyInto(payload, 2, offset, offset + chunkLen)

            emit(payload, marker = isLast && endOfAU, ts = ts)
            offset += chunkLen
            isFirst = false
        }
    }

    private fun emit(payload: ByteArray, marker: Boolean, ts: Long) {
        val pkt = ByteArray(12 + payload.size)
        pkt[0] = 0x80.toByte()
        pkt[1] = ((if (marker) 0x80 else 0x00) or (PT and 0x7F)).toByte()
        pkt[2] = ((seq shr 8) and 0xFF).toByte()
        pkt[3] = (seq and 0xFF).toByte()
        pkt[4] = ((ts shr 24) and 0xFF).toByte()
        pkt[5] = ((ts shr 16) and 0xFF).toByte()
        pkt[6] = ((ts shr  8) and 0xFF).toByte()
        pkt[7] = (ts and 0xFF).toByte()
        val s = ssrc.toLong() and 0xFFFFFFFFL
        pkt[8]  = ((s shr 24) and 0xFF).toByte()
        pkt[9]  = ((s shr 16) and 0xFF).toByte()
        pkt[10] = ((s shr  8) and 0xFF).toByte()
        pkt[11] = (s and 0xFF).toByte()
        payload.copyInto(pkt, 12)
        seq = (seq + 1) and 0xFFFF
        onPacket(pkt)
    }
}
