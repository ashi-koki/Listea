package me.ashikoki.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val completedAt: Long? = null,

    /** Optional completion action: one POST webhook. Only fires when enabled and the URL is valid. */
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",

    /** Lightweight record of the last attempt, so the UI can show never sent / success / failed. */
    val lastDeliveryAt: Long? = null,
    val lastDeliveryStatus: String? = null,
    val lastDeliveryCode: Int? = null,
    val lastDeliveryError: String? = null
)

@Entity(
    tableName = "list_items",
    foreignKeys = [
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("listId")]
)
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int,
    val createdAt: Long
)

/** One row of the Lists screen: a list plus its item counts. Query result, not a table. */
data class ListSummary(
    val id: Long,
    val title: String,
    val completedAt: Long?,
    val totalItems: Int,
    val completedItems: Int
) {
    val isComplete: Boolean get() = completedAt != null
}

/** A list together with its items, for the detail screen. */
data class ListDetail(
    val list: ListEntity,
    val items: List<ListItemEntity>
) {
    val completedCount: Int get() = items.count { it.isCompleted }
    val isComplete: Boolean get() = list.completedAt != null
}
