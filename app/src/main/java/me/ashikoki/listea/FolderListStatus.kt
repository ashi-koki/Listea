package me.ashikoki.listea

import me.ashikoki.listea.data.FolderListScope
import me.ashikoki.listea.data.SourceItemRef

/**
 * What a folder in the browser should say about its List relationship.
 *
 * V3.1 guarantees folder-backed scopes never overlap, so a folder either owns a list, sits inside
 * exactly one ancestor's scope, or has no relationship at all.
 */
sealed interface FolderListStatus {
    data object None : FolderListStatus

    /** This exact folder owns [scope]. */
    data class Direct(val scope: FolderListScope) : FolderListStatus

    /**
     * An ancestor folder owns [scope]; this folder's files are already part of it.
     * Progress is this folder's own subtree, not the owning list's overall total — but the
     * webhook shown alongside it still belongs to [scope].
     */
    data class Inherited(
        val scope: FolderListScope,
        val subtreeCompleted: Int,
        val subtreeTotal: Int
    ) : FolderListStatus {
        /** 0 / 0 is never "complete", so an empty subtree gets no tick. */
        val subtreeIsComplete: Boolean
            get() = subtreeTotal > 0 && subtreeCompleted == subtreeTotal
    }
}

data class SubtreeProgress(val completed: Int, val total: Int)

/**
 * Scopes plus derived per-folder progress and per-file checked state, observed once for the whole
 * Folder screen. Both maps are keyed by owning list id and root-relative path.
 */
data class FolderOwnership(
    val scopes: List<FolderListScope> = emptyList(),
    val subtreeProgress: Map<Pair<Long, String>, SubtreeProgress> = emptyMap(),
    val itemCompletion: Map<Pair<Long, String>, Boolean> = emptyMap()
)

/**
 * Indexes every source-backed item once: into each folder it sits under, and into a direct
 * per-file lookup so a file row can show whether it is checked.
 *
 * Item paths are relative to their list's linked folder, so they are rebased onto the SAF root
 * first. Folder attribution walks whole path segments upwards, which is why `bilibili2/file.jpg`
 * can never be counted under `bilibili`.
 */
fun buildFolderOwnership(
    scopes: List<FolderListScope>,
    items: List<SourceItemRef>
): FolderOwnership {
    val scopePathById = scopes.associate { it.id to (it.relativePath ?: "") }
    // [total, completed] per (listId, folder path)
    val counts = HashMap<Pair<Long, String>, IntArray>()
    val completion = HashMap<Pair<Long, String>, Boolean>()

    for (item in items) {
        val itemPath = item.relativePath ?: continue
        val scopePath = scopePathById[item.listId] ?: continue
        val rootRelative = rootRelativeItemPath(scopePath, itemPath)
        completion[item.listId to rootRelative] = item.isCompleted

        var folder = rootRelative.substringBeforeLast('/', "")
        while (true) {
            val bucket = counts.getOrPut(item.listId to folder) { IntArray(2) }
            bucket[0]++
            if (item.isCompleted) bucket[1]++
            if (folder == scopePath || folder.isEmpty()) break
            folder = folder.substringBeforeLast('/', "")
        }
    }

    return FolderOwnership(
        scopes = scopes,
        subtreeProgress = counts.mapValues { (_, bucket) ->
            SubtreeProgress(completed = bucket[1], total = bucket[0])
        },
        itemCompletion = completion
    )
}

/**
 * Resolves ownership for [folderPath] against every scope under the current root. Pure and
 * in-memory: [scopes] comes from a single query, so this costs nothing per visible row.
 * Picks the nearest ancestor (longest matching path) defensively, though non-overlap means
 * there can only ever be one.
 */
fun folderListStatus(ownership: FolderOwnership, folderPath: String): FolderListStatus {
    var nearest: FolderListScope? = null
    var nearestLength = -1

    for (scope in ownership.scopes) {
        val scopePath = scope.relativePath ?: continue
        if (scopePath == folderPath) return FolderListStatus.Direct(scope)
        if (isAncestorOrSame(scopePath, folderPath) && scopePath.length > nearestLength) {
            nearest = scope
            nearestLength = scopePath.length
        }
    }

    return nearest?.let { scope ->
        val progress = ownership.subtreeProgress[scope.id to folderPath] ?: SubtreeProgress(0, 0)
        FolderListStatus.Inherited(scope, progress.completed, progress.total)
    } ?: FolderListStatus.None
}

/**
 * The three relationships the interface ever talks about.
 *
 * The internal model keeps saying Direct and Inherited, which describe *how* a list covers a
 * folder; these are what the user is told, and they describe *what the folder is*. One mapping,
 * used everywhere, so a folder can never read as one thing on a card and another in a header.
 *
 * A folder with lists only *below* it is [NONE]: being an ancestor of a list is not a
 * relationship the folder itself has.
 */
enum class FolderType(val label: String) {
    NONE("None"),
    LIST("List"),
    SUBLIST("Sublist")
}

fun folderTypeOf(status: FolderListStatus): FolderType = when (status) {
    is FolderListStatus.None -> FolderType.NONE
    is FolderListStatus.Direct -> FolderType.LIST
    is FolderListStatus.Inherited -> FolderType.SUBLIST
}

/**
 * Progress for a folder, or null when it has none to show.
 *
 * A List reports its own list's overall progress; a Sublist reports just its own subtree, which is
 * the part of the ancestor list that actually lives here.
 */
fun folderProgressLabel(status: FolderListStatus): String? = when (status) {
    is FolderListStatus.None -> null
    is FolderListStatus.Direct -> scopeProgressLabel(status.scope)
    is FolderListStatus.Inherited ->
        progressLabel(status.subtreeCompleted, status.subtreeTotal, status.subtreeIsComplete)
}

/** Which list is involved, for a folder that has one. Display only. */
fun folderOwnerTitle(status: FolderListStatus): String? = owningScope(status)?.title

/** The list owning this folder, whether it owns it directly or through an ancestor. */
fun owningScope(status: FolderListStatus): FolderListScope? = when (status) {
    is FolderListStatus.None -> null
    is FolderListStatus.Direct -> status.scope
    is FolderListStatus.Inherited -> status.scope
}

/** "7 / 18", with a tick once the whole list is done. */
fun scopeProgressLabel(scope: FolderListScope): String =
    progressLabel(scope.completedItems, scope.totalItems, scope.isComplete)

/**
 * Webhook state of a folder that owns its list outright, or null.
 *
 * Deliberately null for a Sublist: the webhook belongs to the ancestor list, and repeating it on
 * every folder underneath would suggest each one can deliver something of its own. One folder,
 * one webhook, shown where it is actually configured.
 */
fun statusWebhookLabel(status: FolderListStatus): String? {
    val scope = (status as? FolderListStatus.Direct)?.scope ?: return null
    return webhookStatusLabel(
        enabled = scope.webhookEnabled,
        lastDeliveryStatus = scope.lastDeliveryStatus,
        lastDeliveryCode = scope.lastDeliveryCode
    )
}
