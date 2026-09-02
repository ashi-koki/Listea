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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.DecisionTarget
import me.ashikoki.listea.data.FileReviewStateEntity
import me.ashikoki.listea.data.FolderDiff
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListDetail
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.ListSummary
import me.ashikoki.listea.data.ListeaDatabase
import me.ashikoki.listea.data.ReviewItem
import me.ashikoki.listea.data.ScannedFile
import me.ashikoki.listea.data.decisions
import me.ashikoki.listea.data.rootRelativePathOf
import me.ashikoki.listea.data.toReviewItem
import me.ashikoki.listea.data.UnsentWebhookEntity
import me.ashikoki.listea.data.UnsentWebhookSummary

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
     * Nothing authoritative is known, because the automatic check is switched off and nothing has
     * asked for one. Deliberately its own state rather than an absent one: silence would be read
     * as "fine", and the whole point of the setting is that Listea has not looked.
     */
    data object NotChecked : SourceFreshness

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
 * What a folder-scoped Quick Review is working on.
 *
 * Quick Review used to be gated on a List: it reviewed the owning List's rows, so it had to check
 * that List was not stale before it could show anything, and a folder with no List had nothing to
 * review at all. It now resolves the folder itself, which removes both problems at once — the
 * folder *is* the source, so there is no snapshot to be stale, and no List needs to exist.
 */
sealed interface FolderReview {
    /** The folder is being read and its files resolved. No decision is visible yet. */
    data object Resolving : FolderReview

    /** The folder could not be read: revoked grant, deleted folder, unmounted storage. */
    data object Unavailable : FolderReview

    /**
     * The folder's direct files, each carrying whatever has already been decided about it and
     * what it measures. Everything the folder holds: which of them a round actually queues is the
     * screen's decision, not this one's.
     */
    data class Ready(val items: List<ReviewItem>) : FolderReview
}

/**
 * What changing the root folder would cost, held until the user has seen it.
 *
 * [titles] is every list that would be deleted: the ones created on the root and the ones created
 * on folders below it. Empty means the change is free.
 */
data class RootChangePreview(val rootUri: String, val titles: List<String>)

/**
 * Where a "delete checked files" run has got to.
 *
 * The only thing in Listea that destroys something outside its own database, so the whole run is
 * a state machine the user can see: what was found, what it would cost, and what actually
 * happened. Nothing touches a file until [Confirm] has been answered, and the outcome is reported
 * rather than assumed — a partial delete is a real result here, not an error.
 */
sealed interface DeleteCheckedRequest {
    data object Scanning : DeleteCheckedRequest

    /** Nothing is checked, or nothing checked still has a file. Its own state, not an error. */
    data object NothingChecked : DeleteCheckedRequest

    /**
     * The folder can be read but not changed, so the run pauses to ask for that.
     *
     * Not an error, because nothing has gone wrong and there is something the user can do about
     * it on the spot: Android hands write access over through the folder picker and nowhere else,
     * so the screen asks, opens the picker on the folder already in use, and resumes here.
     */
    data object NeedsWriteAccess : DeleteCheckedRequest

    /** What would go, grouped by folder for reading. [fileCount] counts files, not folders. */
    data class Confirm(
        val groups: List<CheckedFileGroup>,
        val fileCount: Int
    ) : DeleteCheckedRequest

    data object Deleting : DeleteCheckedRequest

    /** [failed] is files the provider would not remove; they are still on the device. */
    data class Done(val deleted: Int, val failed: Int) : DeleteCheckedRequest

    data class Error(val message: String) : DeleteCheckedRequest
}

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

    /**
     * The remembered filters and sort orders. Observed like [settings] and written the same way.
     *
     * Read through [resolveArrangement] rather than directly: which of these a folder is actually
     * showing under depends on [AppSettings.sharedFileArrangement], and that decision belongs in
     * one place.
     */
    val arrangements: StateFlow<FolderArrangements> = settingsStore.arrangements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderArrangements())

    val summaries: StateFlow<List<ListSummary>> = dao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folderListRequest = MutableStateFlow<FolderListRequest?>(null)
    val folderListRequest: StateFlow<FolderListRequest?> = _folderListRequest.asStateFlow()

    private val _resyncRequest = MutableStateFlow<ResyncRequest?>(null)
    val resyncRequest: StateFlow<ResyncRequest?> = _resyncRequest.asStateFlow()

    /**
     * Delivery results waiting to be acknowledged, oldest first. Held here rather than on a screen
     * because a completion webhook can fire from anywhere, and the user should not have to be
     * looking at the right list to find out what happened.
     *
     * A queue rather than a single slot: one swipe can finish a review *and* complete the list it
     * belongs to, which is two deliveries against two different webhook configurations. They used
     * to overwrite each other, so whichever finished second was the only one the user ever saw.
     */
    private val _webhookNotices = MutableStateFlow<List<WebhookNotice>>(emptyList())

    /** The result the user is being shown, or null when there is nothing left to acknowledge. */
    val webhookNotice: StateFlow<WebhookNotice?> = _webhookNotices
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The payloads that never got through, newest first. Observed rather than fetched so the
     * history page and the Settings entry that leads to it can never disagree about how many
     * there are.
     */
    val unsentWebhooks: StateFlow<List<UnsentWebhookSummary>> = dao.observeUnsentWebhooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many are waiting, without loading a single payload to count them. */
    val unsentWebhookCount: StateFlow<Int> = dao.observeUnsentWebhookCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _sourceFreshness = MutableStateFlow<ListFreshness?>(null)
    val sourceFreshness: StateFlow<ListFreshness?> = _sourceFreshness.asStateFlow()

    /** The in-flight automatic check, kept so a newer one can replace it rather than race it. */
    private var freshnessJob: Job? = null

    private val _rootChangePreview = MutableStateFlow<RootChangePreview?>(null)
    val rootChangePreview: StateFlow<RootChangePreview?> = _rootChangePreview.asStateFlow()

    private val _deleteCheckedRequest = MutableStateFlow<DeleteCheckedRequest?>(null)
    val deleteCheckedRequest: StateFlow<DeleteCheckedRequest?> = _deleteCheckedRequest.asStateFlow()

    /**
     * Orders item writes against the payload reads that follow them.
     *
     * A review's last swipe checks an item and then asks for a delivery, as two separate calls
     * from the same handler. Both reach Room on its own executor, so without this the payload
     * could be built from rows the swipe had not landed on yet — and with "send only checked
     * items" on, the very item just swiped would be the one missing. The lock is fair, and it is
     * held only across the database work: the network post happens outside it, so a slow endpoint
     * never blocks the next tap.
     */
    private val writeOrder = Mutex()

    /**
     * Lists with a review round open on screen right now.
     *
     * A round is reported once. Swiping the last item of a List review both completes the List and
     * ends the round, and `list.completed` and `review.exited` go to the *same* webhook for a List
     * review — so without this the receiver gets the same round twice, seconds apart, which is
     * what [leaveReview] has always claimed does not happen.
     *
     * The round's own report is the one that survives, because it is the narrower and more honest
     * of the two: with *Review unchecked items only* and *Send checked items only* both on, it
     * carries exactly the decisions of the round just done, while the completion would carry the
     * whole List — including items checked weeks ago that have already been sent.
     *
     * Read and written only inside [writeOrder], which is what makes it correct rather than merely
     * likely: the swipe's completion write and the exit that closes the round are two separate
     * coroutines, and only the lock guarantees the swipe sees the round still open. Membership is
     * added at the start of each round, so a completion reached anywhere else — the detail page's
     * checkbox, a folder's Quick Review — is never mistaken for a round's work.
     */
    private val openReviewRounds = mutableSetOf<Long>()

    /** Scan result waiting for confirmation. Nothing is written until it is committed. */
    private var pendingFolderList: PendingFolderList? = null
    private var pendingResync: PendingResync? = null

    /** The exact files the confirmation dialog is describing, held until it is answered. */
    private var pendingDeletion: List<String> = emptyList()

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
            dao.observeFolderScopeItems(rootUri),
            dao.observeFileCompletion(rootUri)
        ) { scopes, items, files ->
            buildFolderOwnership(scopes, items, files)
        }.flowOn(Dispatchers.Default)

    /**
     * One list, with its rows resolved into review items.
     *
     * The list is what supplies each row's root: a decision's identity is the file under a grant,
     * and only the list knows which grant its items are under. Manual rows resolve to their own
     * state, which is still theirs.
     */
    fun observeDetail(listId: Long): Flow<ListDetail?> =
        combine(dao.observeList(listId), dao.observeItems(listId)) { list, items ->
            list?.let { owner ->
                ListDetail(owner, items.map { it.toReviewItem(owner.sourceRootUri) })
            }
        }

    /**
     * A folder's own files, resolved for review without a List anywhere in sight.
     *
     * This is the decoupling in one function. The folder is read, its direct files are registered
     * so each has an identity to carry decisions on, and from then on the queue simply follows the
     * decisions held against those identities. No list is consulted, created or replaced, and any
     * List that happens to cover this folder is left completely alone — it will nonetheless show
     * every decision made here, because both are reading the same file state.
     *
     * Subfolders are excluded, exactly as before: Quick Review has always been one folder's own
     * files, and a folder of folders is a place to navigate rather than a queue to review.
     */
    fun observeFolderReview(rootUri: String, folderPath: String): Flow<FolderReview> = flow {
        // Deliberately does not emit [FolderReview.Resolving]: the screen supplies that as its
        // initial value, and emitting it here would also fire on every *re*-collection. Coming
        // back from the background would then drop the queue composable, taking the round's
        // "what was unchecked on entry" snapshot with it and silently re-narrowing the queue to
        // exclude everything just reviewed. Staying on the last resolved value until the next one
        // arrives is what makes a paused review resume as the same round.
        val files = withContext(Dispatchers.IO) { resolveFolderFiles(rootUri, folderPath) }
        if (files == null) {
            emit(FolderReview.Unavailable)
            return@flow
        }
        if (files.isEmpty()) {
            emit(FolderReview.Ready(emptyList()))
            return@flow
        }

        val paths = files.map { it.rootRelativePath }
        dao.registerFiles(
            paths.map {
                FileReviewStateEntity(rootUri = rootUri, relativePath = it, updatedAt = now())
            }
        )
        // Reading them is proof they are there, which un-flags anything deleted and put back.
        dao.markFilesPresent(rootUri, paths)
        emitAll(
            dao.observeFileStates(rootUri, paths).map { states ->
                FolderReview.Ready(folderReviewQueue(rootUri, files, states))
            }
        )
    }

    /**
     * Reads a browsed folder's direct files. Blocking: call on IO. Null when it cannot be read.
     */
    private fun resolveFolderFiles(rootUri: String, folderPath: String): List<FolderFile>? {
        val folder = resolveLinkedFolder(getApplication(), rootUri.toUri(), folderPath)
            ?: return null
        val contents = readFolder(getApplication(), folder.uri) ?: return null
        return folderFilesOf(folderPath, contents)
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

    /** How many checked files a delete would take, for the card that offers it. */
    fun observeCheckedFileCount(rootUri: String): Flow<Int> =
        dao.observeCheckedFileCount(rootUri)

    /**
     * Step 1 of deleting checked files: gather them and describe them. Reads only — nothing is
     * written and no file is touched until the confirmation comes back.
     *
     * The write grant is checked here rather than at the point of deletion, because finding out
     * halfway through a hundred files that Listea was never allowed to delete any of them is the
     * worst possible moment to say so.
     */
    fun requestCheckedFileDeletion(rootUri: String?) = launchDb {
        _deleteCheckedRequest.value = DeleteCheckedRequest.Scanning
        pendingDeletion = emptyList()

        if (rootUri == null) {
            _deleteCheckedRequest.value = DeleteCheckedRequest.Error("No folder is selected.")
            return@launchDb
        }

        val writable = withContext(Dispatchers.IO) {
            hasPersistedWriteAccess(getApplication(), rootUri.toUri())
        }
        if (!writable) {
            _deleteCheckedRequest.value = DeleteCheckedRequest.NeedsWriteAccess
            return@launchDb
        }

        val paths = runCatching { dao.getCheckedFilePaths(rootUri) }.getOrNull()
        if (paths == null) {
            _deleteCheckedRequest.value =
                DeleteCheckedRequest.Error("Could not read the checked files. Nothing was deleted.")
            return@launchDb
        }
        if (paths.isEmpty()) {
            _deleteCheckedRequest.value = DeleteCheckedRequest.NothingChecked
            return@launchDb
        }

        pendingDeletion = paths
        _deleteCheckedRequest.value = DeleteCheckedRequest.Confirm(
            groups = withContext(Dispatchers.Default) { groupCheckedFiles(paths) },
            fileCount = paths.size
        )
    }

    /**
     * Step 2: the permanent part. Deletes exactly the files the dialog listed — re-reading them
     * here could pick up a file checked while the dialog was open, which the user never saw and
     * never agreed to.
     *
     * Only the files that genuinely went are recorded as gone, and recorded as *missing* rather
     * than erased: the decision about a file outlives the file, so a List whose files were deleted
     * stays complete and says its sources are missing, exactly as it would after a re-sync. The
     * existing "remove missing items" cleanup is there for anyone who wants the rows gone too.
     */
    fun confirmDeleteCheckedFiles(rootUri: String?) = launchDb {
        val paths = pendingDeletion
        if (paths.isEmpty() || rootUri == null) return@launchDb
        pendingDeletion = emptyList()
        _deleteCheckedRequest.value = DeleteCheckedRequest.Deleting

        val result = withContext(Dispatchers.IO) {
            deleteCheckedFiles(getApplication(), rootUri.toUri(), paths)
        }
        val recorded = runCatching {
            writeOrder.withLock { dao.markFilesDeleted(rootUri, result.deletedPaths) }
        }

        _deleteCheckedRequest.value = if (recorded.isFailure) {
            // The files are gone either way; what failed is the bookkeeping, and saying so is
            // better than a success message in front of items that still claim to have a file.
            DeleteCheckedRequest.Error(
                "The files were deleted, but Listea could not record that. Use \"Update from " +
                    "folder\" on the Lists they came from."
            )
        } else {
            DeleteCheckedRequest.Done(
                deleted = result.deletedPaths.size,
                failed = result.failedCount
            )
        }
    }

    fun dismissDeleteCheckedRequest() {
        pendingDeletion = emptyList()
        _deleteCheckedRequest.value = null
    }

    fun addItem(listId: Long, title: String) = edit(title) { clean ->
        onCompletionTransition(listId, dao.addItem(listId, clean, now()))
    }

    /**
     * Checks or unchecks one item, wherever the user is looking at it from.
     *
     * The routing is the whole of the new model: a file's decision goes to the file and reaches
     * every List holding it, a manual entry's stays on its row. Either way the write takes the
     * ordering lock and the deliveries it may trigger run outside it.
     */
    fun setItemCompleted(item: ReviewItem, completed: Boolean) = launchDb {
        when (val target = item.target) {
            is DecisionTarget.File -> {
                // The transition and whether a round is open are read under one lock. The exit
                // that closes the round is a separate coroutine racing this one, and only the
                // lock guarantees it cannot close it between the write and the decision.
                val (transitioned, inRound) = writeOrder.withLock {
                    val ids =
                        dao.setFileCompleted(target.rootUri, target.relativePath, completed, now())
                    ids to ids.filterTo(mutableSetOf()) { it in openReviewRounds }
                }
                transitioned.forEach { onCompletionTransition(it, true, it in inRound) }
            }

            is DecisionTarget.ManualItem -> {
                val (transitioned, inRound) = writeOrder.withLock {
                    val done =
                        dao.setManualItemCompleted(target.listId, target.itemId, completed, now())
                    done to (target.listId in openReviewRounds)
                }
                onCompletionTransition(target.listId, transitioned, inRound)
            }
        }
    }

    /**
     * Toggles one review action. Persisted immediately and nothing else: completion is untouched,
     * so this never delivers a webhook. The value simply travels with the item into the next
     * list.completed payload.
     */
    fun setItemAction(item: ReviewItem, action: ItemAction, enabled: Boolean) = launchDb {
        writeOrder.withLock {
            when (val target = item.target) {
                is DecisionTarget.File ->
                    dao.setFileAction(target.rootUri, target.relativePath, action, enabled, now())

                is DecisionTarget.ManualItem ->
                    dao.setManualItemAction(target.itemId, action, enabled)
            }
        }
    }

    /**
     * Removes an item from a list. Membership only: the file's decisions are not a member of
     * anything and are left exactly as they are, so putting the file back later — here or in
     * another list — brings them back with it.
     */
    fun deleteItem(listId: Long, itemId: Long) = launchDb {
        onCompletionTransition(listId, dao.removeItem(listId, itemId, now()))
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
     * The automatic check, run when a list's detail page becomes the active screen. It resolves
     * the folder, scans it, and compares through the same [ListsDao.previewReconcile] the manual
     * update uses. Nothing about membership or review is written: nothing is inserted, flagged,
     * restored, deleted or completed, and no webhook can result.
     *
     * The one thing it does keep from the scan is what each file measured — see
     * [ListsDao.refreshSourceMetrics]. That is not a decision about the list, it is the answer to
     * "how big is this and when was it written", and caching it here is what lets the Items header
     * sort and filter on it without a provider round trip per row. It is also the backfill: a list
     * created before those columns existed measures itself the first time its page is opened.
     *
     * Manual lists have no source to compare against, so they are dropped before any scan starts.
     * A previous check is cancelled rather than left to race: whichever check started last wins.
     *
     * Skipped entirely when the user has switched the automatic check off. The setting is read
     * from the store rather than from the observed [settings] flow, because this runs the instant
     * a page opens and a flow that has not warmed up yet would answer with the default and scan
     * something the user asked it not to. A verdict already held for *this* list survives the
     * skip: it came from a scan that really happened, and discarding it would throw away an
     * update the user had just asked for.
     */
    fun checkSourceFreshness(listId: Long) {
        freshnessJob?.cancel()
        freshnessJob = viewModelScope.launch {
            val list = dao.getList(listId)
            if (list == null || list.sourceRootUri == null || list.sourceRelativePath == null) {
                _sourceFreshness.value = null
                return@launch
            }

            if (!settingsStore.read().autoCheckSourceFreshness) {
                if (_sourceFreshness.value?.listId != listId) {
                    _sourceFreshness.value = ListFreshness(listId, SourceFreshness.NotChecked)
                }
                return@launch
            }

            _sourceFreshness.value = ListFreshness(listId, SourceFreshness.Checking)
            val files = scanLinkedFolder(list)
            val state = if (files == null) {
                SourceFreshness.SourceUnavailable
            } else {
                dao.refreshSourceMetrics(listId, files)
                freshnessOf(dao.previewReconcile(listId, files))
            }
            _sourceFreshness.value = ListFreshness(listId, state)
        }
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

        // Whether or not there is anything to reconcile, the scan measured every file it found
        // and those numbers are worth keeping — an "up to date" verdict is still a fresh look.
        dao.refreshSourceMetrics(listId, files)

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

    fun setRememberReviewPosition(remember: Boolean) = launchDb {
        settingsStore.setRememberReviewPosition(remember)
    }

    fun setAutoCheckSourceFreshness(enabled: Boolean) = launchDb {
        settingsStore.setAutoCheckSourceFreshness(enabled)
    }

    fun setQuickReviewWebhookEnabled(enabled: Boolean) = launchDb {
        settingsStore.setQuickReviewWebhookEnabled(enabled)
    }

    fun setWebhookCompletedItemsOnly(enabled: Boolean) = launchDb {
        settingsStore.setWebhookCompletedItemsOnly(enabled)
    }

    fun setReviewUncheckedOnly(enabled: Boolean) = launchDb {
        settingsStore.setReviewUncheckedOnly(enabled)
    }

    fun setWebhookOnReviewExit(enabled: Boolean) = launchDb {
        settingsStore.setWebhookOnReviewExit(enabled)
    }

    fun setSharedFileArrangement(shared: Boolean) = launchDb {
        settingsStore.setSharedFileArrangement(shared)
    }

    /**
     * Records a listing's new filter and sort, wherever the switch says they belong.
     *
     * The routing is decided here, from an authoritative read rather than from whatever the UI
     * last observed, so a change made in the same breath as flipping the switch cannot be filed
     * against the mode that was on a moment ago. The caller passes the key of whatever it was
     * looking at — a folder under a root, or a List — and does not have to know which mode is
     * active or that the other kind of caller exists.
     */
    fun setArrangement(key: String, arrangement: FileArrangement) = launchDb {
        if (settingsStore.read().sharedFileArrangement) {
            settingsStore.setGlobalArrangement(arrangement)
        } else {
            settingsStore.setFolderArrangement(key, arrangement)
        }
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
     * A Quick Review reached the end of its queue.
     *
     * Sends only when [AppSettings.quickReviewWebhookEnabled] is on, and then through the app's
     * default webhook whole: Quick Review has no configuration of its own, by design, so the
     * default URL is where it posts and the default switch is what enables it. Nothing is sent
     * — and nothing is said — when Quick Review webhooks are off, because that is the state
     * Quick Review has always been in. The payload covers [queueIds], the folder that was
     * reviewed, not the whole list.
     *
     * The settings are read from the store rather than from the observed [settings] flow: this
     * fires on a swipe, and a delivery must never act on a value that has not warmed up yet.
     */
    fun onQuickReviewFinished(
        rootUri: String,
        folderPath: String,
        items: List<ReviewItem>
    ) = launchDb {
        if (!settingsStore.read().quickReviewWebhookEnabled) return@launchDb
        deliverFolderReview(rootUri, folderPath, items, EVENT_QUICK_REVIEW_COMPLETED)
    }

    /**
     * A folder review's round, as a payload.
     *
     * The decisions are re-read under the ordering lock rather than taken from the screen, for the
     * same reason a list's are: the swipe that ended the round is a separate write, and a payload
     * built without waiting for it would be missing the very item that triggered it.
     *
     * The `list` a receiver sees is the folder — it has an id of 0, because no list is involved,
     * and it is named after the folder that was reviewed. That keeps one payload shape on the
     * wire for every event rather than inventing a second one for folder rounds.
     */
    private suspend fun deliverFolderReview(
        rootUri: String,
        folderPath: String,
        items: List<ReviewItem>,
        event: String
    ) {
        val paths = items.mapNotNull { (it.target as? DecisionTarget.File)?.relativePath }
        if (paths.isEmpty()) return

        val fresh = writeOrder.withLock { dao.getFileStates(rootUri, paths) }
            .associateBy { it.relativePath }

        val rows = items.mapIndexedNotNull { index, item ->
            val path = (item.target as? DecisionTarget.File)?.relativePath
                ?: return@mapIndexedNotNull null
            item.copy(decisions = fresh[path]?.decisions ?: item.decisions)
                .toPayloadRow(index)
        }

        deliverPayload(
            list = folderPayloadList(rootUri, folderPath),
            rows = rows,
            event = event,
            requireEnabled = true,
            useDefaultWebhook = true,
            recordAgainstList = false
        )
    }

    /**
     * The stand-in "list" a folder round reports as. Never stored: built here, serialised, gone.
     * Carrying the folder as its source path is what makes the per-item `relativePath` on the wire
     * come out identical to the one a List over the same folder would have produced.
     */
    private fun folderPayloadList(rootUri: String, folderPath: String) = ListEntity(
        id = 0,
        title = folderPath.substringAfterLast('/').ifEmpty { "Selected folder" },
        createdAt = now(),
        sourceRootUri = rootUri,
        sourceRelativePath = folderPath
    )

    /**
     * A review item as a row, purely so the one payload builder can serialise it. Never inserted:
     * a folder-resolved file is not a member of any list and must not become one by accident.
     */
    private fun ReviewItem.toPayloadRow(sortOrder: Int) = ListItemEntity(
        id = id,
        listId = 0,
        title = title,
        isCompleted = decisions.isCompleted,
        sortOrder = sortOrder,
        createdAt = 0,
        sourceUri = sourceUri,
        sourceRelativePath = relativePath,
        rootRelativePath = (target as? DecisionTarget.File)?.relativePath,
        sourceMissing = sourceMissing,
        isFavorite = decisions.isFavorite,
        custom1 = decisions.custom1,
        custom2 = decisions.custom2
    )


    /**
     * A List review round is starting: entering the screen, or "Review again" from the end of one.
     *
     * From here until the round ends, a completion this List reaches is the round's to report.
     */
    fun onReviewStarted(listId: Long) = launchDb {
        writeOrder.withLock { openReviewRounds += listId }
    }

    /**
     * A List review round has ended — its queue finished on screen, or the user left it.
     *
     * Sends only when [AppSettings.webhookOnReviewExit] is on, through the list's own webhook, so
     * a list with its webhook switched off says so exactly as a completion would. This is the one
     * report the round produces, whether or not it completed the List along the way.
     */
    fun onReviewExited(listId: Long, queueIds: List<Long>) = launchDb {
        writeOrder.withLock { openReviewRounds -= listId }
        if (!settingsStore.read().webhookOnReviewExit) return@launchDb
        deliver(
            listId = listId,
            event = EVENT_REVIEW_EXITED,
            requireEnabled = true,
            queueIds = queueIds,
            useDefaultWebhook = false
        )
    }

    /**
     * The same, for a folder review, which has no list and so delivers through the default
     * webhook. Gated on [AppSettings.quickReviewWebhookEnabled] as well, since that switch is
     * what lets a folder round send anything at all.
     */
    fun onFolderReviewExited(
        rootUri: String,
        folderPath: String,
        items: List<ReviewItem>
    ) = launchDb {
        val current = settingsStore.read()
        if (!current.webhookOnReviewExit || !current.quickReviewWebhookEnabled) return@launchDb
        deliverFolderReview(rootUri, folderPath, items, EVENT_REVIEW_EXITED)
    }

    /**
     * The single place the completion action fires. [transitioned] comes from the DAO transaction
     * that performed the edit, so a list that merely stays complete never delivers again.
     */
    private suspend fun onCompletionTransition(
        listId: Long,
        transitioned: Boolean,
        insideReviewRound: Boolean = false
    ) {
        if (!transitioned) return

        // The round that completed this List is about to report it, to this same webhook, narrowed
        // to what the round actually covered — so this would be the same moment described twice.
        // The round's payload is the one worth having: the settings that narrow it are the user
        // saying they want the decisions they just made, not every decision the List has ever
        // accumulated.
        //
        // Gated on the exit report actually being switched on. With it off there is no second
        // payload to prefer, and staying silent here would lose the completion altogether — it has
        // always fired independently of any of the review switches, and still does.
        if (insideReviewRound && settingsStore.read().webhookOnReviewExit) return

        deliver(listId, EVENT_LIST_COMPLETED, requireEnabled = true)
    }

    /**
     * The one delivery path, whatever asked for it.
     *
     * [queueIds] narrows the payload to the items a review actually covered, in stored order;
     * null means the whole list, which is what a completion and the test button send.
     * [useDefaultWebhook] posts to the app's default URL instead of the list's own — Quick
     * Review, which has no webhook configuration of its own — and in that case the caller has
     * already checked the switch that stands in for the list's enabled toggle, so it passes
     * `requireEnabled = false`.
     *
     * Every attempt that reaches a verdict is recorded against the list and announced, including
     * one abandoned over an unusable URL. That an empty default URL fails loudly here is the
     * point: it is the case the user most needs telling about.
     */
    private suspend fun deliver(
        listId: Long,
        event: String,
        requireEnabled: Boolean,
        queueIds: List<Long>? = null,
        useDefaultWebhook: Boolean = false
    ) {
        // The whole payload is read in one pass under the ordering lock, so an item write from
        // the swipe that triggered this has certainly landed and the list and its items cannot
        // disagree about what just happened.
        val stored = writeOrder.withLock {
            dao.getList(listId)?.let { it to scopedItems(listId, queueIds) }
        } ?: return
        deliverPayload(
            list = stored.first,
            rows = stored.second,
            event = event,
            requireEnabled = requireEnabled,
            useDefaultWebhook = useDefaultWebhook,
            recordAgainstList = true
        )
    }

    /**
     * Posts one payload, whatever assembled it.
     *
     * Split out from [deliver] when Quick Review stopped belonging to a List: a folder review has
     * a real round to report and a real body to keep, but no list row to record a delivery
     * against, so [recordAgainstList] is the one thing that differs. Everything the user sees —
     * the notice, the unsent history, the empty-payload and switched-off cases — is deliberately
     * identical, because from the user's side it is the same webhook doing the same job.
     */
    private suspend fun deliverPayload(
        list: ListEntity,
        rows: List<ListItemEntity>,
        event: String,
        requireEnabled: Boolean,
        useDefaultWebhook: Boolean,
        recordAgainstList: Boolean
    ) {
        val current = settingsStore.read()
        val listId = list.id
        // Action wire values resolved now, not when the chips were tapped: an item's boolean
        // means "slot 1 is selected", and what slot 1 is called is current configuration.
        val items = webhookItems(rows, current)

        // Built before the branches below rather than just before the POST: a delivery that
        // never leaves is exactly the one whose body has to be kept.
        val payload = buildWebhookPayload(event, list, items, current)

        // Whichever webhook this is, both halves of its configuration come from the same place:
        // the app default is switched on by the app's own setting, a list's by the list's own.
        val enabled = if (useDefaultWebhook) current.defaultWebhookEnabled else list.webhookEnabled
        val url = (if (useDefaultWebhook) current.defaultWebhookUrl else list.webhookUrl).trim()

        fun tell(outcome: NoticeOutcome, itemCount: Int?, httpCode: Int?, error: String?) =
            announce(list.title, event, url, useDefaultWebhook, itemCount, outcome, httpCode, error)

        // Anything that did not come back successful is kept, in the same words the user was
        // just given, so the history says exactly what the dialog said. Nothing is kept for a
        // payload with nothing in it: there would be nothing to resend.
        suspend fun keep(notice: WebhookNotice) {
            if (items.isEmpty()) return
            dao.insertUnsentWebhook(
                UnsentWebhookEntity(
                    event = event,
                    listTitle = list.title,
                    itemCount = items.size,
                    payload = payload,
                    failedAt = now(),
                    reason = notice.outcomeLabel
                )
            )
        }

        // No delivery is recorded against the list for a switched-off webhook — there was no
        // attempt — but the user is still told, and the body is still kept. A list quietly
        // completing with no delivery is exactly the case that otherwise looks identical to a
        // delivery that silently failed, and the round inside it is worth just as much.
        if (requireEnabled && !enabled) {
            keep(tell(NoticeOutcome.DISABLED, null, null, null))
            return
        }

        val urlProblem = webhookUrlError(url)
        if (urlProblem != null) {
            if (recordAgainstList) {
                dao.recordDelivery(listId, now(), DeliveryStatus.FAILED.name, null, urlProblem)
            }
            keep(tell(NoticeOutcome.FAILED, null, null, urlProblem))
            return
        }

        // A usable webhook with nothing to put in it: a review left without a single swipe, or a
        // payload narrowed to checked items when none are. An empty items array would tell a
        // receiver a round happened when none did, so this is reported rather than posted, and
        // nothing is recorded, because nothing was attempted.
        if (items.isEmpty()) {
            tell(NoticeOutcome.EMPTY, 0, null, null)
            return
        }

        val result = withContext(Dispatchers.IO) { postWebhook(url, payload) }
        if (recordAgainstList) {
            dao.recordDelivery(listId, now(), result.status.name, result.httpCode, result.error)
        }
        val succeeded = result.status == DeliveryStatus.SUCCESS
        val notice = tell(
            outcome = if (succeeded) NoticeOutcome.SENT else NoticeOutcome.FAILED,
            itemCount = items.size,
            httpCode = result.httpCode,
            error = result.error
        )
        if (!succeeded) keep(notice)
    }

    /**
     * Sends a kept payload again, to the default URL as it stands right now.
     *
     * Only the URL is required: a record exists precisely because something was misconfigured,
     * and refusing to retry it because *Default enabled* is still off would be refusing to fix
     * the thing the user came here to fix. The body is replayed byte for byte rather than
     * rebuilt, so what arrives is what failed, whatever has happened to the list since.
     *
     * A record is deleted only once a receiver has actually accepted it. Anything else leaves it
     * exactly where it was and says why.
     */
    fun resendUnsentWebhook(id: Long) = launchDb {
        val record = dao.getUnsentWebhook(id) ?: return@launchDb
        val url = settingsStore.read().defaultWebhookUrl.trim()

        fun tell(outcome: NoticeOutcome, httpCode: Int?, error: String?) = announce(
            listTitle = record.listTitle,
            event = record.event,
            url = url,
            defaultWebhook = true,
            itemCount = record.itemCount,
            outcome = outcome,
            httpCode = httpCode,
            error = error
        )

        val urlProblem = webhookUrlError(url)
        if (urlProblem != null) {
            tell(NoticeOutcome.FAILED, null, urlProblem)
            return@launchDb
        }

        val result = withContext(Dispatchers.IO) { postWebhook(url, record.payload) }
        val succeeded = result.status == DeliveryStatus.SUCCESS
        if (succeeded) dao.deleteUnsentWebhook(id)
        tell(
            if (succeeded) NoticeOutcome.SENT else NoticeOutcome.FAILED,
            result.httpCode,
            result.error
        )
    }

    /** Drops a kept payload for good. The only thing in the app that can lose one. */
    fun deleteUnsentWebhook(id: Long) = launchDb { dao.deleteUnsentWebhook(id) }

    /** The body itself, read only when the user opens one rather than with every history row. */
    suspend fun unsentWebhookPayload(id: Long): String? = dao.getUnsentWebhook(id)?.payload

    /**
     * The list's items, or just the ones a review queued. Read fresh from the database rather
     * than taken from the screen, so the payload reflects what is stored; ids that have since
     * been deleted simply drop out, and stored order is kept either way.
     */
    private suspend fun scopedItems(listId: Long, queueIds: List<Long>?): List<ListItemEntity> {
        val items = dao.getItems(listId)
        if (queueIds == null) return items
        val queued = queueIds.toSet()
        return items.filter { it.id in queued }
    }

    /**
     * Queues an acknowledgeable result behind anything the user has not dealt with yet.
     *
     * Every delivery that was actually attempted gets its own notice: two of them for one list in
     * one moment means two things really happened, and collapsing them would hide one. Results
     * where *nothing* was attempted collapse per list instead, because a list's webhook and the
     * default webhook both being switched off is one thing to say, not two.
     *
     * Returns what it built, collapsed or not, so a caller that also has to file the result away
     * describes it in exactly the words the user was given.
     */
    private fun announce(
        listTitle: String,
        event: String,
        url: String,
        defaultWebhook: Boolean,
        itemCount: Int?,
        outcome: NoticeOutcome,
        httpCode: Int?,
        error: String?
    ): WebhookNotice {
        val notice = WebhookNotice(
            event = event,
            listTitle = listTitle,
            // The URL that was actually used, which for Quick Review is the app default rather
            // than anything on the list.
            host = webhookHost(url),
            itemCount = itemCount,
            outcome = outcome,
            httpCode = httpCode,
            error = error,
            defaultWebhook = defaultWebhook,
            at = now()
        )
        _webhookNotices.update { queued ->
            if (notice.addsTo(queued)) queued + notice else queued
        }
        return notice
    }

    /** Acknowledges the notice on screen, which brings up the next one if there is one. */
    fun dismissWebhookNotice() {
        _webhookNotices.update { it.drop(1) }
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
