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
     */
    @Transaction
    open suspend fun addItem(listId: Long, title: String, now: Long) {
        insertItem(
            ListItemEntity(
                listId = listId,
                title = title,
                sortOrder = maxSortOrder(listId) + 1,
                createdAt = now
            )
        )
        refreshCompletion(listId, now)
    }

    @Transaction
    open suspend fun setItemCompleted(listId: Long, itemId: Long, completed: Boolean, now: Long) {
        updateItemCompleted(itemId, completed)
        refreshCompletion(listId, now)
    }

    @Transaction
    open suspend fun removeItem(listId: Long, itemId: Long, now: Long) {
        deleteItem(itemId)
        refreshCompletion(listId, now)
    }

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
}
