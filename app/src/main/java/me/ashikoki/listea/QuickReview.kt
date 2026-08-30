package me.ashikoki.listea

import me.ashikoki.listea.data.ListItemEntity

/**
 * Quick Review is offered only when the user has left it switched on *and* some list already
 * covers the folder, directly or through an ancestor. No owning list means no business state to
 * review; switched off means the Folder tab is a plain browser and gallery.
 *
 * Switching it off is presentation only. Lists keep their items, completion and actions, and the
 * Folder screen keeps showing which list owns what.
 */
fun quickReviewAvailable(status: FolderListStatus, enabled: Boolean): Boolean =
    enabled && owningScope(status) != null

/**
 * An item path, which is stored relative to its list's linked folder, rebased onto the SAF root
 * so it can be compared against browsed folder paths.
 */
fun rootRelativeItemPath(scopePath: String, itemPath: String): String =
    if (scopePath.isEmpty()) itemPath else "$scopePath/$itemPath"

/**
 * The owning list's items sitting *directly* in [folderPath]: that one level, never a descendant.
 *
 * Matching compares whole parent paths rather than testing a string prefix, which is what keeps
 * `bilibili2/a.jpg` out of `bilibili` and `sub/c.jpg` out of the level above it. Stored order is
 * preserved, so Quick Review walks the folder in the same order the list does.
 *
 * Excluded on purpose:
 *  - manual items, which have no source path and belong to no folder;
 *  - items whose source has vanished, because Quick Review shows the files actually in front of
 *    the user. Both remain fully reviewable in the normal full-list Review.
 *
 * This is a filter over items already loaded for the owning list, not a per-file query.
 */
fun directSourceItems(
    items: List<ListItemEntity>,
    scopePath: String,
    folderPath: String
): List<ListItemEntity> = items.filter { item ->
    val itemPath = item.sourceRelativePath
    itemPath != null &&
        !item.sourceMissing &&
        rootRelativeItemPath(scopePath, itemPath).substringBeforeLast('/', "") == folderPath
}
