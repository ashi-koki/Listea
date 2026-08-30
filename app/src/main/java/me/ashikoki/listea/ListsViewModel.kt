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
import kotlinx.coroutines.Job
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

    /**
     * Overlapping folder lists exist; creation waits for the user to confirm the replacement.
     *
     * [replacesExact] means one of them is this very folder's own list, so the user is rebuilding
     * it rather than taking the folder over from an ancestor or from folders below.
     */
    data class Confirm(
        val folderName: String,
        val fileCount: Int,
        val replacedTitles: List<String>,
        val replacesExact: Boolean
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

/**
 * Whether a folder-backed list has source changes it has not acknowledged yet.
 *
 * Purely derived from a read-only comparison: producing this never inserts, flags, restores or
 * deletes an item, never touches completion or actions, and never delivers a webhook. Acting on
 * it stays manual.
 */
sealed interface SourceFreshness {
    data object Checking : SourceFreshness

    /**
     * No *unacknowledged* changes, which is not the same as the rows matching the filesystem
     * byte for byte. A source file that vanished, was reconciled, and was deliberately kept as a
     * missing item is already acknowledged: it must not make the list stale forever.
     */
    data object UpToDate : SourceFreshness

    data class ChangesAvailable(val diff: FolderDiff) : SourceFreshness

    /** The linked folder could not be resolved or read: revoked grant, deleted folder, bad path. */
    data object SourceUnavailable : SourceFreshness
}

/**
 * A freshness result bound to the list it describes. The screen renders it only when the id
 * matches the list it is showing, so a scan that finishes after the user has moved on can never
 * label a different list.
 */
data class ListFreshness(val listId: Long, val state: SourceFreshness)

/**
 * What the Folder screen should do about a pending Quick Review tap.
 *
 * Quick Review must never review a snapshot Listea already knows is stale, so the tap is gated on
 * the same source check the list page runs, and the verdict decides where the user lands.
 */
sealed interface QuickReviewRequest {
    data object Checking : QuickReviewRequest

    /** Source is up to date: the folder-scoped queue can be reviewed. */
    data class Ready(val listId: Long, val folderPath: String) : QuickReviewRequest

    /**
     * Unacknowledged source changes, or a source that cannot be read. Quick Review is skipped in
     * favour of the owning list's own page, which already explains both and offers the update.
     * No dialog is opened on the user's behalf.
     */
    data class OpenList(val listId: Long) : QuickReviewRequest
}

/**
 * What changing the root folder would cost, held until the user has seen it.
 *
 * [titles] is every list that would be deleted: the ones created on the root and the ones created
 * on folders below it. Empty means the change is free.
 */
data class RootChangePreview(val rootUri: String, val titles: List<String>)

/** Holds the List screens' state. Talks to the DAO directly; there is no repository layer yet. */
class ListsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = ListeaDatabase.get(application).listsDao()
    private val settingsStore = SettingsStore(application)

    /**
     * App settings for the screens to observe. Writes are immediate; the UI simply follows.
     * Anywhere a stale value would actually be wrong — creating a list, sending a webhook — the
     * store is read directly instead, so nothing depends on this having warmed up.
     */
    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val summaries: StateFlow<List<ListSummary>> = dao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folderListRequest = MutableStateFlow<FolderListRequest?>(null)
    val folderListRequest: StateFlow<FolderListRequest?> = _folderListRequest.asStateFlow()

    private val _resyncRequest = MutableStateFlow<ResyncRequest?>(null)
    val resyncRequest: StateFlow<ResyncRequest?> = _resyncRequest.asStateFlow()

    /**
     * The last delivery result, waiting to be acknowledged. Held here rather than on a screen
     * because a completion webhook can fire from anywhere, and the user should not have to be
     * looking at the right list to find out what happened.
     */
    private val _webhookNotice = MutableStateFlow<WebhookNotice?>(null)
    val webhookNotice: StateFlow<WebhookNotice?> = _webhookNotice.asStateFlow()

    private val _sourceFreshness = MutableStateFlow<ListFreshness?>(null)
    val sourceFreshness: StateFlow<ListFreshness?> = _sourceFreshness.asStateFlow()

    /** The in-flight automatic check, kept so a newer one can replace it rather than race it. */
    private var freshnessJob: Job? = null

    private val _rootChangePreview = MutableStateFlow<RootChangePreview?>(null)
    val rootChangePreview: StateFlow<RootChangePreview?> = _rootChangePreview.asStateFlow()

    private val _quickReviewRequest = MutableStateFlow<QuickReviewRequest?>(null)
    val quickReviewRequest: StateFlow<QuickReviewRequest?> = _quickReviewRequest.asStateFlow()

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
        val defaults = settingsStore.read()
        dao.insertList(
            ListEntity(
                title = clean,
                createdAt = now(),
                // A template, copied once. The list's webhook is its own from here on.
                webhookEnabled = defaults.defaultWebhookEnabled,
                webhookUrl = defaults.defaultWebhookUrl
            )
        )
    }

    fun renameList(listId: Long, title: String) = edit(title) { clean ->
        dao.updateListTitle(listId, clean)
    }

    fun deleteList(listId: Long) = launchDb { dao.deleteList(listId) }

    /** Reads what giving up [rootUri] would delete. Writes nothing. */
    fun previewRootChange(rootUri: String) = launchDb {
        val affected = dao.getFolderLists(rootUri).map { it.title }.sorted()
        _rootChangePreview.value = RootChangePreview(rootUri, affected)
    }

    fun dismissRootChangePreview() {
        _rootChangePreview.value = null
    }

    /**
     * Deletes the lists belonging to a root the user has chosen to give up, items and all.
     *
     * Destructive and irreversible, so it is called only after an explicit confirmation and only
     * once a different root has actually been granted — backing out of the folder picker leaves
     * everything exactly as it was.
     */
    fun discardListsForRoot(rootUri: String) = launchDb { dao.deleteListsForRoot(rootUri) }

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

        val files = withContext(Dispatchers.IO) { scanFolderFiles(getApplication(), folderUri) }
        if (files == null) {
            _folderListRequest.value =
                FolderListRequest.Error("Could not read that folder. Nothing was changed.")
            return@launchDb
        }

        // Everything this folder's scope would collide with: its own existing list, an ancestor
        // that already covers it, or lists sitting in folders below it. One overlap rule for all
        // three, so there is a single Create List action rather than three near-identical ones.
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
                replacedTitles = conflicts.map { it.title },
                replacesExact = conflicts.any { it.sourceRelativePath == relativePath }
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
        val defaults = settingsStore.read()
        val created = runCatching {
            dao.createFolderList(
                title = pending.title,
                rootUri = pending.rootUri,
                relativePath = pending.relativePath,
                files = pending.files,
                replacedListIds = pending.replacedIds,
                // Same template as a manual list gets, including when this replaces an
                // overlapping list: there is one default, not one per kind of list.
                webhookEnabled = defaults.defaultWebhookEnabled,
                webhookUrl = defaults.defaultWebhookUrl,
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
     * The automatic check, run when a list's detail page becomes the active screen. Read-only from
     * end to end: it resolves the folder, scans it, and compares through the same
     * [ListsDao.previewReconcile] the manual update uses, then throws the scan away. Nothing is
     * inserted, flagged, restored, deleted or completed, and no webhook can result.
     *
     * Manual lists have no source to compare against, so they are dropped before any scan starts.
     * A previous check is cancelled rather than left to race: whichever check started last wins.
     */
    fun checkSourceFreshness(listId: Long) {
        freshnessJob?.cancel()
        freshnessJob = viewModelScope.launch {
            val list = dao.getList(listId)
            if (list == null || list.sourceRootUri == null || list.sourceRelativePath == null) {
                _sourceFreshness.value = null
                return@launch
            }

            _sourceFreshness.value = ListFreshness(listId, SourceFreshness.Checking)
            val files = scanLinkedFolder(list)
            val state = if (files == null) {
                SourceFreshness.SourceUnavailable
            } else {
                freshnessOf(dao.previewReconcile(listId, files))
            }
            _sourceFreshness.value = ListFreshness(listId, state)
        }
    }

    /**
     * The Quick Review gate. Runs exactly the check the list page runs — same folder resolution,
     * same scan, same [ListsDao.previewReconcile], same verdict — and lets the result choose the
     * destination. Read-only, like every freshness check: nothing is reconciled here.
     *
     * The verdict is published to [sourceFreshness] as well, so a list page reached this way is
     * already showing the reason it was reached.
     */
    fun requestQuickReview(listId: Long, folderPath: String) = launchDb {
        _quickReviewRequest.value = QuickReviewRequest.Checking

        val list = dao.getList(listId)
        if (list == null || list.sourceRootUri == null || list.sourceRelativePath == null) {
            // The owning list vanished, or was never folder-backed: there is nothing to enter.
            _quickReviewRequest.value = null
            return@launchDb
        }

        val files = scanLinkedFolder(list)
        val state = if (files == null) {
            SourceFreshness.SourceUnavailable
        } else {
            freshnessOf(dao.previewReconcile(listId, files))
        }
        publishFreshness(listId, state)

        _quickReviewRequest.value = if (state == SourceFreshness.UpToDate) {
            QuickReviewRequest.Ready(listId, folderPath)
        } else {
            QuickReviewRequest.OpenList(listId)
        }
    }

    fun dismissQuickReviewRequest() {
        _quickReviewRequest.value = null
    }

    /**
     * Step 1 of a manual update: rescan the linked folder and compare. Writes nothing.
     * Deliberately does not go anywhere near the webhook path. Its scan is authoritative, so it
     * publishes freshness too rather than leaving a stale badge next to a fresh dialog.
     */
    fun requestResync(listId: Long) = launchDb {
        _resyncRequest.value = ResyncRequest.Scanning
        pendingResync = null

        val list = dao.getList(listId)
        if (list == null || list.sourceRootUri == null || list.sourceRelativePath == null) {
            _resyncRequest.value = ResyncRequest.Error("This list is not linked to a folder.")
            return@launchDb
        }

        val files = scanLinkedFolder(list)
        if (files == null) {
            _resyncRequest.value =
                ResyncRequest.Error("Could not read the source folder. Nothing was changed.")
            publishFreshness(listId, SourceFreshness.SourceUnavailable)
            return@launchDb
        }

        val diff = dao.previewReconcile(listId, files)
        publishFreshness(listId, freshnessOf(diff))
        if (!diff.hasChanges) {
            _resyncRequest.value = ResyncRequest.UpToDate
            return@launchDb
        }

        pendingResync = PendingResync(listId, files)
        _resyncRequest.value = ResyncRequest.Confirm(diff)
    }

    /**
     * Resolve then scan the folder a list is linked to. Shared by the automatic check and the
     * manual update so there is one scanner, not two. Blocking work stays on IO. Null when the
     * grant is gone, the folder has been deleted, the path no longer resolves, or the scan fails
     * — in every case the list itself is left untouched.
     */
    private suspend fun scanLinkedFolder(list: ListEntity): List<ScannedFile>? {
        val rootUri = list.sourceRootUri ?: return null
        val relativePath = list.sourceRelativePath ?: return null
        return withContext(Dispatchers.IO) {
            resolveLinkedFolder(getApplication(), rootUri.toUri(), relativePath)
                ?.let { scanFolderFiles(getApplication(), it.uri) }
        }
    }

    /** The one place a diff becomes a freshness verdict, so both entry points agree. */
    private fun freshnessOf(diff: FolderDiff): SourceFreshness =
        if (diff.hasChanges) SourceFreshness.ChangesAvailable(diff) else SourceFreshness.UpToDate

    /**
     * Publishes a verdict reached outside the automatic check. Cancels any check still running,
     * so an older scan cannot land on top of this newer, user-initiated result.
     */
    private fun publishFreshness(listId: Long, state: SourceFreshness) {
        freshnessJob?.cancel()
        _sourceFreshness.value = ListFreshness(listId, state)
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

        // Everything the scan found has now been acknowledged, so the warning clears here rather
        // than making the user leave and re-enter the screen to be told the same thing.
        publishFreshness(pending.listId, SourceFreshness.UpToDate)

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

    fun setFolderQuickReviewEnabled(enabled: Boolean) = launchDb {
        settingsStore.setFolderQuickReviewEnabled(enabled)
    }

    fun setCustom1DisplayName(name: String) = launchDb { settingsStore.setCustom1DisplayName(name) }

    fun setCustom1WebhookValue(value: String) = launchDb {
        settingsStore.setCustom1WebhookValue(value)
    }

    fun setCustom2DisplayName(name: String) = launchDb { settingsStore.setCustom2DisplayName(name) }

    fun setCustom2WebhookValue(value: String) = launchDb {
        settingsStore.setCustom2WebhookValue(value)
    }

    fun setDefaultWebhookEnabled(enabled: Boolean) = launchDb {
        settingsStore.setDefaultWebhookEnabled(enabled)
    }

    fun setDefaultWebhookUrl(url: String) = launchDb { settingsStore.setDefaultWebhookUrl(url) }

    fun setVideoAutoplay(enabled: Boolean) = launchDb { settingsStore.setVideoAutoplay(enabled) }

    fun setVideoStartMuted(muted: Boolean) = launchDb { settingsStore.setVideoStartMuted(muted) }

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

        // Nothing is recorded for a switched-off webhook — there was no attempt — but the user is
        // still told. A list quietly completing with no delivery is exactly the case that
        // otherwise looks identical to a delivery that silently failed.
        if (requireEnabled && !list.webhookEnabled) {
            announce(list, event, null, NoticeOutcome.DISABLED, null, null)
            return
        }

        val url = list.webhookUrl.trim()
        val urlProblem = webhookUrlError(url)
        if (urlProblem != null) {
            dao.recordDelivery(listId, now(), DeliveryStatus.FAILED.name, null, urlProblem)
            announce(list, event, null, NoticeOutcome.FAILED, null, urlProblem)
            return
        }

        val items = dao.getItems(listId)
        // Resolved now, not when the chips were tapped: an item's boolean means "slot 1 is
        // selected", and what slot 1 is called on the wire is current configuration.
        val payload = buildWebhookPayload(event, list, items, settingsStore.read())
        val result = withContext(Dispatchers.IO) { postWebhook(url, payload) }
        dao.recordDelivery(listId, now(), result.status.name, result.httpCode, result.error)
        announce(
            list = list,
            event = event,
            itemCount = items.size,
            outcome = if (result.status == DeliveryStatus.SUCCESS) {
                NoticeOutcome.SENT
            } else {
                NoticeOutcome.FAILED
            },
            httpCode = result.httpCode,
            error = result.error
        )
    }

    /**
     * Raises the acknowledgeable result. Completions are driven by user actions one at a time, so
     * a pending notice is simply replaced rather than queued; the stored delivery record still
     * holds the definitive history of anything actually sent.
     */
    private fun announce(
        list: ListEntity,
        event: String,
        itemCount: Int?,
        outcome: NoticeOutcome,
        httpCode: Int?,
        error: String?
    ) {
        _webhookNotice.value = WebhookNotice(
            event = event,
            listTitle = list.title,
            host = webhookHost(list.webhookUrl.trim()),
            itemCount = itemCount,
            outcome = outcome,
            httpCode = httpCode,
            error = error,
            at = now()
        )
    }

    fun dismissWebhookNotice() {
        _webhookNotice.value = null
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
