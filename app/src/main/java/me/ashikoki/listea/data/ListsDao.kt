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
               COALESCE(SUM(CASE WHEN i.isCompleted THEN 1 ELSE 0 END), 0) AS completedItems
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

    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun getItems(listId: Long): List<ListItemEntity>

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

    @Insert
    protected abstract suspend fun insertItem(item: ListItemEntity): Long

    @Query("DELETE FROM list_items WHERE id = :id")
    protected abstract suspend fun deleteItem(id: Long)

    @Query("UPDATE list_items SET isCompleted = :completed WHERE id = :id")
    protected abstract suspend fun updateItemCompleted(id: Long, completed: Boolean)

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
