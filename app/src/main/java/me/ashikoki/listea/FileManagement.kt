package me.ashikoki.listea

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import me.ashikoki.listea.data.ListItemEntity

/**
 * Acting on the files themselves, rather than on the Lists that describe them.
 *
 * Everything else in Listea treats the selected folder as read-only: a folder produces items, a
 * review decides things about them, and a webhook carries those decisions somewhere else. This is
 * the one place that writes back to storage, and it only ever deletes — never moves, renames or
 * creates. The grouping and the deletion are separated on purpose, so what the user is shown
 * before confirming is computed from the same paths the deletion then works through.
 *
 * Everything here speaks in paths under the SAF root, because that is what a checked file *is*
 * now: the decision lives against the path, not against a row in some list, so a file checked in
 * a folder no List has ever covered is as deletable as one checked from a List's own page.
 */

/**
 * One folder's worth of checked files, as the delete preview lists them.
 *
 * [folderPath] is relative to the SAF root and is "" for the root folder itself; the screen knows
 * what the root is called, so naming it is left to the caller rather than baked in here.
 */
data class CheckedFileGroup(val folderPath: String, val fileNames: List<String>) {
    /** The single "->a.jpg, b.mp4" line that sits under the folder. */
    val filesLine: String get() = "->" + fileNames.joinToString(", ")
}

/**
 * The checked files gathered under the folders that hold them, folders and names both in the
 * case-insensitive order a folder listing produces, so the preview reads like the folder tree does.
 *
 * Files reviewed from different places end up on the same line when they share a folder: what is
 * about to be deleted is a folder of files, and where each decision happened to be made is not
 * something the user needs to reason about to answer the question being asked.
 */
fun groupCheckedFiles(paths: List<String>): List<CheckedFileGroup> =
    paths.groupBy(::parentPathOf)
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (folder, inFolder) ->
            CheckedFileGroup(
                folderPath = folder,
                fileNames = inFolder.map(::fileNameOf).sortedWith(String.CASE_INSENSITIVE_ORDER)
            )
        }

private fun parentPathOf(path: String): String = path.substringBeforeLast('/', "")

private fun fileNameOf(path: String): String = path.substringAfterLast('/')

/**
 * What a bulk delete actually managed to do.
 *
 * [deletedPaths] is the files that are now genuinely gone, which is what the database is updated
 * from — never the set that was attempted. A file that would not delete leaves its decision, and
 * its list rows, exactly as they were.
 */
data class FileDeletionResult(val deletedPaths: List<String>, val failedCount: Int)

/**
 * Deletes each file, resolving it from its path under [rootUri]. Blocking: call on an IO
 * dispatcher.
 *
 * Resolution is per *folder*, not per file. A checked file is stored as a path and nothing else —
 * a document URI captured months ago is exactly the kind of thing that goes stale — so the folder
 * is walked to and listed once, and every file being deleted from it is matched by name against
 * that one listing. A hundred files in one folder costs one listing rather than a hundred.
 *
 * Not a transaction, and it cannot be one: every document is its own provider call, and a provider
 * that refuses one file has still deleted the ones before it. So each result is recorded
 * individually and a partial run is reported as a partial run, rather than being rolled back —
 * which is impossible — or reported as a failure, which would leave the user believing files that
 * are gone are still there.
 */
fun deleteCheckedFiles(
    context: Context,
    rootUri: Uri,
    paths: List<String>
): FileDeletionResult {
    val deleted = mutableListOf<String>()
    var failed = 0

    for ((folderPath, inFolder) in paths.groupBy(::parentPathOf)) {
        val folder = resolveLinkedFolder(context, rootUri, folderPath)
        if (folder == null) {
            // The whole folder is unreachable, so none of its files can be reported as gone.
            failed += inFolder.size
            continue
        }
        val children = folder.listFiles().associateBy { it.name }
        for (path in inFolder) {
            val child = children[fileNameOf(path)]
            if (child != null && deleteDocument(context, child.uri)) deleted += path else failed++
        }
    }

    return FileDeletionResult(deletedPaths = deleted, failedCount = failed)
}

/**
 * Removes one document. False when the provider refused it, the grant does not cover writing, or
 * the file was already gone — none of which is worth throwing for, since the caller's job is to
 * carry on with the rest and report the total.
 */
private fun deleteDocument(context: Context, uri: Uri): Boolean =
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        .getOrDefault(false)

/**
 * What to tell the user once a run has finished.
 *
 * Both halves are stated, and the failed half is stated as "still there" rather than as an error:
 * after a permanent delete the only question worth answering is which files exist now.
 */
fun deleteOutcomeMessage(deleted: Int, failed: Int): String {
    val gone = if (deleted == 1) "1 file was deleted" else "$deleted files were deleted"
    if (failed == 0) return "$gone from the device."
    val kept = if (failed == 1) {
        "1 file could not be deleted and is still there"
    } else {
        "$failed files could not be deleted and are still there"
    }
    return "$gone from the device. $kept."
}

/**
 * The files a delivered payload is entitled to offer for deletion: the checked ones that still
 * have a file behind them.
 *
 * The input is the payload's own items, already narrowed by whatever
 * [AppSettings.webhookCompletedItemsOnly] and the review's own queue had to say, so this adds
 * exactly one rule and no others — a payload item that is not checked was reported to the
 * receiver as not checked, and deleting it would act on a decision nobody made.
 *
 * A manual item has no file, and one whose source has gone missing has a path that resolves to
 * nothing; both drop out rather than being counted towards a total that could never be reached.
 * Duplicates are collapsed because the same file can sit in more than one list, and a path offered
 * twice would be a file deleted once and reported as failing the second time.
 */
fun sentCheckedFilePaths(items: List<ListItemEntity>): List<String> = items
    .filter { it.isCompleted && !it.sourceMissing }
    .mapNotNull { it.rootRelativePath?.takeIf(String::isNotEmpty) }
    .distinct()
