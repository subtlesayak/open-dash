package com.example.opendash.dash.protocol

import java.io.ByteArrayOutputStream

/**
 * K1G packet format — verified against better-dash (tripper_app_like_nav.py).
 *
 * OUTGOING (app → dash), big-endian:
 *   [0:2]  outer_len   – total packet size including this field
 *   [2:4]  seg_count   – 1 (fixed header segment) + N TLV segments
 *   [4:8]  zeros
 *   [8:10] flags       – always 0x02 0x01
 *   [10:12] const      – always 0x00 0x05
 *   [12:16] magic      – "K1G " (0x4B 0x31 0x47 0x20)
 *   [16]   seq         – rolling 0–255, patched at send time (see [patchSeq])
 *   [17+]  TLV entries – (type:1)(sub:1)(len:2be)(value:len)
 *
 * INCOMING (dash → app) uses a SHORTER header — segments start at offset 8
 * (same slicing as the app's clk.T()/clk.U() and better-dash's
 * decode_ic_to_app_segments):
 *   [0:2] outer_len  [2:4] seg_count  [4:8] ignored  [8+] TLVs
 */
data class Tlv(val type: Int, val sub: Int, val value: ByteArray = ByteArray(0))

object K1GPacket {
    private val MAGIC = byteArrayOf(0x4B, 0x31, 0x47, 0x20) // "K1G "

    private val FIXED = byteArrayOf(
        0x00, 0x00, 0x00, 0x00,        // reserved
        0x02, 0x01, 0x00, 0x05,        // flags
        0x4B, 0x31, 0x47, 0x20,        // "K1G "
    )

    /** Build an outgoing packet. Seq byte is left 0x00 — [patchSeq] sets it at send time. */
    fun build(vararg tlvs: Tlv): ByteArray {
        val segCount = 1 + tlvs.size

        val out = ByteArrayOutputStream()
        out.write(0); out.write(0)                     // outer_len placeholder
        out.write((segCount shr 8) and 0xFF)
        out.write(segCount and 0xFF)
        out.write(FIXED)
        out.write(0)                                    // seq placeholder
        for (tlv in tlvs) {
            out.write(tlv.type and 0xFF)
            out.write(tlv.sub and 0xFF)
            out.write((tlv.value.size shr 8) and 0xFF)
            out.write(tlv.value.size and 0xFF)
            out.write(tlv.value)
        }

        val bytes = out.toByteArray()
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

    /** Patch the rolling seq byte (right after "K1G ") and fix outer_len. In-place on a copy. */
    fun patchSeq(pkt: ByteArray, seq: Int): ByteArray {
        val out = pkt.copyOf()
        val k = indexOfMagic(out)
        if (k >= 0 && k + 4 < out.size) out[k + 4] = (seq and 0xFF).toByte()
        out[0] = ((out.size shr 8) and 0xFF).toByte()
        out[1] = (out.size and 0xFF).toByte()
        return out
    }

    fun tlv(type: Int, sub: Int, vararg values: Int): Tlv =
        Tlv(type, sub, ByteArray(values.size) { values[it].toByte() })

    fun tlv(type: Int, sub: Int, value: ByteArray): Tlv = Tlv(type, sub, value)

    /** Parse a dash → app packet. Segments start at offset 8. */
    fun parseIncoming(data: ByteArray): List<Tlv> {
        val tlvs = mutableListOf<Tlv>()
        if (data.size < 8) return tlvs
        val segCount = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        var i = 8
        var n = 0
        while (n < segCount && i + 4 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val sub  = data[i + 1].toInt() and 0xFF
            val len  = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            val end = (i + len).coerceAtMost(data.size)
            tlvs += Tlv(type, sub, data.copyOfRange(i, end))
            i = end
            n++
        }
        return tlvs
    }

    private fun indexOfMagic(b: ByteArray): Int {
        outer@ for (i in 0..b.size - 4) {
            for (j in 0..3) if (b[i + j] != MAGIC[j]) continue@outer
            return i
        }
        return -1
    }
}

fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "")
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
