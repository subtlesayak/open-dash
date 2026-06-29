package com.example.opendash.dash.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.DashWallpaperKind

internal object DashWallpaperPlaybackPolicy {
    const val MAX_VIDEO_FPS = 8
    const val MIN_VIDEO_FRAME_INTERVAL_MS = 1_000L / MAX_VIDEO_FPS

    fun shouldDecodeVideoFrame(lastDecodeAtMs: Long, nowMs: Long): Boolean =
        lastDecodeAtMs == 0L || nowMs - lastDecodeAtMs >= MIN_VIDEO_FRAME_INTERVAL_MS
}

class DashIdleRenderer {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 15, 16) }

    private var cachedPath: String? = null
    private var cachedKind: DashWallpaperKind? = null
    private var cachedBitmap: Bitmap? = null
    private var cachedMovie: Movie? = null
    private var cachedRetriever: MediaMetadataRetriever? = null
    private var cachedVideoDurationMs: Long = 0L
    private var lastVideoFrameDecodeAtMs: Long = 0L

    fun draw(
        canvas: Canvas,
        wallpaperPath: String?,
        kind: DashWallpaperKind?,
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
        preserveRpmArc: Boolean = false,
    ) {
        canvas.drawRect(0f, 0f, DashEncoder.WIDTH.toFloat(), DashEncoder.HEIGHT.toFloat(), bgPaint)
        val target = if (preserveRpmArc) analogLowerDisplayRect() else fullFrameRect()
        when (kind) {
            DashWallpaperKind.GIF -> drawGif(canvas, wallpaperPath, horizontalBias, verticalBias, fit, target)
            DashWallpaperKind.VIDEO -> drawVideo(canvas, wallpaperPath, horizontalBias, verticalBias, fit, target)
            else -> loadBitmap(wallpaperPath)?.let { drawBitmap(canvas, it, horizontalBias, verticalBias, fit, target) }
        }
    }

    fun release() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedMovie = null
        cachedRetriever?.release()
        cachedRetriever = null
        cachedVideoDurationMs = 0L
        lastVideoFrameDecodeAtMs = 0L
        cachedPath = null
        cachedKind = null
    }

    private fun loadBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        if (path == cachedPath && cachedKind == DashWallpaperKind.IMAGE && cachedBitmap?.isRecycled == false) {
            return cachedBitmap
        }
        clearCache()
        cachedPath = path
        cachedKind = DashWallpaperKind.IMAGE
        cachedBitmap = BitmapFactory.decodeFile(path)?.let { decoded ->
            if (decoded.width == DashEncoder.WIDTH && decoded.height == DashEncoder.HEIGHT) decoded
            else Bitmap.createScaledBitmap(decoded, DashEncoder.WIDTH, DashEncoder.HEIGHT, true).also {
                decoded.recycle()
            }
        }
        return cachedBitmap
    }

    @Suppress("DEPRECATION")
    private fun drawGif(
        canvas: Canvas,
        path: String?,
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
        target: Rect,
    ) {
        if (path.isNullOrBlank()) return
        if (path != cachedPath || cachedKind != DashWallpaperKind.GIF) {
            clearCache()
            cachedPath = path
            cachedKind = DashWallpaperKind.GIF
            cachedMovie = Movie.decodeFile(path)
        }
        val movie = cachedMovie ?: return
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        movie.setTime((System.currentTimeMillis() % duration).toInt())
        drawMovie(canvas, movie, horizontalBias, verticalBias, fit, target)
    }

    private fun drawVideo(
        canvas: Canvas,
        path: String?,
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
        target: Rect,
    ) {
        if (path.isNullOrBlank()) return
        if (path != cachedPath || cachedKind != DashWallpaperKind.VIDEO) {
            clearCache()
            cachedPath = path
            cachedKind = DashWallpaperKind.VIDEO
            cachedRetriever = MediaMetadataRetriever().apply { setDataSource(path) }
            cachedVideoDurationMs = cachedRetriever
                ?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 1000L
        }
        val retriever = cachedRetriever ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (DashWallpaperPlaybackPolicy.shouldDecodeVideoFrame(lastVideoFrameDecodeAtMs, nowMs)) {
            lastVideoFrameDecodeAtMs = nowMs
            val timeUs = (System.currentTimeMillis() % cachedVideoDurationMs) * 1000L
            runCatching {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull()?.let { frame ->
                cachedBitmap?.recycle()
                cachedBitmap = frame
            }
        }
        cachedBitmap?.takeIf { !it.isRecycled }?.let {
            drawBitmap(canvas, it, horizontalBias, verticalBias, fit, target)
        }
    }

    private fun drawBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
        target: Rect,
    ) {
        when (fit) {
            DashWallpaperFit.CROP -> drawBitmapCropped(canvas, bitmap, horizontalBias, verticalBias, target)
            DashWallpaperFit.FIT_HEIGHT -> drawBitmapFit(canvas, bitmap, fitHeight = true, target)
            DashWallpaperFit.FIT_WIDTH -> drawBitmapFit(canvas, bitmap, fitHeight = false, target)
        }
    }

    private fun drawBitmapCropped(
        canvas: Canvas,
        bitmap: Bitmap,
        horizontalBias: Float,
        verticalBias: Float,
        target: Rect,
    ) {
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstRatio = target.width().toFloat() / target.height().toFloat()
        val src = if (srcRatio > dstRatio) {
            val cropW = (bitmap.height * dstRatio).toInt().coerceAtLeast(1)
            val extra = (bitmap.width - cropW).coerceAtLeast(0)
            val left = ((extra / 2f) + (extra / 2f) * horizontalBias.coerceIn(-1f, 1f)).toInt()
            Rect(left, 0, left + cropW, bitmap.height)
        } else {
            val cropH = (bitmap.width / dstRatio).toInt().coerceAtLeast(1)
            val extra = (bitmap.height - cropH).coerceAtLeast(0)
            val top = ((extra / 2f) + (extra / 2f) * verticalBias.coerceIn(-1f, 1f)).toInt()
            Rect(0, top, bitmap.width, top + cropH)
        }
        canvas.drawBitmap(bitmap, src, target, null)
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap, fitHeight: Boolean, target: Rect) {
        val scale = if (fitHeight) {
            target.height().toFloat() / bitmap.height.toFloat()
        } else {
            target.width().toFloat() / bitmap.width.toFloat()
        }
        val drawW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val drawH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val left = target.left + (target.width() - drawW) / 2
        val top = target.top + (target.height() - drawH) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + drawW, top + drawH), null)
    }

    private fun drawMovie(
        canvas: Canvas,
        movie: Movie,
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
        target: Rect,
    ) {
        val dstW = target.width().toFloat()
        val dstH = target.height().toFloat()
        val scale = when (fit) {
            DashWallpaperFit.CROP -> maxOf(dstW / movie.width(), dstH / movie.height())
            DashWallpaperFit.FIT_HEIGHT -> dstH / movie.height()
            DashWallpaperFit.FIT_WIDTH -> dstW / movie.width()
        }
        val drawnW = movie.width() * scale
        val drawnH = movie.height() * scale
        val extraX = (drawnW - dstW).coerceAtLeast(0f)
        val extraY = (drawnH - dstH).coerceAtLeast(0f)
        val left = if (extraX > 0f) {
            -extraX / 2f - extraX / 2f * horizontalBias.coerceIn(-1f, 1f)
        } else {
            -extraX / 2f
        }
        val top = if (extraY > 0f) {
            -extraY / 2f - extraY / 2f * verticalBias.coerceIn(-1f, 1f)
        } else {
            -extraY / 2f
        }
        val save = canvas.save()
        canvas.clipRect(target)
        canvas.translate(target.left + left, target.top + top)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        canvas.restoreToCount(save)
    }

    private fun clearCache() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedMovie = null
        cachedRetriever?.release()
        cachedRetriever = null
        cachedVideoDurationMs = 0L
        lastVideoFrameDecodeAtMs = 0L
    }

    private fun fullFrameRect(): Rect =
        Rect(0, 0, DashEncoder.WIDTH, DashEncoder.HEIGHT)

    private fun analogLowerDisplayRect(): Rect =
        Rect(
            (DashEncoder.WIDTH * 0.09f).toInt(),
            (DashEncoder.HEIGHT * 0.64f).toInt(),
            (DashEncoder.WIDTH * 0.96f).toInt(),
            (DashEncoder.HEIGHT * 0.98f).toInt(),
        )
}
