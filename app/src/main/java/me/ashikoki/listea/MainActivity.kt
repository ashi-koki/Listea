package me.ashikoki.listea

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.ui.theme.ListeaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ListeaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ListeaApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private enum class MainTab(val label: String) { Folder("Folder"), Lists("Lists") }

/** Top level of the app: the app name, a Folder/Lists switch, and the selected screen. */
@Composable
fun ListeaApp(
    modifier: Modifier = Modifier,
    listsViewModel: ListsViewModel = viewModel()
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Folder) }
    var requestedListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        // A destination rather than a third tab: configuration is not equal in weight to the two
        // screens the app is actually for. Leaving it remounts the Folder screen, which is how a
        // newly chosen root takes effect.
        SettingsScreen(
            modifier = modifier.padding(16.dp),
            viewModel = listsViewModel,
            onBack = { showSettings = false }
        )
        WebhookNoticeDialog(listsViewModel)
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Listea",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showSettings = true }) {
                Text("⚙", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            MainTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.label) }
                )
            }
        }
        val screenModifier = Modifier.weight(1f).padding(16.dp)
        when (tab) {
            MainTab.Folder -> FolderScreen(
                modifier = screenModifier,
                onOpenList = { listId ->
                    requestedListId = listId
                    tab = MainTab.Lists
                },
                onOpenSettings = { showSettings = true }
            )

            MainTab.Lists -> ListsScreen(
                modifier = screenModifier,
                requestedListId = requestedListId,
                onRequestConsumed = { requestedListId = null }
            )
        }
    }

    WebhookNoticeDialog(listsViewModel)
}

/**
 * Reports the outcome of every webhook delivery, whichever screen triggered it: a swipe that
 * completed a list in Review, the last checkbox on the detail screen, or the Test button.
 *
 * Hosted at the top level and above the tabs on purpose. The delivery worth announcing is the one
 * the user was not watching for, and this way the result never has to be inferred from a status
 * line on a screen they would have to navigate to. Dismissing or acknowledging clears it; the
 * stored per-list delivery record keeps the detail afterwards.
 */
@Composable
private fun WebhookNoticeDialog(viewModel: ListsViewModel) {
    val notice by viewModel.webhookNotice.collectAsStateWithLifecycle()
    val current = notice ?: return

    AlertDialog(
        onDismissRequest = { viewModel.dismissWebhookNotice() },
        title = { Text(current.headline) },
        text = {
            Column {
                Text(current.listTitle, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    current.outcomeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    // A switched-off webhook is information, not a failure: it gets neither the
                    // success tint nor the alarm of a delivery that actually went wrong.
                    color = when (current.outcome) {
                        NoticeOutcome.SENT -> MaterialTheme.colorScheme.primary
                        NoticeOutcome.FAILED -> MaterialTheme.colorScheme.error
                        NoticeOutcome.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    buildList {
                        add(current.eventLabel)
                        current.itemCount?.let { add("$it items") }
                        current.host?.let { add(it) }
                        add(current.timeLabel)
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismissWebhookNotice() }) { Text("OK") }
        }
    )
}

/**
 * Keeps a browsing stack across rotation and process death, as flat uri/name pairs.
 *
 * Without this, turning the phone rebuilds the screen from scratch and drops the user back at the
 * selected root instead of the folder they were actually in.
 */
private val DirStackSaver = listSaver<List<DirRef>, String>(
    save = { stack -> stack.flatMap { listOf(it.uri.toString(), it.name) } },
    restore = { flat -> flat.chunked(2).map { DirRef(it[0].toUri(), it[1]) } }
)

/** Same idea for an open Quick Review: rotating should not throw the user out of it. */
private val QuickReviewTargetSaver = listSaver<QuickReviewTarget?, Any>(
    save = { target -> target?.let { listOf(it.listId, it.folderPath) }.orEmpty() },
    restore = { saved -> QuickReviewTarget(saved[0] as Long, saved[1] as String) }
)

/** Holds the in-flight folder read, so a newer navigation can replace it. */
private class LoadJob {
    var job: Job? = null
}

private sealed interface FolderUiState {
    data class NoFolder(val message: String?) : FolderUiState
    data object Loading : FolderUiState

    /** [stack] runs from the persisted root (first) to the directory shown (last). */
    data class Browsing(val stack: List<DirRef>, val contents: FolderContents) : FolderUiState
}

@Composable
fun FolderScreen(
    modifier: Modifier = Modifier,
    onOpenList: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    listsViewModel: ListsViewModel = viewModel()
) {
    val context = LocalContext.current
    val store = remember { FolderStore(context) }
    val folderListRequest by listsViewModel.folderListRequest.collectAsStateWithLifecycle()
    val settings by listsViewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val load = remember { LoadJob() }
    var state by remember { mutableStateOf<FolderUiState>(FolderUiState.Loading) }

    // The contents are re-read on every entry, but *where* the user was is worth keeping.
    var browsedStack by rememberSaveable(stateSaver = DirStackSaver) {
        mutableStateOf(emptyList<DirRef>())
    }

    /**
     * Shows the last directory of [stack]. If it can no longer be read (another app deleted it
     * while we were away) this walks up towards the root; if even the root is gone the saved root
     * URI is dropped and we return to "No folder selected".
     * [quiet] keeps the current list on screen instead of flashing a spinner.
     */
    suspend fun show(stack: List<DirRef>, quiet: Boolean = false) {
        val root = stack.firstOrNull()
        if (root == null) {
            state = FolderUiState.NoFolder(message = null)
            return
        }
        if (!quiet) state = FolderUiState.Loading

        var path = stack
        while (true) {
            val contents = withContext(Dispatchers.IO) {
                if (hasPersistedReadAccess(context, root.uri)) {
                    readFolder(context, path.last().uri)
                } else {
                    null
                }
            }
            if (contents != null) {
                // Keep the displayed name in sync with what the provider reports.
                val shown = path.dropLast(1) + path.last().copy(name = contents.folderName)
                state = FolderUiState.Browsing(stack = shown, contents = contents)
                browsedStack = shown
                return
            }
            if (path.size == 1) break
            path = path.dropLast(1)
        }
        store.clear()
        browsedStack = emptyList()
        state = FolderUiState.NoFolder("That folder is no longer accessible. Please choose it again.")
    }

    /** Single entry point for every load, so a newer one always replaces an older one. */
    fun open(stack: List<DirRef>, quiet: Boolean = false) {
        load.job?.cancel()
        load.job = scope.launch { show(stack, quiet) }
    }

    /**
     * Where to open on entry: the folder the user was last in, or the root.
     *
     * A remembered path is only trusted while it still starts at the current root. Choosing a
     * different folder in Settings leaves the old path pointing outside the new grant, so that
     * case starts over at the root instead.
     */
    fun startingStack(): List<DirRef> {
        val root = store.read() ?: return emptyList()
        val remembered = browsedStack
        return if (remembered.firstOrNull()?.uri == root) remembered else listOf(DirRef(root, ""))
    }

    LaunchedEffect(Unit) { open(startingStack()) }

    // Quick Review lives inside this screen rather than replacing it, so the browsing stack — a
    // plain remember — survives and exiting lands back on the same folder.
    var quickReview by rememberSaveable(stateSaver = QuickReviewTargetSaver) {
        mutableStateOf<QuickReviewTarget?>(null)
    }
    // The open file is remembered by URI rather than by entry: the entry objects come from a
    // directory read that has to happen again after a rotation anyway.
    var viewingFileUri by rememberSaveable { mutableStateOf<String?>(null) }
    val quickReviewRequest by listsViewModel.quickReviewRequest.collectAsStateWithLifecycle()

    // Another app (FolderSync, a file manager, ...) may change the folder while we are backgrounded,
    // so re-read the directory on screen whenever we come back to the foreground. A load started by
    // the folder picker (which also triggers a resume) already has the newer target, so leave it be.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val current = state
        if (current is FolderUiState.Browsing && load.job?.isActive != true) {
            open(current.stack, quiet = true)
        }
    }

    val browsing = state as? FolderUiState.Browsing
    val herePath = browsing?.let { relativePathOf(it.stack) }

    // Where the source check sends the user. Entering is refused for a folder the user has since
    // navigated away from, so a slow scan cannot drop them into a queue they did not ask for.
    LaunchedEffect(quickReviewRequest, herePath) {
        when (val request = quickReviewRequest) {
            is QuickReviewRequest.Ready -> {
                if (request.folderPath == herePath) {
                    quickReview = QuickReviewTarget(request.listId, request.folderPath)
                }
                listsViewModel.dismissQuickReviewRequest()
            }

            // Stale or unreadable source: the owning list's page explains it and offers the
            // update. No reconcile dialog is opened on the user's behalf.
            is QuickReviewRequest.OpenList -> {
                onOpenList(request.listId)
                listsViewModel.dismissQuickReviewRequest()
            }

            else -> Unit
        }
    }

    // Quick Review and the file viewer own Back while either is up.
    BackHandler(
        enabled = quickReview == null && viewingFileUri == null &&
            browsing != null && browsing.stack.size > 1
    ) {
        browsing?.let { open(it.stack.dropLast(1)) }
    }

    val reviewing = quickReview
    if (reviewing != null) {
        QuickReviewScreen(
            modifier = modifier,
            viewModel = listsViewModel,
            target = reviewing,
            onExit = { quickReview = null }
        )
        return
    }

    // Like Quick Review, hosted inside this screen so closing returns to the same folder.
    // Resolved against the current listing, so a rotation reopens the same file once the folder
    // has been read again.
    val folderFiles = browsing?.contents?.entries.orEmpty().filterNot { it.isDirectory }
    val openFile = viewingFileUri?.let { uri -> folderFiles.firstOrNull { it.uri.toString() == uri } }
    if (openFile != null) {
        FileViewerScreen(
            modifier = modifier,
            // Every file of the folder it was opened from, in the order shown, so paging matches
            // the browser. Directories are not files to page through.
            files = folderFiles,
            initial = openFile,
            detailOf = { fileDetail(it) },
            settings = settings,
            onClose = { viewingFileUri = null }
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            is FolderUiState.Loading -> CircularProgressIndicator()

            // Nothing is chosen for the user and no picker opens by itself: the first thing a
            // new install sees is an explanation of where to start.
            is FolderUiState.NoFolder -> {
                Text("No folder selected", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Select a folder in Settings to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                current.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }

            is FolderUiState.Browsing -> {
                val rootUri = current.stack.first().uri.toString()
                val ownershipFlow = remember(rootUri) {
                    listsViewModel.observeFolderOwnership(rootUri)
                }
                val ownership by ownershipFlow
                    .collectAsStateWithLifecycle(initialValue = FolderOwnership())
                val here = relativePathOf(current.stack)
                val quickReviewEnabled = settings.folderQuickReviewEnabled

                // Ownership for the current folder and every visible directory, derived once per
                // data change rather than per card recomposition, from one indexed lookup each.
                val statuses = remember(ownership, here, current.contents.entries) {
                    buildMap {
                        put(here, folderListStatus(ownership, here))
                        current.contents.entries.forEach { entry ->
                            if (entry.isDirectory) {
                                val path = childRelativePath(here, entry.name)
                                put(path, folderListStatus(ownership, path))
                            }
                        }
                    }
                }
                fun statusOf(path: String) = statuses[path] ?: FolderListStatus.None

                fun requestList(path: String, folderUri: Uri, folderName: String) {
                    listsViewModel.requestListForFolder(
                        rootUri = rootUri,
                        relativePath = path,
                        folderUri = folderUri,
                        folderName = folderName
                    )
                }

                Breadcrumb(
                    stack = current.stack,
                    onNavigate = { depth -> open(current.stack.take(depth)) }
                )
                // Display only: nothing in here is tappable. Every action lives in the row below.
                CurrentFolderInfo(
                    entries = current.contents.entries,
                    status = statusOf(here)
                )
                Spacer(Modifier.height(8.dp))
                CurrentFolderActions(
                    status = statusOf(here),
                    quickReviewEnabled = quickReviewEnabled,
                    checkingSource = quickReviewRequest is QuickReviewRequest.Checking,
                    onRefresh = { open(current.stack) },
                    onOpenList = onOpenList,
                    onCreateList = {
                        requestList(here, current.stack.last().uri, current.stack.last().name)
                    },
                    onQuickReview = { listId ->
                        listsViewModel.requestQuickReview(listId, here)
                    }
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                if (current.contents.entries.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("This folder is empty", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(current.contents.entries, key = { it.uri }) { entry ->
                            if (entry.isDirectory) {
                                val path = childRelativePath(here, entry.name)
                                FolderCard(
                                    entry = entry,
                                    status = statusOf(path),
                                    onNavigate = {
                                        open(current.stack + DirRef(entry.uri, entry.name))
                                    }
                                )
                            } else {
                                // A file's checked state comes from whichever list owns the
                                // folder it sits in, direct or inherited — and is hidden
                                // entirely when Folder review integration is switched off, which
                                // changes what is shown and nothing that is stored.
                                val owner = owningScope(statusOf(here))
                                    .takeIf { quickReviewEnabled }
                                FileRow(
                                    entry = entry,
                                    isChecked = owner?.let {
                                        ownership.itemCompletion[
                                            it.id to childRelativePath(here, entry.name)
                                        ]
                                    },
                                    onOpen = { viewingFileUri = entry.uri.toString() }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    when (val request = folderListRequest) {
        null -> Unit

        is FolderListRequest.Scanning -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Scanning folder") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("Looking for files in this folder and everything below it.")
                }
            },
            confirmButton = {}
        )

        is FolderListRequest.Confirm -> AlertDialog(
            onDismissRequest = { listsViewModel.dismissFolderListRequest() },
            // One action, so the wording has to say which of its outcomes is about to happen:
            // rebuilding this folder's own list, or taking it over from elsewhere.
            title = {
                Text(
                    if (request.replacesExact) "Replace existing List?" else "Replace other Lists?"
                )
            },
            text = {
                val count = request.replacedTitles.size
                Column {
                    Text(
                        "Creating a list for " + request.folderName +
                            " (" + request.fileCount + " files) will replace " +
                            countLabel(count, "existing list") + ":"
                    )
                    Spacer(Modifier.height(8.dp))
                    request.replacedTitles.forEach { title -> Text("- $title") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Their items, checked state, actions and webhook settings are replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { listsViewModel.confirmListForFolder() }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { listsViewModel.dismissFolderListRequest() }) { Text("Cancel") }
            }
        )

        is FolderListRequest.Error -> AlertDialog(
            onDismissRequest = { listsViewModel.dismissFolderListRequest() },
            title = { Text("Could not create list") },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = { listsViewModel.dismissFolderListRequest() }) { Text("OK") }
            }
        )
    }
}

/** Compact status for the folder currently open, alongside its entry count. */
/**
 * The path as a row of tappable ancestors. This is how you go up: every segment above the one you
 * are in navigates straight to it, reusing the same browsing stack the rest of the screen uses,
 * so nothing can drift out of the selected SAF root. The current folder is the end of the line
 * and is not a link.
 */
@Composable
private fun Breadcrumb(stack: List<DirRef>, onNavigate: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stack.forEachIndexed { index, dir ->
            val isCurrent = index == stack.lastIndex
            if (index > 0) {
                Text(
                    " / ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                dir.name.ifEmpty { "Root" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = if (isCurrent) {
                    Modifier
                } else {
                    Modifier.clickable { onNavigate(index + 1) }
                }
            )
        }
    }
}

/**
 * Everything the current folder *is*, and nothing it can *do*.
 *
 * Strictly display: no element here is clickable, so a status can never be mistaken for a button.
 * Counts are the folder's direct children only — the listing already in memory — and never a
 * recursive walk.
 */
@Composable
private fun CurrentFolderInfo(entries: List<FolderEntry>, status: FolderListStatus) {
    Column(Modifier.fillMaxWidth()) {
        Text(folderCountsLabel(entries), style = MaterialTheme.typography.bodyMedium)
        Text(
            folderTypeOf(status).label,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(status)
        )
        folderProgressLabel(status)?.let { progress ->
            val owner = folderOwnerTitle(status)
            Text(
                if (owner != null) "$progress · $owner" else progress,
                style = MaterialTheme.typography.bodySmall,
                color = statusDetailColor(status),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Only a folder that owns its list outright has a webhook to report.
        statusWebhookLabel(status)?.let { webhook ->
            Text(
                webhook,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Everything the current folder can *do*, gathered in one place.
 *
 * Refresh re-reads the browser and nothing else. Quick Review and Open List need an owning list.
 * Create List is offered for every folder under one name: what it does about an existing list —
 * this folder's own, an ancestor's, or ones below it — is decided by the overlap rules, and the
 * user confirms before anything is replaced.
 */
@Composable
private fun CurrentFolderActions(
    status: FolderListStatus,
    quickReviewEnabled: Boolean,
    checkingSource: Boolean,
    onRefresh: () -> Unit,
    onOpenList: (Long) -> Unit,
    onCreateList: () -> Unit,
    onQuickReview: (Long) -> Unit
) {
    val owner = owningScope(status)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onRefresh) { Text("Refresh") }

        if (owner != null && quickReviewAvailable(status, quickReviewEnabled)) {
            if (checkingSource) {
                Text(
                    "Checking source…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TextButton(onClick = { onQuickReview(owner.id) }) { Text("Quick Review") }
            }
        }
        owner?.let { scope ->
            TextButton(onClick = { onOpenList(scope.id) }) { Text("Open List") }
        }
        TextButton(onClick = onCreateList) { Text("Create List") }
    }
}

/**
 * A directory row: what it is, and a tap that goes into it. No list action of its own — those
 * belong to the folder you are actually standing in.
 */
@Composable
private fun FolderCard(
    entry: FolderEntry,
    status: FolderListStatus,
    onNavigate: () -> Unit
) {
    OutlinedCard(
        onClick = onNavigate,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // A directory's size and modified time say nothing dependable about its contents, so
            // neither is shown or reasoned about.
            Text(
                folderTypeOf(status).label,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(status)
            )
            folderProgressLabel(status)?.let { progress ->
                Text(
                    progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusDetailColor(status),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            statusWebhookLabel(status)?.let { webhook ->
                Text(
                    webhook,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * A file row. Tapping it only ever opens the file for viewing: no checking, no actions, no
 * workflow. The checked state is shown when the owning list tracks the file, but it is reporting,
 * not a control. [isChecked] is null when no list covers this folder, or when the file is not part
 * of the owning list's snapshot.
 */
@Composable
private fun FileRow(entry: FolderEntry, isChecked: Boolean?, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp)
    ) {
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            fileDetail(entry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isChecked != null) {
            Text(
                if (isChecked) "Checked ✓" else "Not checked",
                style = MaterialTheme.typography.bodySmall,
                color = if (isChecked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * A folder's own contents, one level deep: "3 folders · 5 files · 4.2 GB".
 *
 * Derived from the listing already on screen, so it costs nothing. The size covers the direct
 * files only and is left out when none of them report one — nothing here walks subfolders.
 */
private fun folderCountsLabel(entries: List<FolderEntry>): String {
    val folders = entries.count { it.isDirectory }
    val files = entries.size - folders
    val bytes = entries.filterNot { it.isDirectory }.sumOf { it.sizeBytes ?: 0L }
    return buildList {
        add(countLabel(folders, "folder"))
        add(countLabel(files, "file"))
        if (bytes > 0) add(formatSize(bytes))
    }.joinToString(" · ")
}

private fun countLabel(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"

/** "JPG · 4.2 MB · 2026-07-11 14:22". Type first, because it is what identifies the file. */
private fun fileDetail(entry: FolderEntry): String = buildList {
    fileTypeLabel(entry.name)?.let { add(it) }
    entry.sizeBytes?.let { add(formatSize(it)) }
    entry.lastModified?.let { add(formatTimestamp(it)) }
}.joinToString(" · ")

/** The extension, upper-cased. Null for a file that has none to show. */
private fun fileTypeLabel(name: String): String? =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()

@Composable
private fun statusColor(status: FolderListStatus) = when {
    status is FolderListStatus.Direct && status.scope.isComplete -> MaterialTheme.colorScheme.primary
    status is FolderListStatus.None -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

/** A finished subtree gets the same tint a finished direct list gets. */
@Composable
private fun statusDetailColor(status: FolderListStatus) =
    if (status is FolderListStatus.Inherited && status.subtreeIsComplete) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

