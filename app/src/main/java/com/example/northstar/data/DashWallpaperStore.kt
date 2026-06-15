package com.example.northstar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.graphics.ImageDecoder
import com.example.northstar.dash.video.DashEncoder
import java.io.File
import java.util.Locale

object DashWallpaperPaths {
    const val MAX_SLOTS = 5
    const val DIRECTORY = "dash_wallpaper"
    const val FILE_NAME = "idle_wallpaper.png"
    const val SOURCE_FILE_NAME = "source_wallpaper"

    fun relativePath(): String = "$DIRECTORY/$FILE_NAME"

    fun fileIn(filesDir: File): File = File(File(filesDir, DIRECTORY), FILE_NAME)
    fun fileIn(filesDir: File, slot: Int): File =
        File(File(filesDir, DIRECTORY), "idle_wallpaper_$slot.png")

    fun sourceFileIn(filesDir: File, extension: String): File =
        File(File(filesDir, DIRECTORY), "$SOURCE_FILE_NAME.$extension")
    fun sourceFileIn(filesDir: File, slot: Int, extension: String): File =
        File(File(filesDir, DIRECTORY), "${SOURCE_FILE_NAME}_$slot.$extension")

    fun isWallpaperFile(file: File): Boolean =
        (file.name == FILE_NAME ||
            file.name.matches(Regex("""idle_wallpaper_\d+\.png""")) ||
            file.name.startsWith("$SOURCE_FILE_NAME." ) ||
            file.name.matches(Regex("""${SOURCE_FILE_NAME}_\d+\..+"""))) &&
            file.parentFile?.name == DIRECTORY
}

enum class DashWallpaperKind { IMAGE, GIF, VIDEO }

data class DashWallpaperInfo(
    val slot: Int,
    val path: String,
    val kind: DashWallpaperKind,
    val horizontalBias: Float,
    val verticalBias: Float,
)

class DashWallpaperStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("dash_wallpaper", Context.MODE_PRIVATE)

    val wallpaperFile: File
        get() = DashWallpaperPaths.fileIn(context.filesDir)

    fun currentInfo(): DashWallpaperInfo? {
        val activeSlot = prefs.getInt("active_slot", 0).coerceIn(0, DashWallpaperPaths.MAX_SLOTS - 1)
        return infoForSlot(activeSlot) ?: allInfos().firstOrNull()
    }

    fun currentPath(): String? = currentInfo()?.path

    fun allInfos(): List<DashWallpaperInfo> =
        (0 until DashWallpaperPaths.MAX_SLOTS).mapNotNull(::infoForSlot)

    fun galleryCount(): Int = allInfos().size

    fun cycle(delta: Int): DashWallpaperInfo? {
        val items = allInfos()
        if (items.isEmpty()) return null
        val currentSlot = currentInfo()?.slot ?: items.first().slot
        val currentIndex = items.indexOfFirst { it.slot == currentSlot }.let { if (it == -1) 0 else it }
        val nextIndex = (currentIndex + delta).floorMod(items.size)
        val next = items[nextIndex]
        prefs.edit().putInt("active_slot", next.slot).apply()
        return next
    }

    fun clear() {
        File(context.filesDir, DashWallpaperPaths.DIRECTORY).listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    fun clearCurrent(): DashWallpaperInfo? {
        currentInfo()?.slot?.let(::deleteSlot)
        val next = allInfos().firstOrNull()
        prefs.edit().apply {
            if (next != null) putInt("active_slot", next.slot) else remove("active_slot")
        }.apply()
        return next
    }

    fun saveFromUri(uri: Uri, horizontalBias: Float = 0f, verticalBias: Float = 0f): String {
        val slot = firstEmptySlot() ?: (currentInfo()?.slot ?: 0)
        return saveFromUriToSlot(slot, uri, horizontalBias, verticalBias)
    }

    fun saveManyFromUris(uris: List<Uri>) {
        var replaceSlot = currentInfo()?.slot ?: 0
        uris.take(DashWallpaperPaths.MAX_SLOTS).forEach { uri ->
            val slot = firstEmptySlot() ?: replaceSlot
            saveFromUriToSlot(slot, uri, 0f, 0f)
            replaceSlot = (slot + 1) % DashWallpaperPaths.MAX_SLOTS
        }
    }

    private fun saveFromUriToSlot(slot: Int, uri: Uri, horizontalBias: Float, verticalBias: Float): String {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        return when {
            mime == "image/gif" || uri.toString().lowercase(Locale.US).endsWith(".gif") ->
                saveSource(slot, uri, DashWallpaperKind.GIF, "gif", horizontalBias, verticalBias)
            mime.startsWith("video/") ->
                saveSource(slot, uri, DashWallpaperKind.VIDEO, extensionForMime(mime), horizontalBias, verticalBias)
            else -> saveStillImage(slot, uri, horizontalBias, verticalBias)
        }
    }

    private fun saveStillImage(slot: Int, uri: Uri, horizontalBias: Float, verticalBias: Float): String {
        val source = decode(uri)
        val cropped = cropToDash(source, DashEncoder.WIDTH, DashEncoder.HEIGHT, horizontalBias, verticalBias)
        if (cropped !== source) source.recycle()

        deleteSlot(slot)
        val out = DashWallpaperPaths.fileIn(context.filesDir, slot)
        out.parentFile?.mkdirs()
        out.outputStream().use { stream ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        if (!cropped.isRecycled) cropped.recycle()
        persist(slot, out.absolutePath, DashWallpaperKind.IMAGE, horizontalBias, verticalBias)
        return out.absolutePath
    }

    private fun saveSource(
        slot: Int,
        uri: Uri,
        kind: DashWallpaperKind,
        extension: String,
        horizontalBias: Float,
        verticalBias: Float,
    ): String {
        deleteSlot(slot)
        val out = DashWallpaperPaths.sourceFileIn(context.filesDir, slot, extension)
        out.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open wallpaper media" }
            out.outputStream().use { output -> input.copyTo(output) }
        }
        persist(slot, out.absolutePath, kind, horizontalBias, verticalBias)
        return out.absolutePath
    }

    private fun persist(slot: Int, path: String, kind: DashWallpaperKind, horizontalBias: Float, verticalBias: Float) {
        prefs.edit()
            .putInt("active_slot", slot)
            .putString("path_$slot", path)
            .putString("kind_$slot", kind.name)
            .putFloat("crop_x_$slot", horizontalBias)
            .putFloat("crop_y_$slot", verticalBias)
            .apply()
    }

    private fun infoForSlot(slot: Int): DashWallpaperInfo? {
        val path = prefs.getString("path_$slot", null)
            ?: legacyPathForSlot(slot)
            ?: return null
        val file = File(path).takeIf { it.exists() } ?: return null
        val kind = runCatching {
            DashWallpaperKind.valueOf(prefs.getString("kind_$slot", DashWallpaperKind.IMAGE.name)!!)
        }.getOrDefault(DashWallpaperKind.IMAGE)
        return DashWallpaperInfo(
            slot = slot,
            path = file.absolutePath,
            kind = kind,
            horizontalBias = prefs.getFloat("crop_x_$slot", 0f),
            verticalBias = prefs.getFloat("crop_y_$slot", 0f),
        )
    }

    private fun legacyPathForSlot(slot: Int): String? {
        if (slot != 0) return null
        return prefs.getString("path", null) ?: wallpaperFile.takeIf { it.exists() }?.absolutePath
    }

    private fun firstEmptySlot(): Int? =
        (0 until DashWallpaperPaths.MAX_SLOTS).firstOrNull { infoForSlot(it) == null }

    private fun deleteSlot(slot: Int) {
        DashWallpaperPaths.fileIn(context.filesDir, slot).delete()
        File(context.filesDir, DashWallpaperPaths.DIRECTORY).listFiles()
            ?.filter { it.name.matches(Regex("""${DashWallpaperPaths.SOURCE_FILE_NAME}_$slot\..+""")) }
            ?.forEach { it.delete() }
        prefs.edit()
            .remove("path_$slot")
            .remove("kind_$slot")
            .remove("crop_x_$slot")
            .remove("crop_y_$slot")
            .apply()
    }

    private fun decode(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode wallpaper image" }
            }
        }
    }

    private fun cropToDash(
        source: Bitmap,
        width: Int,
        height: Int,
        horizontalBias: Float,
        verticalBias: Float,
    ): Bitmap {
        val srcRatio = source.width.toFloat() / source.height.toFloat()
        val dstRatio = width.toFloat() / height.toFloat()
        val src = if (srcRatio > dstRatio) {
            val cropW = (source.height * dstRatio).toInt()
            val extra = source.width - cropW
            val left = ((extra / 2f) + (extra / 2f) * horizontalBias.coerceIn(-1f, 1f)).toInt()
            Rect(left, 0, left + cropW, source.height)
        } else {
            val cropH = (source.width / dstRatio).toInt()
            val extra = source.height - cropH
            val top = ((extra / 2f) + (extra / 2f) * verticalBias.coerceIn(-1f, 1f)).toInt()
            Rect(0, top, source.width, top + cropH)
        }

        val dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(dst).drawBitmap(source, src, Rect(0, 0, width, height), null)
        return dst
    }

    private fun extensionForMime(mime: String): String = when (mime) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        else -> "mp4"
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
}
