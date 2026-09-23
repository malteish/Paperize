package com.anthonyla.paperize.core.util

/**
 * Naming rules for copying the current wallpaper into the premium folder.
 *
 * Deliberately free of Android APIs (no [android.net.Uri], no ContentResolver) so the
 * collision handling can be covered by the pure JVM unit tests, in line with the rest of
 * `core/util`. The Android-side plumbing lives in
 * [com.anthonyla.paperize.domain.usecase.CopyToPremiumFolderUseCase].
 */
object PremiumFolderNaming {

    /** A file already present in the premium folder, as listed from its document tree. */
    data class ExistingFile(val name: String, val size: Long)

    /** What the copy should do once the destination folder has been listed. */
    sealed interface CopyPlan {
        /** The very same file (same name, same byte size) is already there — copy nothing. */
        data class AlreadyPresent(val name: String) : CopyPlan

        /** Create a new document under this (possibly de-duplicated) name. */
        data class Create(val name: String) : CopyPlan
    }

    /** Fallback name for a source whose display name cannot be determined. */
    const val FALLBACK_NAME = "wallpaper.jpg"

    /**
     * Decide under which name [sourceName] should land in a premium folder that currently
     * holds [existing].
     *
     * Tapping the widget twice on the same wallpaper is the common case, so an entry with the
     * same name *and* the same byte size counts as already copied rather than as a collision —
     * that keeps the folder free of `photo (1).jpg`, `photo (2).jpg` duplicates of one image.
     * A same-name-but-different-size entry is a genuinely different image and gets a
     * `name (1).ext` style suffix instead.
     *
     * A [sourceSize] of 0 or less means the size is unknown (the provider did not report one);
     * no file is then treated as already present, since the comparison cannot be trusted.
     *
     * Names are compared case-insensitively: the premium folder may well live on an SD card
     * with a case-insensitive filesystem, where `Photo.jpg` and `photo.jpg` are one file.
     */
    fun planCopy(
        sourceName: String,
        sourceSize: Long,
        existing: List<ExistingFile>
    ): CopyPlan {
        val baseName = sanitizeFileName(sourceName)

        fun existingMatching(candidate: String): ExistingFile? =
            existing.firstOrNull { it.name.equals(candidate, ignoreCase = true) }

        val direct = existingMatching(baseName)
        if (direct == null) return CopyPlan.Create(baseName)
        if (sourceSize > 0 && direct.size == sourceSize) return CopyPlan.AlreadyPresent(direct.name)

        val stem = baseName.substringBeforeLast('.', baseName)
        val extension = baseName.substringAfterLast('.', "")
        var suffix = 1
        while (true) {
            val candidate = if (extension.isEmpty()) "$stem ($suffix)" else "$stem ($suffix).$extension"
            val match = existingMatching(candidate)
            if (match == null) return CopyPlan.Create(candidate)
            if (sourceSize > 0 && match.size == sourceSize) return CopyPlan.AlreadyPresent(match.name)
            suffix++
        }
    }

    /**
     * Build the premium-folder name for a source file: its parent folder's name, an
     * underscore, then its own name, so `/some/path/image.jpg` becomes `path_image.jpg`.
     * Without a known [parentFolder] the plain [sourceName] is used.
     */
    fun prefixedName(sourceName: String, parentFolder: String?): String {
        val baseName = sanitizeFileName(sourceName)
        if (parentFolder.isNullOrBlank()) return baseName
        val prefix = sanitizeFileName(parentFolder)
        if (prefix == FALLBACK_NAME) return baseName
        return "${prefix}_$baseName"
    }

    /**
     * Name of the folder directly containing the document [documentId] (as returned by
     * `DocumentsContract.getDocumentId`), or null when the id carries no path.
     *
     * Path-shaped ids come from the external storage provider (`primary:Pictures/Cats/a.jpg`)
     * and the downloads provider (`raw:/storage/emulated/0/Download/a.jpg`). Opaque ids such
     * as the media provider's `image:1234`, or a file at the volume root (`primary:a.jpg`),
     * have no parent folder to report.
     */
    fun parentFolderName(documentId: String): String? {
        val path = documentId.substringAfter(':', documentId).trimEnd('/')
        if ('/' !in path) return null
        return path.substringBeforeLast('/').substringAfterLast('/').trim().ifEmpty { null }
    }

    /**
     * Reduce a source display name to something a document provider will accept: the last
     * path segment only, with characters that are illegal on FAT/exFAT replaced by `_`.
     */
    fun sanitizeFileName(name: String): String {
        val lastSegment = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
            .trim()
        if (lastSegment.isEmpty() || lastSegment == "." || lastSegment == "..") return FALLBACK_NAME
        return lastSegment.map { if (it in ILLEGAL_NAME_CHARS || it.isISOControl()) '_' else it }
            .joinToString("")
    }

    /**
     * Human-readable label for a picked folder tree URI, for the settings screen.
     *
     * A SAF tree URI ends in a percent-encoded document id such as `primary:Pictures/Premium`,
     * so the part after the volume separator is the path the user picked. Anything that does
     * not follow that shape falls back to the raw (decoded) document id, which is still more
     * readable than the full URI.
     */
    fun folderDisplayName(treeUri: String): String {
        val documentId = percentDecode(treeUri.substringAfterLast("/tree/", ""))
        if (documentId.isEmpty()) return treeUri
        val path = documentId.substringAfter(':', documentId)
        return path.ifEmpty { documentId }
    }

    /** Characters no common Android filesystem accepts in a file name. */
    private val ILLEGAL_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * Minimal percent-decoder. `java.net.URLDecoder` is not usable here: it decodes `+` as a
     * space, which would corrupt folder names that legitimately contain a plus sign.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.add(hex.toByte())
                    index += 3
                    continue
                }
            }
            char.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
            index++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
