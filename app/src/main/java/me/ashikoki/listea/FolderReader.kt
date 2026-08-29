package me.ashikoki.listea

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/** A direct child of the selected folder. */
data class FolderEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?
)

data class FolderContents(
    val folderName: String,
    val entries: List<FolderEntry>
)

/**
 * Reads the direct children of [treeUri]. Blocking I/O: call off the main thread.
 * Returns null when the folder can no longer be read (permission revoked, folder deleted,
 * storage unmounted, ...), which the caller treats as "no folder selected".
 */
fun readFolder(context: Context, treeUri: Uri): FolderContents? {
    val stillGranted = context.contentResolver.persistedUriPermissions
        .any { it.uri == treeUri && it.isReadPermission }
    if (!stillGranted) return null

    return runCatching {
        val folder = DocumentFile.fromTreeUri(context, treeUri)
        if (folder == null || !folder.isDirectory || !folder.canRead()) return@runCatching null

        val entries = folder.listFiles()
            .map { child ->
                val isDirectory = child.isDirectory
                FolderEntry(
                    name = child.name ?: "(unnamed)",
                    isDirectory = isDirectory,
                    sizeBytes = if (isDirectory) null else child.length()
                )
            }
            .sortedWith(
                compareByDescending<FolderEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )

        FolderContents(
            folderName = folder.name ?: treeUri.lastPathSegment ?: "Selected folder",
            entries = entries
        )
    }.getOrNull()
}

fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}
