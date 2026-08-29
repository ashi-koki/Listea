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
        val rootRelative = if (scopePath.isEmpty()) itemPath else "$scopePath/$itemPath"
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

/** The list owning this folder, whether it owns it directly or through an ancestor. */
fun owningScope(status: FolderListStatus): FolderListScope? = when (status) {
    is FolderListStatus.None -> null
    is FolderListStatus.Direct -> status.scope
    is FolderListStatus.Inherited -> status.scope
}

/** "7 / 18 complete", with a tick once the whole list is done. */
fun scopeProgressLabel(scope: FolderListScope): String =
    progressLabel(scope.completedItems, scope.totalItems, scope.isComplete)

/**
 * Webhook state of the list owning this folder, or null when nothing owns it.
 * Shares [webhookStatusLabel] with the Lists screen so both read identically.
 */
fun statusWebhookLabel(status: FolderListStatus): String? {
    val scope = owningScope(status) ?: return null
    return webhookStatusLabel(
        enabled = scope.webhookEnabled,
        lastDeliveryStatus = scope.lastDeliveryStatus,
        lastDeliveryCode = scope.lastDeliveryCode
    )
}
