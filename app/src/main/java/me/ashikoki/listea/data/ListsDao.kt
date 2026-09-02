package me.ashikoki.listea.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ListsDao {

    @Query(
        """
        SELECT l.id AS id,
               l.title AS title,
               l.completedAt AS completedAt,
               COUNT(i.id) AS totalItems,
               COALESCE(
                   SUM(
                       CASE WHEN i.rootRelativePath IS NULL THEN i.isCompleted
                            ELSE COALESCE(s.isCompleted, 0) END
                   ), 0
               ) AS completedItems,
               l.webhookEnabled AS webhookEnabled,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode
        FROM lists l
        LEFT JOIN list_items i ON i.listId = l.id
        LEFT JOIN file_review_state s
               ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath
        GROUP BY l.id
        ORDER BY l.createdAt DESC, l.id DESC
        """
    )
    abstract fun observeSummaries(): Flow<List<ListSummary>>

    @Query("SELECT * FROM lists WHERE id = :listId")
    abstract fun observeList(listId: Long): Flow<ListEntity?>

    /**
     * One list's items with their decisions resolved.
     *
     * A source-backed row's checked state and actions are not on the row — they belong to the file
     * and live in `file_review_state` — so every read that shows an item projects them back in
     * here. The CASE is what keeps that honest: a manual item reads its own columns, and a
     * source-backed one reads the file's state and *never* its own, so a value left behind on the
     * row by an older version cannot leak into the UI.
     *
     * Room sees `file_review_state` in the query and invalidates this flow when it changes, which
     * is what makes a decision taken in a folder's Quick Review appear on this list immediately.
     */
    @Query(
        """
        SELECT i.id AS id,
               i.listId AS listId,
               i.title AS title,
               CASE WHEN i.rootRelativePath IS NULL THEN i.isCompleted ELSE COALESCE(s.isCompleted, 0) END AS isCompleted,
               i.sortOrder AS sortOrder,
               i.createdAt AS createdAt,
               i.sourceUri AS sourceUri,
               i.sourceRelativePath AS sourceRelativePath,
               i.rootRelativePath AS rootRelativePath,
               i.sourceMissing AS sourceMissing,
               i.sourceSizeBytes AS sourceSizeBytes,
               i.sourceModifiedAt AS sourceModifiedAt,
               CASE WHEN i.rootRelativePath IS NULL THEN i.isFavorite ELSE COALESCE(s.isFavorite, 0) END AS isFavorite,
               CASE WHEN i.rootRelativePath IS NULL THEN i.custom1 ELSE COALESCE(s.custom1, 0) END AS custom1,
               CASE WHEN i.rootRelativePath IS NULL THEN i.custom2 ELSE COALESCE(s.custom2, 0) END AS custom2
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        LEFT JOIN file_review_state s
               ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath
        WHERE i.listId = :listId
        ORDER BY i.sortOrder ASC, i.id ASC
        """
    )
    abstract fun observeItems(listId: Long): Flow<List<ListItemEntity>>

    @Insert
    abstract suspend fun insertList(list: ListEntity): Long

    @Query("UPDATE lists SET title = :title WHERE id = :id")
    abstract suspend fun updateListTitle(id: Long, title: String)

    @Query("DELETE FROM lists WHERE id = :id")
    abstract suspend fun deleteList(id: Long)

    /**
     * Drops every list linked to one SAF root, whether it was created on the root itself or on a
     * folder below it, and returns how many went. Items cascade with their list.
     *
     * Manual lists have no root and can never match. Lists linked to a *different* root are left
     * alone: they have nothing to do with the folder being given up.
     */
    @Query("DELETE FROM lists WHERE sourceRootUri = :rootUri")
    abstract suspend fun deleteListsForRoot(rootUri: String): Int

    /**
     * Appends an item and re-evaluates completion: adding an item to an already complete list
     * makes it incomplete again.
     *
     * Returns true when this edit moved the list from incomplete to complete. The before/after
     * reads happen inside the same transaction as the write, so concurrent edits cannot both
     * observe the transition, and the caller can fire the completion action exactly once.
     */
    @Transaction
    open suspend fun addItem(listId: Long, title: String, now: Long): Boolean {
        val wasComplete = isComplete(listId) == true
        insertItem(
            ListItemEntity(
                listId = listId,
                title = title,
                sortOrder = maxSortOrder(listId) + 1,
                createdAt = now
            )
        )
        refreshCompletion(listId, now)
        return !wasComplete && isComplete(listId) == true
    }

    /**
     * Checks or unchecks a *manual* item, whose state really is its own. See [addItem].
     *
     * Manual items have no file to hold a decision, so this is the one completion write that
     * still touches a `list_items` column, and it can only ever affect the list the row is in.
     */
    @Transaction
    open suspend fun setManualItemCompleted(
        listId: Long,
        itemId: Long,
        completed: Boolean,
        now: Long
    ): Boolean {
        val wasComplete = isComplete(listId) == true
        updateItemCompleted(itemId, completed)
        refreshCompletion(listId, now)
        return !wasComplete && isComplete(listId) == true
    }

    /**
     * Checks or unchecks a *file*, from wherever it was reached.
     *
     * One write, and then every list that happens to contain that file is re-evaluated — which is
     * the point of moving the state onto the file. Checking the last unreviewed file of a folder
     * from a Quick Review that knows nothing about any List still completes the List covering it,
     * and still fires that List's webhook, because completion is recomputed here for every list
     * the file belongs to.
     *
     * Returns the lists that moved from incomplete to complete, so the caller fires exactly one
     * completion action per list that actually transitioned. Non-overlap means that is at most
     * one today; nothing here assumes it.
     */
    @Transaction
    open suspend fun setFileCompleted(
        rootUri: String,
        relativePath: String,
        completed: Boolean,
        now: Long
    ): List<Long> {
        val affected = listsContainingFile(rootUri, relativePath)
        val wasComplete = affected.filter { isComplete(it) == true }.toSet()

        writeFileState(rootUri, relativePath, now) { it.copy(isCompleted = completed) }

        val transitioned = mutableListOf<Long>()
        for (listId in affected) {
            refreshCompletion(listId, now)
            if (listId !in wasComplete && isComplete(listId) == true) transitioned += listId
        }
        return transitioned
    }

    /** Returns true when removing the last unchecked item completed the list. See [addItem]. */
    @Transaction
    open suspend fun removeItem(listId: Long, itemId: Long, now: Long): Boolean {
        val wasComplete = isComplete(listId) == true
        deleteItem(itemId)
        refreshCompletion(listId, now)
        return !wasComplete && isComplete(listId) == true
    }

    @Query("SELECT * FROM lists WHERE id = :listId")
    abstract suspend fun getList(listId: Long): ListEntity?

    /** Every folder-backed list under one SAF root. Scope overlap is worked out in Kotlin. */
    @Query("SELECT * FROM lists WHERE sourceRootUri = :rootUri")
    abstract fun observeFolderLists(rootUri: String): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE sourceRootUri = :rootUri")
    abstract suspend fun getFolderLists(rootUri: String): List<ListEntity>

    /**
     * Every folder-backed scope under [rootUri] with its progress, in one query. The Folder screen
     * observes this once and derives exact/inherited ownership in memory.
     */
    @Query(
        """
        SELECT l.id AS id,
               l.title AS title,
               l.sourceRelativePath AS relativePath,
               l.completedAt AS completedAt,
               COUNT(i.id) AS totalItems,
               COALESCE(
                   SUM(
                       CASE WHEN i.rootRelativePath IS NULL THEN i.isCompleted
                            ELSE COALESCE(s.isCompleted, 0) END
                   ), 0
               ) AS completedItems,
               l.webhookEnabled AS webhookEnabled,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode
        FROM lists l
        LEFT JOIN list_items i ON i.listId = l.id
        LEFT JOIN file_review_state s
               ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath
        WHERE l.sourceRootUri = :rootUri AND l.sourceRelativePath IS NOT NULL
        GROUP BY l.id
        """
    )
    abstract fun observeFolderScopes(rootUri: String): Flow<List<FolderListScope>>

    /**
     * Source-backed items of every folder-backed list under [rootUri], for deriving per-subtree
     * progress. One query for the whole screen. Manual items are excluded here, so they can never
     * count toward a folder's progress; missing sources are kept, since they still belong to their
     * subtree.
     */
    @Query(
        """
        SELECT i.listId AS listId,
               i.sourceRelativePath AS relativePath,
               COALESCE(s.isCompleted, 0) AS isCompleted
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        LEFT JOIN file_review_state s
               ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath
        WHERE l.sourceRootUri = :rootUri
          AND l.sourceRelativePath IS NOT NULL
          AND i.sourceRelativePath IS NOT NULL
        """
    )
    abstract fun observeFolderScopeItems(rootUri: String): Flow<List<SourceItemRef>>

    /**
     * Creates a folder-backed list in one transaction: drops the confirmed overlapping lists
     * (cascading their items and webhook state), inserts the new list, then all of its items.
     * Either the whole snapshot lands or nothing does, so a failure can never leave a
     * half-populated folder list behind.
     */
    @Transaction
    open suspend fun createFolderList(
        title: String,
        rootUri: String,
        relativePath: String,
        files: List<ScannedFile>,
        replacedListIds: List<Long>,
        webhookEnabled: Boolean,
        webhookUrl: String,
        now: Long
    ): Long {
        replacedListIds.forEach { deleteList(it) }
        val listId = insertList(
            ListEntity(
                title = title,
                createdAt = now,
                // Copied from settings by the caller, once, at creation. The list owns it from
                // here on and a later settings change never reaches back to it.
                webhookEnabled = webhookEnabled,
                webhookUrl = webhookUrl,
                sourceRootUri = rootUri,
                sourceRelativePath = relativePath
            )
        )
        insertItems(
            files.mapIndexed { index, file ->
                ListItemEntity(
                    listId = listId,
                    title = file.name,
                    sortOrder = index,
                    createdAt = now,
                    sourceUri = file.uri,
                    sourceRelativePath = file.relativePath,
                    // The identity its decisions are stored against. Resolved once, at insert,
                    // because a list's folder never moves after this point.
                    rootRelativePath = rootRelativePathOf(relativePath, file.relativePath),
                    // Measured by the scan that produced this file, so the Items header can sort
                    // and filter on it from the moment the list exists.
                    sourceSizeBytes = file.sizeBytes,
                    sourceModifiedAt = file.lastModified
                )
            }
        )
        return listId
    }

    /** The same resolution as [observeItems], for the one-shot reads a webhook payload needs. */
    @Query(
        """
        SELECT i.id AS id,
               i.listId AS listId,
               i.title AS title,
               CASE WHEN i.rootRelativePath IS NULL THEN i.isCompleted ELSE COALESCE(s.isCompleted, 0) END AS isCompleted,
               i.sortOrder AS sortOrder,
               i.createdAt AS createdAt,
               i.sourceUri AS sourceUri,
               i.sourceRelativePath AS sourceRelativePath,
               i.rootRelativePath AS rootRelativePath,
               i.sourceMissing AS sourceMissing,
               i.sourceSizeBytes AS sourceSizeBytes,
               i.sourceModifiedAt AS sourceModifiedAt,
               CASE WHEN i.rootRelativePath IS NULL THEN i.isFavorite ELSE COALESCE(s.isFavorite, 0) END AS isFavorite,
               CASE WHEN i.rootRelativePath IS NULL THEN i.custom1 ELSE COALESCE(s.custom1, 0) END AS custom1,
               CASE WHEN i.rootRelativePath IS NULL THEN i.custom2 ELSE COALESCE(s.custom2, 0) END AS custom2
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        LEFT JOIN file_review_state s
               ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath
        WHERE i.listId = :listId
        ORDER BY i.sortOrder ASC, i.id ASC
        """
    )
    abstract suspend fun getItems(listId: Long): List<ListItemEntity>

    /**
     * Every checked file under one SAF root that Listea still believes exists.
     *
     * Read from the file state and not from any list, which is what makes it right: a file checked
     * in a folder no List covers is just as checked as one checked from a List, and the feature
     * that deletes checked files has to see both or it is lying about what "checked" means.
     *
     * A file already flagged missing is excluded — there is nothing left to delete — and so is an
     * unchecked one, which is exactly the thing the user did not mark.
     */
    @Query(
        """
        SELECT relativePath FROM file_review_state
        WHERE rootUri = :rootUri AND isCompleted = 1 AND sourceMissing = 0
        ORDER BY relativePath COLLATE NOCASE ASC
        """
    )
    abstract suspend fun getCheckedFilePaths(rootUri: String): List<String>

    /** How many there are, for the card that offers the delete. Same filters, no paths loaded. */
    @Query(
        """
        SELECT COUNT(*) FROM file_review_state
        WHERE rootUri = :rootUri AND isCompleted = 1 AND sourceMissing = 0
        """
    )
    abstract fun observeCheckedFileCount(rootUri: String): Flow<Int>

    /**
     * Records that these files are gone, after they have actually been deleted from disk.
     *
     * Flagging rather than deleting, which is what a re-sync does when it finds a file missing for
     * any other reason: the review decisions are the user's work and the file being gone was the
     * point, not a reason to throw them away. Both halves are flagged — the file's own state, so
     * it stops being counted as deletable, and any list rows pointing at it, so those lists say
     * "missing" exactly as they would after a re-sync.
     *
     * The decision itself is untouched, so a list cannot lose its completion by having its files
     * deleted, and nothing on this path can reach the webhook.
     */
    @Transaction
    open suspend fun markFilesDeleted(rootUri: String, relativePaths: List<String>) {
        setFilesMissing(rootUri, relativePaths, true)
        setListRowsMissing(rootUri, relativePaths, true)
    }

    /**
     * Clears the missing flag for files a folder resolution has just found present.
     *
     * A file that went away and came back is present again, and would otherwise stay excluded from
     * the checked-file count forever, because registration cannot update a row that already exists.
     */
    @Transaction
    open suspend fun markFilesPresent(rootUri: String, relativePaths: List<String>) {
        setFilesMissing(rootUri, relativePaths, false)
        setListRowsMissing(rootUri, relativePaths, false)
    }

    @Query(
        """
        UPDATE file_review_state SET sourceMissing = :missing
        WHERE rootUri = :rootUri AND relativePath IN (:relativePaths)
          AND sourceMissing != :missing
        """
    )
    protected abstract suspend fun setFilesMissing(
        rootUri: String,
        relativePaths: List<String>,
        missing: Boolean
    )

    @Query(
        """
        UPDATE list_items SET sourceMissing = :missing
        WHERE rootRelativePath IN (:relativePaths)
          AND listId IN (SELECT id FROM lists WHERE sourceRootUri = :rootUri)
          AND sourceMissing != :missing
        """
    )
    protected abstract suspend fun setListRowsMissing(
        rootUri: String,
        relativePaths: List<String>,
        missing: Boolean
    )

    /** How many source-backed items in this list currently have no source file. */
    @Query(
        """
        SELECT COUNT(*) FROM list_items
        WHERE listId = :listId AND sourceRelativePath IS NOT NULL AND sourceMissing = 1
        """
    )
    abstract suspend fun missingSourceItemCount(listId: Long): Int

    /**
     * Optional, explicitly confirmed cleanup: drops every source-backed item in this list whose
     * source file is gone, then recomputes completion. One transaction, so a failure leaves the
     * list untouched. Returns a row count, never a completion transition, so it cannot cause a
     * webhook to fire.
     */
    @Transaction
    open suspend fun removeMissingSourceItems(listId: Long, now: Long): Int {
        val removed = deleteMissingSourceItems(listId)
        refreshCompletion(listId, now)
        return removed
    }

    /** Read-only comparison, for the confirmation dialog. Writes nothing. */
    @Transaction
    open suspend fun previewReconcile(listId: Long, files: List<ScannedFile>): FolderDiff =
        diffAgainst(getSourceItems(listId), files)

    /**
     * Applies a re-sync in one transaction: appends the new files, flags vanished sources, and
     * restores ones that came back. Existing items keep their id, title, sortOrder, createdAt and
     * checked state; manual items are never selected in the first place.
     *
     * The diff is recomputed here from the same scan so the write is consistent with what the
     * database holds at commit time. Completion is recalculated, but this returns a plain diff and
     * never a completion transition, so it cannot cause a webhook to fire.
     */
    @Transaction
    open suspend fun applyReconcile(listId: Long, files: List<ScannedFile>, now: Long): FolderDiff {
        val items = getSourceItems(listId)
        val diff = diffAgainst(items, files)
        val byPath = items.associateBy { it.sourceRelativePath }
        // The list's own folder, so an appended item gets the same file identity a freshly
        // created list would have given it. An appended file that was reviewed under some other
        // list, or in a folder Quick Review, arrives already carrying that decision.
        val list = getList(listId)
        val listPath = list?.sourceRelativePath.orEmpty()

        val appendFrom = maxSortOrder(listId) + 1
        insertItems(
            diff.addedPaths.mapIndexedNotNull { index, path ->
                val file = files.firstOrNull { it.relativePath == path } ?: return@mapIndexedNotNull null
                ListItemEntity(
                    listId = listId,
                    title = file.name,
                    sortOrder = appendFrom + index,
                    createdAt = now,
                    sourceUri = file.uri,
                    sourceRelativePath = file.relativePath,
                    rootRelativePath = rootRelativePathOf(listPath, file.relativePath),
                    sourceSizeBytes = file.sizeBytes,
                    sourceModifiedAt = file.lastModified
                )
            }
        )

        diff.missingPaths.forEach { path ->
            byPath[path]?.takeIf { !it.sourceMissing }?.let { setSourceMissing(it.id, true) }
        }

        // A file that came back may be a different document, so refresh the pointer too.
        diff.restoredPaths.forEach { path ->
            val item = byPath[path] ?: return@forEach
            val file = files.firstOrNull { it.relativePath == path } ?: return@forEach
            restoreSource(item.id, file.uri)
        }

        // The same verdict, applied to the files themselves. A scan is the best evidence there is
        // about what exists, and things that are not this list read it: without this a file
        // deleted outside Listea would still be offered up as a checked file to delete, and one
        // that came back would stay excluded.
        list?.sourceRootUri?.let { rootUri ->
            setFilesMissing(rootUri, diff.missingPaths.map { rootRelativePathOf(listPath, it) }, true)
            setFilesMissing(rootUri, diff.restoredPaths.map { rootRelativePathOf(listPath, it) }, false)
        }

        refreshCompletion(listId, now)
        return diff
    }

    /**
     * Brings each source-backed row's cached measurements up to date with what a scan just saw.
     *
     * Membership is not touched: no row is added, removed, flagged or restored, and nothing here
     * can change a checked state or a completion. That is what makes it safe to run from the
     * read-only freshness check as well as from a re-sync — the two things a scan can tell you
     * are "the folder has changed" and "this file is 4 MB", and only the first is a decision for
     * the user to make.
     *
     * Only rows whose numbers actually moved are written. A list's page runs this on every entry,
     * and writing every row each time would re-emit the items flow — and rebuild the page — once
     * per visit for no change at all.
     *
     * Returns how many rows were touched, which is nothing but a test's way of asking.
     */
    @Transaction
    open suspend fun refreshSourceMetrics(listId: Long, files: List<ScannedFile>): Int {
        val byPath = files.associateBy { it.relativePath }
        var updated = 0
        getSourceItems(listId).forEach { item ->
            val file = byPath[item.sourceRelativePath] ?: return@forEach
            val unchanged = file.sizeBytes == item.sourceSizeBytes &&
                file.lastModified == item.sourceModifiedAt
            if (unchanged) return@forEach
            setSourceMetrics(item.id, file.sizeBytes, file.lastModified)
            updated++
        }
        return updated
    }

    /** Matches purely on relative path, so same-named files in different subfolders stay distinct. */
    private fun diffAgainst(items: List<ListItemEntity>, files: List<ScannedFile>): FolderDiff {
        val itemPaths = items.mapNotNull { it.sourceRelativePath }.toSet()
        val scanPaths = files.map { it.relativePath }.toSet()

        val added = files.map { it.relativePath }.filterNot { it in itemPaths }.sorted()
        val present = items.filter { it.sourceRelativePath in scanPaths }
        val absent = items.filter { it.sourceRelativePath !in scanPaths }

        return FolderDiff(
            addedPaths = added,
            missingPaths = absent.mapNotNull { it.sourceRelativePath }.sorted(),
            restoredPaths = present.filter { it.sourceMissing }.mapNotNull { it.sourceRelativePath }.sorted(),
            unchangedCount = present.size,
            newlyMissingCount = absent.count { !it.sourceMissing }
        )
    }

    /**
     * Sets one review action on a manual item. Action metadata only: it deliberately does not call
     * [refreshCompletion] and returns nothing, so it can never report a completion transition and
     * therefore can never cause a webhook to be delivered.
     */
    suspend fun setManualItemAction(itemId: Long, action: ItemAction, enabled: Boolean) =
        when (action) {
            ItemAction.FAVORITE -> setItemFavorite(itemId, enabled)
            ItemAction.CUSTOM1 -> setItemCustom1(itemId, enabled)
            ItemAction.CUSTOM2 -> setItemCustom2(itemId, enabled)
        }

    /** The same, on a file, so every list holding it sees the action. Still never completes. */
    @Transaction
    open suspend fun setFileAction(
        rootUri: String,
        relativePath: String,
        action: ItemAction,
        enabled: Boolean,
        now: Long
    ) {
        writeFileState(rootUri, relativePath, now) { action.setOn(it, enabled) }
    }

    /**
     * Read-modify-write of one file's decisions, creating the row on the first decision.
     *
     * Not an upsert: the row's primary key is only a handle, and the real identity is the unique
     * (root, path) pair, which `@Upsert` would not match on. Callers are already inside a
     * transaction, so the read and the write cannot be interleaved with another decision.
     */
    protected open suspend fun writeFileState(
        rootUri: String,
        relativePath: String,
        now: Long,
        change: (ReviewDecisions) -> ReviewDecisions
    ) {
        val existing = getFileState(rootUri, relativePath)
        val next = change(existing?.decisions ?: ReviewDecisions())
        if (existing == null) {
            insertFileState(
                FileReviewStateEntity(
                    rootUri = rootUri,
                    relativePath = relativePath,
                    isCompleted = next.isCompleted,
                    isFavorite = next.isFavorite,
                    custom1 = next.custom1,
                    custom2 = next.custom2,
                    updatedAt = now
                )
            )
        } else {
            updateFileState(
                id = existing.id,
                isCompleted = next.isCompleted,
                isFavorite = next.isFavorite,
                custom1 = next.custom1,
                custom2 = next.custom2,
                updatedAt = now
            )
        }
    }

    /**
     * Gives each of these files an identity to carry decisions on, without asserting a single
     * decision about any of them.
     *
     * IGNORE rather than REPLACE, with every field left at its default: resolving a folder for
     * review must never touch what is already there. This is what lets Quick Review hand its
     * queue a stable id per file while leaving a file reviewed last month exactly as it was.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun registerFiles(states: List<FileReviewStateEntity>)

    /**
     * Every file under one root that anything is known about, for the browser's per-file ticks.
     *
     * Not scoped to a list, because the tick is not about a list: a file reviewed in a folder no
     * List has ever covered is checked, and the browser has to be able to say so. One query for
     * the whole screen, like the ownership it sits beside.
     */
    @Query(
        """
        SELECT relativePath, isCompleted FROM file_review_state WHERE rootUri = :rootUri
        """
    )
    abstract fun observeFileCompletion(rootUri: String): Flow<List<FileCompletion>>

    /** The decisions currently held for these files, for a folder-resolved review queue. */
    @Query(
        """
        SELECT * FROM file_review_state
        WHERE rootUri = :rootUri AND relativePath IN (:relativePaths)
        """
    )
    abstract fun observeFileStates(
        rootUri: String,
        relativePaths: List<String>
    ): Flow<List<FileReviewStateEntity>>

    /** The same, once, for building a payload out of what is stored right now. */
    @Query(
        """
        SELECT * FROM file_review_state
        WHERE rootUri = :rootUri AND relativePath IN (:relativePaths)
        """
    )
    abstract suspend fun getFileStates(
        rootUri: String,
        relativePaths: List<String>
    ): List<FileReviewStateEntity>

    @Query(
        """
        SELECT * FROM file_review_state
        WHERE rootUri = :rootUri AND relativePath = :relativePath
        """
    )
    protected abstract suspend fun getFileState(
        rootUri: String,
        relativePath: String
    ): FileReviewStateEntity?

    @Insert
    protected abstract suspend fun insertFileState(state: FileReviewStateEntity)

    @Query(
        """
        UPDATE file_review_state
        SET isCompleted = :isCompleted,
            isFavorite = :isFavorite,
            custom1 = :custom1,
            custom2 = :custom2,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    protected abstract suspend fun updateFileState(
        id: Long,
        isCompleted: Boolean,
        isFavorite: Boolean,
        custom1: Boolean,
        custom2: Boolean,
        updatedAt: Long
    )

    /** Every list holding this file, so a decision about it can reach all of them. */
    @Query(
        """
        SELECT DISTINCT l.id
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        WHERE l.sourceRootUri = :rootUri AND i.rootRelativePath = :relativePath
        """
    )
    protected abstract suspend fun listsContainingFile(
        rootUri: String,
        relativePath: String
    ): List<Long>

    @Query(
        """
        SELECT id, event, listTitle, itemCount, failedAt, reason
        FROM unsent_webhooks
        ORDER BY failedAt DESC, id DESC
        """
    )
    abstract fun observeUnsentWebhooks(): Flow<List<UnsentWebhookSummary>>

    @Query("SELECT COUNT(*) FROM unsent_webhooks")
    abstract fun observeUnsentWebhookCount(): Flow<Int>

    @Insert
    abstract suspend fun insertUnsentWebhook(record: UnsentWebhookEntity): Long

    @Query("SELECT * FROM unsent_webhooks WHERE id = :id")
    abstract suspend fun getUnsentWebhook(id: Long): UnsentWebhookEntity?

    @Query("DELETE FROM unsent_webhooks WHERE id = :id")
    abstract suspend fun deleteUnsentWebhook(id: Long)

    @Query("UPDATE lists SET reviewCurrentItemId = :itemId WHERE id = :id")
    abstract suspend fun setReviewPosition(id: Long, itemId: Long?)

    @Query("UPDATE lists SET webhookEnabled = :enabled WHERE id = :id")
    abstract suspend fun setWebhookEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE lists SET webhookUrl = :url WHERE id = :id")
    abstract suspend fun setWebhookUrl(id: Long, url: String)

    @Query(
        """
        UPDATE lists
        SET lastDeliveryAt = :at,
            lastDeliveryStatus = :status,
            lastDeliveryCode = :code,
            lastDeliveryError = :error
        WHERE id = :id
        """
    )
    abstract suspend fun recordDelivery(
        id: Long,
        at: Long,
        status: String,
        code: Int?,
        error: String?
    )

    /**
     * A list counts as complete once it holds at least one item and none are left unchecked.
     * An already recorded completedAt is kept, so the original completion time survives edits
     * that do not break completion.
     */
    @Transaction
    open suspend fun refreshCompletion(listId: Long, now: Long) {
        if (itemCount(listId) > 0 && unfinishedItemCount(listId) == 0) {
            markCompleted(listId, now)
        } else {
            clearCompleted(listId)
        }
    }

    /** Only source-backed items take part in reconciliation; manual items are excluded here. */
    @Query(
        """
        SELECT * FROM list_items
        WHERE listId = :listId AND sourceRelativePath IS NOT NULL
        ORDER BY sortOrder ASC, id ASC
        """
    )
    protected abstract suspend fun getSourceItems(listId: Long): List<ListItemEntity>

    /** The sourceRelativePath guard keeps manual items out of the delete, regardless of state. */
    @Query(
        """
        DELETE FROM list_items
        WHERE listId = :listId AND sourceRelativePath IS NOT NULL AND sourceMissing = 1
        """
    )
    protected abstract suspend fun deleteMissingSourceItems(listId: Long): Int

    @Query("UPDATE list_items SET sourceMissing = :missing WHERE id = :id")
    protected abstract suspend fun setSourceMissing(id: Long, missing: Boolean)

    @Query(
        """
        UPDATE list_items
        SET sourceSizeBytes = :sizeBytes, sourceModifiedAt = :modifiedAt
        WHERE id = :id
        """
    )
    protected abstract suspend fun setSourceMetrics(id: Long, sizeBytes: Long?, modifiedAt: Long?)

    @Query("UPDATE list_items SET sourceMissing = 0, sourceUri = :sourceUri WHERE id = :id")
    protected abstract suspend fun restoreSource(id: Long, sourceUri: String)

    @Insert
    protected abstract suspend fun insertItem(item: ListItemEntity): Long

    @Insert
    protected abstract suspend fun insertItems(items: List<ListItemEntity>)

    @Query("DELETE FROM list_items WHERE id = :id")
    protected abstract suspend fun deleteItem(id: Long)

    @Query("UPDATE list_items SET isCompleted = :completed WHERE id = :id")
    protected abstract suspend fun updateItemCompleted(id: Long, completed: Boolean)

    @Query("UPDATE list_items SET isFavorite = :enabled WHERE id = :id")
    protected abstract suspend fun setItemFavorite(id: Long, enabled: Boolean)

    @Query("UPDATE list_items SET custom1 = :enabled WHERE id = :id")
    protected abstract suspend fun setItemCustom1(id: Long, enabled: Boolean)

    @Query("UPDATE list_items SET custom2 = :enabled WHERE id = :id")
    protected abstract suspend fun setItemCustom2(id: Long, enabled: Boolean)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM list_items WHERE listId = :listId")
    protected abstract suspend fun maxSortOrder(listId: Long): Int

    @Query("SELECT COUNT(*) FROM list_items WHERE listId = :listId")
    protected abstract suspend fun itemCount(listId: Long): Int

    /**
     * How many of a list's items are still unchecked, by the state that actually counts.
     *
     * This is what decides whether a list is complete, so it has to read the same resolved value
     * the user sees. Reading the raw column here would leave a list permanently incomplete the
     * moment its files were checked from a folder's Quick Review instead of from the list.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM list_items i
        JOIN lists l ON l.id = i.listId
        LEFT JOIN file_review_state s
               ON s.rootUri = l.sourceRootUri AND s.relativePath = i.rootRelativePath
        WHERE i.listId = :listId
          AND (
              CASE WHEN i.rootRelativePath IS NULL THEN i.isCompleted
                   ELSE COALESCE(s.isCompleted, 0) END
          ) = 0
        """
    )
    protected abstract suspend fun unfinishedItemCount(listId: Long): Int

    @Query("UPDATE lists SET completedAt = :now WHERE id = :id AND completedAt IS NULL")
    protected abstract suspend fun markCompleted(id: Long, now: Long)

    @Query("UPDATE lists SET completedAt = NULL WHERE id = :id")
    protected abstract suspend fun clearCompleted(id: Long)

    /** Null when the list no longer exists, e.g. it was deleted while an edit was in flight. */
    @Query("SELECT completedAt IS NOT NULL FROM lists WHERE id = :id")
    protected abstract suspend fun isComplete(id: Long): Boolean?
}
