package com.example.opendash.dash.video

/**
 * Splits Annex-B H.264 output from MediaCodec into individual NAL units,
 * handles the dash-specific IDR bundling requirement, and filters NAL types
 * the dash rejects (SEI, AUD).
 *
 * Dash-specific rules (from better-dash analysis):
 *  - SPS (type 7) and PPS (type 8): cache, do NOT send raw
 *  - IDR (type 5): prepend cached SPS + PPS with Annex-B start codes, send bundle
 *  - SEI (type 6) and AUD (type 9): discard
 *  - All other slices (types 1–4): send as-is
 */
class NalProcessor(private val onNal: (ByteArray, Boolean) -> Unit) {
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private val TAG = "NalProcessor"

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var loggedParams = false
    private var idrCount = 0

    fun process(annexB: ByteArray) {
        for (nal in split(annexB)) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                7    -> sps = normalizeSpsForDash(nal)
                8    -> pps = nal
                5    -> emitIdr(nal)
                6, 9 -> Unit // SEI, AUD — discard
                else -> if (type in 1..4 || type in 10..12) onNal(nal, false)
            }
        }
    }

    /**
     * The Tripper firmware whitelists the stock phone's SPS shape (67 42 00 29…)
     * before it will leave the loading state. MediaCodec emits a different
     * constraint byte (e.g. 67 42 C0 29…); rewrite byte[2] to 0x00 to match.
     * The constraint byte doesn't affect slice-header parsing, so this is safe.
     */
    private fun normalizeSpsForDash(sps: ByteArray): ByteArray {
        if (sps.size >= 4 &&
            (sps[0].toInt() and 0x1F) == 7 &&
            sps[1] == 0x42.toByte() &&
            sps[3] == 0x29.toByte()
        ) {
            val out = sps.copyOf()
            out[2] = 0x00
            return out
        }
        return sps
    }

    private fun emitIdr(idr: ByteArray) {
        val s = sps; val p = pps
        if (!loggedParams && s != null && p != null) {
            loggedParams = true
            android.util.Log.i(TAG, "SPS=${s.hex()} PPS=${p.hex()}")
        }
        if (s == null || p == null) {
            android.util.Log.w(TAG, "IDR with no SPS/PPS cached — dash will not decode")
        }
        val nal = if (s != null && p != null) {
            s + START_CODE_4 + p + START_CODE_4 + idr
        } else idr
        if (++idrCount <= 3) android.util.Log.d(TAG, "emit IDR #$idrCount (${nal.size}B bundled)")
        onNal(nal, true)
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    /** Split Annex-B stream on 4-byte (0x00000001) or 3-byte (0x000001) start codes. */
    private fun split(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < data.size) {
            val sc4 = i + 3 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val sc3 = !sc4 && i + 2 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 1.toByte()
            when {
                sc4 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 4; i += 4 }
                sc3 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 3; i += 3 }
                else -> i++
            }
        }
        if (start in 0 until data.size) nals += data.copyOfRange(start, data.size)
        return nals
    }
}
