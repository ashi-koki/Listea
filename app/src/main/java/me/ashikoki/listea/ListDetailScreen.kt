package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.itemActionLabel

@Composable
fun ListDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    listId: Long,
    onBack: () -> Unit,
    onReview: () -> Unit = {}
) {
    val detailFlow = remember(listId) { viewModel.observeDetail(listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    var newItem by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ListItemEntity?>(null) }

    BackHandler { onBack() }

    val current = detail
    if (current == null) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = onBack) { Text("‹ Lists") }
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        return
    }

    fun submitNewItem() {
        viewModel.addItem(listId, newItem)
        newItem = ""
    }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Lists") }
            Spacer(Modifier.weight(1f))
            // Review is available for every list with items, folder-backed or manual.
            if (current.items.isNotEmpty()) {
                Button(onClick = onReview) { Text("Review") }
            }
        }
        Text(current.list.title, style = MaterialTheme.typography.titleLarge)
        Text(
            progressLabel(current.completedCount, current.items.size, current.isComplete),
            style = MaterialTheme.typography.bodyMedium,
            color = if (current.isComplete) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.height(12.dp))

        // Only folder-backed lists have a source to reconcile against.
        val rootUri = current.list.sourceRootUri
        val relativePath = current.list.sourceRelativePath
        if (rootUri != null && relativePath != null) {
            SourceSection(
                rootUri = rootUri,
                relativePath = relativePath,
                missingCount = current.items.count { it.sourceMissing },
                onResync = { viewModel.requestResync(listId) },
                onClearMissing = { viewModel.requestMissingCleanup(listId) }
            )
            Spacer(Modifier.height(12.dp))
        }

        WebhookSection(
            list = current.list,
            onEnabledChange = { viewModel.setWebhookEnabled(listId, it) },
            onUrlChange = { viewModel.setWebhookUrl(listId, it) },
            onTest = { viewModel.testWebhook(listId) }
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text("New item") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitNewItem() })
            )
            Button(onClick = { submitNewItem() }, enabled = newItem.isNotBlank()) { Text("Add") }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        if (current.items.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("No items yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(current.items, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        onToggle = { viewModel.setItemCompleted(item, it) },
                        onEdit = { editing = item },
                        onDelete = { viewModel.deleteItem(item) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { target ->
        TextPromptDialog(
            title = "Edit item",
            label = "Item text",
            initialText = target.title,
            confirmLabel = "Save",
            onConfirm = { viewModel.renameItem(target.id, it); editing = null },
            onDismiss = { editing = null }
        )
    }

    ResyncDialogs(viewModel)
}

@Composable
private fun ResyncDialogs(viewModel: ListsViewModel) {
    val request by viewModel.resyncRequest.collectAsStateWithLifecycle()

    when (val current = request) {
        null -> Unit

        is ResyncRequest.Scanning -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking folder") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("Comparing this list with its source folder.")
                }
            },
            confirmButton = {}
        )

        is ResyncRequest.UpToDate -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("List is up to date") },
            text = { Text("Nothing has changed in the source folder.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("OK") }
            }
        )

        is ResyncRequest.Confirm -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Folder changes detected") },
            text = {
                val diff = current.diff
                Column {
                    Text(countLine(diff.addedPaths.size, "new file"))
                    Text(countLine(diff.missingPaths.size, "missing file"))
                    if (diff.restoredPaths.isNotEmpty()) {
                        Text(countLine(diff.restoredPaths.size, "restored file"))
                    }
                    Text("${diff.unchangedCount} unchanged")
                    Spacer(Modifier.height(8.dp))
                    diff.addedPaths.take(PREVIEW_PATHS).forEach { Text("+ $it") }
                    diff.missingPaths.take(PREVIEW_PATHS).forEach { Text("- $it") }
                    Text(
                        "Missing files stay in the list and keep their checked state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmResync() }) { Text("Update list") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Cancel") }
            }
        )

        is ResyncRequest.CleanupMissing -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Remove missing items?") },
            text = {
                Column {
                    Text(
                        countLine(current.missingCount, "item") +
                            if (current.missingCount == 1) {
                                " no longer exists in the source folder."
                            } else {
                                " no longer exist in the source folder."
                            }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keeping them preserves their checked state. Removing them cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRemoveMissingItems() }) {
                    Text("Remove ${countLine(current.missingCount, "item")}")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Keep items") }
            }
        )

        is ResyncRequest.Error -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Could not refresh") },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("OK") }
            }
        )
    }
}

private const val PREVIEW_PATHS = 5

private fun countLine(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"

/**
 * Where a folder-backed list came from, the manual re-sync action, and — whenever any source is
 * missing — a standing cleanup action. The cleanup offer shown right after a re-sync can be
 * dismissed, and a later re-sync will report "up to date" because the items are already flagged,
 * so this button is the way back to it.
 */
@Composable
private fun SourceSection(
    rootUri: String,
    relativePath: String,
    missingCount: Int,
    onResync: () -> Unit,
    onClearMissing: () -> Unit
) {
    val context = LocalContext.current
    val label by produceState(relativePath, rootUri, relativePath) {
        value = withContext(Dispatchers.IO) {
            sourceFolderLabel(context, rootUri.toUri(), relativePath)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Source", style = MaterialTheme.typography.labelMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onResync) { Text("Refresh from folder") }
            if (missingCount > 0) {
                TextButton(onClick = onClearMissing) {
                    Text(
                        "Clear $missingCount missing",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * The list's optional completion action. Collapsed by default so the items stay the focus.
 * Nothing here sends anything on its own: delivery is triggered by the ViewModel when the
 * database reports a real incomplete -> complete transition, or by the Test button.
 */
@Composable
private fun WebhookSection(
    list: ListEntity,
    onEnabledChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onTest: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Seeded once per list so incoming database updates never fight with typing.
    var urlText by remember(list.id) { mutableStateOf(list.webhookUrl) }
    val urlProblem = webhookUrlError(urlText)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Webhook",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (list.webhookEnabled) "On" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.bodyMedium)
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Send when this list is completed",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = list.webhookEnabled, onCheckedChange = onEnabledChange)
            }
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it; onUrlChange(it) },
                label = { Text("POST URL") },
                singleLine = true,
                isError = urlText.isNotBlank() && urlProblem != null,
                supportingText = urlProblem?.let { problem -> { Text(problem) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onTest, enabled = urlProblem == null) {
                    Text("Test webhook")
                }
                Text(
                    deliveryLabel(list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: ListItemEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                color = if (item.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            // Folder-backed items keep their path so bilibili/a.jpg stays distinct from danbooru/a.jpg.
            item.sourceRelativePath?.takeIf { it != item.title }?.let { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Read-only here: actions are set in Review, this row just reports them.
            itemActionLabel(item)?.let { actions ->
                Text(
                    actions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Subtle: the item stays checkable and is never hidden or removed.
            if (item.sourceMissing) {
                Text(
                    "Source missing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        IconButton(onClick = onDelete) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
    }
}
