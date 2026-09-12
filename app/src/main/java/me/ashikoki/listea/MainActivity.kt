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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.ui.theme.ListeaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The scaffold lives in ListeaApp, which needs to know whether a nested screen is open
            // before it can decide what chrome to show.
            ListeaTheme { ListeaApp() }
        }
    }
}

/**
 * The app shell: three top-level destinations, and the nested screens reached from them.
 *
 * The nested flags live here rather than inside the screens that render them, because the shell
 * is what has to know when to take its chrome away. The *rendering* stays where it was: the file
 * viewer needs the folder listing FolderScreen holds, and Quick Review returns to the exact
 * browsing stack it was launched from, so both are still drawn by FolderScreen and both keep
 * working across rotation.
 */
@Composable
fun ListeaApp(
    modifier: Modifier = Modifier,
    listsViewModel: ListsViewModel = viewModel()
) {
    var destination by rememberSaveable { mutableStateOf(TopLevelDestination.Folder) }

    // Nested destinations, hoisted purely so the shell can tell whether one is open.
    var openListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var quickReview by rememberSaveable(stateSaver = QuickReviewTargetSaver) {
        mutableStateOf<QuickReviewTarget?>(null)
    }
    var viewingFileUri by rememberSaveable { mutableStateOf<String?>(null) }
    var webhookHistoryOpen by rememberSaveable { mutableStateOf(false) }

    // Which folder the browser is standing in, owned here rather than by FolderScreen itself.
    // FolderScreen is composed only while Folder is the selected destination, so anything it
    // remembers depends on the destination switch handing that state back; ListeaApp is never
    // taken out of the composition, so state held here cannot be dropped by a trip through
    // Lists or Settings, however that switch is implemented.
    var browsedStack by rememberSaveable(stateSaver = DirStackSaver) {
        mutableStateOf(emptyList<DirRef>())
    }

    val nested = openListId != null || reviewListId != null ||
        quickReview != null || viewingFileUri != null || webhookHistoryOpen

    // Android convention for a bottom bar: back from a secondary destination returns to the first
    // one rather than leaving the app. Disabled while nested, so the nested screens keep their own.
    BackHandler(enabled = !nested && destination != TopLevelDestination.Folder) {
        destination = TopLevelDestination.Folder
    }

    // Each destination keeps its own saved state while the other two are off screen. The `when`
    // below only ever composes one of them, so without a holder the two that are not showing are
    // forgotten along with everything they remembered: switching to Lists and back landed on the
    // selected root instead of the folder being browsed, and a trip to Settings reset the Lists
    // filter to All. The holder hands each screen its state back when it returns.
    val destinationState = rememberSaveableStateHolder()

    ListeaTopLevelScaffold(
        destination = destination,
        onSelectDestination = { destination = it },
        showChrome = !nested,
        modifier = modifier
    ) { contentModifier ->
        destinationState.SaveableStateProvider(destination) {
            when (destination) {
                TopLevelDestination.Folder -> FolderScreen(
                    modifier = contentModifier,
                    quickReview = quickReview,
                    onQuickReviewChange = { quickReview = it },
                    viewingFileUri = viewingFileUri,
                    onViewFileChange = { viewingFileUri = it },
                    onOpenList = { listId ->
                        openListId = listId
                        destination = TopLevelDestination.Lists
                    },
                    onOpenSettings = { destination = TopLevelDestination.Settings },
                    browsedStack = browsedStack,
                    onBrowsedStackChange = { browsedStack = it },
                    listsViewModel = listsViewModel
                )

                TopLevelDestination.Lists -> ListsScreen(
                    modifier = contentModifier,
                    viewModel = listsViewModel,
                    openListId = openListId,
                    onOpenListChange = { openListId = it },
                    reviewListId = reviewListId,
                    onReviewListChange = { reviewListId = it }
                )

                TopLevelDestination.Settings -> SettingsScreen(
                    modifier = contentModifier,
                    viewModel = listsViewModel,
                    historyOpen = webhookHistoryOpen,
                    onHistoryOpenChange = { webhookHistoryOpen = it }
                )
            }
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
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    current.outcomeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    // A switched-off webhook, or one with nothing to carry, is information
                    // rather than failure: neither gets the success tint, nor the alarm of a
                    // delivery that actually went wrong.
                    color = when (current.outcome) {
                        NoticeOutcome.SENT -> MaterialTheme.colorScheme.primary
                        NoticeOutcome.FAILED -> MaterialTheme.colorScheme.error
                        NoticeOutcome.DISABLED,
                        NoticeOutcome.EMPTY -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.height(ListeaDimens.RowGap))
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
    save = { target -> target?.let { listOf(it.rootUri, it.folderPath) }.orEmpty() },
    restore = { saved -> QuickReviewTarget(saved[0] as String, saved[1] as String) }
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
    quickReview: QuickReviewTarget? = null,
    onQuickReviewChange: (QuickReviewTarget?) -> Unit = {},
    viewingFileUri: String? = null,
    onViewFileChange: (String?) -> Unit = {},
    onOpenList: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    browsedStack: List<DirRef> = emptyList(),
    onBrowsedStackChange: (List<DirRef>) -> Unit = {},
    listsViewModel: ListsViewModel = viewModel()
) {
    val context = LocalContext.current
    val store = remember { FolderStore(context) }
    val folderListRequest by listsViewModel.folderListRequest.collectAsStateWithLifecycle()
    val settings by listsViewModel.settings.collectAsStateWithLifecycle()
    val arrangements by listsViewModel.arrangements.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val load = remember { LoadJob() }
    var state by remember { mutableStateOf<FolderUiState>(FolderUiState.Loading) }

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
                // The contents are re-read on every entry, but *where* the user was is worth
                // keeping, so the position goes back to the shell that outlives this screen.
                onBrowsedStackChange(shown)
                return
            }
            if (path.size == 1) break
            path = path.dropLast(1)
        }
        store.clear()
        onBrowsedStackChange(emptyList())
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

    // Quick Review and the file viewer are still drawn here, so the browsing stack survives and
    // exiting lands back on the same folder. Only their on/off state is owned by the shell, which
    // needs it to hide the bottom navigation.

    // Another app (FolderSync, a file manager, ...) may change the folder while we are backgrounded,
    // so re-read the directory on screen whenever we come back to the foreground. A load started by
    // the folder picker (which also triggers a resume) already has the newer target, so leave it be.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val current = state
        if (current is FolderUiState.Browsing && load.job?.isActive != true) {
            open(current.stack, quiet = true)
        }
    }

    // Quick Review edits the same files this listing describes, and can leave them checked,
    // deleted or moved. Coming back re-reads the folder so the rows — and anything the filter is
    // deciding from them — describe what is there now rather than what was there on the way in.
    // Checked state alone would have kept itself current through its own database flow; existence
    // would not have.
    var wasQuickReviewing by remember { mutableStateOf(false) }
    LaunchedEffect(quickReview) {
        val returned = wasQuickReviewing && quickReview == null
        wasQuickReviewing = quickReview != null
        if (returned) (state as? FolderUiState.Browsing)?.let { open(it.stack, quiet = true) }
    }

    val browsing = state as? FolderUiState.Browsing
    val herePath = browsing?.let { relativePathOf(it.stack) }

    // ---------------------------------------------------------------------------------------
    // What the Files section is showing, derived once for the whole screen.
    //
    // Hoisted above the nested screens rather than left in the Browsing branch below, because
    // the file viewer is one of those nested screens and pages exactly this subset: tapping the
    // third of eight filtered pictures has to swipe through those eight and not through the
    // forty files the folder happens to hold. The branch below reuses these rather than deriving
    // a second, quietly different answer to the same question.
    //
    // All of it tolerates there being no folder open, because this runs before the state is
    // narrowed: with no folder there are no files, and nothing below it is ever consulted.
    // ---------------------------------------------------------------------------------------
    val rootUri = browsing?.stack?.first()?.uri?.toString()
    val ownershipFlow = remember(rootUri) {
        rootUri?.let { listsViewModel.observeFolderOwnership(it) } ?: flowOf(FolderOwnership())
    }
    val ownership by ownershipFlow.collectAsStateWithLifecycle(initialValue = FolderOwnership())
    val here = herePath.orEmpty()
    val quickReviewEnabled = settings.folderQuickReviewEnabled

    // Split once per listing rather than per row, so the two sections below can be headed
    // separately without testing isDirectory on every item.
    val entries = browsing?.contents?.entries.orEmpty()
    val directories = remember(entries) { entries.filter { it.isDirectory } }
    val files = remember(entries) { entries.filterNot { it.isDirectory } }

    // A file's checked state comes from whichever list owns the folder it sits in, direct or
    // inherited. Resolved once here rather than per row, because the filter and the sort need it
    // as much as the tick does.
    val owner = owningScope(folderListStatus(ownership, here))

    /**
     * What a row's tick shows, or null for no tick at all.
     *
     * The file's own state first, so a file reviewed in a folder no List covers still shows its
     * tick. Falling back to list membership keeps a member that has never been reviewed showing
     * an empty tick rather than none. Null when nothing anywhere holds an opinion, because an
     * empty tick on every file of an uncovered folder would claim a review relationship that
     * does not exist.
     */
    fun shownCheckState(entry: FolderEntry): Boolean? {
        if (!quickReviewEnabled) return null
        val path = childRelativePath(here, entry.name)
        return ownership.fileCompletion[path]
            ?: owner?.let { ownership.itemCompletion[it.id to path] }
    }

    /**
     * What the filter and the sort read, which is not quite the same question.
     *
     * "Nothing recorded" is not an opinion worth drawing a tick for, but it is a perfectly good
     * answer to "is this checked?" — no. Collapsing it to false here rather than in
     * [shownCheckState] is what lets Unchecked mean every file still to be looked at, rather than
     * only the ones some list already knows about.
     */
    fun sortableCheckState(entry: FolderEntry): Boolean? =
        if (!quickReviewEnabled) null else shownCheckState(entry) ?: false

    // With no root the key names nothing, which costs nothing: there are no files to arrange, and
    // the shared mode does not consult it at all.
    val arrangement = resolveArrangement(
        arrangements,
        folderArrangementKey(rootUri.orEmpty(), here),
        settings.sharedFileArrangement
    )

    // Re-derived whenever the listing, the arrangement or any checked state changes, which is
    // exactly what makes this survive a refresh: a file deleted outside the app leaves the
    // listing and a file checked in Quick Review changes group, and both arrive here as a new
    // input rather than as something to invalidate by hand.
    val shownFiles = remember(files, arrangement, ownership, here, quickReviewEnabled, owner) {
        arrangeFiles(files, arrangement, System.currentTimeMillis()) { entry ->
            FileFacts(
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
                isChecked = sortableCheckState(entry)
            )
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
            onExit = { onQuickReviewChange(null) }
        )
        return
    }

    // Like Quick Review, hosted inside this screen so closing returns to the same folder.
    // Resolved against the current listing, so a rotation reopens the same file once the folder
    // has been read again.
    val openFile = viewingFileUri?.let { uri -> shownFiles.firstOrNull { it.uri.toString() == uri } }

    // A file can be deleted by another app between opening it and the folder being re-read. Give
    // the viewer up rather than leave the shell believing a nested screen is still open, which
    // would strip the bottom navigation off a folder page that has no way back.
    LaunchedEffect(viewingFileUri, shownFiles) {
        if (viewingFileUri != null && browsing != null && openFile == null) {
            onViewFileChange(null)
        }
    }

    if (openFile != null) {
        FileViewerScreen(
            modifier = modifier,
            // Exactly what the browser is showing, in the order it shows it, so a swipe reaches
            // the next row on the page. A filter left on means eight pictures to page through,
            // not forty files with thirty-two of them hidden on the page behind. Directories are
            // not files to page through either way.
            files = shownFiles,
            initial = openFile,
            // Where these files sit, for the Info sheet. The viewer shows one folder's direct
            // files, so this is the same path for every card in it.
            folderPath = herePath.orEmpty(),
            settings = settings,
            onClose = { onViewFileChange(null) }
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            is FolderUiState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            // Nothing is chosen for the user and no picker opens by itself: the first thing a
            // new install sees is an explanation of where to start.
            is FolderUiState.NoFolder -> Column(
                Modifier.padding(ListeaDimens.PagePadding)
            ) {
                Text("No folder selected", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                Text(
                    "Select a folder in Settings to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                current.message?.let {
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(ListeaDimens.SectionGap))
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }

            is FolderUiState.Browsing -> {
                // Non-null here, unlike the hoisted `rootUri` above, which has to survive the
                // states in which no folder is open at all. Everything else this branch needs —
                // the ownership, the arrangement, the files and the subset shown — is hoisted,
                // because the file viewer above pages that same subset.
                val root = current.stack.first().uri.toString()

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
                        rootUri = root,
                        relativePath = path,
                        folderUri = folderUri,
                        folderName = folderName
                    )
                }

                val folderKey = folderArrangementKey(root, here)
                var sortOpen by remember { mutableStateOf(false) }
                var filterOpen by remember { mutableStateOf(false) }

                // One scroll surface for the page. The breadcrumb, the folder summary and the
                // action row scroll away with everything else rather than pinning most of the
                // screen open above a small list viewport — which is what left almost nothing
                // usable in landscape.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = ListeaDimens.PagePadding,
                        vertical = ListeaDimens.RowGap
                    ),
                    verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                ) {
                    item {
                        Breadcrumb(
                            stack = current.stack,
                            onNavigate = { depth -> open(current.stack.take(depth)) }
                        )
                    }
                    item {
                        CurrentFolderCard(
                            entries = current.contents.entries,
                            status = statusOf(here),
                            quickReviewEnabled = quickReviewEnabled,
                            onRefresh = { open(current.stack) },
                            onOpenList = onOpenList,
                            onCreateList = {
                                requestList(here, current.stack.last().uri, current.stack.last().name)
                            },
                            // Straight in. No list is consulted, so there is nothing to
                            // check and nothing to be stale: the folder is the source.
                            onQuickReview = {
                                onQuickReviewChange(QuickReviewTarget(root, here))
                            }
                        )
                    }

                    if (current.contents.entries.isEmpty()) {
                        item {
                            Spacer(Modifier.height(ListeaDimens.SectionGap))
                            Text(
                                "This folder is empty",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (directories.isNotEmpty()) {
                        item {
                            SectionHeader(
                                "Directories",
                                Modifier.padding(top = ListeaDimens.RowGap)
                            )
                        }
                        items(directories, key = { it.uri }) { entry ->
                            val path = childRelativePath(here, entry.name)
                            FolderCard(
                                entry = entry,
                                status = statusOf(path),
                                onNavigate = {
                                    open(current.stack + DirRef(entry.uri, entry.name))
                                }
                            )
                        }
                    }

                    if (files.isNotEmpty()) {
                        item {
                            // The header keeps the folder's whole file count behind it: the
                            // controls that decide what is shown must not themselves disappear
                            // when the filter they set happens to match nothing.
                            ArrangeableSectionHeader(
                                title = "Files",
                                arrangement = arrangement,
                                onSort = { sortOpen = true },
                                onFilter = { filterOpen = true },
                                modifier = Modifier.padding(top = ListeaDimens.RowGap)
                            )
                            if (!arrangement.isFilterDefault) {
                                FilterSummary(shown = shownFiles.size, total = files.size)
                            }
                        }
                        items(shownFiles, key = { it.uri }) { entry ->
                            FileRow(
                                entry = entry,
                                // Hidden entirely while Folder review integration is off, which
                                // changes what is shown and nothing that is stored.
                                isChecked = shownCheckState(entry),
                                onOpen = { onViewFileChange(entry.uri.toString()) }
                            )
                        }
                    }
                }

                fun applyArrangement(updated: FileArrangement) {
                    listsViewModel.setArrangement(folderKey, updated)
                    sortOpen = false
                    filterOpen = false
                }

                if (sortOpen) {
                    FileSortDialog(
                        arrangement = arrangement,
                        // Sorting by something the page is not showing would be an order with no
                        // visible reason for being what it is.
                        showCheckedSort = quickReviewEnabled,
                        onDismiss = { sortOpen = false },
                        onApply = ::applyArrangement
                    )
                }
                if (filterOpen) {
                    FileFilterDialog(
                        arrangement = arrangement,
                        showCheckedGroup = quickReviewEnabled,
                        onDismiss = { filterOpen = false },
                        onApply = ::applyArrangement
                    )
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
                    Spacer(Modifier.width(ListeaDimens.SectionGap))
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
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    request.replacedTitles.forEach { title -> Text("- $title") }
                    Spacer(Modifier.height(ListeaDimens.RowGap))
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
 * What this folder is on the left, and what can be done with it on the right.
 *
 * The information is strictly display: nothing in it is tappable, so a status can never be
 * mistaken for a button. The counts are the folder's direct children only — the listing already
 * in memory — and never a recursive walk.
 *
 * Four short status lines do not need a phone's full width, so the actions sit beside them rather
 * than under them, and drop underneath only when the width genuinely cannot hold both. Either way
 * every action valid here is rendered: V4.0 put the same four in one unbreakable Row and a narrow
 * portrait screen cut the last of them off the right edge.
 */
@Composable
private fun CurrentFolderCard(
    entries: List<FolderEntry>,
    status: FolderListStatus,
    quickReviewEnabled: Boolean,
    onRefresh: () -> Unit,
    onOpenList: (Long) -> Unit,
    onCreateList: () -> Unit,
    onQuickReview: () -> Unit
) {
    val owner = owningScope(status)

    SectionCard {
        InfoActionsRow(
            info = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                ) {
                    FolderTypeLabel(folderTypeOf(status))
                    folderProgressLabel(status)?.let { progress ->
                        StatusLine(
                            progress,
                            tone = progressTone(status),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Which list is involved, so a Sublist says whose subtree it is part of.
                folderOwnerTitle(status)?.let { StatusLine(it) }
                // Only a folder that owns its list outright has a webhook to report.
                statusWebhookLabel(status)?.let { StatusLine(it) }
                Text(
                    folderCountsLabel(entries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            actions = {
                FolderAction("Refresh", Icons.Filled.Refresh, onClick = onRefresh)
                // Offered on every folder now, subject only to the Folder integration setting.
                // It reviews this folder's own files, so whether any List covers them — and
                // whether that List is up to date — has stopped being a precondition.
                if (quickReviewAvailable(quickReviewEnabled)) {
                    FolderAction(
                        label = "Quick Review",
                        icon = Icons.Filled.Visibility,
                        onClick = onQuickReview
                    )
                }
                owner?.let { scope ->
                    FolderAction(
                        "Open List",
                        Icons.AutoMirrored.Filled.List,
                        onClick = { onOpenList(scope.id) }
                    )
                }
                // Always offered for a readable folder. What it does about an existing list —
                // this folder's own, an ancestor's, or ones below it — is decided by the overlap
                // rules, and the user confirms before anything is replaced.
                FolderAction("Create List", Icons.Filled.Add, onClick = onCreateList)
            }
        )
    }
}

/**
 * One folder action, sized to its label so the column's icons line up down the left.
 *
 * Borderless on purpose: four bordered buttons stacked in a narrow column read as a wall, and the
 * card is information first.
 */
@Composable
private fun FolderAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ListeaDimens.IconSize))
        Spacer(Modifier.width(ListeaDimens.RowGap))
        Text(label, maxLines = 1)
    }
}

/**
 * A directory card: what it is, and a tap that goes into it. No list action of its own — those
 * belong to the folder you are actually standing in.
 *
 * Says exactly as much as the current-folder card says about the same folder, from the same
 * [FolderType] and the same helpers, so a folder can never read as one thing here and another
 * once you are inside it.
 */
@Composable
private fun FolderCard(
    entry: FolderEntry,
    status: FolderListStatus,
    onNavigate: () -> Unit
) {
    OutlinedCard(
        onClick = onNavigate,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ListeaDimens.CardPadding,
                    vertical = ListeaDimens.RowGap
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // A directory's size and modified time say nothing dependable about its
                // contents, so neither is shown or reasoned about.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                ) {
                    FolderTypeLabel(folderTypeOf(status))
                    folderProgressLabel(status)?.let { progress ->
                        StatusLine(
                            progress,
                            tone = progressTone(status),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Null for a Sublist by construction: the webhook belongs to the ancestor list,
                // and repeating it on every folder underneath would suggest each can deliver
                // something of its own.
                statusWebhookLabel(status)?.let { StatusLine(it) }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A file row. Tapping it only ever opens the file for viewing: no checking, no actions, no
 * workflow. The checked state is shown when the owning list tracks the file, but it is reporting,
 * not a control. [isChecked] is null when nothing is known about the file — never reviewed and
 * in no list — or when Folder review integration is switched off.
 */
@Composable
private fun FileRow(entry: FolderEntry, isChecked: Boolean?, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = ListeaDimens.RowGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                fileDetail(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        isChecked?.let { checked ->
            Icon(
                if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (checked) "Checked" else "Not checked",
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(ListeaDimens.IconSize)
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

/** "JPG · 4.2 MB · 2026-07-11 14:22". Type first, because it is what identifies the file. */
private fun fileDetail(entry: FolderEntry): String = buildList {
    fileTypeLabel(entry.name)?.let { add(it) }
    entry.sizeBytes?.let { add(formatSize(it)) }
    entry.lastModified?.let { add(formatTimestamp(it)) }
}.joinToString(" · ")

/** The extension, upper-cased. Null for a file that has none to show. */
private fun fileTypeLabel(name: String): String? =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()

/**
 * Whether a folder's progress reads as finished.
 *
 * A finished subtree gets the same tint a finished direct list gets, and 0 / 0 is never
 * "complete" — that rule lives on [FolderListStatus.Inherited] and is simply read here.
 */
private fun progressTone(status: FolderListStatus): StatusTone = when (status) {
    is FolderListStatus.None -> StatusTone.Neutral
    is FolderListStatus.Direct ->
        if (status.scope.isComplete) StatusTone.Positive else StatusTone.Neutral
    is FolderListStatus.Inherited ->
        if (status.subtreeIsComplete) StatusTone.Positive else StatusTone.Neutral
}
