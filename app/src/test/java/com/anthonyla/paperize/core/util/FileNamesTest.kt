package com.anthonyla.paperize.core.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FileNames helpers used when copying wallpapers into the premium folder
 */
class FileNamesTest {

    // ============================================================
    // Test: mimeTypeForFileName
    // ============================================================

    @Test
    fun `mimeTypeForFileName maps common image extensions`() {
        assertEquals("image/jpeg", FileNames.mimeTypeForFileName("sunset.jpg"))
        assertEquals("image/jpeg", FileNames.mimeTypeForFileName("sunset.jpeg"))
        assertEquals("image/png", FileNames.mimeTypeForFileName("sunset.png"))
        assertEquals("image/webp", FileNames.mimeTypeForFileName("sunset.webp"))
        assertEquals("image/svg+xml", FileNames.mimeTypeForFileName("vector.svg"))
    }

    @Test
    fun `mimeTypeForFileName is case insensitive`() {
        assertEquals("image/jpeg", FileNames.mimeTypeForFileName("SUNSET.JPG"))
    }

    @Test
    fun `mimeTypeForFileName falls back for unknown and missing extensions`() {
        assertEquals(FileNames.FALLBACK_MIME_TYPE, FileNames.mimeTypeForFileName("wallpaper.xyz"))
        assertEquals(FileNames.FALLBACK_MIME_TYPE, FileNames.mimeTypeForFileName("wallpaper"))
    }

    // ============================================================
    // Test: uniqueFileName
    // ============================================================

    @Test
    fun `uniqueFileName keeps the name when the folder is empty`() {
        val taken = emptySet<String>()
        assertEquals("sunset.jpg", FileNames.uniqueFileName("sunset.jpg") { it in taken })
    }

    @Test
    fun `uniqueFileName appends a counter when the name is taken`() {
        val taken = setOf("sunset.jpg")
        assertEquals("sunset (1).jpg", FileNames.uniqueFileName("sunset.jpg") { it in taken })
    }

    @Test
    fun `uniqueFileName skips counters that are also taken`() {
        val taken = setOf("sunset.jpg", "sunset (1).jpg", "sunset (2).jpg")
        assertEquals("sunset (3).jpg", FileNames.uniqueFileName("sunset.jpg") { it in taken })
    }

    @Test
    fun `uniqueFileName handles names without an extension`() {
        val taken = setOf("wallpaper")
        assertEquals("wallpaper (1)", FileNames.uniqueFileName("wallpaper") { it in taken })
    }

    @Test
    fun `uniqueFileName only replaces the last extension`() {
        val taken = setOf("archive.tar.gz")
        assertEquals("archive.tar (1).gz", FileNames.uniqueFileName("archive.tar.gz") { it in taken })
    }

    @Test
    fun `uniqueFileName falls back to a timestamp when every counter is taken`() {
        val result = FileNames.uniqueFileName("sunset.jpg", timestamp = 12345L) { true }
        assertEquals("sunset (12345).jpg", result)
    }
}
