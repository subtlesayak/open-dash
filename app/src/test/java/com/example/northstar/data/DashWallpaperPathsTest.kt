package com.example.northstar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashWallpaperPathsTest {
    @Test
    fun relativePath_usesStablePrivateLocation() {
        assertEquals("dash_wallpaper/idle_wallpaper.png", DashWallpaperPaths.relativePath())
    }

    @Test
    fun fileIn_resolvesBelowFilesDir() {
        val filesDir = File("build/test-files")
        val file = DashWallpaperPaths.fileIn(filesDir)

        assertEquals(File(filesDir, "dash_wallpaper/idle_wallpaper.png"), file)
        assertEquals(File(filesDir, "dash_wallpaper/idle_wallpaper_3.png"), DashWallpaperPaths.fileIn(filesDir, 3))
        assertEquals(File(filesDir, "dash_wallpaper/source_wallpaper_2.mp4"), DashWallpaperPaths.sourceFileIn(filesDir, 2, "mp4"))
    }

    @Test
    fun isWallpaperFile_acceptsOnlyExpectedFileNameAndParent() {
        assertTrue(DashWallpaperPaths.isWallpaperFile(File("files/dash_wallpaper/idle_wallpaper.png")))
        assertTrue(DashWallpaperPaths.isWallpaperFile(File("files/dash_wallpaper/idle_wallpaper_4.png")))
        assertTrue(DashWallpaperPaths.isWallpaperFile(File("files/dash_wallpaper/source_wallpaper_1.gif")))
        assertFalse(DashWallpaperPaths.isWallpaperFile(File("files/dash_wallpaper/other.png")))
        assertFalse(DashWallpaperPaths.isWallpaperFile(File("files/other/idle_wallpaper.png")))
    }
}
