package me.ashikoki.listea

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
                    FolderScreen(modifier = Modifier.padding(innerPadding))
                }
            }
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
fun FolderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { FolderStore(context) }
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Listea", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

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
                Text(
                    current.stack.joinToString(" / ") { it.name },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${current.contents.entries.size} entries",
                    style = MaterialTheme.typography.bodyMedium
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
                            EntryRow(
                                entry = entry,
                                onOpen = { open(current.stack + DirRef(entry.uri, entry.name)) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FolderEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDirectory, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = buildList {
                add(if (entry.isDirectory) "Directory" else "File")
                entry.sizeBytes?.let { add(formatSize(it)) }
                entry.lastModified?.let { add(formatTimestamp(it)) }
            }.joinToString(" · ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.isDirectory) {
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
