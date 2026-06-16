package com.example.opendash.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * MediaCodec H.264 encoder for the Tripper Dash stream:
 *   526 × 300, up to 24 fps, ~1.2 Mbps, Baseline L4.1, 1-second IDR interval.
 *
 * [FPS] is the MAX/hint rate — the frame loop feeds ~24 fps while moving (buttery,
 * Android-Auto-style motion) and throttles to a few fps when stopped to save power.
 * The hardware encoder auto-timestamps each frame from the input surface, so the
 * variable feed rate is fine. Tiny resolution → still far under the OLED-projection
 * draw this whole project exists to avoid.
 *
 * Frames are drawn with Android Canvas via the input Surface's hardware
 * canvas — call [renderFrame] with a draw lambda, then [drain] to pull
 * encoded NAL data out.
 *
 * @param onEncodedData  called with (annexBBytes, isKeyFrame) for each output buffer.
 */
class DashEncoder(private val onEncodedData: (ByteArray, Boolean) -> Unit) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        const val FPS     = 24
        const val BITRATE = 1_200_000
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val TAG = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        // Prefer an explicit HARDWARE AVC encoder — a software fallback runs the
        // CPU hot, which is exactly what this whole project exists to avoid.
        val name = selectHardwareEncoder(format)
        codec = (if (name != null) MediaCodec.createByCodecName(name)
                 else MediaCodec.createEncoderByType(MIME)).also { c ->
            Log.i(TAG, "Encoder: ${c.name}")
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        }
    }

    /** Find a hardware AVC encoder that supports this format; null → let the OS pick. */
    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // First try true hardware-accelerated encoders (API 29+ flag).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
                if (info.isHardwareAccelerated && !info.isSoftwareOnly) {
                    return info.name
                }
            }
        }
        // Fallback: the OS-preferred encoder for this format.
        return runCatching { list.findEncoderForFormat(format) }.getOrNull()
    }

    /** Draw one frame into the encoder via hardware canvas. */
    fun renderFrame(draw: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            return
        }
        try {
            draw(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Pull all available encoded buffers; call after every [renderFrame]. */
    fun drain() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // SPS/PPS come as CODEC_CONFIG buffer
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    // Pass EVERY buffer through, including CODEC_CONFIG (SPS/PPS) — the
                    // NAL processor needs the parameter sets to bundle them with each IDR,
                    // otherwise the dash can't initialise its decoder and times out.
                    if (info.size > 0) {
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
