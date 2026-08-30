package me.ashikoki.listea.data

import androidx.room.Dao
import androidx.room.Insert
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
               COALESCE(SUM(CASE WHEN i.isCompleted THEN 1 ELSE 0 END), 0) AS completedItems,
               l.webhookEnabled AS webhookEnabled,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode
        FROM lists l
        LEFT JOIN list_items i ON i.listId = l.id
        GROUP BY l.id
        ORDER BY l.createdAt DESC, l.id DESC
        """
    )
    abstract fun observeSummaries(): Flow<List<ListSummary>>

    @Query("SELECT * FROM lists WHERE id = :listId")
    abstract fun observeList(listId: Long): Flow<ListEntity?>

    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY sortOrder ASC, id ASC")
    abstract fun observeItems(listId: Long): Flow<List<ListItemEntity>>

    @Insert
    abstract suspend fun insertList(list: ListEntity): Long

    @Query("UPDATE lists SET title = :title WHERE id = :id")
    abstract suspend fun updateListTitle(id: Long, title: String)

    @Query("DELETE FROM lists WHERE id = :id")
    abstract suspend fun deleteList(id: Long)

    @Query("UPDATE list_items SET title = :title WHERE id = :id")
    abstract suspend fun updateItemTitle(id: Long, title: String)

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

    /** Returns true when checking this item completed the list. See [addItem]. */
    @Transaction
    open suspend fun setItemCompleted(
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
               COALESCE(SUM(CASE WHEN i.isCompleted THEN 1 ELSE 0 END), 0) AS completedItems,
               l.webhookEnabled AS webhookEnabled,
               l.lastDeliveryStatus AS lastDeliveryStatus,
               l.lastDeliveryCode AS lastDeliveryCode
        FROM lists l
        LEFT JOIN list_items i ON i.listId = l.id
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
               i.isCompleted AS isCompleted
        FROM list_items i
        JOIN lists l ON l.id = i.listId
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
        now: Long
    ): Long {
        replacedListIds.forEach { deleteList(it) }
        val listId = insertList(
            ListEntity(
                title = title,
                createdAt = now,
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
                    sourceRelativePath = file.relativePath
                )
            }
        )
        return listId
    }

    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun getItems(listId: Long): List<ListItemEntity>

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

        val appendFrom = maxSortOrder(listId) + 1
        insertItems(
            diff.addedPaths.mapIndexedNotNull { index, path ->
                val file = files.firstOrNull { it.relativePath == path } ?: return@mapIndexedNotNull null
                ListItemEntity(
                    listId = listId,
                    title = file.name,
                    isCompleted = false,
                    sortOrder = appendFrom + index,
                    createdAt = now,
                    sourceUri = file.uri,
                    sourceRelativePath = file.relativePath
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

        refreshCompletion(listId, now)
        return diff
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
     * Sets one review action on one item. Action metadata only: it deliberately does not call
     * [refreshCompletion] and returns nothing, so it can never report a completion transition and
     * therefore can never cause a webhook to be delivered.
     */
    suspend fun setItemAction(itemId: Long, action: ItemAction, enabled: Boolean) = when (action) {
        ItemAction.FAVORITE -> setItemFavorite(itemId, enabled)
        ItemAction.CUSTOM1 -> setItemCustom1(itemId, enabled)
        ItemAction.CUSTOM2 -> setItemCustom2(itemId, enabled)
    }

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

    @Query("SELECT COUNT(*) FROM list_items WHERE listId = :listId AND isCompleted = 0")
    protected abstract suspend fun unfinishedItemCount(listId: Long): Int

    @Query("UPDATE lists SET completedAt = :now WHERE id = :id AND completedAt IS NULL")
    protected abstract suspend fun markCompleted(id: Long, now: Long)

    @Query("UPDATE lists SET completedAt = NULL WHERE id = :id")
    protected abstract suspend fun clearCompleted(id: Long)

    /** Null when the list no longer exists, e.g. it was deleted while an edit was in flight. */
    @Query("SELECT completedAt IS NOT NULL FROM lists WHERE id = :id")
    protected abstract suspend fun isComplete(id: Long): Boolean?
}
