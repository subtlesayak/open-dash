package com.example.opendash.dash.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.DashWallpaperKind

class DashIdleRenderer {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 15, 16) }

    private var cachedPath: String? = null
    private var cachedKind: DashWallpaperKind? = null
    private var cachedBitmap: Bitmap? = null
    private var cachedMovie: Movie? = null
    private var cachedRetriever: MediaMetadataRetriever? = null
    private var cachedVideoDurationMs: Long = 0L

    fun draw(
        canvas: Canvas,
        wallpaperPath: String?,
        kind: DashWallpaperKind?,
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
    ) {
        canvas.drawRect(0f, 0f, DashEncoder.WIDTH.toFloat(), DashEncoder.HEIGHT.toFloat(), bgPaint)
        when (kind) {
            DashWallpaperKind.GIF -> drawGif(canvas, wallpaperPath, horizontalBias, verticalBias, fit)
            DashWallpaperKind.VIDEO -> drawVideo(canvas, wallpaperPath, horizontalBias, verticalBias, fit)
            else -> loadBitmap(wallpaperPath)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    fun release() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedMovie = null
        cachedRetriever?.release()
        cachedRetriever = null
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
    private fun drawGif(canvas: Canvas, path: String?, horizontalBias: Float, verticalBias: Float, fit: DashWallpaperFit) {
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
        drawMovie(canvas, movie, horizontalBias, verticalBias, fit)
    }

    private fun drawVideo(canvas: Canvas, path: String?, horizontalBias: Float, verticalBias: Float, fit: DashWallpaperFit) {
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
        val timeUs = (System.currentTimeMillis() % cachedVideoDurationMs) * 1000L
        val frame = runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return
        drawBitmap(canvas, frame, horizontalBias, verticalBias, fit)
        frame.recycle()
    }

    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, horizontalBias: Float, verticalBias: Float, fit: DashWallpaperFit) {
        when (fit) {
            DashWallpaperFit.CROP -> drawBitmapCropped(canvas, bitmap, horizontalBias, verticalBias)
            DashWallpaperFit.FIT_HEIGHT -> drawBitmapFit(canvas, bitmap, fitHeight = true)
            DashWallpaperFit.FIT_WIDTH -> drawBitmapFit(canvas, bitmap, fitHeight = false)
        }
    }

    private fun drawBitmapCropped(canvas: Canvas, bitmap: Bitmap, horizontalBias: Float, verticalBias: Float) {
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstRatio = DashEncoder.WIDTH.toFloat() / DashEncoder.HEIGHT.toFloat()
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
        canvas.drawBitmap(bitmap, src, Rect(0, 0, DashEncoder.WIDTH, DashEncoder.HEIGHT), null)
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap, fitHeight: Boolean) {
        val scale = if (fitHeight) {
            DashEncoder.HEIGHT.toFloat() / bitmap.height.toFloat()
        } else {
            DashEncoder.WIDTH.toFloat() / bitmap.width.toFloat()
        }
        val drawW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val drawH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val left = (DashEncoder.WIDTH - drawW) / 2
        val top = (DashEncoder.HEIGHT - drawH) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + drawW, top + drawH), null)
    }

    private fun drawMovie(canvas: Canvas, movie: Movie, horizontalBias: Float, verticalBias: Float, fit: DashWallpaperFit) {
        val dstW = DashEncoder.WIDTH.toFloat()
        val dstH = DashEncoder.HEIGHT.toFloat()
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
        canvas.translate(left, top)
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
    }
}
