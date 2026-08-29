package me.ashikoki.listea

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import me.ashikoki.listea.data.ScannedFile

/** Guards against a pathological provider; real folder trees are nowhere near this deep. */
private const val MAX_DEPTH = 64

/**
 * Path of the directory currently being browsed, relative to the selected SAF root.
 * The root itself is "".
 */
fun relativePathOf(stack: List<DirRef>): String = stack.drop(1).joinToString("/") { it.name }

fun childRelativePath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/** True when [a] is the same folder as [b] or one of its ancestors. "" is the root. */
fun isAncestorOrSame(a: String, b: String): Boolean =
    a.isEmpty() || a == b || b.startsWith("$a/")

/**
 * Folder-backed lists may not overlap: one scope must not contain the other. Siblings such as
 * `2026-07-11/bilibili` and `2026-07-11/danbooru` do not overlap and can coexist.
 */
fun scopesOverlap(a: String, b: String): Boolean = isAncestorOrSame(a, b) || isAncestorOrSame(b, a)

/**
 * Walks [relativePath] down from the SAF root to the folder a list is linked to.
 * Blocking: call on an IO dispatcher. Returns null if the root grant is gone or any segment of
 * the path no longer exists, so a re-sync can abort before writing anything.
 */
fun resolveLinkedFolder(context: Context, rootUri: Uri, relativePath: String): DocumentFile? =
    runCatching {
        if (!hasPersistedReadAccess(context, rootUri)) return@runCatching null
        var folder = DocumentFile.fromTreeUri(context, rootUri) ?: return@runCatching null
        if (!folder.isDirectory || !folder.canRead()) return@runCatching null
        if (relativePath.isNotEmpty()) {
            for (segment in relativePath.split("/")) {
                folder = folder.listFiles()
                    .firstOrNull { it.isDirectory && it.name == segment }
                    ?: return@runCatching null
            }
        }
        folder
    }.getOrNull()

/**
 * Human-readable source location, e.g. "Listea / 2026-07-11 / bilibili".
 * Blocking: call on an IO dispatcher. Never exposes a content:// URI.
 */
fun sourceFolderLabel(context: Context, rootUri: Uri, relativePath: String): String {
    val rootName = runCatching { DocumentFile.fromTreeUri(context, rootUri)?.name }.getOrNull()
    return buildList {
        add(rootName ?: "Selected folder")
        if (relativePath.isNotEmpty()) addAll(relativePath.split("/"))
    }.joinToString(" / ")
}

/**
 * Every file in the subtree rooted at [folderUri], depth first. Blocking: call on an IO
 * dispatcher.
 *
 * Directories are traversed but never emitted. Children only ever come from [DocumentFile.listFiles]
 * on a folder already inside the subtree, so the walk cannot escape it, and each child is visited
 * exactly once so files cannot be duplicated. Returns null if any part of the scan fails, so the
 * caller can abort before writing anything.
 */
fun scanFolderFiles(context: Context, folderUri: Uri): List<ScannedFile>? = runCatching {
    val root = DocumentFile.fromTreeUri(context, folderUri)
    if (root == null || !root.isDirectory || !root.canRead()) return@runCatching null

    val files = mutableListOf<ScannedFile>()
    val pending = ArrayDeque<Pair<DocumentFile, String>>()
    pending.addLast(root to "")

    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeLast()
        for (child in directory.listFiles()) {
            val name = child.name ?: continue
            val path = childRelativePath(prefix, name)
            if (child.isDirectory) {
                check(path.count { it == '/' } < MAX_DEPTH) { "Folder nesting too deep" }
                pending.addLast(child to path)
            } else {
                files += ScannedFile(name = name, relativePath = path, uri = child.uri.toString())
            }
        }
    }

    files.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, ScannedFile::relativePath)
            .thenBy(ScannedFile::relativePath)
    )
}.getOrNull()
