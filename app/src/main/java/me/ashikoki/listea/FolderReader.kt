package me.ashikoki.listea

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A direct child of the directory currently being shown. */
data class FolderEntry(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val lastModified: Long?
)

data class FolderContents(
    val folderName: String,
    val entries: List<FolderEntry>
)

/**
 * One directory on the path from the selected root to the directory being shown.
 * [uri] is the root tree URI for the first element and a tree-document URI below it;
 * both are accepted by [DocumentFile.fromTreeUri], so navigating down never re-prompts for SAF access.
 */
data class DirRef(val uri: Uri, val name: String)

/** True while the user's originally selected root is still readable through SAF. */
fun hasPersistedReadAccess(context: Context, rootUri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions
        .any { it.uri == rootUri && it.isReadPermission }

/**
 * True when the grant on the root also covers writing, which deleting a file needs.
 *
 * Separate from [hasPersistedReadAccess] because it can legitimately be false on a root that
 * works perfectly for everything else: browsing, scanning and reviewing only ever read, and a
 * root granted by an older version of Listea — or by a provider that hands out read-only trees —
 * is still a good root. File management asks for this before offering to delete anything.
 */
fun hasPersistedWriteAccess(context: Context, rootUri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions
        .any { it.uri == rootUri && it.isWritePermission }

/**
 * Where the folder picker should open when Listea already holds [treeUri].
 *
 * The picker's initial location is read as a *document* URI, not as the tree URI that names the
 * grant — handed the tree URI it finds no document id in it, ignores the hint entirely and opens
 * wherever it last was. That is why re-granting used to mean walking the whole path down by hand.
 * Rebuilding the very same folder as a document inside its own tree is what the picker will
 * actually follow.
 *
 * Null when [treeUri] is not a tree URI after all, which simply means no starting point: the
 * picker opens at its default rather than the caller having to care.
 */
fun initialPickerUri(treeUri: Uri): Uri? = runCatching {
    DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )
}.getOrNull()

/**
 * Reads the direct children of [dirUri] (the selected root, or any directory below it).
 * Blocking I/O: call off the main thread.
 * Returns null when that directory can no longer be read (deleted by another app, permission
 * revoked, storage unmounted, ...), which the caller handles by falling back to a parent.
 */
fun readFolder(context: Context, dirUri: Uri): FolderContents? = runCatching {
    val folder = DocumentFile.fromTreeUri(context, dirUri)
    if (folder == null || !folder.isDirectory || !folder.canRead()) return@runCatching null

    val entries = folder.listFiles()
        .map { child ->
            val isDirectory = child.isDirectory
            FolderEntry(
                name = child.name ?: "(unnamed)",
                uri = child.uri,
                isDirectory = isDirectory,
                sizeBytes = if (isDirectory) null else child.length(),
                lastModified = child.lastModified().takeIf { it > 0 }
            )
        }
        .sortedWith(
            compareByDescending<FolderEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )

    FolderContents(
        folderName = folder.name ?: dirUri.lastPathSegment ?: "Selected folder",
        entries = entries
    )
}.getOrNull()

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

fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
