package me.ashikoki.listea

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun ListeaApp(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Folder) }
    var requestedListId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(modifier.fillMaxSize()) {
        Text(
            text = "Listea",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
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
                }
            )

            MainTab.Lists -> ListsScreen(
                modifier = screenModifier,
                requestedListId = requestedListId,
                onRequestConsumed = { requestedListId = null }
            )
        }
    }
}

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
    listsViewModel: ListsViewModel = viewModel()
) {
    val context = LocalContext.current
    val store = remember { FolderStore(context) }
    val folderListRequest by listsViewModel.folderListRequest.collectAsStateWithLifecycle()
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
                state = FolderUiState.Browsing(
                    stack = path.dropLast(1) + path.last().copy(name = contents.folderName),
                    contents = contents
                )
                return
            }
            if (path.size == 1) break
            path = path.dropLast(1)
        }
        store.clear()
        state = FolderUiState.NoFolder("That folder is no longer accessible. Please choose it again.")
    }

    /** Single entry point for every load, so a newer one always replaces an older one. */
    fun open(stack: List<DirRef>, quiet: Boolean = false) {
        load.job?.cancel()
        load.job = scope.launch { show(stack, quiet) }
    }

    /** The saved root as a one-element stack; its name is filled in by [show]. */
    fun savedRootStack(): List<DirRef> =
        store.read()?.let { listOf(DirRef(it, "")) } ?: emptyList()

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (granted) {
            store.save(uri)
            open(listOf(DirRef(uri, "")))
        } else {
            state = FolderUiState.NoFolder("Could not keep access to that folder. Please try again.")
        }
    }

    LaunchedEffect(Unit) { open(savedRootStack()) }

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
    BackHandler(enabled = browsing != null && browsing.stack.size > 1) {
        browsing?.let { open(it.stack.dropLast(1)) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            is FolderUiState.Loading -> CircularProgressIndicator()

            is FolderUiState.NoFolder -> {
                Text("No folder selected", style = MaterialTheme.typography.bodyLarge)
                current.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
            }

            is FolderUiState.Browsing -> {
                val rootUri = current.stack.first().uri.toString()
                val ownershipFlow = remember(rootUri) {
                    listsViewModel.observeFolderOwnership(rootUri)
                }
                val ownership by ownershipFlow
                    .collectAsStateWithLifecycle(initialValue = FolderOwnership())
                val here = relativePathOf(current.stack)

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

                Text(
                    current.stack.joinToString(" / ") { it.name },
                    style = MaterialTheme.typography.titleMedium
                )
                CurrentFolderStatus(
                    entryCount = current.contents.entries.size,
                    status = statusOf(here),
                    onOpenList = onOpenList,
                    onCreateList = {
                        requestList(here, current.stack.last().uri, current.stack.last().name)
                    }
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { open(current.stack) }) { Text("Refresh") }
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Change folder") }
                    if (current.stack.size > 1) {
                        TextButton(onClick = { open(current.stack.dropLast(1)) }) {
                            Text("Up")
                        }
                    }
                }
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
                                    },
                                    onOpenList = onOpenList,
                                    onCreateList = { requestList(path, entry.uri, entry.name) }
                                )
                            } else {
                                // A file's checked state comes from whichever list owns the
                                // folder it sits in, direct or inherited.
                                val owner = owningScope(statusOf(here))
                                FileRow(
                                    entry = entry,
                                    isChecked = owner?.let {
                                        ownership.itemCompletion[
                                            it.id to childRelativePath(here, entry.name)
                                        ]
                                    }
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
            title = { Text("Replace existing folder lists?") },
            text = {
                val count = request.replacedTitles.size
                Column {
                    Text(
                        "Creating a list for " + request.folderName +
                            " (" + request.fileCount + " files) will replace " + count +
                            " existing folder " + (if (count == 1) "list" else "lists") + ":"
                    )
                    Spacer(Modifier.height(8.dp))
                    request.replacedTitles.forEach { title -> Text("- $title") }
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
@Composable
private fun CurrentFolderStatus(
    entryCount: Int,
    status: FolderListStatus,
    onOpenList: (Long) -> Unit,
    onCreateList: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$entryCount entries", style = MaterialTheme.typography.bodyMedium)
            Text(
                statusHeadline(status),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(status)
            )
            statusDetail(status)?.let { detail ->
                Text(
                    detail,
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
        StatusAction(status = status, onOpenList = onOpenList, onCreateList = onCreateList)
    }
}

/**
 * A directory with its List ownership. Tapping the card navigates into the folder; the List
 * action stays a separate button, as in V3.1.
 */
@Composable
private fun FolderCard(
    entry: FolderEntry,
    status: FolderListStatus,
    onNavigate: () -> Unit,
    onOpenList: (Long) -> Unit,
    onCreateList: () -> Unit
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
            Text(
                entryDetail(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        statusHeadline(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor(status)
                    )
                    statusDetail(status)?.let { detail ->
                        Text(
                            detail,
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
                StatusAction(status = status, onOpenList = onOpenList, onCreateList = onCreateList)
            }
        }
    }
}

/**
 * A file row. Still has no action of its own, but shows its review state when the owning list
 * tracks it. [isChecked] is null when no list covers this folder, or when the file is not part of
 * the owning list's snapshot.
 */
@Composable
private fun FileRow(entry: FolderEntry, isChecked: Boolean?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            entryDetail(entry),
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

@Composable
private fun StatusAction(
    status: FolderListStatus,
    onOpenList: (Long) -> Unit,
    onCreateList: () -> Unit
) {
    when (status) {
        is FolderListStatus.Direct ->
            TextButton(onClick = { onOpenList(status.scope.id) }) { Text("Open") }

        is FolderListStatus.None ->
            TextButton(onClick = onCreateList) { Text("Create list") }

        // Offered deliberately and labelled differently: this runs the V3.1 confirmation, which
        // spells out that the ancestor list would be replaced.
        is FolderListStatus.Inherited ->
            TextButton(onClick = onCreateList) { Text("Create own list") }
    }
}

private fun statusHeadline(status: FolderListStatus): String = when (status) {
    is FolderListStatus.None -> "No list"
    is FolderListStatus.Direct -> "List · " + scopeProgressLabel(status.scope)
    is FolderListStatus.Inherited -> "Managed by parent list"
}

private fun statusDetail(status: FolderListStatus): String? = when (status) {
    is FolderListStatus.None -> null
    is FolderListStatus.Direct -> if (status.scope.isComplete) "Completed" else "In progress"

    // This folder's own subtree, not the owning list's overall total.
    is FolderListStatus.Inherited ->
        progressLabel(status.subtreeCompleted, status.subtreeTotal, status.subtreeIsComplete) +
            " · " + status.scope.title
}

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

private fun entryDetail(entry: FolderEntry): String = buildList {
    add(if (entry.isDirectory) "Directory" else "File")
    entry.sizeBytes?.let { add(formatSize(it)) }
    entry.lastModified?.let { add(formatTimestamp(it)) }
}.joinToString(" · ")
