package me.ashikoki.listea

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.FolderDiff
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListDetail
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.ListSummary
import me.ashikoki.listea.data.ListeaDatabase
import me.ashikoki.listea.data.ScannedFile

/** What the Folder screen should be showing about a pending "create list from folder" action. */
sealed interface FolderListRequest {
    data object Scanning : FolderListRequest

    /** Overlapping folder lists exist; creation waits for the user to confirm the replacement. */
    data class Confirm(
        val folderName: String,
        val fileCount: Int,
        val replacedTitles: List<String>
    ) : FolderListRequest

    data class Error(val message: String) : FolderListRequest
}

/** What the List detail screen should be showing about a pending "refresh from folder" action. */
sealed interface ResyncRequest {
    data object Scanning : ResyncRequest
    data object UpToDate : ResyncRequest

    /** Changes were found; nothing is written until the user confirms. */
    data class Confirm(val diff: FolderDiff) : ResyncRequest

    /**
     * Reconciliation is already applied. Offers the optional, destructive follow-up of dropping
     * every source-backed item in the list that currently has no source file. Keeping them is the
     * default; nothing is deleted unless the user picks removal.
     */
    data class CleanupMissing(val listId: Long, val missingCount: Int) : ResyncRequest

    data class Error(val message: String) : ResyncRequest
}

/** Holds the List screens' state. Talks to the DAO directly; there is no repository layer yet. */
class ListsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = ListeaDatabase.get(application).listsDao()

    val summaries: StateFlow<List<ListSummary>> = dao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folderListRequest = MutableStateFlow<FolderListRequest?>(null)
    val folderListRequest: StateFlow<FolderListRequest?> = _folderListRequest.asStateFlow()

    private val _resyncRequest = MutableStateFlow<ResyncRequest?>(null)
    val resyncRequest: StateFlow<ResyncRequest?> = _resyncRequest.asStateFlow()

    /** Scan result waiting for confirmation. Nothing is written until it is committed. */
    private var pendingFolderList: PendingFolderList? = null
    private var pendingResync: PendingResync? = null

    private data class PendingResync(val listId: Long, val files: List<ScannedFile>)

    private data class PendingFolderList(
        val title: String,
        val rootUri: String,
        val relativePath: String,
        val files: List<ScannedFile>,
        val replacedIds: List<Long>
    )

    /**
     * Every folder-backed scope under [rootUri] plus per-folder subtree progress: two observations
     * for the whole screen, never one per visible row. The indexing pass runs off the main thread
     * and only when the data changes.
     */
    fun observeFolderOwnership(rootUri: String): Flow<FolderOwnership> =
        combine(
            dao.observeFolderScopes(rootUri),
            dao.observeFolderScopeItems(rootUri)
        ) { scopes, items ->
            buildFolderOwnership(scopes, items)
        }.flowOn(Dispatchers.Default)

    fun observeDetail(listId: Long): Flow<ListDetail?> =
        combine(dao.observeList(listId), dao.observeItems(listId)) { list, items ->
            list?.let { ListDetail(it, items) }
        }

    fun createList(title: String) = edit(title) { clean ->
        dao.insertList(ListEntity(title = clean, createdAt = now()))
    }

    fun renameList(listId: Long, title: String) = edit(title) { clean ->
        dao.updateListTitle(listId, clean)
    }

    fun deleteList(listId: Long) = launchDb { dao.deleteList(listId) }

    fun addItem(listId: Long, title: String) = edit(title) { clean ->
        onCompletionTransition(listId, dao.addItem(listId, clean, now()))
    }

    fun renameItem(itemId: Long, title: String) = edit(title) { clean ->
        dao.updateItemTitle(itemId, clean)
    }

    fun setItemCompleted(item: ListItemEntity, completed: Boolean) = launchDb {
        onCompletionTransition(
            item.listId,
            dao.setItemCompleted(item.listId, item.id, completed, now())
        )
    }

    /**
     * Toggles one review action. Persisted immediately and nothing else: completion is untouched,
     * so this never delivers a webhook. The value simply travels with the item into the next
     * list.completed payload.
     */
    fun setItemAction(item: ListItemEntity, action: ItemAction, enabled: Boolean) = launchDb {
        dao.setItemAction(item.id, action, enabled)
    }

    fun deleteItem(item: ListItemEntity) = launchDb {
        onCompletionTransition(item.listId, dao.removeItem(item.listId, item.id, now()))
    }

    /**
     * Step 1 of creating a folder-backed list: scan, then work out overlapping scopes.
     * Writes nothing. Commits immediately only when there is nothing to replace.
     */
    fun requestListForFolder(
        rootUri: String,
        relativePath: String,
        folderUri: Uri,
        folderName: String
    ) = launchDb {
        _folderListRequest.value = FolderListRequest.Scanning
        pendingFolderList = null

        val existing = dao.getFolderLists(rootUri)
        if (existing.any { it.sourceRelativePath == relativePath }) {
            // Already linked to this exact folder; the UI offers "Open list" instead.
            _folderListRequest.value = null
            return@launchDb
        }

        val files = withContext(Dispatchers.IO) { scanFolderFiles(getApplication(), folderUri) }
        if (files == null) {
            _folderListRequest.value =
                FolderListRequest.Error("Could not read that folder. Nothing was changed.")
            return@launchDb
        }

        val conflicts = existing.filter { other ->
            val otherPath = other.sourceRelativePath ?: return@filter false
            scopesOverlap(relativePath, otherPath)
        }
        pendingFolderList = PendingFolderList(
            title = folderName,
            rootUri = rootUri,
            relativePath = relativePath,
            files = files,
            replacedIds = conflicts.map { it.id }
        )

        if (conflicts.isEmpty()) {
            commitPendingFolderList()
        } else {
            _folderListRequest.value = FolderListRequest.Confirm(
                folderName = folderName,
                fileCount = files.size,
                replacedTitles = conflicts.map { it.title }
            )
        }
    }

    /** Step 2: the user accepted replacing the overlapping lists. */
    fun confirmListForFolder() = launchDb { commitPendingFolderList() }

    fun dismissFolderListRequest() {
        pendingFolderList = null
        _folderListRequest.value = null
    }

    private suspend fun commitPendingFolderList() {
        val pending = pendingFolderList ?: return
        val created = runCatching {
            dao.createFolderList(
                title = pending.title,
                rootUri = pending.rootUri,
                relativePath = pending.relativePath,
                files = pending.files,
                replacedListIds = pending.replacedIds,
                now = now()
            )
        }
        pendingFolderList = null
        _folderListRequest.value = if (created.isSuccess) {
            null
        } else {
            FolderListRequest.Error("Could not create the list. Nothing was changed.")
        }
    }

    /**
     * Step 1 of a manual re-sync: rescan the linked folder and compare. Writes nothing.
     * Deliberately does not go anywhere near the webhook path.
     */
    fun requestResync(listId: Long) = launchDb {
        _resyncRequest.value = ResyncRequest.Scanning
        pendingResync = null

        val list = dao.getList(listId)
        val rootUri = list?.sourceRootUri
        val relativePath = list?.sourceRelativePath
        if (rootUri == null || relativePath == null) {
            _resyncRequest.value = ResyncRequest.Error("This list is not linked to a folder.")
            return@launchDb
        }

        val files = withContext(Dispatchers.IO) {
            val folder = resolveLinkedFolder(getApplication(), rootUri.toUri(), relativePath)
            folder?.let { scanFolderFiles(getApplication(), it.uri) }
        }
        if (files == null) {
            _resyncRequest.value =
                ResyncRequest.Error("Could not read the source folder. Nothing was changed.")
            return@launchDb
        }

        val diff = dao.previewReconcile(listId, files)
        if (!diff.hasChanges) {
            _resyncRequest.value = ResyncRequest.UpToDate
            return@launchDb
        }

        pendingResync = PendingResync(listId, files)
        _resyncRequest.value = ResyncRequest.Confirm(diff)
    }

    /** Step 2: apply the whole reconciliation in one transaction, or nothing at all. */
    fun confirmResync() = launchDb {
        val pending = pendingResync ?: return@launchDb
        val applied = runCatching { dao.applyReconcile(pending.listId, pending.files, now()) }
        pendingResync = null

        if (applied.isFailure) {
            _resyncRequest.value = ResyncRequest.Error("Could not update the list. Nothing was changed.")
            return@launchDb
        }

        // Step 3, only when something is actually missing: offer the optional cleanup. The count
        // covers every currently missing source-backed item, including ones flagged by an earlier
        // re-sync, so the prompt matches what removal would actually delete.
        val missing = dao.missingSourceItemCount(pending.listId)
        _resyncRequest.value = if (missing > 0) {
            ResyncRequest.CleanupMissing(pending.listId, missing)
        } else {
            null
        }
    }

    /**
     * Raises the same cleanup prompt on demand, so the offer is recoverable: dismissing the
     * post-re-sync dialog keeps the items, and a later re-sync then reports "up to date" because
     * they are already flagged. Writes nothing by itself.
     */
    fun requestMissingCleanup(listId: Long) = launchDb {
        val missing = dao.missingSourceItemCount(listId)
        if (missing > 0) {
            _resyncRequest.value = ResyncRequest.CleanupMissing(listId, missing)
        }
    }

    /**
     * The explicit destructive choice. Deletes only source-backed items whose source is gone;
     * completion is recomputed inside the same transaction. Never reaches the webhook path.
     */
    fun confirmRemoveMissingItems() = launchDb {
        val request = _resyncRequest.value as? ResyncRequest.CleanupMissing ?: return@launchDb
        val removed = runCatching { dao.removeMissingSourceItems(request.listId, now()) }
        _resyncRequest.value = if (removed.isSuccess) {
            null
        } else {
            ResyncRequest.Error("Could not remove the missing items. Nothing was changed.")
        }
    }

    fun dismissResyncRequest() {
        pendingResync = null
        _resyncRequest.value = null
    }

    /** Remembers where Review is, by item id. Never touches completion state. */
    fun setReviewPosition(listId: Long, itemId: Long?) = launchDb {
        dao.setReviewPosition(listId, itemId)
    }

    fun setWebhookEnabled(listId: Long, enabled: Boolean) = launchDb {
        dao.setWebhookEnabled(listId, enabled)
    }

    fun setWebhookUrl(listId: Long, url: String) = launchDb {
        dao.setWebhookUrl(listId, url)
    }

    /** Explicit user action, so it sends regardless of the enabled toggle and never touches state. */
    fun testWebhook(listId: Long) = launchDb {
        deliver(listId, EVENT_WEBHOOK_TEST, requireEnabled = false)
    }

    /**
     * The single place the completion action fires. [transitioned] comes from the DAO transaction
     * that performed the edit, so a list that merely stays complete never delivers again.
     */
    private suspend fun onCompletionTransition(listId: Long, transitioned: Boolean) {
        if (transitioned) deliver(listId, EVENT_LIST_COMPLETED, requireEnabled = true)
    }

    private suspend fun deliver(listId: Long, event: String, requireEnabled: Boolean) {
        val list = dao.getList(listId) ?: return
        if (requireEnabled && !list.webhookEnabled) return

        val url = list.webhookUrl.trim()
        val urlProblem = webhookUrlError(url)
        if (urlProblem != null) {
            dao.recordDelivery(listId, now(), DeliveryStatus.FAILED.name, null, urlProblem)
            return
        }

        val payload = buildWebhookPayload(event, list, dao.getItems(listId))
        val result = withContext(Dispatchers.IO) { postWebhook(url, payload) }
        dao.recordDelivery(listId, now(), result.status.name, result.httpCode, result.error)
    }

    /** Runs [block] with a trimmed title, ignoring blank input. */
    private fun edit(title: String, block: suspend (String) -> Unit) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        launchDb { block(clean) }
    }

    private fun launchDb(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun now() = System.currentTimeMillis()
}
