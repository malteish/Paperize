package com.anthonyla.paperize.core.util

import com.anthonyla.paperize.core.util.PremiumFolderNaming.CopyPlan
import com.anthonyla.paperize.core.util.PremiumFolderNaming.ExistingFile
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PremiumFolderNaming, the pure part of the copy-to-premium-folder feature.
 */
class PremiumFolderNamingTest {

    // ============================================================
    // Test: planCopy
    // ============================================================

    @Test
    fun `planCopy keeps the source name in an empty folder`() {
        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, emptyList())

        assertEquals(CopyPlan.Create("photo.jpg"), plan)
    }

    @Test
    fun `planCopy keeps the source name when nothing collides`() {
        val existing = listOf(ExistingFile("other.jpg", 500L))

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.Create("photo.jpg"), plan)
    }

    @Test
    fun `planCopy reports the same file as already present`() {
        val existing = listOf(ExistingFile("photo.jpg", 1000L))

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.AlreadyPresent("photo.jpg"), plan)
    }

    @Test
    fun `planCopy matches already present names case insensitively`() {
        val existing = listOf(ExistingFile("Photo.JPG", 1000L))

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.AlreadyPresent("Photo.JPG"), plan)
    }

    @Test
    fun `planCopy suffixes a different image with the same name`() {
        val existing = listOf(ExistingFile("photo.jpg", 500L))

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.Create("photo (1).jpg"), plan)
    }

    @Test
    fun `planCopy keeps counting up while suffixed names are taken`() {
        val existing = listOf(
            ExistingFile("photo.jpg", 500L),
            ExistingFile("photo (1).jpg", 600L),
            ExistingFile("photo (2).jpg", 700L)
        )

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.Create("photo (3).jpg"), plan)
    }

    @Test
    fun `planCopy finds the image among already suffixed copies`() {
        val existing = listOf(
            ExistingFile("photo.jpg", 500L),
            ExistingFile("photo (1).jpg", 1000L)
        )

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.AlreadyPresent("photo (1).jpg"), plan)
    }

    @Test
    fun `planCopy never reports already present when the size is unknown`() {
        val existing = listOf(ExistingFile("photo.jpg", 1000L))

        val plan = PremiumFolderNaming.planCopy("photo.jpg", 0L, existing)

        assertEquals(CopyPlan.Create("photo (1).jpg"), plan)
    }

    @Test
    fun `planCopy suffixes names without an extension`() {
        val existing = listOf(ExistingFile("wallpaper", 500L))

        val plan = PremiumFolderNaming.planCopy("wallpaper", 1000L, existing)

        assertEquals(CopyPlan.Create("wallpaper (1)"), plan)
    }

    @Test
    fun `planCopy suffixes names with several dots before the extension`() {
        val existing = listOf(ExistingFile("my.photo.final.png", 500L))

        val plan = PremiumFolderNaming.planCopy("my.photo.final.png", 1000L, existing)

        assertEquals(CopyPlan.Create("my.photo.final (1).png"), plan)
    }

    @Test
    fun `planCopy sanitizes the source name before comparing`() {
        val existing = listOf(ExistingFile("photo.jpg", 1000L))

        val plan = PremiumFolderNaming.planCopy("Pictures/photo.jpg", 1000L, existing)

        assertEquals(CopyPlan.AlreadyPresent("photo.jpg"), plan)
    }

    // ============================================================
    // Test: sanitizeFileName
    // ============================================================

    @Test
    fun `sanitizeFileName keeps a plain name`() {
        assertEquals("photo.jpg", PremiumFolderNaming.sanitizeFileName("photo.jpg"))
    }

    @Test
    fun `sanitizeFileName strips directories and document id prefixes`() {
        assertEquals("photo.jpg", PremiumFolderNaming.sanitizeFileName("Pictures/photo.jpg"))
        assertEquals("photo.jpg", PremiumFolderNaming.sanitizeFileName("primary:Pictures/photo.jpg"))
        assertEquals("photo.jpg", PremiumFolderNaming.sanitizeFileName("C:\\Users\\photo.jpg"))
    }

    @Test
    fun `sanitizeFileName replaces characters filesystems reject`() {
        assertEquals("a_b_c_.jpg", PremiumFolderNaming.sanitizeFileName("a?b*c\".jpg"))
    }

    @Test
    fun `sanitizeFileName falls back for empty and dot names`() {
        assertEquals(PremiumFolderNaming.FALLBACK_NAME, PremiumFolderNaming.sanitizeFileName(""))
        assertEquals(PremiumFolderNaming.FALLBACK_NAME, PremiumFolderNaming.sanitizeFileName("   "))
        assertEquals(PremiumFolderNaming.FALLBACK_NAME, PremiumFolderNaming.sanitizeFileName("."))
        assertEquals(PremiumFolderNaming.FALLBACK_NAME, PremiumFolderNaming.sanitizeFileName(".."))
    }

    // ============================================================
    // Test: folderDisplayName
    // ============================================================

    @Test
    fun `folderDisplayName shows the picked path`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPremium"

        assertEquals("Pictures/Premium", PremiumFolderNaming.folderDisplayName(uri))
    }

    @Test
    fun `folderDisplayName decodes spaces and plus signs`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMy+Pics%20Folder"

        assertEquals("My+Pics Folder", PremiumFolderNaming.folderDisplayName(uri))
    }

    @Test
    fun `folderDisplayName falls back to the document id without a volume prefix`() {
        val uri = "content://com.example.provider/tree/12345"

        assertEquals("12345", PremiumFolderNaming.folderDisplayName(uri))
    }

    @Test
    fun `folderDisplayName falls back to the raw uri when it is not a tree uri`() {
        val uri = "content://com.example.provider/document/12345"

        assertEquals(uri, PremiumFolderNaming.folderDisplayName(uri))
    }

    @Test
    fun `folderDisplayName keeps the volume when the path is empty`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A"

        assertEquals("primary:", PremiumFolderNaming.folderDisplayName(uri))
    }

    // ============================================================
    // Test: prefixedName
    // ============================================================

    @Test
    fun `prefixedName prepends the parent folder with an underscore`() {
        assertEquals("path_image.jpg", PremiumFolderNaming.prefixedName("image.jpg", "path"))
    }

    @Test
    fun `prefixedName keeps the plain name without a parent folder`() {
        assertEquals("image.jpg", PremiumFolderNaming.prefixedName("image.jpg", null))
        assertEquals("image.jpg", PremiumFolderNaming.prefixedName("image.jpg", " "))
    }

    @Test
    fun `prefixedName sanitizes illegal characters in the folder name`() {
        assertEquals("a_b_image.jpg", PremiumFolderNaming.prefixedName("image.jpg", "a?b"))
    }

    // ============================================================
    // Test: parentFolderName
    // ============================================================

    @Test
    fun `parentFolderName reads the direct parent of an external storage id`() {
        assertEquals("path", PremiumFolderNaming.parentFolderName("primary:some/path/image.jpg"))
    }

    @Test
    fun `parentFolderName reads the direct parent of a raw downloads id`() {
        assertEquals(
            "Download",
            PremiumFolderNaming.parentFolderName("raw:/storage/emulated/0/Download/image.jpg")
        )
    }

    @Test
    fun `parentFolderName returns null for a file at the volume root`() {
        assertNull(PremiumFolderNaming.parentFolderName("primary:image.jpg"))
    }

    @Test
    fun `parentFolderName returns null for an opaque media id`() {
        assertNull(PremiumFolderNaming.parentFolderName("image:1234"))
    }
}
