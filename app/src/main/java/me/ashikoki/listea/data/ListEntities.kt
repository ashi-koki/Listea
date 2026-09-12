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
    indices = [Index("listId"), Index("rootRelativePath")]
)
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * What this item is called outside Listea — on the webhook and in whatever the webhook feeds.
     *
     * Assigned once, when the row is created, and never rewritten. [id] stays what it always was:
     * a local handle for foreign keys, resume positions and queue lookups, which is all a
     * sequence number is any good for. See [newItemPublicId] for what this is made of and why the
     * two are not the same thing.
     */
    val publicId: String,
    val listId: Long,
    val title: String,
    /**
     * Manual items only. A source-backed item's checked state belongs to the *file*, not to this
     * row: it lives in [FileReviewStateEntity] and is projected back in by the queries that read
     * items, so two Lists over the same file can never disagree about it. Reading this column
     * directly on a source-backed row is a bug — it is left at its default and never written.
     */
    val isCompleted: Boolean = false,
    val sortOrder: Int,
    val createdAt: Long,

    /** Source file identity for folder-backed items. Null on manually added items. */
    val sourceUri: String? = null,
    val sourceRelativePath: String? = null,

    /**
     * The same file's path from the SAF root rather than from this list's folder: the identity a
     * review decision is actually stored against, in [FileReviewStateEntity].
     *
     * Denormalised on purpose. It is derivable — the owning list's folder plus [sourceRelativePath]
     * — but a list's folder link never changes after creation, so it cannot drift, and having it
     * on the row is what lets every query that needs a decision join straight to it instead of
     * concatenating paths in SQL. Null for manual items, which have no file.
     */
    val rootRelativePath: String? = null,

    /**
     * Set by a re-sync when the source file is gone. The item and its review state are kept:
     * external tools move and re-sync files, and a completed review is worth more than the
     * current folder state.
     */
    val sourceMissing: Boolean = false,

    /**
     * What the source file measured at the last scan of the linked folder, cached here so the
     * Items header can filter and sort on it without touching the provider.
     *
     * Cached rather than authoritative, and null-tolerant on purpose. A list *is* a snapshot of a
     * folder — that is what the whole freshness machinery exists to reconcile — so a measurement
     * taken with the snapshot is the honest thing to hold. Null means no scan has recorded one
     * yet, and by the unknown rule (see FileArrange.kt) an unmeasured item is never filtered out
     * and never claims a position it has not earned in a sort.
     *
     * Refreshed by every scan of the linked folder, including the read-only freshness check, so a
     * list created before these existed fills itself in the first time its page is opened.
     */
    val sourceSizeBytes: Long? = null,
    val sourceModifiedAt: Long? = null,

    /**
     * Review actions: metadata for downstream automation, deliberately not completion state.
     * Checking an item is not an action, and setting an action never checks anything. Stored as
     * three flat columns rather than a tag table, because the set is fixed for now.
     *
     * Manual items only, for the same reason as [isCompleted]: an action is a decision about a
     * file, and a decision about a file is stored against the file.
     */
    val isFavorite: Boolean = false,
    val custom1: Boolean = false,
    val custom2: Boolean = false
)

/**
 * Every decision a review can reach about one file, and the file it is about.
 *
 * This is the point the Lists were decoupled from. A decision belongs to the file, not to whoever
 * happened to be looking at it: a folder-scoped Quick Review, a List built over that folder, and
 * a second List built over it next month are three ways of *reaching* the same file and must all
 * see and write the same state. Storing it here is what makes that true by construction rather
 * than by everyone remembering to copy it around.
 *
 * Identity is [rootUri] plus [relativePath] — the SAF grant the file is under, and where it sits
 * beneath it — because that is the only name for a file that survives everything Listea does to
 * it. Row ids, list membership and document URIs all come and go; the path under the root does
 * not. A unique index enforces one row per file.
 *
 * [id] exists so a resolved file has a stable handle for a review queue to hold on to, which a
 * folder-resolved item has no [ListItemEntity] to borrow one from. Rows are created as files are
 * resolved for review, with every decision false, so registering a file never asserts anything
 * about it.
 */
@Entity(
    tableName = "file_review_state",
    indices = [Index(value = ["rootUri", "relativePath"], unique = true)]
)
data class FileReviewStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * The webhook-facing name for this file's registration, exactly as [ListItemEntity.publicId]
     * is for a list row — and the reason both tables carry one. A folder Quick Review has no list
     * rows to send, so it sends these; before they existed the two tables' sequence numbers went
     * out under the same field name and a receiver had no way to tell which space an id came from.
     */
    val publicId: String,
    val rootUri: String,
    val relativePath: String,
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false,
    val custom1: Boolean = false,
    val custom2: Boolean = false,

    /**
     * Set when the file behind this state is gone, exactly as [ListItemEntity.sourceMissing] is.
     *
     * The decision is kept rather than deleted, for the reason it always was: a completed review
     * is worth more than the current state of a folder, and a list whose files were deleted must
     * not silently become incomplete again. What this flag buys is honesty everywhere else — a
     * file that no longer exists is not offered for deletion and is not counted as one that could
     * be. Cleared again the moment a folder resolution finds the file present.
     */
    val sourceMissing: Boolean = false,

    /** When the decision last changed, for nothing but debugging: no behaviour reads it. */
    val updatedAt: Long
)

/** One file's checked state, for the browser rows. Query result, not a table. */
data class FileCompletion(val relativePath: String, val isCompleted: Boolean)

/**
 * The four decisions a review can hold about one item, apart from where they are stored.
 *
 * Exists so the screens can stop caring whether they are looking at a List's row or a folder's
 * file. Both produce one of these, and the action bar, the Info sheet and the webhook all read it
 * the same way.
 */
data class ReviewDecisions(
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false,
    val custom1: Boolean = false,
    val custom2: Boolean = false
)

/**
 * A file's path from the SAF root: the owning list's folder, then the file's path inside it.
 *
 * The canonical join, kept here because it is what a decision's identity is made of. Everything
 * that needs to name a file the same way a [FileReviewStateEntity] does goes through this.
 */
fun rootRelativePathOf(listPath: String, itemPath: String): String =
    if (listPath.isEmpty()) itemPath else "$listPath/$itemPath"

/** The decisions a file's stored state is carrying. */
val FileReviewStateEntity.decisions: ReviewDecisions
    get() = ReviewDecisions(isCompleted, isFavorite, custom1, custom2)

/** The decisions a stored row is carrying, whatever its origin. */
val ListItemEntity.decisions: ReviewDecisions
    get() = ReviewDecisions(isCompleted, isFavorite, custom1, custom2)

/**
 * Where a decision is written when the user makes one.
 *
 * Two cases only, and they are not symmetric: [File] is the normal one and is shared by everything
 * that can see that file, while [ManualItem] is a row that *is* the item — a manually typed entry
 * has no file to be a decision about, so its state stays where it always was, on its own row.
 */
sealed interface DecisionTarget {
    data class File(val rootUri: String, val relativePath: String) : DecisionTarget
    data class ManualItem(val listId: Long, val itemId: Long) : DecisionTarget
}

/**
 * One card in a review queue, whatever resolved it.
 *
 * The type both review modes speak. A List resolves its membership rows into these; a folder
 * resolves its files into these; from there the queue mechanics, the gestures, the action bar and
 * the media renderer cannot tell the two apart, which is exactly the decoupling — Quick Review
 * stopped being a filtered view of a List and became a peer of it.
 *
 * [id] is only an identity *within a queue*: a list row's id for List review, a file state row's
 * id for a folder review. It is never a cross-mode identity, and nothing persists it except a
 * List's own resume position, which only List review has. What is shared across modes is
 * [target], and that is a path, not an id.
 *
 * [publicId] is the opposite of that in every respect: it comes off the stored row either mode
 * resolved, it means the same thing across both, and it is the only one of the two a receiver
 * ever sees. Carried here because a folder round's payload is built from these items and has no
 * list rows to read it off instead.
 */
data class ReviewItem(
    val id: Long,
    val publicId: String,
    val title: String,
    val sourceUri: String?,

    /** For display: relative to whatever the queue is scoped to, not to the root. */
    val relativePath: String?,
    val sourceMissing: Boolean,
    val target: DecisionTarget,
    val decisions: ReviewDecisions,

    /**
     * What the file measures, for the filter and the sort that decide which items a page shows
     * and which of them a review round walks.
     *
     * Carried on the item rather than looked up beside it because both queues can answer it from
     * what they already have — a folder review off the listing it just read, a list review off the
     * row it resolved — and because a filter that has to join against a second structure is a
     * filter that will eventually be given the wrong one. Null is "not measured", never zero: see
     * the unknown rule in FileArrange.kt.
     */
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,

    /**
     * The `list_items` row this came from, for the operations that are membership rather than
     * review — renaming and removing an entry from a List. Null for a folder-resolved file, which
     * belongs to no List and has no membership to edit.
     */
    val rowId: Long? = null
)

/**
 * A stored row resolved for review, with its decisions taken from wherever they actually live.
 *
 * [decisions] is passed in rather than read off the row: the queries that load items project the
 * shared file state into the entity's columns, and this keeps the one place that assumption is
 * made visible instead of scattering `item.isCompleted` across the screens again.
 */
fun ListItemEntity.toReviewItem(rootUri: String?): ReviewItem = ReviewItem(
    id = id,
    publicId = publicId,
    title = title,
    sourceUri = sourceUri,
    relativePath = sourceRelativePath,
    sourceMissing = sourceMissing,
    target = if (rootUri != null && rootRelativePath != null) {
        DecisionTarget.File(rootUri, rootRelativePath)
    } else {
        DecisionTarget.ManualItem(listId, id)
    },
    decisions = decisions,
    sizeBytes = sourceSizeBytes,
    lastModified = sourceModifiedAt,
    rowId = id
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

    fun isSetOn(decisions: ReviewDecisions): Boolean = when (this) {
        FAVORITE -> decisions.isFavorite
        CUSTOM1 -> decisions.custom1
        CUSTOM2 -> decisions.custom2
    }

    /** The same slot with [enabled] applied, for a write that replaces the whole set. */
    fun setOn(decisions: ReviewDecisions, enabled: Boolean): ReviewDecisions = when (this) {
        FAVORITE -> decisions.copy(isFavorite = enabled)
        CUSTOM1 -> decisions.copy(custom1 = enabled)
        CUSTOM2 -> decisions.copy(custom2 = enabled)
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

/**
 * A webhook body that never reached a receiver, kept so the decisions inside it are not lost.
 *
 * Written for every attempt that did not come back successful — a switched-off webhook, a missing
 * or malformed URL, a non-2xx response, a network failure — as long as there was something in it
 * to send. An empty payload is not worth keeping, and a delivery that succeeded has nothing left
 * to do.
 *
 * Deliberately standalone, with no foreign key to a list: the whole point is that the payload
 * outlives whatever went wrong, and deleting the list it came from must not quietly take the
 * record with it. [payload] is the exact JSON that was going to be posted, so resending sends
 * what failed rather than something rebuilt out of state that has since moved on.
 */
@Entity(tableName = "unsent_webhooks")
data class UnsentWebhookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event: String,
    val listTitle: String,
    val itemCount: Int,
    val payload: String,

    /** When the delivery failed. This is what names the record in the history. */
    val failedAt: Long,

    /** Why it failed, in the same words the dialog used at the time. */
    val reason: String
)

/**
 * One row of the unsent history: everything the list needs, and not the payload.
 *
 * A payload is as big as the round it covers, so the history observes this instead and reads the
 * body itself only when the user opens one.
 */
data class UnsentWebhookSummary(
    val id: Long,
    val event: String,
    val listTitle: String,
    val itemCount: Int,
    val failedAt: Long,
    val reason: String
)

/** One file found by a recursive folder scan, before it becomes a [ListItemEntity]. */
data class ScannedFile(
    val name: String,
    val relativePath: String,
    val uri: String,

    /**
     * What the provider said about the file at scan time, for the Items header to filter and sort
     * on. Null means it would not say, which every filter reads as "no reason to exclude this".
     *
     * Measured during the walk that was happening anyway rather than by a second pass: a list's
     * items span a whole subtree, and asking the provider for a size per row when the header's
     * filter icon is tapped would make that icon the most expensive control on the page — the
     * same reason the folder browser reads these off its listing.
     */
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
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

/**
 * A list together with its items resolved for review, for the detail screen.
 *
 * [ReviewItem] rather than the stored rows, because a source-backed row does not carry its own
 * decisions any more: the resolution happens once, here, and the screen reads one shape whether
 * it is showing a folder-backed file or a manually typed entry.
 */
data class ListDetail(
    val list: ListEntity,
    val items: List<ReviewItem>
) {
    val completedCount: Int get() = items.count { it.decisions.isCompleted }
    val isComplete: Boolean get() = list.completedAt != null
}
