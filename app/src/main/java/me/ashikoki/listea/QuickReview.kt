package me.ashikoki.listea

import me.ashikoki.listea.data.DecisionTarget
import me.ashikoki.listea.data.FileReviewStateEntity
import me.ashikoki.listea.data.ReviewItem
import me.ashikoki.listea.data.decisions
import me.ashikoki.listea.data.rootRelativePathOf

/**
 * Quick Review is offered on any folder the user can browse, whenever they have left it switched
 * on. Switched off means the Folder tab is a plain browser and gallery.
 *
 * It used to require a list covering the folder, because it reviewed that list's rows and had
 * nowhere else to put a decision. A decision now belongs to the file, so a folder needs nothing
 * but its own files to be reviewable, and no list is created, consulted or replaced by reviewing
 * one.
 *
 * Switching it off is presentation only. Lists keep their items, completion and actions, and the
 * Folder screen keeps showing which list owns what.
 */
fun quickReviewAvailable(enabled: Boolean): Boolean = enabled

/**
 * An item path, which is stored relative to its list's linked folder, rebased onto the SAF root
 * so it can be compared against browsed folder paths.
 */
fun rootRelativeItemPath(scopePath: String, itemPath: String): String =
    rootRelativePathOf(scopePath, itemPath)

/**
 * One direct file of a browsed folder, before the decisions held about it are attached.
 *
 * [sizeBytes] and [lastModified] are carried rather than dropped because the folder's filter and
 * sort are read off them, and Quick Review now reviews exactly what that filter and sort left on
 * screen. Null means the provider did not say, which every filter treats as "does not exclude" —
 * see the unknown rule in FileArrange.kt.
 */
data class FolderFile(
    val name: String,
    val uri: String,
    val rootRelativePath: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** The direct files of [folderPath], named the way a decision about them is named. */
fun folderFilesOf(folderPath: String, contents: FolderContents): List<FolderFile> =
    contents.entries
        .filter { !it.isDirectory }
        .map {
            FolderFile(
                name = it.name,
                uri = it.uri.toString(),
                rootRelativePath = rootRelativePathOf(folderPath, it.name),
                sizeBytes = it.sizeBytes,
                lastModified = it.lastModified
            )
        }

/**
 * A browsed folder's files turned into a review queue.
 *
 * The whole of what Quick Review resolves, and deliberately pure: the folder listing decides which
 * files there are and in what order, and the stored state decides what is already known about each
 * one. Nothing about any List takes part, which is exactly the property worth pinning down — a
 * folder inside a List, a folder with a List of its own, and a folder no List has ever heard of
 * all produce the same queue from the same files.
 *
 * Files keep their browsing order here. Narrowing and ordering them for an actual round is a
 * separate, later decision — see [reviewRound] — so this stays the complete picture of
 * what the folder holds and what is known about it.
 *
 * A file with no state row is dropped rather than shown undecided: a row is registered for every
 * file before this runs, so a missing one means the file went away underneath us, and reviewing
 * something that is no longer there helps nobody.
 */
fun folderReviewQueue(
    rootUri: String,
    files: List<FolderFile>,
    states: List<FileReviewStateEntity>
): List<ReviewItem> {
    val byPath = states.associateBy { it.relativePath }
    return files.mapNotNull { file ->
        val state = byPath[file.rootRelativePath] ?: return@mapNotNull null
        ReviewItem(
            id = state.id,
            // Off the registration row, not made here: a folder round's payload has to name a
            // file the same way every time it reports on it.
            publicId = state.publicId,
            title = file.name,
            sourceUri = file.uri,
            // Relative to the folder being reviewed, which for a direct file is its own name.
            relativePath = file.name,
            sourceMissing = false,
            target = DecisionTarget.File(rootUri, file.rootRelativePath),
            decisions = state.decisions,
            // Straight off the listing, which measured them while it was reading the folder.
            sizeBytes = file.sizeBytes,
            lastModified = file.lastModified
        )
    }
}
