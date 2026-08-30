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
    val lastDeliveryError: String? = null,

    /**
     * Folder link. Null on manually created lists. [sourceRootUri] is the persisted SAF root tree
     * URI (the grant we actually hold) and [sourceRelativePath] is the display-name path beneath
     * it, "" for the root folder itself. Stored this way so the link survives restarts without
     * depending on a raw filesystem path or on a child document URI staying resolvable.
     */
    val sourceRootUri: String? = null,
    val sourceRelativePath: String? = null,

    /**
     * Where swipe Review should resume. A stable item id, never an index: re-sync, missing-item
     * cleanup and manual deletes all shift positions, and a stale id simply falls back.
     */
    val reviewCurrentItemId: Long? = null
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
    val createdAt: Long,

    /** Source file identity for folder-backed items. Null on manually added items. */
    val sourceUri: String? = null,
    val sourceRelativePath: String? = null,

    /**
     * Set by a re-sync when the source file is gone. The item and its review state are kept:
     * external tools move and re-sync files, and a completed review is worth more than the
     * current folder state.
     */
    val sourceMissing: Boolean = false,

    /**
     * Review actions: metadata for downstream automation, deliberately not completion state.
     * Checking an item is not an action, and setting an action never checks anything. Stored as
     * three flat columns rather than a tag table, because the set is fixed for now.
     */
    val isFavorite: Boolean = false,
    val custom1: Boolean = false,
    val custom2: Boolean = false
)

/**
 * The fixed set of downstream action slots an item can carry. Declaration order is the order the
 * webhook emits them in, so the array a receiver sees is deterministic and not dependent on how
 * the user toggled them.
 *
 * A slot's identity is the slot, not its name. The label the user sees and the value the webhook
 * sends are both configuration, held in AppSettings and resolved when they are needed; these are
 * only the factory values. That is what lets C1 be renamed without rewriting a single item row.
 */
enum class ItemAction(val defaultWireName: String, val defaultLabel: String) {
    FAVORITE("favorite", "★"),
    CUSTOM1("cust1", "C1"),
    CUSTOM2("cust2", "C2");

    fun isSetOn(item: ListItemEntity): Boolean = when (this) {
        FAVORITE -> item.isFavorite
        CUSTOM1 -> item.custom1
        CUSTOM2 -> item.custom2
    }
}

/**
 * Result of comparing a folder-backed list against a fresh scan of its source folder.
 * Manual items take no part in this.
 */
data class FolderDiff(
    val addedPaths: List<String>,
    val missingPaths: List<String>,
    val restoredPaths: List<String>,
    val unchangedCount: Int,
    val newlyMissingCount: Int
) {
    /** Only a real delta is worth writing; an item that was already missing and still is is not. */
    val hasChanges: Boolean
        get() = addedPaths.isNotEmpty() || newlyMissingCount > 0 || restoredPaths.isNotEmpty()
}

/**
 * A folder-backed list plus its item counts, keyed by the folder it owns. One query returns every
 * scope under a SAF root, so the Folder screen never queries per visible row.
 * [relativePath] is nullable only to match the column; the query filters nulls out.
 */
data class FolderListScope(
    val id: Long,
    val title: String,
    val relativePath: String?,
    val completedAt: Long?,
    val totalItems: Int,
    val completedItems: Int,

    /**
     * Just enough of the webhook state to label a folder card. A delivery always records its
     * timestamp and status together, so a null [lastDeliveryStatus] means nothing has been sent.
     */
    val webhookEnabled: Boolean,
    val lastDeliveryStatus: String?,
    val lastDeliveryCode: Int?
) {
    val isComplete: Boolean get() = completedAt != null
}

/**
 * A source-backed item of a folder-backed list, reduced to what subtree progress needs.
 * [relativePath] is relative to the owning list's linked folder, not to the SAF root.
 * Missing sources are included on purpose: they still belong to their subtree and still count.
 */
data class SourceItemRef(
    val listId: Long,
    val relativePath: String?,
    val isCompleted: Boolean
)

/** One file found by a recursive folder scan, before it becomes a [ListItemEntity]. */
data class ScannedFile(
    val name: String,
    val relativePath: String,
    val uri: String
)

/** One row of the Lists screen: a list plus its item counts. Query result, not a table. */
data class ListSummary(
    val id: Long,
    val title: String,
    val completedAt: Long?,
    val totalItems: Int,
    val completedItems: Int,

    /** Same webhook fields the Folder cards show, so both screens read identically. */
    val webhookEnabled: Boolean,
    val lastDeliveryStatus: String?,
    val lastDeliveryCode: Int?
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
